package com.terrasync.app

import android.content.Context
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.floor

/**
 * Immutable indexed snapshot of merged local (SQLite WAL) and cloud (Firestore) folders,
 * pins, and tracks for O(1) lookup and stack spatial bucketing.
 */
data class FolderIndexSnapshot(
    val layers: List<LayerItem> = emptyList(),
    val pins: List<PinItem> = emptyList(),
    val tracks: List<TrackItem> = emptyList(),
    val layerById: Map<String, LayerItem> = emptyMap(),
    val pinById: Map<String, PinItem> = emptyMap(),
    val pinsByLayerId: Map<String, List<PinItem>> = emptyMap(),
    val tracksByLayerId: Map<String, List<TrackItem>> = emptyMap(),
    val stackedPinBuckets: Map<Long, List<PinItem>> = emptyMap(),
    val dataVersion: Long = 0L,
    val isLiveConnected: Boolean = false
)

internal const val STACKED_CELL_SIZE = 0.00003

internal fun stackedBucketKey(cx: Int, cy: Int): Long =
    (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)

internal fun buildFolderIndexSnapshot(
    mergedLayers: List<LayerItem>,
    mergedPins: List<PinItem>,
    mergedTracks: List<TrackItem>,
    version: Long,
    isLiveConnected: Boolean,
    cellSize: Double = STACKED_CELL_SIZE
): FolderIndexSnapshot {
    val sortedLayers = mergedLayers.sortedByDescending { it.updatedAt }
    val lById = sortedLayers.associateBy { it.id }
    val pById = mergedPins.associateBy { it.id }
    val pByLayer = mergedPins.groupBy { it.layerId }
    val tByLayer = mergedTracks.groupBy { it.layerId }
    val buckets: Map<Long, List<PinItem>> = if (RustCoreBridge.isAvailable() && mergedPins.size > 500) {
        emptyMap()
    } else {
        HashMap<Long, MutableList<PinItem>>(mergedPins.size).also { map ->
            for (pItem in mergedPins) {
                val bx = floor(pItem.lat / cellSize).toInt()
                val by = floor(pItem.lng / cellSize).toInt()
                map.getOrPut(stackedBucketKey(bx, by)) { ArrayList(2) }.add(pItem)
            }
        }
    }
    return FolderIndexSnapshot(
        layers = sortedLayers,
        pins = mergedPins,
        tracks = mergedTracks,
        layerById = lById,
        pinById = pById,
        pinsByLayerId = pByLayer,
        tracksByLayerId = tByLayer,
        stackedPinBuckets = buckets,
        dataVersion = version,
        isLiveConnected = isLiveConnected
    )
}

internal fun findExactStackedPinsFromSnapshot(
    snapshot: FolderIndexSnapshot,
    pin: PinItem,
    hiddenLayerIds: Set<String>,
    visibleStatuses: Set<String>,
    sentIds: Set<String>,
    cellSize: Double = STACKED_CELL_SIZE
): List<PinItem> {
    RustCoreBridge.findExactStackedPins(
        version = snapshot.dataVersion,
        pins = snapshot.pins,
        targetPin = pin,
        hiddenLayerIds = hiddenLayerIds,
        visibleStatuses = visibleStatuses,
        sentIds = sentIds,
        cellSize = cellSize
    )?.let { return it }
    val cx = floor(pin.lat / cellSize).toInt()
    val cy = floor(pin.lng / cellSize).toInt()
    val stack = ArrayList<PinItem>(4)
    for (dx in -1..1) {
        for (dy in -1..1) {
            val bucket = snapshot.stackedPinBuckets[stackedBucketKey(cx + dx, cy + dy)] ?: continue
            for (it in bucket) {
                val normStatus = PinStatus.fromRaw(it.status).key
                if (it.layerId !in hiddenLayerIds &&
                    normStatus in visibleStatuses &&
                    it.id !in sentIds &&
                    abs(it.lat - pin.lat) <= cellSize &&
                    abs(it.lng - pin.lng) <= cellSize &&
                    RoutingEngine.haversineMeters(it.lat, it.lng, pin.lat, pin.lng) <= 2.0
                ) {
                    stack.add(it)
                }
            }
        }
    }
    if (stack.none { it.id == pin.id }) return listOf(pin)
    if (stack.size > 1) stack.sortBy { it.id }
    return stack
}

