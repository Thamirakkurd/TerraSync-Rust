package com.terrasync.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

class TerraSyncCarAppService : CarAppService() {
    override fun onCreate() {
        super.onCreate()
        RoutingEngine.appContext = applicationContext
        val ver = BuildConfig.VERSION_NAME.trim().removePrefix("v").removePrefix("V")
            .ifBlank { "3.11.0" }
        RoutingEngine.userAgent = "TerraSync/$ver (https://github.com/Thamirakkurd/TerraSync)"
    }

    override fun createHostValidator(): HostValidator {
        return if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = TerraSyncCarSession()
}

class TerraSyncCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return TerraSyncCarFoldersScreen(carContext)
    }
}

private class TerraSyncCarFoldersScreen(carContext: CarContext) : Screen(carContext) {
    private val repository = TerraSyncRepository(carContext.applicationContext)
    private val repo get() = repository.syncRepo
    private val mainHandler = Handler(Looper.getMainLooper())
    private var layers: List<LayerItem> = emptyList()
    private var pins: List<PinItem> = emptyList()
    private var loaded = false
    private var destroyed = false

    private fun refreshMergedState(updatedCloudPin: PinItem? = null) {
        if (destroyed) return
        if (updatedCloudPin != null) {
            repository.applyOptimisticCloudPinUpdate(updatedCloudPin)
        } else {
            repository.applyMergedFolders()
        }
    }

