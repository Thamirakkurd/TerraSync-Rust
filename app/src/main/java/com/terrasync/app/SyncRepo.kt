package com.terrasync.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.SetOptions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SyncRepo(context: Context? = null) {
    private val appCtx: Context? = context?.applicationContext
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance().apply {
        runCatching {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(
                    PersistentCacheSettings.newBuilder()
                        .setSizeBytes(100L * 1024L * 1024L)
                        .build()
                )
                .build()
        }
    }

    private val auth: FirebaseAuth? = runCatching { FirebaseAuth.getInstance() }.getOrNull()
    private val mainHandler = Handler(Looper.getMainLooper())
    companion object {
        private val sharedSyncExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "terrasync-sync").apply { isDaemon = true }
        }
    }
    private val syncExecutor get() = sharedSyncExecutor
    private val stateLock = Any()
    private val layerRegs = mutableMapOf<String, List<ListenerRegistration>>()
    private val layerMap = mutableMapOf<String, LayerItem>()
    private val pinDocsByLayer = mutableMapOf<String, LinkedHashMap<String, PinItem>>()
    private val pendingPinIdsByLayer = mutableMapOf<String, MutableSet<String>>()
    private val trackDocsByLayer = mutableMapOf<String, LinkedHashMap<String, TrackItem>>()
    private val liveListenerStates = ConcurrentHashMap<String, Boolean>()
    private val pendingWritesByLayer = ConcurrentHashMap<String, Int>()
    @Volatile private var connected: Boolean = false
    @Volatile private var listenerCallback: ((List<LayerItem>, List<PinItem>, List<TrackItem>, Boolean) -> Unit)? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingEmitRunnable: Runnable? = null

    fun isLayerLive(layerId: String): Boolean {
        if (!isOnline()) return false
        return liveListenerStates["pins:$layerId"] == true
    }

    private fun computeConnected(): Boolean {
        if (!isOnline()) return false
        if (liveListenerStates.isEmpty()) return true
        val pinStates = liveListenerStates.filterKeys { it.startsWith("pins:") }
        return if (pinStates.isNotEmpty()) {
            pinStates.values.all { it }
        } else {
            liveListenerStates.values.all { it }
        }
    }

    init {
        ensureAuthenticated()
        ensureNetworkMonitorRegistered()
    }

    private fun ensureNetworkMonitorRegistered() {
        if (networkCallback != null) return
        val ctx = appCtx ?: return
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                private fun refreshConnectivityAndAuth() {
                    mainHandler.post {
                        val onlineNow = isOnline()
                        if (onlineNow && currentUid().isNullOrBlank()) {
                            ensureAuthenticated()
                        }
                        val next = computeConnected()
                        if (next != connected) {
                            connected = next
                            emitNow()
                        }
                    }
                }

                override fun onAvailable(network: Network) {
                    refreshConnectivityAndAuth()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    refreshConnectivityAndAuth()
                }

                override fun onLost(network: Network) {
                    mainHandler.post {
                        val next = computeConnected()
                        if (next != connected) {
                            connected = next
                            emitNow()
                        }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }
    }

    fun currentUid(): String? = runCatching { auth?.currentUser?.uid }.getOrNull()

    fun ensureAuthenticated(onReady: ((String?) -> Unit)? = null) {
        val a = auth
        if (a == null) {
            onReady?.invoke(null)
            return
        }
        val existing = a.currentUser
        if (existing != null) {
            onReady?.invoke(existing.uid)
            return
        }
        runCatching {
            a.signInAnonymously()
                .addOnSuccessListener { res -> onReady?.invoke(res.user?.uid) }
                .addOnFailureListener { onReady?.invoke(null) }
        }.onFailure {
            onReady?.invoke(null)
        }
    }

    private fun isOnline(): Boolean = NetworkState.hasValidatedInternet(appCtx ?: RoutingEngine.appContext)

    private data class BatchWriteOp(
        val ref: DocumentReference,
        val data: Map<String, Any>,
        val merge: Boolean = false
    )

    private fun commitOpsInBatches(
        ops: List<BatchWriteOp>,
        successMsg: String,
        shareCode: String,
        onDone: (Boolean, String, String) -> Unit,
        requireServerCommit: Boolean = false
    ) {
        val chunks = ops.chunked(400)
        if (chunks.isEmpty()) {
            onDone(true, successMsg, shareCode)
            return
        }

        val online = isOnline()
        if (requireServerCommit && !online) {
            onDone(false, "Internet connection required to upload to cloud — local copy kept safely", "")
            return
        }

        val firstError = AtomicReference<String?>(null)
        val finished = AtomicBoolean(false)

        if (!online) {
            chunks.forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { op ->
                    if (op.merge) batch.set(op.ref, op.data, SetOptions.merge()) else batch.set(op.ref, op.data)
                }
                batch.commit()
            }
            if (finished.compareAndSet(false, true)) {
                onDone(true, "$successMsg (offline — will sync when online)", shareCode)
            }
            return
        }

        val timeoutMs = if (requireServerCommit) {
            maxOf(30_000L, chunks.size * 10_000L).coerceAtMost(120_000L)
        } else {
            maxOf(15_000L, chunks.size * 6_000L).coerceAtMost(60_000L)
        }
        val timeoutRunnable = Runnable {
            if (finished.compareAndSet(false, true)) {
                val err = firstError.get()
                if (err != null) {
                    onDone(false, "Sync error: $err", shareCode)
                } else if (requireServerCommit) {
                    onDone(false, "Upload timed out — local copy kept safely", "")
                } else {
                    onDone(true, "$successMsg (saved locally — syncing)", shareCode)
                }
            }
        }
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)

        fun commitChunk(index: Int, attempt: Int) {
            if (finished.get()) return
            if (index >= chunks.size) {
                if (finished.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    onDone(true, successMsg, shareCode)
                }
                return
            }
            val chunk = chunks[index]
            val batch = db.batch()
            chunk.forEach { op ->
                if (op.merge) batch.set(op.ref, op.data, SetOptions.merge()) else batch.set(op.ref, op.data)
            }
            batch.commit()
                .addOnSuccessListener {
                    commitChunk(index + 1, 0)
                }
                .addOnFailureListener { e ->
                    val code = (e as? FirebaseFirestoreException)?.code
                    val retryable = code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                        code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ||
                        code == FirebaseFirestoreException.Code.ABORTED ||
                        code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED
                    if (retryable && attempt < 2 && !finished.get()) {
                        mainHandler.postDelayed({ commitChunk(index, attempt + 1) }, 800L * (attempt + 1))
                    } else {
                        firstError.compareAndSet(null, e.message ?: "Batch write failed")
                        if (finished.compareAndSet(false, true)) {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            onDone(false, "Sync error: ${firstError.get()}", shareCode)
                        }
                    }
                }
        }
        commitChunk(0, 0)
    }

    fun syncJoinedLayers(
        joinedLayerIds: Set<String>,
        onUpdate: (List<LayerItem>, List<PinItem>, List<TrackItem>, Boolean) -> Unit
    ) {
        ensureNetworkMonitorRegistered()
        listenerCallback = onUpdate

        val removed = synchronized(stateLock) {
            val drop = layerRegs.keys - joinedLayerIds
            drop.forEach { layerId ->
                layerRegs.remove(layerId)?.forEach { it.remove() }
                layerMap.remove(layerId)
                pinDocsByLayer.remove(layerId)
                pendingPinIdsByLayer.remove(layerId)
                trackDocsByLayer.remove(layerId)
                pendingWritesByLayer.remove(layerId)
                liveListenerStates.remove("layer:$layerId")
                liveListenerStates.remove("pins:$layerId")
                liveListenerStates.remove("tracks:$layerId")
            }
            drop
        }

        fun updateConnectedFlag(key: String, isLive: Boolean) {
            liveListenerStates[key] = isLive
            connected = computeConnected()
        }

        if (joinedLayerIds.isEmpty()) {
            liveListenerStates.clear()
            pendingWritesByLayer.clear()
            connected = isOnline()
            emitNow()
            return
        }

        val added = synchronized(stateLock) { joinedLayerIds - layerRegs.keys }
        added.forEach { layerId ->
            val regsForLayer = mutableListOf<ListenerRegistration>()
            regsForLayer += db.collection("layers").document(layerId)
                .addSnapshotListener(syncExecutor, MetadataChanges.INCLUDE) { doc, err ->
                    if (err != null) {
                        updateConnectedFlag("layer:$layerId", false)
                        emit()
                        return@addSnapshotListener
                    }
                    updateConnectedFlag("layer:$layerId", doc?.metadata?.isFromCache != true)
                    synchronized(stateLock) {
                        if (doc != null && doc.exists()) {
                            layerMap[layerId] = LayerItem(
                                id = doc.id,
                                name = doc.getString("name") ?: "Layer",
                                shareCode = doc.getString("shareCode") ?: "",
                                createdBy = doc.getString("createdBy") ?: "",
                                creatorDeviceId = doc.getString("creatorDeviceId") ?: "",
                                creatorUid = doc.getString("creatorUid") ?: "",
                                updatedAt = doc.getLong("updatedAt") ?: 0L
                            )
                        } else {
                            layerMap.remove(layerId)
                            pinDocsByLayer.remove(layerId)
                            pendingPinIdsByLayer.remove(layerId)
                            trackDocsByLayer.remove(layerId)
                        }
                    }
                    emit()
                }

            regsForLayer += db.collection("pins").whereEqualTo("layerId", layerId)
                .addSnapshotListener(syncExecutor, MetadataChanges.INCLUDE) { snap, err ->
                    if (err != null) {
                        updateConnectedFlag("pins:$layerId", false)
                        emit()
                        return@addSnapshotListener
                    }
                    updateConnectedFlag("pins:$layerId", snap?.metadata?.isFromCache != true)
                    if (snap != null) {
                        synchronized(stateLock) {
                            val pinMap = pinDocsByLayer.getOrPut(layerId) { LinkedHashMap() }
                            val pendingSet = pendingPinIdsByLayer.getOrPut(layerId) { HashSet() }
                            for (change in snap.documentChanges) {
                                val d = change.document
                                if (change.type == DocumentChange.Type.REMOVED) {
                                    pinMap.remove(d.id)
                                    pendingSet.remove(d.id)
                                } else {
                                    val lat = d.getDouble("lat")
                                    val lng = d.getDouble("lng")
                                    if (lat != null && lng != null) {
                                        pinMap[d.id] = PinItem(
                                            id = d.id,
                                            layerId = d.getString("layerId") ?: layerId,
                                            name = d.getString("name") ?: "Pin",
                                            description = d.getString("description") ?: "",
                                            lat = lat,
                                            lng = lng,
                                            status = PinStatus.fromRaw(d.getString("status")).key,
                                            issueReason = d.getString("issueReason") ?: "",
                                            updatedAt = d.getLong("updatedAt") ?: 0L,
                                            isEdited = d.getBoolean("isEdited") ?: false,
                                            editedBy = d.getString("editedBy") ?: "",
                                            editedAt = d.getLong("editedAt") ?: 0L,
                                            editHistory = d.getString("editHistory") ?: "",
                                            folderPath = d.getString("folderPath") ?: ""
                                        )
                                        if (d.metadata.hasPendingWrites()) pendingSet.add(d.id) else pendingSet.remove(d.id)
                                    } else {
                                        pinMap.remove(d.id)
                                        pendingSet.remove(d.id)
                                    }
                                }
                            }
                            pendingWritesByLayer[layerId] = pendingSet.size
                        }
                    }
                    emit()
                }

            regsForLayer += db.collection("tracks").whereEqualTo("layerId", layerId)
                .addSnapshotListener(syncExecutor, MetadataChanges.INCLUDE) { snap, err ->
                    if (err != null) {
                        updateConnectedFlag("tracks:$layerId", false)
                        emit()
                        return@addSnapshotListener
                    }
                    updateConnectedFlag("tracks:$layerId", snap?.metadata?.isFromCache != true)
                    if (snap != null) {
                        synchronized(stateLock) {
                            val trackMap = trackDocsByLayer.getOrPut(layerId) { LinkedHashMap() }
                            for (change in snap.documentChanges) {
                                val d = change.document
                                if (change.type == DocumentChange.Type.REMOVED) {
                                    trackMap.remove(d.id)
                                } else {
                                    val coords = d.getString("coords")
                                    if (coords != null) {
                                        trackMap[d.id] = TrackItem(
                                            id = d.id,
                                            layerId = d.getString("layerId") ?: layerId,
                                            name = d.getString("name") ?: "Track",
                                            coords = coords,
                                            updatedAt = d.getLong("updatedAt") ?: 0L,
                                            folderPath = d.getString("folderPath") ?: ""
                                        )
                                    } else {
                                        trackMap.remove(d.id)
                                    }
                                }
                            }
                        }
                    }
                    emit()
                }
            synchronized(stateLock) {
                layerRegs[layerId] = regsForLayer
            }
        }

        if (removed.isNotEmpty() || added.isEmpty()) {
            connected = computeConnected()
            emitNow()
        }
    }

    private fun emit() {
        mainHandler.post {
            pendingEmitRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable {
                pendingEmitRunnable = null
                emitNow()
            }
            pendingEmitRunnable = r
            mainHandler.postDelayed(r, 40L)
        }
    }

    private fun emitNow() {
        val cb = listenerCallback ?: return
        pendingEmitRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingEmitRunnable = null
        val (sortedLayers, allPins, allTracks) = synchronized(stateLock) {
            val l = layerMap.values.sortedByDescending { it.updatedAt }
            val p = pinDocsByLayer.values.flatMap { it.values }
            val t = trackDocsByLayer.values.flatMap { it.values }
            Triple(l, p, t)
        }
        cb.invoke(sortedLayers, allPins, allTracks, connected)
    }

    fun stopListening() {
        val regsToRemove = synchronized(stateLock) {
            val copy = layerRegs.values.flatten()
            layerRegs.clear()
            layerMap.clear()
            pinDocsByLayer.clear()
            pendingPinIdsByLayer.clear()
            trackDocsByLayer.clear()
            copy
        }
        regsToRemove.forEach { it.remove() }
        pendingEmitRunnable = null
        liveListenerStates.clear()
        pendingWritesByLayer.clear()
        listenerCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        val cb = networkCallback
        networkCallback = null
        if (cb != null) {
            val ctx = appCtx
            if (ctx != null) {
                runCatching {
                    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    cm.unregisterNetworkCallback(cb)
                }
            }
        }
    }

    fun generateShareCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun buildPinPayload(pinId: String, layerId: String, p: PinItem, updatedAt: Long): Map<String, Any> {
        val m = HashMap<String, Any>(16)
        m["id"] = pinId
        m["layerId"] = layerId
        m["name"] = p.name
        m["description"] = p.description
        m["lat"] = p.lat
        m["lng"] = p.lng
        m["status"] = p.status
        m["issueReason"] = p.issueReason
        m["updatedAt"] = updatedAt
        m["isEdited"] = p.isEdited
        m["editedBy"] = p.editedBy
        m["editedAt"] = p.editedAt
        m["editHistory"] = p.editHistory
        if (p.folderPath.isNotBlank()) m["folderPath"] = p.folderPath
        return m
    }

    private fun buildTrackPayload(trackId: String, layerId: String, t: TrackItem, updatedAt: Long): Map<String, Any> {
        val m = HashMap<String, Any>(8)
        m["id"] = trackId
        m["layerId"] = layerId
        m["name"] = t.name
        m["coords"] = t.coords
        m["updatedAt"] = updatedAt
        if (t.folderPath.isNotBlank()) m["folderPath"] = t.folderPath
        return m
    }

    fun importLayer(
        layerId: String,
        layerName: String,
        createdBy: String,
        creatorDeviceId: String,
        parsed: ParsedKml,
        requireServerCommit: Boolean = false,
        onDone: (Boolean, String, String) -> Unit
    ) {
        val dispatch: (String?) -> Unit = { resolvedUid ->
            val creatorUid = resolvedUid?.takeIf { it.isNotBlank() } ?: currentUid() ?: ""
            val now = System.currentTimeMillis()
            val shareCode = generateShareCode()
            val ops = mutableListOf<BatchWriteOp>()

            ops += BatchWriteOp(
                db.collection("layers").document(layerId),
                mapOf(
                    "id" to layerId,
                    "name" to layerName,
                    "shareCode" to shareCode,
                    "createdBy" to createdBy,
                    "creatorDeviceId" to creatorDeviceId,
                    "creatorUid" to creatorUid,
                    "updatedAt" to now
                )
            )

            parsed.pins.forEach { p ->
                ops += BatchWriteOp(db.collection("pins").document(p.id), buildPinPayload(p.id, layerId, p, now))
            }

            parsed.tracks.forEach { t ->
                ops += BatchWriteOp(db.collection("tracks").document(t.id), buildTrackPayload(t.id, layerId, t, now))
            }

            commitOpsInBatches(
                ops,
                "Imported ${parsed.pins.size} pins into \"$layerName\" (Code: $shareCode)",
                shareCode,
                onDone,
                requireServerCommit = requireServerCommit
            )
        }
        if (currentUid() == null && !isOnline()) {
            dispatch(null)
        } else {
            ensureAuthenticated(dispatch)
        }
    }

    fun appendToCloudLayer(
        layerId: String,
        layerName: String,
        newPins: List<PinItem>,
        newTracks: List<TrackItem>,
        onDone: (Boolean, String, String) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val ops = mutableListOf<BatchWriteOp>()

        newPins.forEach { p ->
            val newId = UUID.randomUUID().toString()
            ops += BatchWriteOp(db.collection("pins").document(newId), buildPinPayload(newId, layerId, p, now))
        }

        newTracks.forEach { t ->
            val newId = UUID.randomUUID().toString()
            ops += BatchWriteOp(db.collection("tracks").document(newId), buildTrackPayload(newId, layerId, t, now))
        }

        ops += BatchWriteOp(db.collection("layers").document(layerId), mapOf("updatedAt" to now), merge = true)
        commitOpsInBatches(
            ops,
            "Merged ${newPins.size} pins into \"$layerName\"",
            "",
            onDone
        )
    }

    fun combineLayers(
        newLayerId: String,
        layerName: String,
        createdBy: String,
        creatorDeviceId: String,
        sourcePins: List<PinItem>,
        sourceTracks: List<TrackItem>,
        onDone: (Boolean, String, String) -> Unit
    ) {
        val dispatch: (String?) -> Unit = { resolvedUid ->
            val creatorUid = resolvedUid?.takeIf { it.isNotBlank() } ?: currentUid() ?: ""
            val now = System.currentTimeMillis()
            val shareCode = generateShareCode()
            val ops = mutableListOf<BatchWriteOp>()

            ops += BatchWriteOp(
                db.collection("layers").document(newLayerId),
                mapOf(
                    "id" to newLayerId,
                    "name" to layerName,
                    "shareCode" to shareCode,
                    "createdBy" to createdBy,
                    "creatorDeviceId" to creatorDeviceId,
                    "creatorUid" to creatorUid,
                    "updatedAt" to now
                )
            )

            sourcePins.forEach { p ->
                val newId = UUID.randomUUID().toString()
                ops += BatchWriteOp(
                    db.collection("pins").document(newId),
                    buildPinPayload(newId, newLayerId, p, p.updatedAt.takeIf { it > 0L } ?: now)
                )
            }

            sourceTracks.forEach { t ->
                val newId = UUID.randomUUID().toString()
                ops += BatchWriteOp(
                    db.collection("tracks").document(newId),
                    buildTrackPayload(newId, newLayerId, t, now)
                )
            }

            commitOpsInBatches(
                ops,
                "Combined ${sourcePins.size} pins into \"$layerName\" (Code: $shareCode)",
                shareCode,
                onDone
            )
        }
        if (currentUid() == null && !isOnline()) {
            dispatch(null)
        } else {
            ensureAuthenticated(dispatch)
        }
    }

    fun joinLayerByCode(rawCode: String, onDone: (Boolean, String, String?) -> Unit) {
        val code = rawCode.trim().uppercase().replace("-", "").replace(" ", "")
        if (code.length < 4) {
            onDone(false, "Enter a valid share code", null)
            return
        }
        if (!isOnline()) {
            onDone(false, "Internet connection required to join a shared folder", null)
            return
        }
        db.collection("layers")
            .whereEqualTo("shareCode", code)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                val doc = snap.documents.firstOrNull()
                if (doc == null) {
                    onDone(false, "No layer found for code $code", null)
                } else {
                    val name = doc.getString("name") ?: "Shared Layer"
                    onDone(true, "Joined \"$name\"", doc.id)
                }
            }
            .addOnFailureListener { e ->
                onDone(false, e.message ?: "Lookup failed", null)
            }
    }

    private fun sanitizeHistoryToken(raw: String): String {
        return raw.replace(Regex("[\\r\\n]+"), " ").replace("→", "-").trim()
    }

    private fun buildHistory(newLine: String, existingHistory: String): String {
        return PinEditHistoryAudit.prependHistoryLine(newLine, existingHistory)
    }

    private fun mergeHistoryUnion(newLine: String, remoteHistory: String, localHistory: String): String {
        val merged = LinkedHashSet<String>()
        val cleanNew = newLine.trim()
        if (cleanNew.isNotEmpty()) merged.add(cleanNew)
        remoteHistory.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { merged.add(it) }
        localHistory.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { merged.add(it) }
        return merged.take(40).joinToString("\n")
    }

    private fun updatePinWithHistory(
        pinId: String,
        newHistoryLine: String,
        fallbackHistory: String,
        baseFields: Map<String, Any>,
        onError: ((String) -> Unit)?
    ) {
        val docRef = db.collection("pins").document(pinId)
        val fallbackPayload = baseFields + ("editHistory" to buildHistory(newHistoryLine, fallbackHistory))
        if (!isOnline()) {
            docRef.update(fallbackPayload)
                .addOnFailureListener { e -> onError?.invoke(e.message ?: "Failed to save pin update") }
            return
        }
        db.runTransaction { tx ->
            val snap = tx.get(docRef)
            val liveHistory = snap.getString("editHistory") ?: fallbackHistory
            val mergedHistory = mergeHistoryUnion(newHistoryLine, liveHistory, fallbackHistory)
            tx.update(docRef, baseFields + ("editHistory" to mergedHistory))
            null
        }.addOnFailureListener { e ->
            val code = (e as? FirebaseFirestoreException)?.code
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE || code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED) {
                docRef.update(fallbackPayload)
                    .addOnFailureListener { err -> onError?.invoke(err.message ?: "Failed to save pin update") }
            } else {
                onError?.invoke(e.message ?: "Failed to save pin update")
            }
        }
    }

    fun updatePinStatus(
        pin: PinItem,
        status: String,
        deviceStamp: String,
        issueReason: String = "",
        onError: ((String) -> Unit)? = null
    ): PinItem {
        val now = System.currentTimeMillis()
        val cleanReason = if (status.equals("issue", ignoreCase = true) || status.equals("done", ignoreCase = true)) {
            sanitizeHistoryToken(issueReason)
        } else ""
        val safeStamp = sanitizeHistoryToken(deviceStamp)
        val detail = if (cleanReason.isNotBlank()) "${statusLabel(status)}: $cleanReason" else statusLabel(status)
        val line = "${formatTime(now)} • $safeStamp → $detail"
        updatePinWithHistory(
            pinId = pin.id,
            newHistoryLine = line,
            fallbackHistory = pin.editHistory,
            baseFields = mapOf(
                "status" to status,
                "issueReason" to cleanReason,
                "updatedAt" to now,
                "isEdited" to true,
                "editedBy" to deviceStamp,
                "editedAt" to now
            ),
            onError = onError
        )
        return pin.copy(
            status = status,
            issueReason = cleanReason,
            updatedAt = now,
            isEdited = true,
            editedBy = deviceStamp,
            editedAt = now,
            editHistory = buildHistory(line, pin.editHistory)
        )
    }

    fun updatePinsStatusBatch(
        targetPins: List<PinItem>,
        status: String,
        deviceStamp: String,
        issueReason: String = "",
        onError: ((String) -> Unit)? = null
    ): List<PinItem> {
        if (targetPins.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val cleanReason = if (status.equals("issue", ignoreCase = true) || status.equals("done", ignoreCase = true)) {
            sanitizeHistoryToken(issueReason)
        } else ""
        val safeStamp = sanitizeHistoryToken(deviceStamp)
        val detail = if (cleanReason.isNotBlank()) "${statusLabel(status)}: $cleanReason" else statusLabel(status)
        val line = "${formatTime(now)} • $safeStamp → $detail"
        val updatedPins = ArrayList<PinItem>(targetPins.size)
        val reported = AtomicBoolean(false)
        val baseFields = mapOf(
            "status" to status,
            "issueReason" to cleanReason,
            "updatedAt" to now,
            "isEdited" to true,
            "editedBy" to deviceStamp,
            "editedAt" to now
        )

        targetPins.forEach { pin ->
            val mergedHistory = buildHistory(line, pin.editHistory)
            updatedPins.add(
                pin.copy(
                    status = status,
                    issueReason = cleanReason,
                    updatedAt = now,
                    isEdited = true,
                    editedBy = deviceStamp,
                    editedAt = now,
                    editHistory = mergedHistory
                )
            )
        }

        fun commitBatchFallback(chunk: List<PinItem>) {
            val batch = db.batch()
            chunk.forEach { pin ->
                val ref = db.collection("pins").document(pin.id)
                batch.update(ref, baseFields + ("editHistory" to buildHistory(line, pin.editHistory)))
            }
            batch.commit().addOnFailureListener { e ->
                if (reported.compareAndSet(false, true)) {
                    onError?.invoke(e.message ?: "Failed to update selected pins")
                }
            }
        }

        if (!isOnline()) {
            targetPins.chunked(400).forEach(::commitBatchFallback)
            return updatedPins
        }

        targetPins.chunked(40).forEach { chunk ->
            db.runTransaction { tx ->
                val refsAndSnaps = chunk.map { pin ->
                    val ref = db.collection("pins").document(pin.id)
                    Triple(pin, ref, tx.get(ref))
                }
                refsAndSnaps.forEach { (pin, ref, snap) ->
                    val liveHistory = snap.getString("editHistory") ?: pin.editHistory
                    tx.update(ref, baseFields + ("editHistory" to buildHistory(line, liveHistory)))
                }
                null
            }.addOnFailureListener { e ->
                val code = (e as? FirebaseFirestoreException)?.code
                if (code == FirebaseFirestoreException.Code.UNAVAILABLE || code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED) {
                    commitBatchFallback(chunk)
                } else if (reported.compareAndSet(false, true)) {
                    onError?.invoke(e.message ?: "Failed to update selected pins")
                }
            }
        }
        return updatedPins
    }

    fun updatePinText(
        pin: PinItem,
        newName: String,
        newDesc: String,
        deviceStamp: String,
        onError: ((String) -> Unit)? = null
    ): PinItem {
        val now = System.currentTimeMillis()
        val resolvedName = newName.ifBlank { pin.name }
        val line = "${formatTime(now)} • ${sanitizeHistoryToken(deviceStamp)} edited text"
        updatePinWithHistory(
            pinId = pin.id,
            newHistoryLine = line,
            fallbackHistory = pin.editHistory,
            baseFields = mapOf(
                "name" to resolvedName,
                "description" to newDesc,
                "updatedAt" to now,
                "isEdited" to true,
                "editedBy" to deviceStamp,
                "editedAt" to now
            ),
            onError = onError
        )
        return pin.copy(
            name = resolvedName,
            description = newDesc,
            updatedAt = now,
            isEdited = true,
            editedBy = deviceStamp,
            editedAt = now,
            editHistory = buildHistory(line, pin.editHistory)
        )
    }

    fun addManualPin(
        name: String,
        description: String,
        lat: Double,
        lng: Double,
        deviceStamp: String,
        deviceId: String,
        onError: ((String) -> Unit)? = null
    ): String {
        val now = System.currentTimeMillis()
        val cleanId = deviceId.filter { it.isLetterOrDigit() }.uppercase().ifBlank { "LOCAL" }
        val layerId = "manual_$cleanId"
        val shareCode = generateShareCode()
        val id = UUID.randomUUID().toString()
        val layerRef = db.collection("layers").document(layerId)
        val pinRef = db.collection("pins").document(id)
        val newLayerData = mapOf(
            "id" to layerId,
            "name" to "My Field Pins",
            "shareCode" to shareCode,
            "createdBy" to deviceStamp,
            "creatorDeviceId" to cleanId,
            "creatorUid" to (currentUid() ?: ""),
            "updatedAt" to now
        )
        val pinData = mapOf(
            "id" to id,
            "layerId" to layerId,
            "name" to name.ifBlank { "New Pin" },
            "description" to description,
            "lat" to lat,
            "lng" to lng,
            "status" to "pending",
            "issueReason" to "",
            "updatedAt" to now,
            "isEdited" to false,
            "editedBy" to "",
            "editedAt" to 0L,
            "editHistory" to ""
        )

        fun commitOfflineFallback() {
            val batch = db.batch()
            val hasLayer = synchronized(stateLock) { layerMap.containsKey(layerId) }
            if (hasLayer) {
                batch.set(layerRef, mapOf("updatedAt" to now), SetOptions.merge())
            } else {
                batch.set(layerRef, newLayerData, SetOptions.merge())
            }
            batch.set(pinRef, pinData)
            batch.commit().addOnFailureListener { e ->
                onError?.invoke(e.message ?: "Failed to save pin")
            }
        }

        if (!isOnline()) {
            commitOfflineFallback()
            return layerId
        }

        db.runTransaction { tx ->
            val layerSnap = tx.get(layerRef)
            if (layerSnap.exists()) {
                tx.update(layerRef, "updatedAt", now)
            } else {
                tx.set(layerRef, newLayerData)
            }
            tx.set(pinRef, pinData)
            null
        }.addOnFailureListener { e ->
            val code = (e as? FirebaseFirestoreException)?.code
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE || code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED) {
                commitOfflineFallback()
            } else {
                onError?.invoke(e.message ?: "Failed to save pin")
            }
        }
        return layerId
    }

    fun renameLayer(layerId: String, newName: String, onError: ((String) -> Unit)? = null) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        db.collection("layers").document(layerId).update(
            mapOf(
                "name" to clean,
                "updatedAt" to System.currentTimeMillis()
            )
        ).addOnFailureListener { e ->
            onError?.invoke(e.message ?: "Failed to rename folder")
        }
    }

    private fun recomputeHistoryFields(lines: List<String>, now: Long): Map<String, Any> {
        val recomputed = PinEditHistoryAudit.recomputeFromHistoryLines(lines, now)
        return mapOf(
            "status" to recomputed.status,
            "issueReason" to recomputed.issueReason,
            "updatedAt" to now,
            "isEdited" to recomputed.isEdited,
            "editedBy" to recomputed.editedBy,
            "editedAt" to recomputed.editedAt,
            "editHistory" to recomputed.editHistory
        )
    }

    fun deletePinHistoryEntry(pin: PinItem, lineIndex: Int, onError: ((String) -> Unit)? = null): PinItem {
        val lines = pin.editHistory.lines().map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        if (lineIndex !in lines.indices) return pin
        val targetLine = lines.removeAt(lineIndex)
        val now = System.currentTimeMillis()
        val optimisticFields = recomputeHistoryFields(lines, now)
        val docRef = db.collection("pins").document(pin.id)

        if (!isOnline()) {
            docRef.update(optimisticFields)
                .addOnFailureListener { e -> onError?.invoke(e.message ?: "Failed to delete edit") }
        } else {
            db.runTransaction { tx ->
                val snap = tx.get(docRef)
                val liveLines = (snap.getString("editHistory") ?: pin.editHistory)
                    .lines().map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
                val liveIdx = liveLines.indexOf(targetLine)
                if (liveIdx >= 0) {
                    liveLines.removeAt(liveIdx)
                } else if (lineIndex in liveLines.indices) {
                    liveLines.removeAt(lineIndex)
                }
                tx.update(docRef, recomputeHistoryFields(liveLines, now))
                null
            }.addOnFailureListener { e ->
                val code = (e as? FirebaseFirestoreException)?.code
                if (code == FirebaseFirestoreException.Code.UNAVAILABLE || code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED) {
                    docRef.update(optimisticFields)
                        .addOnFailureListener { err -> onError?.invoke(err.message ?: "Failed to delete edit") }
                } else {
                    onError?.invoke(e.message ?: "Failed to delete edit")
                }
            }
        }

        return pin.copy(
            status = optimisticFields["status"] as String,
            issueReason = optimisticFields["issueReason"] as String,
            updatedAt = now,
            isEdited = optimisticFields["isEdited"] as Boolean,
            editedBy = optimisticFields["editedBy"] as String,
            editedAt = optimisticFields["editedAt"] as Long,
            editHistory = optimisticFields["editHistory"] as String
        )
    }

    fun resetPinStatusToPending(pin: PinItem, onError: ((String) -> Unit)? = null): PinItem {
        val now = System.currentTimeMillis()
        db.collection("pins").document(pin.id).update(
            mapOf(
                "status" to "pending",
                "issueReason" to "",
                "updatedAt" to now,
                "isEdited" to false,
                "editedBy" to "",
                "editedAt" to 0L,
                "editHistory" to ""
            )
        ).addOnFailureListener { e ->
            onError?.invoke(e.message ?: "Failed to reset pin status")
        }
        return pin.copy(
            status = "pending",
            issueReason = "",
            updatedAt = now,
            isEdited = false,
            editedBy = "",
            editedAt = 0L,
            editHistory = ""
        )
    }

    fun pendingUploadCount(): Int = pendingWritesByLayer.values.sum()

    fun restorePinStatusSnapshot(previousPin: PinItem, onError: ((String) -> Unit)? = null): PinItem {
        val now = System.currentTimeMillis()
        db.collection("pins").document(previousPin.id).update(
            mapOf(
                "status" to previousPin.status,
                "issueReason" to previousPin.issueReason,
                "updatedAt" to now,
                "isEdited" to previousPin.isEdited,
                "editedBy" to previousPin.editedBy,
                "editedAt" to previousPin.editedAt,
                "editHistory" to previousPin.editHistory
            )
        ).addOnFailureListener { e ->
            onError?.invoke(e.message ?: "Failed to restore pin status")
        }
        return previousPin.copy(updatedAt = now)
    }

    fun deletePin(pinId: String, onError: ((String) -> Unit)? = null) {
        db.collection("pins").document(pinId).delete()
            .addOnFailureListener { e -> onError?.invoke(e.message ?: "Failed to delete pin") }
    }

    fun deletePinsBatch(pinIds: Collection<String>, onError: ((String) -> Unit)? = null) {
        if (pinIds.isEmpty()) return
        val reported = AtomicBoolean(false)
        pinIds.distinct().chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { id -> batch.delete(db.collection("pins").document(id)) }
            batch.commit().addOnFailureListener { e ->
                if (reported.compareAndSet(false, true)) {
                    onError?.invoke(e.message ?: "Failed to delete selected pins")
                }
            }
        }
    }

    fun deleteLayer(layerId: String, pinIds: List<String>, trackIds: List<String>, onError: ((String) -> Unit)? = null) {
        val layerRef = db.collection("layers").document(layerId)
        val childRefs = ArrayList<DocumentReference>(pinIds.size + trackIds.size)
        pinIds.forEach { childRefs += db.collection("pins").document(it) }
        trackIds.forEach { childRefs += db.collection("tracks").document(it) }

        if (childRefs.isEmpty()) {
            layerRef.delete().addOnFailureListener { e ->
                onError?.invoke(e.message ?: "Failed to delete folder from cloud")
            }
            return
        }

        if (!isOnline()) {
            val allRefs = ArrayList<DocumentReference>(childRefs.size + 1).apply {
                addAll(childRefs)
                add(layerRef)
            }
            val reported = AtomicBoolean(false)
            allRefs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it) }
                batch.commit().addOnFailureListener { e ->
                    if (reported.compareAndSet(false, true)) {
                        onError?.invoke(e.message ?: "Failed to delete folder from cloud")
                    }
                }
            }
            return
        }

        val childChunks = childRefs.chunked(400)
        val remaining = AtomicInteger(childChunks.size)
        val hadFailure = AtomicBoolean(false)
        childChunks.forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it) }
            batch.commit()
                .addOnFailureListener { e ->
                    if (hadFailure.compareAndSet(false, true)) {
                        onError?.invoke(e.message ?: "Failed to delete folder contents from cloud")
                    }
                }
                .addOnCompleteListener {
                    if (remaining.decrementAndGet() == 0 && !hadFailure.get()) {
                        layerRef.delete().addOnFailureListener { e ->
                            onError?.invoke(e.message ?: "Failed to delete folder from cloud")
                        }
                    }
                }
        }
    }

    private fun formatTime(ts: Long): String = TimeFormatters.canonicalHistory(ts)
}