/**
 * Coordinates LocalFolderStore (SQLite WAL) and SyncRepo (Firestore) into a unified,
 * race-free FolderIndexSnapshot and unified pin/layer mutation API.
 */
class TerraSyncRepository(
    context: Context,
    syncRepoOverride: SyncRepo? = null
) {
    private val appContext: Context = context.applicationContext
    private val syncRepoDelegate = lazy { syncRepoOverride ?: SyncRepo(appContext) }
    val syncRepo: SyncRepo get() = syncRepoDelegate.value
    private val mergeIndexGeneration = AtomicInteger(0)
    private val pendingCallbacksLock = Any()
    private val pendingIndexedCallbacks = mutableListOf<(FolderIndexSnapshot) -> Unit>()
    private var isPreloadingLocal = false
    private val pendingGeoDeltaPins = LinkedHashMap<String, PinItem>()
    private var pendingGeoDeltaInvalidated = false

    @Volatile private var cloudLayers: List<LayerItem> = emptyList()
    @Volatile private var cloudPins: List<PinItem> = emptyList()
    @Volatile private var cloudTracks: List<TrackItem> = emptyList()
    @Volatile private var liveConnected: Boolean = false
    @Volatile var snapshot: FolderIndexSnapshot = FolderIndexSnapshot()
        private set

    private val snapshotListeners = java.util.concurrent.CopyOnWriteArraySet<(FolderIndexSnapshot) -> Unit>()
    var onSnapshotUpdated: ((FolderIndexSnapshot) -> Unit)? = null

    fun addSnapshotListener(listener: (FolderIndexSnapshot) -> Unit) {
        snapshotListeners.add(listener)
    }

    fun removeSnapshotListener(listener: (FolderIndexSnapshot) -> Unit) {
        snapshotListeners.remove(listener)
    }

    fun applyMergedFolders(
        onIndexed: ((FolderIndexSnapshot) -> Unit)? = null,
        deltaUpdatedPins: List<PinItem>? = null
    ) {
        synchronized(pendingCallbacksLock) {
            if (onIndexed != null) {
                pendingIndexedCallbacks.add(onIndexed)
            }
            if (deltaUpdatedPins != null) {
                if (!pendingGeoDeltaInvalidated) {
                    for (p in deltaUpdatedPins) pendingGeoDeltaPins[p.id] = p
                }
            } else {
                pendingGeoDeltaInvalidated = true
                pendingGeoDeltaPins.clear()
            }
        }
        val cachedLocal = LocalFolderStore.getCachedOrNull()
        val local = if (cachedLocal != null) {
            cachedLocal
        } else {
            val shouldTriggerPreload = synchronized(pendingCallbacksLock) {
                if (LocalFolderStore.getCachedOrNull() != null) {
                    false
                } else if (!isPreloadingLocal) {
                    isPreloadingLocal = true
                    true
                } else {
                    false
                }
            }
            if (LocalFolderStore.getCachedOrNull() != null) {
                LocalFolderStore.getCachedOrNull()!!
            } else {
                if (shouldTriggerPreload) {
                    LocalFolderStore.preloadAsync(appContext) {
                        AppExecutors.postMain {
                            synchronized(pendingCallbacksLock) {
                                isPreloadingLocal = false
                            }
                            applyMergedFolders()
                        }
                    }
                }
                return
            }
        }

        val gen = mergeIndexGeneration.incrementAndGet()
        val mergedLayersRaw = cloudLayers + local.layers
        val mergedPins = cloudPins + local.pins
        val mergedTracks = cloudTracks + local.tracks
        val nextVer = snapshot.dataVersion + 1L
        val connectedSnap = liveConnected

        fun commitSnapshot(built: FolderIndexSnapshot) {
            val prevSnap = snapshot
            val deltaBatch: List<PinItem> = synchronized(pendingCallbacksLock) {
                val out = if (!pendingGeoDeltaInvalidated && pendingGeoDeltaPins.isNotEmpty()) {
                    pendingGeoDeltaPins.values.toList()
                } else {
                    emptyList()
                }
                pendingGeoDeltaPins.clear()
                pendingGeoDeltaInvalidated = false
                out
            }
            snapshot = built
            if (deltaBatch.isNotEmpty() && built.pins.size == prevSnap.pins.size) {
                RustCoreBridge.patchGeoPins(prevSnap.dataVersion, built.dataVersion, built.pins.size, deltaBatch)
            }
            val joinedEmpty = DevicePrefs.getJoinedLayerIds(appContext).isEmpty()
            if (built.layers.isNotEmpty() || connectedSnap || joinedEmpty) {
                DevicePrefs.saveCachedLayerNames(appContext, built.layers.map { it.name })
                if (connectedSnap || joinedEmpty) {
                    DevicePrefs.prunePinReferencesKeeping(appContext, built.pinById.keys)
                }
            }
            val callbacksToNotify = synchronized(pendingCallbacksLock) {
                if (pendingIndexedCallbacks.isEmpty()) {
                    emptyList()
                } else {
                    pendingIndexedCallbacks.toList().also { pendingIndexedCallbacks.clear() }
                }
            }
            onSnapshotUpdated?.invoke(built)
            snapshotListeners.forEach { it.invoke(built) }
            callbacksToNotify.forEach { it.invoke(built) }
        }

        if (mergedPins.size < 200) {
            val built = buildFolderIndexSnapshot(
                mergedLayers = mergedLayersRaw,
                mergedPins = mergedPins,
                mergedTracks = mergedTracks,
                version = nextVer,
                isLiveConnected = connectedSnap
            )
            AppExecutors.postMain {
                if (gen == mergeIndexGeneration.get()) {
                    commitSnapshot(built)
                }
            }
            return
        }

        AppExecutors.uiQueryExecutor.execute {
            val built = buildFolderIndexSnapshot(
                mergedLayers = mergedLayersRaw,
                mergedPins = mergedPins,
                mergedTracks = mergedTracks,
                version = nextVer,
                isLiveConnected = connectedSnap
            )
            AppExecutors.postMain {
                if (gen == mergeIndexGeneration.get()) {
                    commitSnapshot(built)
                }
            }
        }
    }

    fun refreshJoinedSync(onIndexed: ((FolderIndexSnapshot) -> Unit)? = null) {
        val joined = DevicePrefs.getJoinedLayerIds(appContext)
        syncRepo.syncJoinedLayers(joined) { newLayers, newPins, newTracks, connected ->
            cloudLayers = newLayers
            cloudPins = newPins
            cloudTracks = newTracks
            liveConnected = connected
            applyMergedFolders(onIndexed)
        }
    }

    fun joinShareCode(rawCode: String, onDone: (Boolean, String, String?) -> Unit) {
        syncRepo.joinLayerByCode(rawCode, onDone)
    }

    fun isLayerOwner(layer: LayerItem?): Boolean {
        if (layer == null) return false
        if (LocalFolderStore.isLocalLayerId(layer.id)) return true
        val uid = syncRepo.currentUid()
        if (layer.creatorUid.isNotBlank()) {
            return !uid.isNullOrBlank() && layer.creatorUid == uid
        }
        val myDevId = DevicePrefs.getDeviceId(appContext)
        return (layer.creatorDeviceId.isNotBlank() && layer.creatorDeviceId.equals(myDevId, ignoreCase = true)) ||
            (layer.creatorDeviceId.isBlank() && layer.createdBy.contains("#$myDevId", ignoreCase = true))
    }

    fun buildLocalDeviceAliasMap(): Map<String, String> {
        val custom = DevicePrefs.getCustomName(appContext).trim()
        if (custom.isBlank()) return emptyMap()
        val devId = DevicePrefs.getDeviceId(appContext).trim()
        return if (devId.isNotBlank()) mapOf(devId to custom) else emptyMap()
    }

    fun normalizeLayerNameKey(name: String): String {
        return name.trim().lowercase(Locale.US).replace(Regex("[_\\s-]+"), " ")
    }

    fun makeUniqueLayerName(baseName: String): String {
        val clean = baseName.trim().ifBlank { "Imported Folder" }
        val existingKeys = buildSet {
            snapshot.layers.forEach { add(normalizeLayerNameKey(it.name)) }
            DevicePrefs.getCachedLayerNames(appContext).forEach { add(normalizeLayerNameKey(it)) }
        }
        if (normalizeLayerNameKey(clean) !in existingKeys) return clean
        val stem = clean.replace(Regex("\\s*\\(\\d+\\)$"), "").trim().ifBlank { clean }
        var n = 2
        while (normalizeLayerNameKey("$stem ($n)") in existingKeys) {
            n++
        }
        return "$stem ($n)"
    }

    fun applyOptimisticCloudPinUpdate(updated: PinItem) {
        if (cloudPins.any { it.id == updated.id }) {
            cloudPins = cloudPins.map { if (it.id == updated.id) updated else it }
        }
        applyMergedFolders(deltaUpdatedPins = listOf(updated))
    }

    fun savePinStatus(
        pin: PinItem,
        status: String,
        stamp: String,
        reason: String = "",
        onError: ((String) -> Unit)? = null
    ) {
        if (LocalFolderStore.isLocalLayerId(pin.layerId)) {
            val updated = LocalFolderStore.updatePinStatus(appContext, pin, status, stamp, reason)
            applyMergedFolders(deltaUpdatedPins = listOf(updated))
        } else {
            val updated = syncRepo.updatePinStatus(pin, status, stamp, reason, onError)
            applyOptimisticCloudPinUpdate(updated)
        }
    }

    fun savePinsStatusBatch(
        targetPins: List<PinItem>,
        status: String,
        stamp: String,
        reason: String = "",
        onError: ((String) -> Unit)? = null
    ) {
        if (targetPins.isEmpty()) return
        val localPins = targetPins.filter { LocalFolderStore.isLocalLayerId(it.layerId) }
        val remotePins = targetPins.filterNot { LocalFolderStore.isLocalLayerId(it.layerId) }
        val updatedDelta = ArrayList<PinItem>(targetPins.size)
        if (localPins.isNotEmpty()) {
            updatedDelta.addAll(LocalFolderStore.updatePinsStatusBatch(appContext, localPins, status, stamp, reason))
        }
        if (remotePins.isNotEmpty()) {
            val updatedCloud = syncRepo.updatePinsStatusBatch(remotePins, status, stamp, reason, onError)
            updatedDelta.addAll(updatedCloud)
            val byId = updatedCloud.associateBy { it.id }
            cloudPins = cloudPins.map { byId[it.id] ?: it }
        }
        applyMergedFolders(deltaUpdatedPins = updatedDelta)
    }

    fun savePinText(
        pin: PinItem,
        newName: String,
        newDesc: String,
        stamp: String,
        onError: ((String) -> Unit)? = null
    ) {
        if (LocalFolderStore.isLocalLayerId(pin.layerId)) {
            val updated = LocalFolderStore.updatePinText(appContext, pin, newName, newDesc, stamp)
            applyMergedFolders(deltaUpdatedPins = listOf(updated))
        } else {
            val updated = syncRepo.updatePinText(pin, newName, newDesc, stamp, onError)
            applyOptimisticCloudPinUpdate(updated)
        }
    }

    fun deletePinHistoryEntry(
        pin: PinItem,
        idx: Int,
        onError: ((String) -> Unit)? = null
    ): PinItem {
        return if (LocalFolderStore.isLocalLayerId(pin.layerId)) {
            val updated = LocalFolderStore.deletePinHistoryEntry(appContext, pin, idx)
            applyMergedFolders(deltaUpdatedPins = listOf(updated))
            updated
        } else {
            val updated = syncRepo.deletePinHistoryEntry(pin, idx, onError)
            applyOptimisticCloudPinUpdate(updated)
            updated
        }
    }

    fun restorePinStatusSnapshot(
        previousPin: PinItem,
        onError: ((String) -> Unit)? = null
    ) {
        if (LocalFolderStore.isLocalLayerId(previousPin.layerId)) {
            val restored = LocalFolderStore.restorePinStatusSnapshot(appContext, previousPin)
            applyMergedFolders(deltaUpdatedPins = listOf(restored))
        } else {
            val restored = syncRepo.restorePinStatusSnapshot(previousPin, onError)
            applyOptimisticCloudPinUpdate(restored)
        }
    }

    fun resetPinStatus(
        pin: PinItem,
        onError: ((String) -> Unit)? = null
    ): PinItem {
        return if (LocalFolderStore.isLocalLayerId(pin.layerId)) {
            val updated = LocalFolderStore.resetPinStatusToPending(appContext, pin)
            applyMergedFolders(deltaUpdatedPins = listOf(updated))
            updated
        } else {
            val updated = syncRepo.resetPinStatusToPending(pin, onError)
            applyOptimisticCloudPinUpdate(updated)
            updated
        }
    }

    fun deletePin(pin: PinItem, onError: ((String) -> Unit)? = null) {
        DevicePrefs.removePinReferences(appContext, listOf(pin.id))
        if (LocalFolderStore.isLocalLayerId(pin.layerId)) {
            LocalFolderStore.deletePin(appContext, pin.id)
            applyMergedFolders()
        } else {
            syncRepo.deletePin(pin.id, onError)
            cloudPins = cloudPins.filterNot { it.id == pin.id }
            applyMergedFolders()
        }
    }

    fun deletePins(pinsToDelete: List<PinItem>, onError: ((String) -> Unit)? = null) {
        if (pinsToDelete.isEmpty()) return
        DevicePrefs.removePinReferences(appContext, pinsToDelete.map { it.id })
        val localIds = pinsToDelete.filter { LocalFolderStore.isLocalLayerId(it.layerId) }.map { it.id }
        val cloudIds = pinsToDelete.filterNot { LocalFolderStore.isLocalLayerId(it.layerId) }.map { it.id }
        if (localIds.isNotEmpty()) {
            LocalFolderStore.deletePins(appContext, localIds)
        }
        if (cloudIds.isNotEmpty()) {
            val dropSet = cloudIds.toSet()
            syncRepo.deletePinsBatch(cloudIds, onError)
            cloudPins = cloudPins.filterNot { it.id in dropSet }
        }
        applyMergedFolders()
    }

    fun addManualPin(
        name: String,
        description: String,
        lat: Double,
        lng: Double,
        deviceStamp: String,
        deviceId: String
    ): PinItem {
        val saved = LocalFolderStore.addManualPin(appContext, name, description, lat, lng, deviceStamp, deviceId)
        applyMergedFolders()
        return saved
    }

    fun renameLayer(layer: LayerItem, newName: String, onError: ((String) -> Unit)? = null) {
        if (LocalFolderStore.isLocalLayerId(layer.id)) {
            LocalFolderStore.renameLayer(appContext, layer.id, newName)
            applyMergedFolders()
        } else {
            syncRepo.renameLayer(layer.id, newName, onError)
        }
    }

    fun deleteLocalLayer(layerId: String) {
        val deletedPinIds = snapshot.pinsByLayerId[layerId]?.map { it.id }.orEmpty()
        LocalFolderStore.deleteLayer(appContext, layerId)
        DevicePrefs.removeJoinedLayerId(appContext, layerId)
        DevicePrefs.removePinReferences(appContext, deletedPinIds)
        applyMergedFolders()
    }

    fun saveNewLocalLayer(
        layerId: String,
        layerName: String,
        stamp: String,
        deviceId: String,
        parsed: ParsedKml
    ) {
        LocalFolderStore.saveNewLayer(appContext, layerId, layerName, stamp, deviceId, parsed)
        applyMergedFolders()
    }

    internal fun setCloudStateForTest(
        layers: List<LayerItem> = emptyList(),
        pins: List<PinItem> = emptyList(),
        tracks: List<TrackItem> = emptyList(),
        connected: Boolean = false
    ) {
        cloudLayers = layers
        cloudPins = pins
        cloudTracks = tracks
        liveConnected = connected
    }

    fun stop() {
        onSnapshotUpdated = null
        snapshotListeners.clear()
        if (syncRepoDelegate.isInitialized()) {
            syncRepo.stopListening()
        }
    }
}