    init {
        repository.onSnapshotUpdated = { snap ->
            mainHandler.post {
                if (!destroyed) {
                    layers = snap.layers
                    pins = snap.pins
                    loaded = true
                    invalidate()
                }
            }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                LocalFolderStore.preloadAsync(carContext)
                repository.refreshJoinedSync()
            }

            override fun onResume(owner: LifecycleOwner) {
                if (!loaded || destroyed) return
                LocalFolderStore.preloadAsync(carContext) {
                    if (!destroyed) refreshMergedState()
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                destroyed = true
                mainHandler.removeCallbacksAndMessages(null)
                repository.stop()
            }
        })
    }

    private val locationTracker = CarLocationTracker(carContext, lifecycle) { invalidate() }

    private fun getLastKnownLocation(): Location? = locationTracker.getBestLocation()

    override fun onGetTemplate(): Template {
        if (!loaded) {
            return ListTemplate.Builder()
                .setTitle("TerraSync")
                .setHeaderAction(Action.APP_ICON)
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
            .setNoItemsMessage("No bookmark folders synced yet. Import a KML on your phone.")

        val hidden = DevicePrefs.getHiddenLayerIds(carContext)
        val pinnedIds = DevicePrefs.getPinnedLayerIds(carContext)
        val visibleLayers = layers
            .filter { it.id !in hidden }
            .sortedWith(compareByDescending<LayerItem> { it.id in pinnedIds }.thenByDescending { it.updatedAt })
        val myLoc = getLastKnownLocation()
        val visiblePins = ArrayList<PinItem>(pins.size)
        val pinsByLayer = HashMap<String, ArrayList<PinItem>>(visibleLayers.size.coerceAtLeast(8))
        var pendingCount = 0
        var issueCount = 0
        var nearestUnfinished: PinItem? = null
        var nearestDistM = Double.POSITIVE_INFINITY
        var firstIssue: PinItem? = null
        var firstPending: PinItem? = null
        for (pin in pins) {
            if (pin.layerId in hidden) continue
            visiblePins.add(pin)
            pinsByLayer.getOrPut(pin.layerId) { ArrayList() }.add(pin)
            val st = PinStatus.fromRaw(pin.status)
            if (st == PinStatus.PENDING) {
                pendingCount++
                if (firstPending == null) firstPending = pin
            } else if (st == PinStatus.ISSUE) {
                issueCount++
                if (firstIssue == null) firstIssue = pin
            }
            if (st != PinStatus.DONE && myLoc != null) {
                if (!nearestDistM.isFinite() || kotlin.math.abs(pin.lat - myLoc.latitude) * 110_000.0 < nearestDistM) {
                    val d = RoutingEngine.haversineMeters(myLoc.latitude, myLoc.longitude, pin.lat, pin.lng)
                    if (d < nearestDistM) {
                        nearestDistM = d
                        nearestUnfinished = pin
                    }
                }
            }
        }
        if (myLoc == null) {
            nearestUnfinished = firstIssue ?: firstPending
        }
        var headerCount = 0

        if (visiblePins.isNotEmpty()) {
            if (nearestUnfinished != null) {
                val folderTitle = visibleLayers.find { it.id == nearestUnfinished.layerId }?.name
                    ?: layers.find { it.id == nearestUnfinished.layerId }?.name
                    ?: "Pin"
                val distStr = if (myLoc != null) {
                    val m = RoutingEngine.haversineMeters(myLoc.latitude, myLoc.longitude, nearestUnfinished.lat, nearestUnfinished.lng)
                    if (m < 1000.0) "${m.toInt()} m" else "${"%.1f".format(java.util.Locale.US, m / 1000.0)} km"
                } else {
                    PinStatus.fromRaw(nearestUnfinished.status).label
                }
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("${AppStrings.nearestPending(carContext)}: ${nearestUnfinished.name.ifBlank { "Pin" }}")
                        .addText("$distStr • $folderTitle")
                        .setBrowsable(true)
                        .setOnClickListener {
                            val isCloud = !LocalFolderStore.isLocalLayerId(nearestUnfinished.layerId)
                            screenManager.push(
                                TerraSyncCarPinDetailScreen(carContext, nearestUnfinished, folderTitle, repo) {
                                    val updated = if (isCloud) nearestUnfinished.copy(status = "done", issueReason = "") else null
                                    refreshMergedState(updated)
                                }
                            )
                        }
                        .build()
                )
                headerCount++
            }

            listBuilder.addItem(
                Row.Builder()
                    .setTitle("All Visible Pins (${visiblePins.size})")
                    .addText("Pending: $pendingCount  •  Issue: $issueCount")
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(
                            TerraSyncCarPinsScreen(carContext, "All Visible Pins", visiblePins, visibleLayers, repo) { cloudPinId ->
                                val updated = cloudPinId?.let { id -> pins.firstOrNull { it.id == id }?.copy(status = "done", issueReason = "") }
                                refreshMergedState(updated)
                            }
                        )
                    }
                    .build()
            )
            headerCount++
        }

        val folderSlots = (6 - headerCount).coerceAtLeast(0)
        visibleLayers.take(folderSlots).forEach { layer ->
            val folderPins = pinsByLayer[layer.id].orEmpty()
            var pending = 0
            var issues = 0
            for (fp in folderPins) {
                when (PinStatus.fromRaw(fp.status)) {
                    PinStatus.PENDING -> pending++
                    PinStatus.ISSUE -> issues++
                    else -> {}
                }
            }
            val isPinned = layer.id in pinnedIds
            val prefix = if (isPinned) "★ " else ""
            val subPrefix = if (isPinned) "Pinned • " else ""
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("$prefix${layer.name.ifBlank { "Folder" }}")
                    .addText("${subPrefix}${folderPins.size} pins  •  Pending: $pending  •  Issue: $issues")
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(
                            TerraSyncCarPinsScreen(carContext, layer.name, folderPins, visibleLayers, repo) { cloudPinId ->
                                val updated = cloudPinId?.let { id -> pins.firstOrNull { it.id == id }?.copy(status = "done", issueReason = "") }
                                refreshMergedState(updated)
                            }
                        )
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle("TerraSync Folders")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(listBuilder.build())
            .build()
    }
}

private class TerraSyncCarPinsScreen(
    carContext: CarContext,
    private val title: String,
    private val sourcePins: List<PinItem>,
    private val layers: List<LayerItem>,
    private val repo: SyncRepo,
    private val onPinMarkedDone: (String?) -> Unit
) : Screen(carContext) {

    private val workingPins = sourcePins.toMutableList()
    private val locationTracker = CarLocationTracker(carContext, lifecycle) { invalidate() }
    private var statusFilter: String = "all"

    private fun getLastKnownLocation(): Location? = locationTracker.getBestLocation()

    override fun onGetTemplate(): Template {
        val myLoc = getLastKnownLocation()
        val filteredPins = when (statusFilter) {
            "pending" -> workingPins.filter { PinStatus.fromRaw(it.status) == PinStatus.PENDING }
            "issue" -> workingPins.filter { PinStatus.fromRaw(it.status) == PinStatus.ISSUE }
            else -> workingPins
        }
        val prioritized = filteredPins.sortedWith(
            compareBy<PinItem> {
                when (PinStatus.fromRaw(it.status)) {
                    PinStatus.ISSUE -> 0
                    PinStatus.PENDING -> 1
                    PinStatus.DONE -> 2
                }
            }.thenBy {
                if (myLoc != null) {
                    RoutingEngine.haversineMeters(myLoc.latitude, myLoc.longitude, it.lat, it.lng)
                } else 0.0
            }
        ).take(6)

        val listBuilder = ItemList.Builder()
            .setNoItemsMessage("No pins available in this folder.")

        prioritized.forEach { pin ->
            val meters = if (myLoc != null) {
                RoutingEngine.haversineMeters(myLoc.latitude, myLoc.longitude, pin.lat, pin.lng)
            } else 100.0

            val distanceObj = if (meters < 1000.0) {
                Distance.create(meters.coerceAtLeast(1.0), Distance.UNIT_METERS)
            } else {
                Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
            }

            val folderName = layers.find { it.id == pin.layerId }?.name ?: "Pin"
            val pinStatus = PinStatus.fromRaw(pin.status)
            val details = buildString {
                append("  • ")
                append(pinStatus.label)
                if ((pinStatus == PinStatus.ISSUE || pinStatus == PinStatus.DONE) && pin.issueReason.isNotBlank()) {
                    append(" (${DailyReportGenerator.flattenSingleLine(pin.issueReason)})")
                } else if (folderName.isNotBlank()) {
                    append(" • $folderName")
                }
            }

            val line = SpannableString(details).apply {
                setSpan(DistanceSpan.create(distanceObj), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }

            val markerColor = when (pinStatus) {
                PinStatus.DONE -> CarColor.GREEN
                PinStatus.ISSUE -> CarColor.RED
                PinStatus.PENDING -> CarColor.YELLOW
            }

            val place = Place.Builder(CarLocation.create(pin.lat, pin.lng))
                .setMarker(PlaceMarker.Builder().setColor(markerColor).build())
                .build()

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(pin.name.ifBlank { "Pin" })
                    .addText(line)
                    .setMetadata(Metadata.Builder().setPlace(place).build())
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(
                            TerraSyncCarPinDetailScreen(carContext, pin, folderName, repo) {
                                val idx = workingPins.indexOfFirst { it.id == pin.id }
                                if (idx >= 0) workingPins[idx] = workingPins[idx].copy(status = "done", issueReason = "")
                                val cloudPinId = if (LocalFolderStore.isLocalLayerId(pin.layerId)) null else pin.id
                                onPinMarkedDone(cloudPinId)
                                invalidate()
                            }
                        )
                    }
                    .build()
            )
        }

        val filterTag = when (statusFilter) {
            "pending" -> " • Pending"
            "issue" -> " • Issues"
            else -> ""
        }
        val chipLabel = when (statusFilter) {
            "pending" -> "Pending"
            "issue" -> "Issues"
            else -> "All"
        }
        val builder = PlaceListMapTemplate.Builder()
            .setTitle("$title$filterTag")
            .setHeaderAction(Action.BACK)
            .setItemList(listBuilder.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(chipLabel)
                            .setOnClickListener {
                                statusFilter = when (statusFilter) {
                                    "all" -> "pending"
                                    "pending" -> "issue"
                                    else -> "all"
                                }
                                invalidate()
                            }
                            .build()
                    )
                    .build()
            )

        if (myLoc != null) {
            builder.setCurrentLocationEnabled(true)
        }
        return builder.build()
    }
}

private class CarLocationTracker(
    private val context: Context,
    lifecycle: Lifecycle,
    private val onLocationMoved: () -> Unit
) : DefaultLifecycleObserver {

    @Volatile private var lastKnown: Location? = null
    @Volatile private var registered = false

    private val listener = LocationListener { loc ->
        val prev = lastKnown
        lastKnown = loc
        val movedMeters = if (prev == null) Float.MAX_VALUE else prev.distanceTo(loc)
        if (movedMeters >= 40f) {
            onLocationMoved()
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        start()
    }

    override fun onStop(owner: LifecycleOwner) {
        stop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stop()
    }

    @SuppressLint("MissingPermission")
    fun getBestLocation(): Location? {
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return lastKnown
        val cached = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        val cur = lastKnown
        return when {
            cur == null -> cached
            cached == null -> cur
            cached.time > cur.time -> cached
            else -> cur
        }
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (registered) return
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        var any = false
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 10_000L, 40f, listener, Looper.getMainLooper())
                    any = true
                }
            }
        }
        registered = any
    }

    private fun stop() {
        if (!registered) return
        registered = false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        runCatching { lm.removeUpdates(listener) }
    }
}

private class TerraSyncCarPinDetailScreen(
    carContext: CarContext,
    private val pin: PinItem,
    private val folderName: String,
    private val repo: SyncRepo,
    private val onMarkedDone: () -> Unit
) : Screen(carContext) {

    private var currentStatus = pin.status
    private var currentReason = pin.issueReason

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("Status: ${statusLabel(currentStatus)}")
                    .addText("Folder: $folderName")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Coordinates")
                    .addText("${"%.6f".format(java.util.Locale.US, pin.lat)}, ${"%.6f".format(java.util.Locale.US, pin.lng)}")
                    .build()
            )

        if (currentReason.isNotBlank() && (currentStatus.equals("issue", true) || currentStatus.equals("done", true))) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(if (currentStatus.equals("done", true)) "Done Note" else "Issue")
                    .addText(currentReason)
                    .build()
            )
        } else if (pin.description.isNotBlank()) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Notes")
                    .addText(pin.description.take(120))
                    .build()
            )
        }

        val navAction = Action.Builder()
            .setTitle("Navigate")
            .setBackgroundColor(CarColor.BLUE)
            .setOnClickListener {
                val uri = Uri.parse("geo:0,0?q=${pin.lat},${pin.lng}(${Uri.encode(pin.name)})")
                val navIntent = Intent(CarContext.ACTION_NAVIGATE, uri)
                runCatching {
                    carContext.startCarApp(navIntent)
                }.onFailure {
                    CarToast.makeText(carContext, "No car navigation app available", CarToast.LENGTH_SHORT).show()
                }
            }
            .build()
        paneBuilder.addAction(navAction)

        if (!currentStatus.equals("done", true)) {
            val doneAction = Action.Builder()
                .setTitle("Mark Done")
                .setBackgroundColor(CarColor.GREEN)
                .setOnClickListener {
                    val stamp = DevicePrefs.getDeviceStamp(carContext)
                    if (LocalFolderStore.isLocalLayerId(pin.layerId)) {
                        LocalFolderStore.updatePinStatus(carContext, pin, "done", stamp)
                    } else {
                        repo.updatePinStatus(pin, "done", stamp)
                    }
                    currentStatus = "done"
                    currentReason = ""
                    onMarkedDone()
                    CarToast.makeText(carContext, "Marked Done", CarToast.LENGTH_SHORT).show()
                    invalidate()
                }
                .build()
            paneBuilder.addAction(doneAction)
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(pin.name.ifBlank { "Pin Details" })
            .setHeaderAction(Action.BACK)
            .build()
    }
}
