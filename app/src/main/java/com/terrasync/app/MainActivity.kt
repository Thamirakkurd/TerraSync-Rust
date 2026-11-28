package com.terrasync.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil

class MainActivity : AppCompatActivity() {

    companion object {
        internal val ALL_STATUSES = setOf("pending", "done", "issue")

        private val SCALE_STEPS_METRIC = doubleArrayOf(
            5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0,
            10000.0, 20000.0, 50000.0, 100000.0, 200000.0
        )
        private val SCALE_STEPS_IMPERIAL = doubleArrayOf(
            20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2640.0, 5280.0, 10560.0,
            26400.0, 52800.0, 105600.0, 264000.0
        )
    }

    private lateinit var mapView: MapView
    private lateinit var topCluster: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var routeFromBanner: LinearLayout
    private lateinit var tvRouteFromText: TextView
    private lateinit var tvRouteFromHint: TextView
    private lateinit var btnClearRouteFrom: ImageButton
    private lateinit var syncStatusContainer: FrameLayout
    private lateinit var btnSyncStatus: ImageButton
    private lateinit var tvSyncPendingBadge: TextView
    private lateinit var undoController: StatusUndoController
    private lateinit var btnCompass: ImageButton
    private lateinit var btnStyleToggle: ImageButton
    private lateinit var btnStatusFilter: ImageButton
    internal lateinit var btnSentToggle: TextView
    private lateinit var btnLasso: ImageButton
    private lateinit var btnRuler: ImageButton
    private lateinit var lassoOverlay: LassoOverlayView
    private lateinit var lassoCard: LinearLayout
    private lateinit var tvLassoTitle: TextView
    private lateinit var tvLassoSubtitle: TextView
    private lateinit var btnLassoMarkSent: TextView
    private lateinit var btnLassoRedraw: TextView
    private lateinit var btnLassoUndo: TextView
    private lateinit var btnLassoReset: TextView
    private lateinit var btnLassoModeToggle: TextView
    private lateinit var btnLassoClose: ImageButton
    private lateinit var leftBottomColumn: LinearLayout
    private lateinit var rightChromeColumn: LinearLayout
    private lateinit var rightArcCluster: FrameLayout
    private lateinit var zoomPillContainer: LinearLayout
    private lateinit var zoomPillDivider: View
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var btnLocate: ImageButton
    private lateinit var btnTabSearch: ImageButton
    private lateinit var btnTabBookmarks: ImageButton
    private lateinit var btnTabSettings: ImageButton
    private lateinit var btnTabAbout: ImageButton
    private lateinit var bottomDock: LinearLayout
    private lateinit var mapControlsRow: LinearLayout
    private lateinit var bottomNavDock: SwipeDockFrameLayout
    private lateinit var bottomIconRow: LinearLayout
    private lateinit var fullScreenSwipeUpCatcher: View
    private var activeOpenNavTab: Int = -1
    private var isFullScreenMapMode: Boolean
        get() = viewModel.isFullScreenMap
        set(value) { viewModel.isFullScreenMap = value }
    private var isCompassVisible: Boolean = false
    private var isSwappingSheetInPlace: Boolean = false
    private lateinit var speedBadge: LinearLayout
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvSpeedUnit: TextView
    private lateinit var rulerCard: LinearLayout
    private lateinit var btnRulerUndo: ImageButton
    private lateinit var tvRulerDistance: TextView
    private lateinit var tvRulerSubtitle: TextView
    private lateinit var btnRulerReset: TextView
    private lateinit var btnRulerClose: ImageButton
    private lateinit var navBanner: LinearLayout
    private lateinit var tvNavArrow: TextView
    private lateinit var tvNavStepDist: TextView
    private lateinit var tvNavInstruction: TextView
    private lateinit var navBottomBar: LinearLayout
    private lateinit var tvNavEta: TextView
    private lateinit var tvNavMeta: TextView
    internal lateinit var btnNavVoice: ImageButton
    private lateinit var btnNavStop: ImageButton
    private lateinit var tvScaleLabel: TextView
    private lateinit var scaleBarLine: ScaleBarView
    private lateinit var scaleBarContainer: LinearLayout

    private lateinit var inScreenSheet: LinearLayout
    private lateinit var sheetDragHandle: View
    internal lateinit var sheetStickyHeader: LinearLayout
    private lateinit var sheetScrollView: androidx.core.widget.NestedScrollView
    internal lateinit var sheetContent: LinearLayout
    private lateinit var sheetBehavior: BottomSheetBehavior<LinearLayout>
    internal lateinit var pageOverlayContainer: FrameLayout

    internal val map: MapLibreMap?
        get() = if (::mapRenderer.isInitialized) mapRenderer.map else null
    internal val contourManager = ContourOverlayManager()
    private val viewModel: MapViewModel by viewModels()
    private var isActivityStarted = false
    private var isActivityResumed = false
    internal lateinit var offlineMaps: OfflineMapManager
    internal lateinit var repository: TerraSyncRepository
    internal val repo: SyncRepo get() = repository.syncRepo
    private lateinit var mapRenderer: MapLayerRenderer
    private lateinit var gpsController: GpsLocationController
    private lateinit var navigationController: NavigationController
    private lateinit var mapToolsController: MapToolsController
    private lateinit var pinCardController: PinCardSheetController
    private lateinit var placeDialogController: PlaceAndPinDialogController
    private lateinit var searchController: SearchController
    private lateinit var folderActionsController: FolderActionsController
    private lateinit var bookmarksPageController: BookmarksPageController
    private lateinit var pinsListController: PinsListController

    internal val layers: List<LayerItem> get() = viewModel.folderSnapshot.layers
    internal val pins: List<PinItem> get() = viewModel.folderSnapshot.pins
    internal val tracks: List<TrackItem> get() = viewModel.folderSnapshot.tracks
    private val pinById: Map<String, PinItem> get() = viewModel.folderSnapshot.pinById
    private val layerById: Map<String, LayerItem> get() = viewModel.folderSnapshot.layerById
    internal val pinsByLayerId: Map<String, List<PinItem>> get() = viewModel.folderSnapshot.pinsByLayerId
    private val tracksByLayerId: Map<String, List<TrackItem>> get() = viewModel.folderSnapshot.tracksByLayerId

    internal var lastPinsRenderKey: String?
        get() = if (::mapRenderer.isInitialized) mapRenderer.lastPinsRenderKey else null
        set(value) { if (::mapRenderer.isInitialized) mapRenderer.lastPinsRenderKey = value }

    internal var visibleStatuses: MutableSet<String>
        get() = viewModel.visibleStatuses
        set(value) { viewModel.visibleStatuses = value }
    internal val isLiveConnected: Boolean
        get() = viewModel.folderSnapshot.isLiveConnected
    internal var mapStyleMode: MapStyleMode
        get() = viewModel.mapStyleMode
        set(value) { viewModel.mapStyleMode = value }
    internal var travelMode: TravelMode
        get() = viewModel.travelMode
        set(value) { viewModel.travelMode = value }
    internal var cachedScaleBarEnabled: Boolean = true
    internal var cachedSpeedUnit: SpeedUnit = SpeedUnit.METRIC
    internal var lastScaleLabel: String = ""
    internal var lastScaleWidthPx: Int = -1
    private var isFollowingGps: Boolean
        get() = viewModel.isFollowingGps
        set(value) { viewModel.isFollowingGps = value }
    private var hasAutoZoomedInitial: Boolean
        get() = viewModel.hasAutoZoomedInitial
        set(value) { viewModel.hasAutoZoomedInitial = value }
    private var statusBarInsetTop = 0
    private var navBarInsetBottom = 0
    private var imeInsetBottom = 0
    private var sideInsetLeft = 0
    private var sideInsetRight = 0
    private var activeSheetPeekDp = 280
    internal var sheetQuickSelectPinId: String? = null
    internal var activePageCleanup: (() -> Unit)? = null
    private var sheetOpenSeq: Int = 0
    private var isSheetPendingOpen: Boolean = false

    internal var hideSentPins: Boolean
        get() = viewModel.hideSentPins
        set(value) { viewModel.hideSentPins = value }
    private val isLassoMode: Boolean get() = viewModel.isLassoMode
    private val lassoPendingPinIds: List<String> get() = viewModel.lassoPendingPinIds
    internal var isRulerMode: Boolean
        get() = viewModel.isRulerMode
        set(value) { viewModel.isRulerMode = value }
    private val selectedPinId: String? get() = viewModel.selectedPinId
    internal val currentLocation: Location?
        get() = if (::gpsController.isInitialized) gpsController.currentLocation else null
    private val previewRoute: NavRoute? get() = viewModel.previewRoute
    private val activeNavRoute: NavRoute? get() = viewModel.activeNavRoute

    // Full-screen overlay stack
    internal val pageBackStack = mutableListOf<() -> Unit>()
    private var backCallback: OnBackPressedCallback? = null
    internal var liveBookmarksRefresh: (() -> Unit)? = null
    internal var livePinListRefresh: (() -> Unit)? = null
    internal var liveEditHistoryRefresh: (() -> Unit)? = null

    internal val kmlPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleImportUri(uri)
    }

    private val backupCreateLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        Toast.makeText(this, "Creating TerraSync backup...", Toast.LENGTH_SHORT).show()
        AppExecutors.batchIoExecutor.execute {
            val res = BackupManager.exportToUri(applicationContext, uri, getInstalledVersionName())
            AppExecutors.postMain {
                if (isFinishing || isDestroyed) return@postMain
                res.onSuccess { s ->
                    Toast.makeText(
                        this,
                        "Backup saved (${s.layerCount} folders, ${s.pinCount} pins)",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { e ->
                    Toast.makeText(this, e.message ?: "Backup failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val backupRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        AppExecutors.batchIoExecutor.execute {
            val inspect = BackupManager.inspectBackupUri(applicationContext, uri)
            AppExecutors.postMain {
                if (isFinishing || isDestroyed) return@postMain
                inspect.onFailure { e ->
                    Toast.makeText(this, e.message ?: "Invalid backup file", Toast.LENGTH_LONG).show()
                }.onSuccess { info ->
                    val dateStr = if (info.exportedAt > 0L) {
                        java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.US)
                            .format(java.util.Date(info.exportedAt))
                    } else "Unknown date"
                    val counts = if (info.layerCount >= 0 && info.pinCount >= 0) {
                        "${info.layerCount} local folders • ${info.pinCount} pins"
                    } else "Local folders & settings"
                    showThemedDialog(
                        themedDialogBuilder("Restore TerraSync Backup?")
                            .setMessage("Backup from $dateStr (v${info.appVersion})\n$counts\n\nThis will replace current local folders and settings.")
                            .setPositiveButton("Restore") { _, _ ->
                                Toast.makeText(this, "Restoring backup...", Toast.LENGTH_SHORT).show()
                                AppExecutors.batchIoExecutor.execute {
                                    val res = BackupManager.restoreFromUri(applicationContext, uri)
                                    AppExecutors.postMain {
                                        if (isFinishing || isDestroyed) return@postMain
                                        res.onSuccess { restored ->
                                            repository.refreshJoinedSync()
                                            repository.applyMergedFolders()
                                            renderMapData()
                                            Toast.makeText(
                                                this,
                                                "Restored ${restored.layerCount} folders & ${restored.pinCount} pins",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }.onFailure { e ->
                                            Toast.makeText(this, e.message ?: "Restore failed", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .create(),
                        true
                    )
                }
            }
        }
    }

    internal fun launchExportBackup() {
        runCatching { backupCreateLauncher.launch(BackupManager.suggestedFileName()) }
            .onFailure { Toast.makeText(this, "Could not open file saver", Toast.LENGTH_SHORT).show() }
    }

    internal fun launchRestoreBackup() {
        runCatching { backupRestoreLauncher.launch(arrayOf("*/*")) }
            .onFailure { Toast.makeText(this, "Could not open file picker", Toast.LENGTH_SHORT).show() }
    }

    private val locPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            if (!ensureLocationServiceOn()) return@registerForActivityResult
            startLiveGpsUpdates()
            if (::navigationController.isInitialized) navigationController.handleLocationPermissionGranted()
        } else {
            if (::navigationController.isInitialized) navigationController.onLocationPermissionDenied()
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    internal fun ensureNotificationPermissionForDownloads() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun applyTransparentSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            run { window.navigationBarDividerColor = Color.TRANSPARENT }
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTransparentSystemBars()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val installedVer = getInstalledVersionName()
        val appUserAgent = "TerraSync/$installedVer (https://github.com/Thamirakkurd/TerraSync)"
        RoutingEngine.userAgent = appUserAgent
        RoutingEngine.appContext = applicationContext

        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(RoutingEngine.mapTileOkHttpClient)
        MapLibre.setConnected(true)
        contourManager.initDiskCache(cacheDir)
        offlineMaps = OfflineMapManager.getInstance(this).apply {
            refreshAll()
            setOnMapPackInstalledListener {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && mapStyleMode == MapStyleMode.VECTOR && ::mapRenderer.isInitialized) {
                        loadMapStyle()
                    }
                }
            }
        }
        if (!DevicePrefs.isKurdishGlyphCacheMigratedV2(applicationContext)) {
            DevicePrefs.markKurdishGlyphCacheMigratedV2(applicationContext)
            offlineMaps.clearAmbientCache {}
        }
        repository = TerraSyncRepository(applicationContext).apply {
            onSnapshotUpdated = { snap ->
                if (!isFinishing && !isDestroyed) {
                    onRepositorySnapshotUpdated(snap)
                }
            }
        }
        LocalFolderStore.preloadAsync(applicationContext) {
            repository.applyMergedFolders()
        }
        AppExecutors.batchIoExecutor.execute {
            LocalPmTilesStore.ensureLoaded(applicationContext)
            OfflineRoutingEngine.cleanupOrphanedTempFiles(applicationContext)
        }
        setContentView(R.layout.activity_main)

        visibleStatuses = DevicePrefs.getVisibleStatuses(this).toMutableSet()
        hideSentPins = DevicePrefs.isHideSentPins(this)
        mapStyleMode = DevicePrefs.getMapStyleMode(this)
        travelMode = DevicePrefs.getTravelMode(this)
        cachedScaleBarEnabled = DevicePrefs.isScaleBarEnabled(this)
        cachedSpeedUnit = DevicePrefs.getSpeedUnit(this)

        bindViews()
        mapRenderer = MapLayerRenderer(
            context = this,
            viewModel = viewModel,
            contourManager = contourManager,
            isDarkMapUi = ::isDarkMapUi,
            isActivityAlive = { !isFinishing && !isDestroyed },
            isActivityStarted = { isActivityStarted },
            isActivityResumed = { isActivityResumed },
            getRulerGeoPoints = { viewModel.rulerPoints },
            getGpsDisplayPose = { if (::gpsController.isInitialized) gpsController.currentPoseOrNull() else null },
            getActiveNavTrimmedGeom = { if (::navigationController.isInitialized) navigationController.activeNavTrimmedGeom else null },
            onUpdateSyncBadge = ::updateSyncStatusBadge,
            onFlushPendingZoom = { if (::mapToolsController.isInitialized) mapToolsController.flushPendingZoomIfReady() }
        )
        gpsController = GpsLocationController(
            context = this,
            viewModel = viewModel,
            renderer = mapRenderer,
            speedBadge = speedBadge,
            tvSpeedValue = tvSpeedValue,
            tvSpeedUnit = tvSpeedUnit,
            btnLocate = btnLocate,
            isOverlayVisible = { ::pageOverlayContainer.isInitialized && pageOverlayContainer.visibility == View.VISIBLE },
            isActivityStarted = { isActivityStarted },
            isActivityResumed = { isActivityResumed },
            currentPalette = ::currentPalette,
            themedDialogBuilder = ::themedDialogBuilder,
            showThemedDialog = ::showThemedDialog,
            getCachedSpeedUnit = { cachedSpeedUnit }
        )

        val root = findViewById<ViewGroup>(R.id.rootContainer)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val navOnly = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            statusBarInsetTop = sys.top
            navBarInsetBottom = navOnly.bottom
            imeInsetBottom = ime.bottom
            sideInsetLeft = sys.left
            sideInsetRight = sys.right
            val isLayoutRtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val insetStart = if (isLayoutRtl) sideInsetRight else sideInsetLeft
            val insetEnd = if (isLayoutRtl) sideInsetLeft else sideInsetRight
            (topCluster.layoutParams as ViewGroup.MarginLayoutParams).apply {
                topMargin = statusBarInsetTop + dp(12)
                marginStart = insetStart + dp(14)
                marginEnd = insetEnd + dp(14)
            }
            (navBanner.layoutParams as ViewGroup.MarginLayoutParams).apply {
                topMargin = statusBarInsetTop + dp(12)
                marginStart = insetStart + dp(14)
                marginEnd = insetEnd + dp(14)
            }
            (bottomDock.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = maxOf(navBarInsetBottom, dp(12)) + dp(10)
                marginStart = insetStart
                marginEnd = insetEnd
            }
            (fullScreenSwipeUpCatcher.layoutParams as ViewGroup.MarginLayoutParams).apply {
                height = maxOf(navBarInsetBottom, dp(14)) + dp(28)
            }
            (inScreenSheet.layoutParams as ViewGroup.MarginLayoutParams).apply {
                topMargin = 0
                marginStart = insetStart
                marginEnd = insetEnd
            }
            val maxSheetH = (resources.displayMetrics.heightPixels - statusBarInsetTop - dp(12)).coerceAtLeast(dp(240))
            if (::sheetBehavior.isInitialized) {
                sheetBehavior.maxHeight = maxSheetH
            }
            applySheetBottomInset(maxOf(navBarInsetBottom, imeInsetBottom))
            applyPageBottomInset(maxOf(navBarInsetBottom, imeInsetBottom))
            topCluster.requestLayout()
            navBanner.requestLayout()
            bottomDock.requestLayout()
            fullScreenSwipeUpCatcher.requestLayout()
            inScreenSheet.requestLayout()
            if (imeInsetBottom > 0 && ::sheetBehavior.isInitialized && sheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                if (sheetBehavior.state != BottomSheetBehavior.STATE_EXPANDED) {
                    sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                }
                if (::sheetScrollView.isInitialized) {
                    sheetScrollView.post { sheetScrollView.fullScroll(View.FOCUS_DOWN) }
                    sheetScrollView.postDelayed({
                        if (!isFinishing && !isDestroyed) sheetScrollView.fullScroll(View.FOCUS_DOWN)
                    }, 120L)
                }
            }
            insets
        }

        UiAnimations.attachImePaddingGlide(
            inScreenSheet,
            navBottomProvider = { navBarInsetBottom },
            restingBottomProvider = { maxOf(navBarInsetBottom, imeInsetBottom) },
            applyBottom = ::applySheetBottomInset
        )
        UiAnimations.attachImePaddingGlide(
            pageOverlayContainer,
            navBottomProvider = { navBarInsetBottom },
            restingBottomProvider = { maxOf(navBarInsetBottom, imeInsetBottom) },
            applyBottom = ::applyPageBottomInset
        )

        sheetBehavior = BottomSheetBehavior.from(inScreenSheet).apply {
            state = BottomSheetBehavior.STATE_HIDDEN
            isHideable = true
            isFitToContents = true
            skipCollapsed = false
            maxHeight = (resources.displayMetrics.heightPixels - statusBarInsetTop - dp(12)).coerceAtLeast(dp(240))
            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                        isSheetPendingOpen = false
                        sheetQuickSelectPinId = null
                        clearSheetStickyHeader()
                        clearSelectedMarker()
                        if (::navigationController.isInitialized) navigationController.cancelPreviewOnSheetClosed()
                        if (activeNavRoute == null) {
                            resetMapPadding()
                        }
                        if (!isFullScreenMapMode) {
                            bottomDock.translationY = 0f
                            UiAnimations.fadeShow(bottomDock, 165L)
                        }
                    } else if ((newState == BottomSheetBehavior.STATE_COLLAPSED || newState == BottomSheetBehavior.STATE_EXPANDED) &&
                        !isRulerMode && !isLassoMode && activeNavRoute == null
                    ) {
                        bottomDock.animate().cancel()
                        bottomDock.alpha = 0f
                        bottomDock.translationY = 0f
                        bottomDock.visibility = View.GONE
                    }
                }
                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    if (UiAnimations.isReducedMotion(this@MainActivity)) return
                    if (isSwappingSheetInPlace || isFullScreenMapMode || isRulerMode || isLassoMode || activeNavRoute != null) return
                    bottomDock.animate().cancel()
                    if (slideOffset < -0.15f) {
                        val reveal = ((-slideOffset - 0.15f) / 0.85f).coerceIn(0f, 1f)
                        if (bottomDock.visibility != View.VISIBLE) {
                            bottomDock.visibility = View.VISIBLE
                        }
                        bottomDock.alpha = reveal
                        bottomDock.translationY = (1f - reveal) * dp(14)
                    } else if (bottomDock.visibility == View.VISIBLE) {
                        bottomDock.alpha = 0f
                        bottomDock.visibility = View.INVISIBLE
                    }
                }
            })
        }

        navigationController = NavigationController(
            activity = this,
            viewModel = viewModel,
            renderer = mapRenderer,
            gpsController = gpsController,
            topCluster = topCluster,
            routeFromBanner = routeFromBanner,
            tvRouteFromText = tvRouteFromText,
            navBanner = navBanner,
            tvNavArrow = tvNavArrow,
            tvNavStepDist = tvNavStepDist,
            tvNavInstruction = tvNavInstruction,
            navBottomBar = navBottomBar,
            tvNavEta = tvNavEta,
            tvNavMeta = tvNavMeta,
            bottomDock = bottomDock,
            bottomNavDock = bottomNavDock,
            btnRuler = btnRuler,
            btnLasso = btnLasso,
            sheetContent = sheetContent,
            sheetBehavior = sheetBehavior,
            undoController = undoController,
            dp = ::dp,
            currentPalette = ::currentPalette,
            isActivityAlive = { !isFinishing && !isDestroyed },
            isActivityResumed = { isActivityResumed },
            getCachedSpeedUnit = { cachedSpeedUnit },
            launchLocationPermission = {
                locPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            clearSheetStickyHeader = ::clearSheetStickyHeader,
            clearQuickSelectPinId = { sheetQuickSelectPinId = null },
            showInScreenSheet = ::showInScreenSheet,
            hideInScreenSheet = ::hideInScreenSheet,
            safeStartActivity = ::safeStartActivity,
            applyKeepAwakeFlag = ::applyKeepAwakeFlag,
            applyExtraMapToolsVisibility = ::applyExtraMapToolsVisibility,
            applyZoomButtonsVisibility = ::applyZoomButtonsVisibility,
            dismissToolsForNav = {
                if (isFullScreenMapMode) setFullScreenMapMode(false, animate = false)
                if (isLassoMode) toggleLassoMode(false)
                if (isRulerMode) toggleRulerMode(false)
            }
        ).apply { initTts() }

        mapToolsController = MapToolsController(
            activity = this,
            viewModel = viewModel,
            renderer = mapRenderer,
            gpsController = gpsController,
            mapView = mapView,
            lassoOverlay = lassoOverlay,
            lassoCard = lassoCard,
            tvLassoTitle = tvLassoTitle,
            tvLassoSubtitle = tvLassoSubtitle,
            btnLassoModeToggle = btnLassoModeToggle,
            btnLassoMarkSent = btnLassoMarkSent,
            btnLassoRedraw = btnLassoRedraw,
            btnLassoUndo = btnLassoUndo,
            btnLassoReset = btnLassoReset,
            rulerCard = rulerCard,
            btnRulerUndo = btnRulerUndo,
            tvRulerDistance = tvRulerDistance,
            tvRulerSubtitle = tvRulerSubtitle,
            btnRulerReset = btnRulerReset,
            btnSentToggle = btnSentToggle,
            bottomDock = bottomDock,
            bottomNavDock = bottomNavDock,
            pageOverlayContainer = pageOverlayContainer,
            undoController = undoController,
            dp = ::dp,
            currentPalette = ::currentPalette,
            themedDialogBuilder = ::themedDialogBuilder,
            showThemedDialog = ::showThemedDialog,
            isActivityAlive = { !isFinishing && !isDestroyed },
            getInsetsTopBottom = { statusBarInsetTop to navBarInsetBottom },
            getSpeedUnit = { DevicePrefs.getSpeedUnit(this@MainActivity) },
            updateFilterIconTint = ::updateFilterIconTint,
            applyMapChromeTheme = ::applyMapChromeTheme,
            isFullScreenMapMode = { isFullScreenMapMode },
            setFullScreenMapMode = ::setFullScreenMapMode,
            hideInScreenSheet = ::hideInScreenSheet,
            isSheetHidden = { sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN },
            closeFullPage = ::closeFullPage,
            showPinCard = { pin, animateSwap -> showPinCard(pin, animateSwap = animateSwap) },
            showTappedPlaceCard = ::showTappedPlaceCard,
            onReverseGeocodeResult = { _, bestTitle, bestSub, distShort ->
                if (::placeDialogController.isInitialized) {
                    placeDialogController.updateReverseGeocodeViews(bestTitle, bestSub, distShort)
                }
            },
            onJoinShareCode = ::joinShareCode,
            onImportUri = ::handleImportUri
        )

        pinCardController = PinCardSheetController(
            activity = this,
            viewModel = viewModel,
            repository = repository,
            sheetContent = sheetContent,
            undoController = undoController,
            dp = ::dp,
            currentPalette = ::currentPalette,
            themedDialogBuilder = ::themedDialogBuilder,
            showThemedDialog = ::showThemedDialog,
            hideSoftKeyboard = ::hideSoftKeyboard,
            clearSheetStickyHeader = ::clearSheetStickyHeader,
            clearQuickSelectPinId = { sheetQuickSelectPinId = null },
            showInScreenSheet = { peekDp, inPlace, expandToFit -> showInScreenSheet(peekDp, inPlace, expandToFit) },
            hideInScreenSheet = ::hideInScreenSheet,
            safeStartActivity = { intent, msg -> safeStartActivity(intent, msg) },
            copyToClipboard = ::copyToClipboard,
            findExactStackedPins = ::findExactStackedPins,
            renderSelectedMarker = { if (::mapRenderer.isInitialized) mapRenderer.renderSelectedMarker() },
            fireStatusPulse = ::fireStatusPulse,
            renderMapData = ::renderMapData,
            updateSentToggleUi = ::updateSentToggleUi,
            getCurrentLocation = { currentLocation },
            openRoutePreview = { lat, lng, title, mode -> openRoutePreview(lat, lng, title, mode) },
            setRouteOrigin = ::setRouteOrigin,
            showFieldQuickSelectSheet = { pin, isIssue -> placeDialogController.showFieldQuickSelectSheet(pin, isIssue) },
            showEditPinDialog = { pin -> placeDialogController.showEditPinDialog(pin) },
            notifySyncError = ::notifySyncError
        )

        placeDialogController = PlaceAndPinDialogController(
            activity = this,
            viewModel = viewModel,
            repository = repository,
            sheetStickyHeader = sheetStickyHeader,
            sheetScrollView = sheetScrollView,
            sheetContent = sheetContent,
            sheetBehavior = sheetBehavior,
            dp = ::dp,
            currentPalette = ::currentPalette,
            themedDialogBuilder = ::themedDialogBuilder,
            showThemedDialog = ::showThemedDialog,
            isActivityAlive = { !isFinishing && !isDestroyed },
            hideSoftKeyboard = ::hideSoftKeyboard,
            clearSheetStickyHeader = ::clearSheetStickyHeader,
            setQuickSelectPinId = { id -> sheetQuickSelectPinId = id },
            showInScreenSheet = { peekDp, inPlace, expandToFit -> showInScreenSheet(peekDp, inPlace, expandToFit) },
            hideInScreenSheet = ::hideInScreenSheet,
            safeStartActivity = { intent, msg -> safeStartActivity(intent, msg) },
            copyToClipboard = ::copyToClipboard,
            makePinShareMessage = { name, lat, lng, pinId -> pinCardController.makePinShareMessage(name, lat, lng, pinId) },
            getCurrentLocation = { currentLocation },
            openRoutePreview = { lat, lng, title, mode -> openRoutePreview(lat, lng, title, mode) },
            setRouteOrigin = ::setRouteOrigin,
            ensurePinVisibleOnMap = ::ensurePinVisibleOnMap,
            fireStatusPulse = ::fireStatusPulse,
            renderMapData = ::renderMapData,
            showStatusUndo = pinCardController::showStatusUndo,
            showPinCard = { pin, animateSwap, swapForward -> pinCardController.showPinCard(pin, animateSwap, swapForward) },
            notifySyncError = ::notifySyncError
        )

        searchController = SearchController(
            activity = this,
            viewModel = viewModel,
            gpsController = gpsController,
            dp = ::dp,
            currentPalette = ::currentPalette,
            getCachedSpeedUnit = { cachedSpeedUnit },
            isActivityAlive = { !isFinishing && !isDestroyed },
            hideSoftKeyboard = ::hideSoftKeyboard,
            makePageFrame = { title, onBack, rightAction, scrollable -> makePageFrame(title, onBack, rightAction, scrollable) },
            closeFullPage = ::closeFullPage,
            setActivePageCleanup = { cleanup -> activePageCleanup = cleanup },
            launchLocationPermission = {
                locPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            ensurePinVisibleOnMap = ::ensurePinVisibleOnMap,
            selectPinAndOpenCard = { pin, zoomIfFar -> selectPinAndOpenCard(pin, zoomIfFar) },
            selectCoordinateOnMap = { latLng, explicitTitle, fallbackSub, zoomTo ->
                selectCoordinateOnMap(latLng, explicitTitle, fallbackSub, zoomTo)
            },
            getMapCenter = { map?.cameraPosition?.target }
        )

        folderActionsController = FolderActionsController(
            activity = this,
            viewModel = viewModel,
            repository = repository,
            dp = ::dp,
            currentPalette = ::currentPalette,
            themedDialogBuilder = ::themedDialogBuilder,
            showThemedDialog = ::showThemedDialog,
            isActivityAlive = { !isFinishing && !isDestroyed },
            safeStartActivity = { intent, msg -> safeStartActivity(intent, msg) },
            showDailyReport = { layer, layerPins, layerTracks, badge ->
                showDailyWhatsAppReportDialog(layer, layerPins, layerTracks, badge)
            },
            refreshJoinedSync = ::refreshJoinedSync,
            collectLayerLatLngs = ::collectLayerLatLngs,
            zoomToKmlBounds = { pts, layerId -> zoomToKmlBounds(pts, layerId) },
            notifySyncError = ::notifySyncError
        )

        pinsListController = PinsListController(
            activity = this,
            viewModel = viewModel,
            repository = repository,
            dp = ::dp,
            currentPalette = ::currentPalette,
            themedDialogBuilder = ::themedDialogBuilder,
            showThemedDialog = ::showThemedDialog,
            getCachedSpeedUnit = { cachedSpeedUnit },
            getCurrentLocation = { currentLocation },
            isActivityAlive = { !isFinishing && !isDestroyed },
            getPageBackStack = { pageBackStack },
            makePageFrame = { title, onBack, rightAction, scrollable -> makePageFrame(title, onBack, rightAction, scrollable) },
            pushSubPage = ::pushSubPage,
            popSubPage = ::popSubPage,
            closeFullPage = ::closeFullPage,
            setLivePinListRefresh = { fn -> livePinListRefresh = fn },
            openEditHistoryPage = { layer -> buildEditHistoryPage(layer) },
            ensurePinVisibleOnMap = ::ensurePinVisibleOnMap,
            selectPinAndOpenCard = { pin, zoomIfFar -> selectPinAndOpenCard(pin, zoomIfFar) },
            collectLayerLatLngs = ::collectLayerLatLngs,
            zoomToKmlBounds = { pts, layerId -> zoomToKmlBounds(pts, layerId) },
            updateSentToggleUi = ::updateSentToggleUi,
            renderMapData = ::renderMapData,
            notifySyncError = ::notifySyncError
        )

        bookmarksPageController = BookmarksPageController(
            activity = this,
            viewModel = viewModel,
            repository = repository,
            folderActions = folderActionsController,
            searchController = searchController,
            gpsController = gpsController,
            dp = ::dp,
            currentPalette = ::currentPalette,
            getCachedSpeedUnit = { cachedSpeedUnit },
            getInsetsLTRB = {
                intArrayOf(sideInsetLeft, statusBarInsetTop, sideInsetRight, maxOf(navBarInsetBottom, imeInsetBottom))
            },
            isActivityAlive = { !isFinishing && !isDestroyed },
            hideSoftKeyboard = ::hideSoftKeyboard,
            pushSubPage = ::pushSubPage,
            popSubPage = ::popSubPage,
            closeFullPage = ::closeFullPage,
            setLiveBookmarksRefresh = { fn -> liveBookmarksRefresh = fn },
            setActivePageCleanup = { cleanup -> activePageCleanup = cleanup },
            launchKmlPicker = { kmlPicker.launch(arrayOf("*/*")) },
            openPinsListPage = { title, sourcePins, layer -> pinsListController.buildPinsListPage(title, sourcePins, layer) },
            openEditHistoryPage = { layer -> buildEditHistoryPage(layer) },
            showDailyReport = { layer, layerPins, layerTracks ->
                showDailyWhatsAppReportDialog(layer, layerPins, layerTracks)
            },
            renderMapData = ::renderMapData,
            collectLayerLatLngs = ::collectLayerLatLngs,
            zoomToKmlBounds = { pts, layerId -> zoomToKmlBounds(pts, layerId) },
            refreshJoinedSync = ::refreshJoinedSync
        )

        gpsController.hasPendingRouteRequest = { navigationController.pendingRouteReq != null }
        gpsController.onPendingRouteCheck = { loc -> navigationController.handlePendingRouteOnFix(loc) }
        gpsController.onActiveNavigationLocation = { loc, route, dest -> navigationController.updateNavigationProgress(loc, route, dest) }

        applyMapChromeTheme()
        refreshLocalizedChrome()
        applyKeepAwakeFlag()

        setupClickListeners()
        setupBackNavigation()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            mapRenderer.map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(36.8669, 42.9900))
                .zoom(11.0)
                .build()

            loadMapStyle()
            updateScaleBar()
            updateCompassForBearing(m.cameraPosition.bearing, animate = false)

            m.addOnCameraMoveListener {
                updateScaleBar()
                updateCompassForBearing(m.cameraPosition.bearing, animate = true)
                if (isLassoMode && lassoPendingPinIds.isNotEmpty()) {
                    mapToolsController.clearLassoSelection()
                }
            }

            m.addOnCameraIdleListener {
                contourManager.scheduleUpdate(
                    m,
                    DevicePrefs.isContoursEnabled(this),
                    mapStyleMode == MapStyleMode.VECTOR
                )
            }

            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && isFollowingGps) {
                    isFollowingGps = false
                    updateLocationIconTint()
                    syncGpsPollingProfile()
                }
            }

            m.addOnMapClickListener { latLng ->
                if (activeNavRoute != null) return@addOnMapClickListener true
                if (isRulerMode) {
                    mapToolsController.addRulerPoint(latLng)
                } else if (!isLassoMode) {
                    mapToolsController.handleMapTap(m, latLng)
                }
                true
            }

            m.addOnMapLongClickListener { latLng ->
                if (activeNavRoute == null && !isRulerMode && !isLassoMode) placeDialogController.showAddPinDialog(latLng)
                true
            }

            mapToolsController.consumePendingSharedPinOnMapReady()
        }

        refreshJoinedSync()
        handleDeepLinkIntent(intent)
        if (!DevicePrefs.isOperatorSetupDone(this)) {
            window.decorView.post {
                if (!isFinishing && !isDestroyed && !DevicePrefs.isOperatorSetupDone(this)) {
                    showFirstInstallOperatorDialog()
                }
            }
        }
    }

    internal fun themedDialogBuilder(title: String): AlertDialog.Builder {
        val p = currentPalette()
        return AlertDialog.Builder(this)
            .setCustomTitle(DesignSystem.createThemedDialogTitle(this, title, p))
    }

    internal fun showThemedDialog(dlg: AlertDialog, destructivePositive: Boolean = false): AlertDialog {
        val p = currentPalette()
        dlg.show()
        DesignSystem.styleAlertDialog(this, dlg, p, destructivePositive = destructivePositive)
        return dlg
    }

    private fun showFirstInstallOperatorDialog() {
        val p = currentPalette()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }
        box.addView(TextView(this).apply {
            text = "تیما خو بنڤیسە"
            textSize = 15f
            textDirection = View.TEXT_DIRECTION_RTL
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.accentPrimary)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                setColor(p.accentPrimarySoft)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                bottomMargin = dp(10)
            }
        })
        box.addView(TextView(this).apply {
            text = "Enter your operator or team name (e.g. Team 212, Ali, Supervisor) so every pin edit and cloud submission is stamped accurately."
            textSize = 13.5f
            setLineSpacing(dp(2).toFloat(), 1.08f)
            setTextColor(p.textSecondary)
            setPadding(0, 0, 0, dp(14))
        })

        val etName = DesignSystem.createThemedEditText(this, p, "Operator or Team Name").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        box.addView(etName)

        val baseDeviceStamp = DevicePrefs.getDeviceStamp(this)
        val devIdShort = DevicePrefs.getDeviceId(this).take(6)
        val tvPreview = TextView(this).apply {
            text = "Preview: $baseDeviceStamp"
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(p.accentPrimary)
            setTypeface(null, Typeface.BOLD)
            DesignSystem.applyTabularNumerals(this)
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }
        box.addView(tvPreview)

        etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val typed = s?.toString()?.trim().orEmpty()
                tvPreview.text = if (typed.isNotBlank()) {
                    "Preview: $typed • #$devIdShort"
                } else {
                    "Preview: $baseDeviceStamp"
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dlgScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(box)
        }
        val dlg = themedDialogBuilder("Field Operator Stamp")
            .setView(dlgScroll)
            .setCancelable(false)
            .setPositiveButton("Save & Continue", null)
            .setNegativeButton("Skip for now") { _, _ ->
                DevicePrefs.setOperatorSetupDone(this, true)
                Toast.makeText(this, "You can set your stamp anytime in Settings → Field Data & Sync", Toast.LENGTH_SHORT).show()
            }
            .create()

        fun trySave() {
            val entered = etName.text.toString().trim()
            if (entered.isBlank()) {
                Toast.makeText(this, "Please enter your operator or team name (or tap Skip)", Toast.LENGTH_SHORT).show()
                return
            }
            DevicePrefs.setCustomName(this, entered)
            DevicePrefs.setOperatorSetupDone(this, true)
            Toast.makeText(this, "Field Stamp set: ${DevicePrefs.getDeviceStamp(this)}", Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        }

        etName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                trySave()
                true
            } else false
        }

        dlg.setOnShowListener {
            DesignSystem.styleAlertDialog(this, dlg, p)
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                if (this is MaterialButton) {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(p.accentPrimary)
                    setTextColor(Color.WHITE)
                    cornerRadius = dp(20)
                    insetTop = 0
                    insetBottom = 0
                    strokeWidth = 0
                    elevation = 0f
                    stateListAnimator = null
                } else {
                    ViewCompat.setBackgroundTintList(this, null)
                    background = GradientDrawable().apply {
                        setColor(p.accentPrimary)
                        cornerRadius = dp(20).toFloat()
                    }
                    setTextColor(Color.WHITE)
                }
                setPadding(dp(18), dp(8), dp(18), dp(8))
                minHeight = dp(40)
                setOnClickListener { trySave() }
            }
            etName.requestFocus()
        }
        dlg.show()
    }

    private fun bindViews() {
        mapView = findViewById(R.id.mapView)
        topCluster = findViewById(R.id.topCluster)
        topBar = findViewById(R.id.topBar)
        routeFromBanner = findViewById(R.id.routeFromBanner)
        tvRouteFromText = findViewById(R.id.tvRouteFromText)
        tvRouteFromHint = findViewById(R.id.tvRouteFromHint)
        btnClearRouteFrom = findViewById(R.id.btnClearRouteFrom)
        syncStatusContainer = findViewById(R.id.syncStatusContainer)
        btnSyncStatus = findViewById(R.id.btnSyncStatus)
        tvSyncPendingBadge = findViewById(R.id.tvSyncPendingBadge)
        undoController = StatusUndoController(
            findViewById(R.id.undoBanner),
            findViewById(R.id.tvUndoMessage),
            findViewById(R.id.btnUndoAction)
        )
        btnCompass = findViewById(R.id.btnCompass)
        btnStyleToggle = findViewById(R.id.btnStyleToggle)
        btnStatusFilter = findViewById(R.id.btnStatusFilter)
        btnSentToggle = findViewById(R.id.btnSentToggle)
        btnLasso = findViewById(R.id.btnLasso)
        btnRuler = findViewById(R.id.btnRuler)
        lassoOverlay = findViewById(R.id.lassoOverlay)
        lassoCard = findViewById(R.id.lassoCard)
        tvLassoTitle = findViewById(R.id.tvLassoTitle)
        tvLassoSubtitle = findViewById(R.id.tvLassoSubtitle)
        btnLassoMarkSent = findViewById(R.id.btnLassoMarkSent)
        btnLassoRedraw = findViewById(R.id.btnLassoRedraw)
        btnLassoUndo = findViewById(R.id.btnLassoUndo)
        btnLassoReset = findViewById(R.id.btnLassoReset)
        btnLassoModeToggle = findViewById(R.id.btnLassoModeToggle)
        btnLassoClose = findViewById(R.id.btnLassoClose)
        leftBottomColumn = findViewById(R.id.leftBottomColumn)
        rightChromeColumn = findViewById(R.id.rightChromeColumn)
        rightArcCluster = findViewById(R.id.rightArcCluster)
        zoomPillContainer = findViewById(R.id.zoomPillContainer)
        zoomPillDivider = findViewById(R.id.zoomPillDivider)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnLocate = findViewById(R.id.btnLocate)
        applyExtraMapToolsVisibility()
        applyZoomButtonsVisibility()
        applyResponsiveChromeSizing()
        btnTabSearch = findViewById(R.id.btnTabSearch)
        btnTabBookmarks = findViewById(R.id.btnTabBookmarks)
        btnTabSettings = findViewById(R.id.btnTabSettings)
        btnTabAbout = findViewById(R.id.btnTabAbout)
        bottomDock = findViewById(R.id.bottomDock)
        mapControlsRow = findViewById(R.id.mapControlsRow)
        bottomNavDock = findViewById(R.id.bottomNavDock)
        bottomIconRow = findViewById(R.id.bottomIconRow)
        fullScreenSwipeUpCatcher = findViewById(R.id.fullScreenSwipeUpCatcher)
        bottomNavDock.onSwipeProgress = { prog ->
            if (!isFullScreenMapMode &&
                activeNavRoute == null &&
                !isRulerMode &&
                !isLassoMode &&
                pageOverlayContainer.visibility != View.VISIBLE &&
                sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN &&
                !UiAnimations.isReducedMotion(this)
            ) {
                val isRtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val dir = if (isRtl) -1f else 1f
                topCluster.translationY = -dp(20) * prog
                topCluster.alpha = 1f - 0.25f * prog
                rightChromeColumn.translationX = dir * dp(28) * prog
                rightChromeColumn.alpha = 1f - 0.25f * prog
                leftBottomColumn.translationY = dp(18) * prog
                leftBottomColumn.alpha = 1f - 0.25f * prog
            }
        }
        bottomNavDock.onSwipeCancel = {
            if (!isFullScreenMapMode && !UiAnimations.isReducedMotion(this)) {
                listOf(topCluster, rightChromeColumn, leftBottomColumn).forEach { v ->
                    v.animate().cancel()
                    v.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(150L)
                        .setInterpolator(UiAnimations.EMPHASIZED_DECELERATE)
                        .start()
                }
            }
        }
        bottomNavDock.onSwipeDown = {
            if (activeNavRoute == null &&
                !isRulerMode &&
                !isLassoMode &&
                pageOverlayContainer.visibility != View.VISIBLE &&
                sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN
            ) {
                setFullScreenMapMode(true)
            }
        }
        setupFullScreenSwipeUpCatcher()
        speedBadge = findViewById(R.id.speedBadge)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        tvSpeedUnit = findViewById(R.id.tvSpeedUnit)
        rulerCard = findViewById(R.id.rulerCard)
        btnRulerUndo = findViewById(R.id.btnRulerUndo)
        tvRulerDistance = findViewById(R.id.tvRulerDistance)
        tvRulerSubtitle = findViewById(R.id.tvRulerSubtitle)
        btnRulerReset = findViewById(R.id.btnRulerReset)
        btnRulerClose = findViewById(R.id.btnRulerClose)
        navBanner = findViewById(R.id.navBanner)
        tvNavArrow = findViewById(R.id.tvNavArrow)
        tvNavStepDist = findViewById(R.id.tvNavStepDist)
        tvNavInstruction = findViewById(R.id.tvNavInstruction)
        navBottomBar = findViewById(R.id.navBottomBar)
        tvNavEta = findViewById(R.id.tvNavEta)
        tvNavMeta = findViewById(R.id.tvNavMeta)
        btnNavVoice = findViewById(R.id.btnNavVoice)
        btnNavStop = findViewById(R.id.btnNavStop)
        tvScaleLabel = findViewById(R.id.tvScaleLabel)
        scaleBarLine = findViewById(R.id.scaleBarLine)
        scaleBarContainer = findViewById(R.id.scaleBarContainer)
        DesignSystem.applyTabularNumerals(tvSpeedValue)
        DesignSystem.applyTabularNumerals(tvScaleLabel)
        DesignSystem.applyTabularNumerals(tvRulerDistance)
        DesignSystem.applyTabularNumerals(tvNavStepDist)
        DesignSystem.applyTabularNumerals(tvNavEta)
        DesignSystem.applyTabularNumerals(tvNavMeta)
        inScreenSheet = findViewById(R.id.inScreenSheet)
        sheetDragHandle = findViewById(R.id.sheetDragHandle)
        sheetStickyHeader = findViewById(R.id.sheetStickyHeader)
        sheetScrollView = findViewById(R.id.sheetScrollView)
        sheetContent = findViewById(R.id.sheetContent)
        pageOverlayContainer = findViewById(R.id.pageOverlayContainer)
    }

    internal fun isDarkMapUi(): Boolean {
        return when (DevicePrefs.getThemeMode(this)) {
            "night" -> true
            "auto" -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
    }

    internal fun currentPalette(): ThemePalette = DesignSystem.palette(isDarkMapUi())

    internal fun updateSystemBarContrast(forFullPage: Boolean = ::pageOverlayContainer.isInitialized && pageOverlayContainer.visibility == View.VISIBLE) {
        val night = isDarkMapUi()
        val useLightIcons = if (forFullPage) night else (night || mapStyleMode == MapStyleMode.SATELLITE)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !useLightIcons
            isAppearanceLightNavigationBars = !useLightIcons
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupFullScreenSwipeUpCatcher() {
        var downY = 0f
        var restored = false
        val triggerPx = dp(22).toFloat()
        fullScreenSwipeUpCatcher.setOnTouchListener { _, ev ->
            if (!isFullScreenMapMode) return@setOnTouchListener false
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    restored = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!restored && (downY - ev.rawY) >= triggerPx) {
                        restored = true
                        setFullScreenMapMode(false)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!restored && (downY - ev.rawY) >= triggerPx * 0.5f) {
                        restored = true
                        setFullScreenMapMode(false)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** Auto-hides the top-right Compass when facing North (~0°) and smoothly fades/scales it in on rotation. */
    private fun updateCompassForBearing(bearing: Double, animate: Boolean = true) {
        if (!::btnCompass.isInitialized) return
        val norm = ((bearing % 360.0) + 540.0) % 360.0 - 180.0
        val shouldShow = kotlin.math.abs(norm) > 1.2 && !isFullScreenMapMode
        btnCompass.rotation = (-bearing).toFloat()
        if (shouldShow == isCompassVisible) {
            if (!shouldShow && btnCompass.visibility != View.INVISIBLE) {
                btnCompass.visibility = View.INVISIBLE
                btnCompass.alpha = 0f
                btnCompass.isEnabled = false
            }
            return
        }
        isCompassVisible = shouldShow
        btnCompass.animate().cancel()
        if (shouldShow) {
            btnCompass.isEnabled = true
            btnCompass.visibility = View.VISIBLE
            if (!animate || UiAnimations.isReducedMotion(this)) {
                btnCompass.alpha = 1f
                btnCompass.scaleX = 1f
                btnCompass.scaleY = 1f
                return
            }
            btnCompass.alpha = 0f
            btnCompass.scaleX = 0.84f
            btnCompass.scaleY = 0.84f
            btnCompass.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(165L)
                .setInterpolator(UiAnimations.EMPHASIZED_DECELERATE)
                .withLayer()
                .start()
        } else {
            btnCompass.isEnabled = false
            if (!animate || UiAnimations.isReducedMotion(this)) {
                btnCompass.alpha = 0f
                btnCompass.scaleX = 0.84f
                btnCompass.scaleY = 0.84f
                btnCompass.visibility = View.INVISIBLE
                return
            }
            btnCompass.animate()
                .alpha(0f)
                .scaleX(0.84f)
                .scaleY(0.84f)
                .setDuration(150L)
                .setInterpolator(UiAnimations.FAST_EXIT)
                .withLayer()
                .withEndAction {
                    if (!isCompassVisible) btnCompass.visibility = View.INVISIBLE
                }
                .start()
        }
    }

    /**
     * 120fps GPU-only Full-Screen Clean Map Mode:
     * Top controls glide UP, right-side arc + FAB glide RIGHT, bottom dock + scale bar glide DOWN.
     * Transparent Android status bar stays visible so time/signal/battery remain readable outdoors.
     */
    private fun setFullScreenMapMode(enabled: Boolean, animate: Boolean = true) {
        if (isFullScreenMapMode == enabled) return
        isFullScreenMapMode = enabled
        val isRtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val rightShift = (if (isRtl) -dp(170) else dp(170)).toFloat()
        val topShift = -dp(96).toFloat()
        val bottomShift = dp(110).toFloat()
        val hideDur = if (animate) 185L else 0L
        val showDur = if (animate) 235L else 0L

        if (enabled) {
            fullScreenSwipeUpCatcher.visibility = View.VISIBLE
            UiAnimations.animateChromeSlide(bottomNavDock, hide = true, shiftY = bottomShift, durationMs = hideDur, startDelayMs = 0L)
            UiAnimations.animateChromeSlide(leftBottomColumn, hide = true, shiftY = bottomShift, durationMs = hideDur, startDelayMs = 15L)
            UiAnimations.animateChromeSlide(rightChromeColumn, hide = true, shiftX = rightShift, durationMs = hideDur, startDelayMs = 30L)
            UiAnimations.animateChromeSlide(topCluster, hide = true, shiftY = topShift, durationMs = hideDur, startDelayMs = 45L)
        } else {
            if (animate) UiAnimations.hapticTick(bottomNavDock)
            fullScreenSwipeUpCatcher.visibility = View.GONE
            if (bottomDock.visibility != View.VISIBLE && sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
                bottomDock.alpha = 1f
                bottomDock.translationY = 0f
                bottomDock.visibility = View.VISIBLE
            }
            if (activeNavRoute == null) {
                UiAnimations.animateChromeSlide(topCluster, hide = false, shiftY = topShift, durationMs = showDur, startDelayMs = 0L)
                map?.cameraPosition?.bearing?.let { updateCompassForBearing(it, animate = false) }
            }
            UiAnimations.animateChromeSlide(rightChromeColumn, hide = false, shiftX = rightShift, durationMs = showDur, startDelayMs = 20L)
            UiAnimations.animateChromeSlide(leftBottomColumn, hide = false, shiftY = bottomShift, durationMs = showDur, startDelayMs = 35L)
            if (activeNavRoute == null && !isRulerMode && !isLassoMode) {
                UiAnimations.animateChromeSlide(bottomNavDock, hide = false, shiftY = bottomShift, durationMs = showDur, startDelayMs = 45L)
            }
        }
    }

    /**
     * Styles the 4 individual equal-width inner pills inside the frosted bottom navigation tray.
     * On the map (activeOpenNavTab == -1), ALL 4 pills remain neutral white with dark icons.
     * Only a pressed pill or currently open section turns solid forest emerald with a white icon.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun syncNavDockPills(pressedIdx: Int = -1) {
        if (!::bottomNavDock.isInitialized) return
        val p = currentPalette()
        val tabs = listOf(btnTabSearch, btnTabBookmarks, btnTabSettings, btnTabAbout)
        tabs.forEachIndexed { idx, btn ->
            val active = (idx == activeOpenNavTab) || (idx == pressedIdx)
            val fill = if (active) p.accentPrimary else p.navPillNeutral
            val iconColor = if (active) Color.WHITE else p.inactiveNavIcon
            val content = DesignSystem.buildOpaqueDrawable(
                ctx = this,
                fillColor = fill,
                radiusDp = 25f
            )
            val mask = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(25).toFloat()
            }
            val rippleCol = if (active) Color.parseColor("#2EFFFFFF") else p.rippleColor
            btn.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(rippleCol),
                content,
                mask
            )
            btn.setColorFilter(iconColor)
            btn.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        if (!UiAnimations.isReducedMotion(v.context)) {
                            v.animate().cancel()
                            v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(60L)
                                .setInterpolator(UiAnimations.FAST_OUT_SLOW_IN).withLayer().start()
                        }
                        if (activeOpenNavTab != idx) {
                            (v as? ImageButton)?.let { ib ->
                                ib.background = DesignSystem.buildOpaqueDrawable(this, p.accentPrimary, 25f)
                                ib.setColorFilter(Color.WHITE)
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL,
                    android.view.MotionEvent.ACTION_OUTSIDE -> {
                        if (!UiAnimations.isReducedMotion(v.context)) {
                            v.animate().cancel()
                            v.animate().scaleX(1f).scaleY(1f).setDuration(110L)
                                .setInterpolator(UiAnimations.FAST_OUT_SLOW_IN).withLayer().start()
                        }
                        if (event.actionMasked != android.view.MotionEvent.ACTION_UP || activeOpenNavTab != idx) {
                            v.post { if (activeOpenNavTab != idx) syncNavDockPills() }
                        }
                    }
                }
                false
            }
        }
    }

    internal fun applyMapChromeTheme() {
        applyTransparentSystemBars()
        val p = currentPalette()
        val night = p.isDark
        val darkMapBg = night || mapStyleMode == MapStyleMode.SATELLITE
        updateSystemBarContrast()
        val iconTint = p.iconTint
        val scaleColor = Color.parseColor(if (darkMapBg) "#F4F4F0" else "#1C1E1B")
        val scaleHaloColor = Color.parseColor(if (darkMapBg) "#CC161715" else "#E6F9F9F7")

        // Opaque milky-matte 52dp circular utility controls (#F3F5F1 Light / #222421 Dark + directional downward shadows)
        DesignSystem.applyFloatingControl(this, btnSyncStatus, p, 26f, 6f)
        DesignSystem.applyFloatingControl(this, btnCompass, p, 26f, 6f)
        DesignSystem.applyFloatingControl(this, btnStyleToggle, p, 26f, 6f)
        DesignSystem.applyFloatingControl(this, btnStatusFilter, p, 26f, 6f)
        DesignSystem.applyFloatingControl(
            this,
            btnLasso,
            p,
            26f,
            6f,
            activeFillColor = if (isLassoMode) p.accentPrimary else null
        )
        DesignSystem.applyFloatingControl(
            this,
            btnRuler,
            p,
            26f,
            6f,
            activeFillColor = if (isRulerMode) p.accentPrimary else null
        )

        // Custom milky-matte compass dial with apricot-orange North tick (#F0651F)
        btnCompass.setImageResource(if (night) R.drawable.ic_compass_dial_dark else R.drawable.ic_compass_dial)
        btnCompass.clearColorFilter()

        // Option B: Slim Milky-Matte Vertical Zoom Pill (46dp wide, 23dp radius)
        DesignSystem.applyFloatingSurface(
            this,
            zoomPillContainer,
            p,
            radiusDp = 23f,
            elevationDp = 6f,
            fillOverride = p.surfaceFrosted,
            strokeOverride = p.floatingBorder
        )
        zoomPillDivider.setBackgroundColor(p.borderSubtle)
        UiAnimations.applyRoundedRipple(this, btnZoomIn, Color.TRANSPARENT, 20f, night)
        UiAnimations.applyRoundedRipple(this, btnZoomOut, Color.TRANSPARENT, 20f, night)
        UiAnimations.attachSnappyPress(btnZoomIn, 0.90f)
        UiAnimations.attachSnappyPress(btnZoomOut, 0.90f)

        // Two-Layer Floating Bottom Navigation Bar: Opaque Milky-Matte Outer Tray + 4 Crisp White Inner Pills
        DesignSystem.applyFloatingSurface(
            this,
            bottomNavDock,
            p,
            radiusDp = 31f,
            elevationDp = 9f,
            fillOverride = p.surfaceFrostedTray,
            strokeOverride = p.floatingBorder
        )
        bottomIconRow.background = null
        bottomIconRow.elevation = 0f
        syncNavDockPills()

        btnStyleToggle.setColorFilter(
            when (mapStyleMode) {
                MapStyleMode.SATELLITE -> p.accentWarm
                MapStyleMode.VECTOR -> iconTint
            }
        )
        btnLasso.setColorFilter(if (isLassoMode) Color.WHITE else iconTint)
        btnRuler.setColorFilter(if (isRulerMode) Color.WHITE else iconTint)
        updateSentToggleUi()
        btnZoomIn.setColorFilter(iconTint)
        btnZoomOut.setColorFilter(iconTint)
        updateFilterIconTint()
        updateLocationIconTint()
        updateSyncStatusBadge()
        undoController.applyTheme(this, p)
        btnRulerReset.text = AppStrings.clear(this)

        // Crisp minimalist bracketed scale bar (locked on 14dp left rail, zero relayout on zoom/pan)
        scaleBarContainer.background = null
        scaleBarContainer.elevation = 0f
        scaleBarContainer.setPadding(0, dp(2), dp(2), dp(2))
        tvScaleLabel.setTextColor(scaleColor)
        tvScaleLabel.setShadowLayer(4f, 0f, 0f, scaleHaloColor)
        scaleBarLine.setBarStyle(scaleColor, scaleHaloColor)
        scaleBarContainer.visibility = if (DevicePrefs.isScaleBarEnabled(this)) View.VISIBLE else View.GONE

        // Crisp circular Speed HUD with tabular numerals
        DesignSystem.applyFloatingSurface(this, speedBadge, p, radiusDp = 30f, elevationDp = 5f)
        tvSpeedValue.setTextColor(p.textPrimary)
        tvSpeedUnit.setTextColor(p.textMuted)

        DesignSystem.applyFloatingSurface(this, routeFromBanner, p, radiusDp = 20f, elevationDp = 5f, strokeOverride = p.accentPrimary)
        tvRouteFromText.setTextColor(p.textPrimary)
        tvRouteFromHint.setTextColor(p.textSecondary)
        btnClearRouteFrom.setColorFilter(p.textMuted)

        DesignSystem.applyFloatingSurface(this, rulerCard, p, radiusDp = 24f, elevationDp = 8f)
        tvRulerDistance.setTextColor(p.textPrimary)
        tvRulerSubtitle.setTextColor(p.textMuted)
        btnRulerReset.setTextColor(p.accentWarm)
        UiAnimations.applyRoundedRipple(this, btnRulerReset, p.accentWarmContainer, 18f, night)
        UiAnimations.attachSnappyPress(btnRulerReset)
        btnRulerUndo.setColorFilter(iconTint)
        UiAnimations.attachSnappyPress(btnRulerUndo)
        btnRulerClose.setColorFilter(iconTint)
        UiAnimations.attachSnappyPress(btnRulerClose)

        DesignSystem.applyFloatingSurface(this, lassoCard, p, radiusDp = 24f, elevationDp = 8f)
        tvLassoTitle.setTextColor(p.textPrimary)
        tvLassoSubtitle.setTextColor(p.textMuted)
        btnLassoClose.setColorFilter(iconTint)
        UiAnimations.attachSnappyPress(btnLassoClose)
        UiAnimations.attachSnappyPress(btnLassoModeToggle)
        UiAnimations.applyRoundedRipple(this, btnLassoMarkSent, p.accentPrimary, 12f, isDark = true)
        UiAnimations.attachSnappyPress(btnLassoMarkSent)
        btnLassoRedraw.setTextColor(p.textPrimary)
        UiAnimations.applyRoundedRipple(this, btnLassoRedraw, p.surfaceTonal, 12f, night)
        UiAnimations.attachSnappyPress(btnLassoRedraw)
        btnLassoUndo.setTextColor(p.textPrimary)
        UiAnimations.applyRoundedRipple(this, btnLassoUndo, p.surfaceTonal, 12f, night)
        UiAnimations.attachSnappyPress(btnLassoUndo)
        btnLassoReset.setTextColor(p.red)
        UiAnimations.applyRoundedRipple(this, btnLassoReset, p.redSoft, 12f, night)
        UiAnimations.attachSnappyPress(btnLassoReset)

        // Bottom Sheet: 28dp top corners, 0dp bottom, warm canvas (#FAF9F6 / #161715)
        DesignSystem.applyFloatingSurface(
            this,
            inScreenSheet,
            p,
            radiusDp = 28f,
            elevationDp = 12f,
            topOnly = true,
            fillOverride = p.surfaceCanvas,
            strokeOverride = if (night) p.borderSubtle else Color.TRANSPARENT
        )
        sheetDragHandle.background = GradientDrawable().apply {
            setColor(p.handleColor)
            cornerRadius = dp(4).toFloat()
        }
        DesignSystem.applyNavBannerSurface(this, navBanner, p)
        tvNavArrow.background = GradientDrawable().apply {
            setColor(Color.parseColor("#29FFFFFF"))
            cornerRadius = dp(12).toFloat()
        }
        tvNavStepDist.setTextColor(Color.WHITE)
        tvNavInstruction.setTextColor(Color.parseColor("#EBF9F0"))
        DesignSystem.applyFloatingSurface(this, navBottomBar, p, radiusDp = 24f, elevationDp = 8f)
        tvNavEta.setTextColor(p.accentPrimary)
        tvNavMeta.setTextColor(p.textSecondary)
        UiAnimations.applyRoundedRipple(this, btnNavVoice, p.surfaceTonal, 22f, night)
        btnNavVoice.setColorFilter(iconTint)
        UiAnimations.attachSnappyPress(btnNavVoice)
        UiAnimations.applyRoundedRipple(this, btnNavStop, p.redSoft, 22f, night)
        btnNavStop.setColorFilter(p.red)
        UiAnimations.attachSnappyPress(btnNavStop)
        UiAnimations.attachSnappyPress(btnClearRouteFrom)
        if (isLassoMode) updateLassoUi()
    }

    internal fun refreshLocalizedChrome() {
        if (::btnTabSearch.isInitialized) {
            btnTabSearch.contentDescription = AppStrings.search(this)
            btnTabBookmarks.contentDescription = AppStrings.bookmarks(this)
            btnTabSettings.contentDescription = AppStrings.settings(this)
            btnTabAbout.contentDescription = AppStrings.about(this)
        }
        applyMapChromeTheme()
        updateFilterIconTint()
        if (isRulerMode) updateRulerUi()
    }

    private fun setupClickListeners() {
        btnSyncStatus.setOnClickListener {
            UiAnimations.hapticTick(btnSyncStatus)
            UiAnimations.animatePulseBump(btnSyncStatus)
            val state = if (isLiveConnected) AppStrings.cloudSynced(this) else AppStrings.offlineCache(this)
            val pendingUploads = repo.pendingUploadCount()
            val pendingPart = if (pendingUploads > 0) AppStrings.pendingUploadCount(this, pendingUploads) else AppStrings.allSynced(this)
            val localCnt = layers.count { LocalFolderStore.isLocalLayerId(it.id) }
            val cloudCnt = layers.size - localCnt
            Toast.makeText(this, "$state • $pendingPart • $cloudCnt cloud, $localCnt local • ${pins.size} bookmarks", Toast.LENGTH_SHORT).show()
        }

        btnCompass.setOnClickListener {
            UiAnimations.hapticTick(btnCompass)
            UiAnimations.animatePulseBump(btnCompass, 1.08f)
            map?.let { m ->
                val reset = CameraPosition.Builder(m.cameraPosition)
                    .bearing(0.0)
                    .tilt(0.0)
                    .build()
                m.animateCamera(CameraUpdateFactory.newCameraPosition(reset), 280)
            }
        }

        btnStyleToggle.setOnClickListener { showMapLayersSheet() }

        btnStatusFilter.setOnClickListener { showMultiStatusFilterDialog() }
        btnSentToggle.setOnClickListener {
            // Tapping the active-filter pill clears active status / unsent filters cleanly back to All Pins
            UiAnimations.hapticTick(btnSentToggle)
            visibleStatuses.clear()
            visibleStatuses.addAll(ALL_STATUSES)
            DevicePrefs.setVisibleStatuses(this, visibleStatuses)
            hideSentPins = false
            DevicePrefs.setHideSentPins(this, false)
            updateSentToggleUi()
            updateFilterIconTint()
            renderMapData()
            Toast.makeText(this, AppStrings.showingAllPins(this), Toast.LENGTH_SHORT).show()
        }
        btnLasso.setOnClickListener { toggleLassoMode(!isLassoMode) }
        btnLassoModeToggle.setOnClickListener { mapToolsController.toggleLassoMoveMapMode() }
        btnLassoMarkSent.setOnClickListener { mapToolsController.confirmLassoMarkSent() }
        btnLassoRedraw.setOnClickListener { mapToolsController.clearLassoSelection() }
        btnLassoUndo.setOnClickListener { mapToolsController.undoLastLassoMark() }
        btnLassoReset.setOnClickListener { mapToolsController.confirmResetAllSentPins() }
        btnLassoClose.setOnClickListener { toggleLassoMode(false) }
        lassoOverlay.onLoopStarted = {
            viewModel.clearLassoSelection()
            mapToolsController.updateLassoUi()
        }
        lassoOverlay.onLoopCompleted = { poly -> mapToolsController.handleLassoLoopCompleted(poly) }
        btnRuler.setOnClickListener { toggleRulerMode(!isRulerMode) }
        btnRulerUndo.setOnClickListener { mapToolsController.undoRulerPoint() }
        btnRulerReset.setOnClickListener { mapToolsController.resetRulerPoints() }
        btnRulerClose.setOnClickListener { toggleRulerMode(false) }
        btnClearRouteFrom.setOnClickListener { clearRouteOrigin() }

        btnZoomIn.setOnClickListener {
            map?.let { m -> m.animateCamera(CameraUpdateFactory.zoomTo((m.cameraPosition.zoom + 1.0).coerceAtMost(20.0)), 250) }
        }
        btnZoomOut.setOnClickListener {
            map?.let { m -> m.animateCamera(CameraUpdateFactory.zoomTo((m.cameraPosition.zoom - 1.0).coerceAtLeast(3.0)), 250) }
        }

        btnLocate.setOnClickListener {
            if (!hasLocationPermission()) {
                locPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                return@setOnClickListener
            }
            if (!ensureLocationServiceOn()) return@setOnClickListener
            val staleStream = System.currentTimeMillis() - gpsController.lastLiveLocationFixMs > 10_000L
            isFollowingGps = true
            startLiveGpsUpdates(forceRestart = staleStream)
            updateLocationIconTint()
            UiAnimations.animatePulseBump(btnLocate)
            val loc = currentLocation
            if (loc != null) {
                if (activeNavRoute != null) {
                    moveCameraForNavigation(loc, 550)
                } else {
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 16.5))
                }
            } else {
                Toast.makeText(this, "Acquiring GPS fix...", Toast.LENGTH_SHORT).show()
            }
        }

        btnNavVoice.setOnClickListener {
            val next = !DevicePrefs.isVoiceNav(this)
            DevicePrefs.setVoiceNav(this, next)
            btnNavVoice.alpha = if (next) 1.0f else 0.4f
            UiAnimations.animateScalePop(btnNavVoice, 0.84f, 150L)
        }
        btnNavVoice.alpha = if (DevicePrefs.isVoiceNav(this)) 1.0f else 0.4f

        btnNavStop.setOnClickListener { stopNavigation() }

        btnTabSearch.setOnClickListener {
            if (activeOpenNavTab == 0 && pageOverlayContainer.visibility == View.VISIBLE && pageBackStack.isEmpty()) return@setOnClickListener
            activeOpenNavTab = 0
            syncNavDockPills()
            openFullPage { searchController.buildSearchPage() }
        }
        btnTabBookmarks.setOnClickListener {
            if (activeOpenNavTab == 1 && pageOverlayContainer.visibility == View.VISIBLE && pageBackStack.isEmpty()) return@setOnClickListener
            activeOpenNavTab = 1
            syncNavDockPills()
            openFullPage(::buildBookmarksAndTracksPage)
        }
        btnTabSettings.setOnClickListener {
            if (activeOpenNavTab == 2 && pageOverlayContainer.visibility == View.VISIBLE && pageBackStack.isEmpty()) return@setOnClickListener
            activeOpenNavTab = 2
            syncNavDockPills()
            openFullPage(::buildSettingsPage)
        }
        btnTabAbout.setOnClickListener {
            if (activeOpenNavTab == 3 && pageOverlayContainer.visibility == View.VISIBLE && pageBackStack.isEmpty()) return@setOnClickListener
            activeOpenNavTab = 3
            syncNavDockPills()
            openFullPage(::buildAboutPage)
        }
    }

    private fun applySheetBottomInset(bottom: Int) {
        sheetContent.setPadding(dp(16), 0, dp(16), bottom + dp(20))
    }

    private fun applyPageBottomInset(bottom: Int) {
        for (i in 0 until pageOverlayContainer.childCount) {
            pageOverlayContainer.getChildAt(i).setPadding(sideInsetLeft, statusBarInsetTop, sideInsetRight, bottom)
        }
    }

    private fun setupBackNavigation() {
        val cb = object : OnBackPressedCallback(true) {
            private var predictiveBackActive = false

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                predictiveBackActive = pageOverlayContainer.visibility == View.VISIBLE &&
                    !UiAnimations.isReducedMotion(this@MainActivity)
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (predictiveBackActive) {
                    UiAnimations.applyPredictiveBackProgress(
                        pageOverlayContainer,
                        backEvent.progress,
                        backEvent.swipeEdge == BackEventCompat.EDGE_LEFT
                    )
                }
            }

            override fun handleOnBackCancelled() {
                if (predictiveBackActive) {
                    predictiveBackActive = false
                    UiAnimations.cancelPredictiveBack(pageOverlayContainer)
                }
            }

            override fun handleOnBackPressed() {
                predictiveBackActive = false
                if (pageOverlayContainer.visibility == View.VISIBLE) {
                    if (pageBackStack.isNotEmpty()) {
                        UiAnimations.resetPredictiveBack(pageOverlayContainer)
                        val back = pageBackStack.removeAt(pageBackStack.lastIndex)
                        back()
                    } else {
                        closeFullPage()
                    }
                    return
                }
                if (activeNavRoute != null) {
                    stopNavigation()
                    return
                }
                if (previewRoute != null) {
                    if (::navigationController.isInitialized) navigationController.cancelPreviewOnSheetClosed()
                    hideInScreenSheet()
                    return
                }
                if (viewModel.routeOrigin != null) {
                    clearRouteOrigin()
                    return
                }
                if (isLassoMode) {
                    toggleLassoMode(false)
                    return
                }
                if (isRulerMode) {
                    toggleRulerMode(false)
                    return
                }
                if (sheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN || isSheetPendingOpen) {
                    val qsPinId = sheetQuickSelectPinId
                    if (qsPinId != null) {
                        val freshPin = pinById[qsPinId]
                        sheetQuickSelectPinId = null
                        if (freshPin != null) {
                            showPinCard(freshPin, animateSwap = true, swapForward = false)
                            return
                        }
                    }
                    hideInScreenSheet()
                    return
                }
                if (isFullScreenMapMode) {
                    setFullScreenMapMode(false)
                    return
                }
                moveTaskToBack(true)
            }
        }
        backCallback = cb
        onBackPressedDispatcher.addCallback(this, cb)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        if (::mapToolsController.isInitialized) mapToolsController.handleDeepLinkIntent(intent)
    }

    internal fun ensurePinVisibleOnMap(pin: PinItem) {
        if (::mapToolsController.isInitialized) mapToolsController.ensurePinVisibleOnMap(pin)
    }

    private fun selectCoordinateOnMap(
        latLng: LatLng,
        explicitTitle: String?,
        fallbackSub: String = "",
        zoomTo: Double? = null
    ) {
        if (::mapToolsController.isInitialized) mapToolsController.selectCoordinateOnMap(latLng, explicitTitle, fallbackSub, zoomTo)
    }

    internal fun loadMapStyle() = mapRenderer.loadMapStyle()
    internal fun apply3dBuildingsToggle(style: Style) = mapRenderer.apply3dBuildingsToggle(style)
    internal fun applyHillshadeSettings(style: Style) = mapRenderer.applyHillshadeSettings(style)
    private fun findExactStackedPins(pin: PinItem): List<PinItem> = mapToolsController.findExactStackedPins(pin)

    internal fun updateSyncStatusBadge() {
        if (!::btnSyncStatus.isInitialized || !::tvSyncPendingBadge.isInitialized) return
        val p = currentPalette()
        val pendingUploads = if (::repository.isInitialized) repo.pendingUploadCount() else 0
        btnSyncStatus.setColorFilter(
            when {
                pendingUploads > 0 -> p.accentWarm
                isLiveConnected -> p.accentPrimary
                else -> p.amber
            }
        )
        if (pendingUploads > 0) {
            tvSyncPendingBadge.text = if (pendingUploads > 99) "99+" else "$pendingUploads"
            tvSyncPendingBadge.background = DesignSystem.buildOpaqueDrawable(
                ctx = this,
                fillColor = p.accentWarm,
                radiusDp = 9f,
                strokeColor = p.surfaceFrosted,
                strokeDp = 1.5f
            )
            UiAnimations.animateSpeedBadgeVisibility(tvSyncPendingBadge, true)
        } else {
            UiAnimations.animateSpeedBadgeVisibility(tvSyncPendingBadge, false)
        }
    }

    internal fun renderMapData() {
        if (::mapRenderer.isInitialized) mapRenderer.renderMapData() else updateSyncStatusBadge()
    }

    private fun renderRouteOverlay(styleArg: Style? = null) {
        if (::mapRenderer.isInitialized) mapRenderer.renderRouteOverlay(styleArg)
    }

    private fun fireStatusPulse(lat: Double, lng: Double, statusHex: String) {
        if (::mapRenderer.isInitialized) mapRenderer.fireStatusPulse(lat, lng, statusHex)
    }

    internal fun updateSentToggleUi() {
        if (::btnSentToggle.isInitialized && ::mapToolsController.isInitialized) mapToolsController.updateSentToggleUi()
    }

    internal fun activeLocalSentCount(): Int =
        if (::mapToolsController.isInitialized) mapToolsController.activeLocalSentCount() else 0

    private fun toggleLassoMode(enabled: Boolean) {
        if (::mapToolsController.isInitialized) mapToolsController.toggleLassoMode(enabled)
    }

    private fun updateLassoUi() {
        if (::mapToolsController.isInitialized) mapToolsController.updateLassoUi()
    }

    private fun toggleRulerMode(enabled: Boolean) {
        if (::mapToolsController.isInitialized) mapToolsController.toggleRulerMode(enabled)
    }

    internal fun updateRulerUi() {
        if (::mapToolsController.isInitialized) mapToolsController.updateRulerUi()
    }

    private fun setRouteOrigin(lat: Double, lng: Double, label: String) {
        if (::navigationController.isInitialized) navigationController.setRouteOrigin(lat, lng, label)
    }

    private fun clearRouteOrigin() {
        if (::navigationController.isInitialized) navigationController.clearRouteOrigin()
    }

    internal fun selectPinAndOpenCard(pin: PinItem, zoomIfFar: Boolean = false) {
        if (::mapToolsController.isInitialized) mapToolsController.selectPinAndOpenCard(pin, zoomIfFar)
    }

    private fun clearSelectedMarker() {
        if (::placeDialogController.isInitialized) placeDialogController.clearTappedViews()
        if (::mapToolsController.isInitialized) mapToolsController.clearSelectedMarker()
    }

    private fun resetMapPadding() {
        if (::mapToolsController.isInitialized) mapToolsController.resetMapPadding()
    }

    internal fun showInScreenSheet(
        peekDp: Int = 280,
        inPlace: Boolean = false,
        expandToFit: Boolean = false
    ) {
        activeSheetPeekDp = peekDp
        val bottomClearance = maxOf(navBarInsetBottom, imeInsetBottom)
        val screenH = resources.displayMetrics.heightPixels
        val maxSheetH = (screenH - statusBarInsetTop - dp(12)).coerceAtLeast(dp(240))
        sheetBehavior.maxHeight = maxSheetH
        val targetPeek = if (expandToFit) {
            val availW = (resources.displayMetrics.widthPixels - sideInsetLeft - sideInsetRight).coerceAtLeast(dp(240))
            inScreenSheet.measure(
                View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val measured = inScreenSheet.measuredHeight.coerceAtLeast(dp(peekDp) + bottomClearance)
            val capH = if (imeInsetBottom > 0) maxSheetH else (screenH * 0.85f).toInt().coerceAtLeast(dp(240))
            minOf(measured, capH)
        } else {
            val maxPeek = (screenH * 0.68f).toInt().coerceAtLeast(dp(200))
            minOf(dp(peekDp) + bottomClearance, maxPeek)
        }

        val alreadyOpen = sheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED ||
            sheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED
        if (alreadyOpen) {
            isSwappingSheetInPlace = true
            if (::sheetScrollView.isInitialized && !inPlace) sheetScrollView.scrollTo(0, 0)
            sheetBehavior.setPeekHeight(targetPeek, !UiAnimations.isReducedMotion(this))
            inScreenSheet.requestLayout()
            if (expandToFit) {
                inScreenSheet.post {
                    if (!isFinishing && !isDestroyed && sheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                        sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                    }
                    isSwappingSheetInPlace = false
                }
            } else {
                inScreenSheet.post { isSwappingSheetInPlace = false }
            }
        } else {
            bottomDock.translationY = 0f
            UiAnimations.fadeHide(bottomDock, 120L)
            if (::sheetScrollView.isInitialized) sheetScrollView.scrollTo(0, 0)
            sheetBehavior.setPeekHeight(targetPeek, false)
            inScreenSheet.requestLayout()
            val openToken = ++sheetOpenSeq
            isSheetPendingOpen = true
            inScreenSheet.post {
                if (!isFinishing && !isDestroyed && openToken == sheetOpenSeq) {
                    isSheetPendingOpen = false
                    sheetBehavior.state = if (expandToFit) {
                        BottomSheetBehavior.STATE_EXPANDED
                    } else {
                        BottomSheetBehavior.STATE_COLLAPSED
                    }
                }
            }
        }
    }

    internal fun clearSheetStickyHeader() {
        if (::sheetStickyHeader.isInitialized) {
            sheetStickyHeader.removeAllViews()
            sheetStickyHeader.visibility = View.GONE
        }
    }

    internal fun hideInScreenSheet() {
        hideSoftKeyboard()
        val wasPending = isSheetPendingOpen
        isSheetPendingOpen = false
        sheetOpenSeq++
        if (wasPending && sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            sheetQuickSelectPinId = null
            clearSheetStickyHeader()
            clearSelectedMarker()
            if (::navigationController.isInitialized) navigationController.cancelPreviewOnSheetClosed()
            if (activeNavRoute == null) {
                resetMapPadding()
            }
            if (!isFullScreenMapMode) {
                bottomDock.translationY = 0f
                UiAnimations.fadeShow(bottomDock, 165L)
            }
        }
        sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun showPinCard(pin: PinItem, animateSwap: Boolean = false, swapForward: Boolean = true) {
        if (::pinCardController.isInitialized) pinCardController.showPinCard(pin, animateSwap, swapForward)
    }

    private fun showTappedPlaceCard(title: String, subtitle: String, lat: Double, lng: Double) {
        if (::placeDialogController.isInitialized) placeDialogController.showTappedPlaceCard(title, subtitle, lat, lng)
    }

    // --- Route Preview (Route From + Car / Bicycle / Walk Modes) & Active Navigation ---

    private fun openRoutePreview(destLat: Double, destLng: Double, destTitle: String, modeOverride: TravelMode = travelMode) {
        navigationController.openRoutePreview(destLat, destLng, destTitle, modeOverride)
    }

    private fun moveCameraForNavigation(loc: Location, durationMs: Int = 500) {
        navigationController.moveCameraForNavigation(loc, durationMs)
    }

    private fun stopNavigation() {
        navigationController.stopNavigation()
    }

    private fun hasLocationPermission(): Boolean = gpsController.hasLocationPermission()
    private fun ensureLocationServiceOn(): Boolean = gpsController.ensureLocationServiceOn()
    private fun syncGpsPollingProfile(forceRestart: Boolean = false) = gpsController.syncGpsPollingProfile(forceRestart)
    private fun startLiveGpsUpdates(forceRestart: Boolean = false) = gpsController.startLiveGpsUpdates(forceRestart)
    private fun stopLiveGpsUpdates() = gpsController.stopLiveGpsUpdates()
    internal fun updateSpeedBadge(loc: Location?) = gpsController.updateSpeedBadge(loc)
    private fun updateLocationIconTint() = gpsController.updateLocationIconTint()

    internal fun updateFilterIconTint() {
        val p = currentPalette()
        val color = when {
            visibleStatuses.containsAll(ALL_STATUSES) && !hideSentPins -> p.iconTint
            visibleStatuses == setOf("pending") && !hideSentPins -> p.amber
            visibleStatuses == setOf("issue") && !hideSentPins -> p.red
            visibleStatuses == setOf("done") && !hideSentPins -> p.green
            else -> p.accent
        }
        btnStatusFilter.setColorFilter(color)
    }

    internal fun updateScaleBar() {
        if (::scaleBarContainer.isInitialized) {
            if (!cachedScaleBarEnabled) {
                if (scaleBarContainer.visibility != View.GONE) scaleBarContainer.visibility = View.GONE
                return
            } else if (scaleBarContainer.visibility != View.VISIBLE) {
                scaleBarContainer.visibility = View.VISIBLE
            }
        }
        val m = map ?: return
        val center = m.cameraPosition.target ?: return
        val mpp = m.projection.getMetersPerPixelAtLatitude(center.latitude)
        if (mpp <= 0.0 || !mpp.isFinite()) return
        val density = resources.displayMetrics.density
        val maxPx = 110f * density
        val maxMeters = mpp * maxPx

        val label: String
        val widthPx: Int
        if (cachedSpeedUnit == SpeedUnit.IMPERIAL) {
            val maxFt = maxMeters * 3.28084
            var chosenFt = SCALE_STEPS_IMPERIAL[0]
            for (s in SCALE_STEPS_IMPERIAL) {
                if (s <= maxFt) chosenFt = s else break
            }
            val chosenMeters = chosenFt / 3.28084
            widthPx = (chosenMeters / mpp).toInt().coerceIn(dp(32), dp(120))
            label = when {
                chosenFt >= 5280.0 -> "${(chosenFt / 5280.0).toInt()} mi"
                chosenFt == 2640.0 -> "0.5 mi"
                else -> "${chosenFt.toInt()} ft"
            }
        } else {
            var chosen = SCALE_STEPS_METRIC[0]
            for (s in SCALE_STEPS_METRIC) {
                if (s <= maxMeters) chosen = s else break
            }
            widthPx = (chosen / mpp).toInt().coerceIn(dp(32), dp(120))
            label = if (chosen >= 1000.0) "${(chosen / 1000.0).toInt()} km" else "${chosen.toInt()} m"
        }

        if (label != lastScaleLabel) {
            lastScaleLabel = label
            tvScaleLabel.text = label
        }
        if (kotlin.math.abs(widthPx - lastScaleWidthPx) >= 2) {
            lastScaleWidthPx = widthPx
            scaleBarLine.setBarWidthPx(widthPx)
        }
    }

    // --- Full-Screen Overlay Page Host ---

    internal fun hideSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
        currentFocus?.clearFocus()
    }

    internal fun openFullPage(builder: () -> View) {
        pageOverlayContainer.animate().cancel()
        activePageCleanup?.invoke()
        activePageCleanup = null
        liveBookmarksRefresh = null
        livePinListRefresh = null
        liveEditHistoryRefresh = null
        hideSoftKeyboard()
        pageBackStack.clear()
        pageOverlayContainer.setBackgroundColor(currentPalette().pageBackdrop)
        pageOverlayContainer.removeAllViews()
        pageOverlayContainer.addView(builder())
        UiAnimations.animatePageEnter(pageOverlayContainer)
        syncGpsPollingProfile()
        updateSystemBarContrast(forFullPage = true)
    }

    internal fun pushSubPage(onBack: () -> Unit, builder: () -> View) {
        pageOverlayContainer.animate().cancel()
        activePageCleanup?.invoke()
        activePageCleanup = null
        liveBookmarksRefresh = null
        livePinListRefresh = null
        liveEditHistoryRefresh = null
        hideSoftKeyboard()
        pageBackStack.add(onBack)
        pageOverlayContainer.setBackgroundColor(currentPalette().pageBackdrop)
        val v = builder()
        UiAnimations.transitionSubPagePush(pageOverlayContainer, v, AppStrings.isRtl(this))
        updateSystemBarContrast(forFullPage = true)
    }

    internal fun popSubPage(builder: () -> View) {
        pageOverlayContainer.animate().cancel()
        activePageCleanup?.invoke()
        activePageCleanup = null
        liveBookmarksRefresh = null
        livePinListRefresh = null
        liveEditHistoryRefresh = null
        offlineMaps.setStateListener(null)
        offlineMaps.setOnMapPackInstalledListener(null)
        hideSoftKeyboard()
        pageOverlayContainer.setBackgroundColor(currentPalette().pageBackdrop)
        val backView = builder()
        UiAnimations.transitionSubPagePop(pageOverlayContainer, backView, AppStrings.isRtl(this))
        updateSystemBarContrast(forFullPage = true)
    }

    internal fun closeFullPage() {
        pageOverlayContainer.animate().cancel()
        activePageCleanup?.invoke()
        activePageCleanup = null
        liveBookmarksRefresh = null
        livePinListRefresh = null
        liveEditHistoryRefresh = null
        offlineMaps.setStateListener(null)
        hideSoftKeyboard()
        pageBackStack.clear()
        // Reset nav bar pills back to neutral white whenever returning to the map
        activeOpenNavTab = -1
        syncNavDockPills()
        updateSystemBarContrast(forFullPage = false)
        if (pageOverlayContainer.visibility != View.VISIBLE) {
            pageOverlayContainer.removeAllViews()
            syncGpsPollingProfile()
            return
        }
        UiAnimations.animatePageExit(pageOverlayContainer) {
            pageOverlayContainer.removeAllViews()
            syncGpsPollingProfile()
        }
    }

    internal fun makePageFrame(
        title: String,
        onBack: () -> Unit,
        rightAction: View? = null,
        scrollable: Boolean = true
    ): Pair<LinearLayout, LinearLayout> {
        val p = currentPalette()
        val night = p.isDark

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (AppStrings.isRtl(this@MainActivity)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(p.pageBackdrop)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(sideInsetLeft, statusBarInsetTop, sideInsetRight, maxOf(navBarInsetBottom, imeInsetBottom))
        }

        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(14), dp(8))
        }
        appBar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(p.textPrimary)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            UiAnimations.applyRowRipple(this@MainActivity, this, night)
            UiAnimations.attachSnappyPress(this)
            setOnClickListener { onBack() }
        })
        appBar.addView(TextView(this).apply {
            text = title
            textSize = 20f
            letterSpacing = -0.01f
            setTextColor(p.textPrimary)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(8)
            }
        })
        if (rightAction != null) appBar.addView(rightAction)
        outer.addView(appBar)

        outer.addView(DesignSystem.createHairlineDivider(this, p))

        if (scrollable) {
            val scroll = ScrollView(this).apply {
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(28))
            }
            scroll.addView(body)
            outer.addView(scroll)
            return outer to body
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        outer.addView(body)
        return outer to body
    }

    internal fun buildBookmarksAndTracksPage(): View =
        bookmarksPageController.buildBookmarksAndTracksPage()

    private fun handleImportUri(uri: Uri) {
        if (::folderActionsController.isInitialized) folderActionsController.handleImportUri(uri)
    }

    internal fun buildLocalDeviceAliasMap(): Map<String, String> = repository.buildLocalDeviceAliasMap()

    internal fun isLayerOwner(layer: LayerItem?): Boolean = repository.isLayerOwner(layer)

    private fun notifySyncError(err: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            Toast.makeText(this, "Sync failed: $err", Toast.LENGTH_LONG).show()
        }
    }

    internal fun deletePinHistoryEntryUnified(pin: PinItem, idx: Int): PinItem {
        return repository.deletePinHistoryEntry(pin, idx, ::notifySyncError)
    }

    internal fun restorePinStatusSnapshotUnified(previousPin: PinItem) {
        repository.restorePinStatusSnapshot(previousPin, ::notifySyncError)
    }

    internal fun applyExtraMapToolsVisibility() {
        val show = DevicePrefs.isShowExtraMapTools(this)
        if (::btnLasso.isInitialized) {
            if (btnLasso.isAttachedToWindow && activeNavRoute == null) {
                UiAnimations.animateSpeedBadgeVisibility(btnLasso, show)
            } else {
                btnLasso.visibility = if (show && activeNavRoute == null) View.VISIBLE else View.GONE
            }
        }
        if (!show && isLassoMode) toggleLassoMode(false)
    }

    internal fun applyZoomButtonsVisibility() {
        if (!::zoomPillContainer.isInitialized) return
        val show = DevicePrefs.isShowZoomButtons(this)
        if (zoomPillContainer.isAttachedToWindow) {
            UiAnimations.animateSpeedBadgeVisibility(zoomPillContainer, show)
        } else {
            zoomPillContainer.visibility = if (show) View.VISIBLE else View.GONE
        }
    }


    private fun onRepositorySnapshotUpdated(snap: FolderIndexSnapshot) {
        viewModel.updateFolderSnapshot(snap)
        if (::mapRenderer.isInitialized) {
            val activeTrackIds = snap.tracks.mapTo(HashSet(snap.tracks.size)) { it.id }
            mapRenderer.retainTrackPointCache(activeTrackIds)
        }
        renderMapData()
        liveBookmarksRefresh?.invoke()
        livePinListRefresh?.invoke()
        liveEditHistoryRefresh?.invoke()
        if (isLassoMode) updateLassoUi()
    }


    internal fun refreshJoinedSync() {
        repository.refreshJoinedSync { snap ->
            if (isFinishing || isDestroyed) return@refreshJoinedSync
            if (::mapToolsController.isInitialized) {
                mapToolsController.handleSyncedSnapshotForDeepLinkAndAutoZoom(snap)
            }
        }
    }

    private fun joinShareCode(rawCode: String) {
        if (::folderActionsController.isInitialized) folderActionsController.joinShareCode(rawCode)
    }

    // --- Utilities & Lifecycle ---

    internal fun applyKeepAwakeFlag() {
        if (DevicePrefs.isKeepAwake(this) || activeNavRoute != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    internal fun safeStartActivity(intent: Intent, fallbackMsg: String = "No app available to handle this action") {
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, fallbackMsg, Toast.LENGTH_SHORT).show() }
    }

    internal fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun collectLayerLatLngs(layerPins: List<PinItem>, layerTracks: List<TrackItem>): List<LatLng> =
        mapToolsController.collectLayerLatLngs(layerPins, layerTracks)

    private fun zoomToKmlBounds(pts: List<LatLng>, layerIdToReveal: String? = null) =
        mapToolsController.zoomToKmlBounds(pts, layerIdToReveal)

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        mapView.onStart()
    }
    @Suppress("DEPRECATION")
    internal fun getInstalledVersionName(): String {
        val fallback = BuildConfig.VERSION_NAME.trim().removePrefix("v").removePrefix("V")
        return runCatching {
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            (info.versionName ?: fallback).trim().removePrefix("v").removePrefix("V")
        }.getOrDefault(fallback)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyTransparentSystemBars()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        backCallback?.isEnabled = true
        mapView.onResume()
        MapLibre.setConnected(true)
        if (hasLocationPermission()) startLiveGpsUpdates()
        renderMapData()
    }
    override fun onPause() {
        isActivityResumed = false
        if (::mapRenderer.isInitialized) mapRenderer.pause()
        if (::gpsController.isInitialized) gpsController.pause()
        mapView.onPause()
        super.onPause()
    }
    override fun onStop() {
        isActivityStarted = false
        LocalFolderStore.flushPendingWritesAsync(applicationContext)
        mapView.onStop()
        RustCoreBridge.trimNativeMemory(aggressive = false)
        super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            val aggressive = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            RustCoreBridge.trimNativeMemory(aggressive = aggressive)
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                runCatching { mapView.onLowMemory() }
            }
        }
    }
    override fun onLowMemory() {
        super.onLowMemory()
        RustCoreBridge.trimNativeMemory(aggressive = true)
        mapView.onLowMemory()
    }
    override fun onDestroy() {
        liveBookmarksRefresh = null
        livePinListRefresh = null
        liveEditHistoryRefresh = null
        activePageCleanup?.invoke()
        activePageCleanup = null
        pageOverlayContainer.animate().cancel()
        if (::undoController.isInitialized) undoController.dismiss(animate = false)
        contourManager.destroy()
        offlineMaps.setStateListener(null)
        if (::searchController.isInitialized) searchController.destroy()
        if (::mapToolsController.isInitialized) mapToolsController.destroy()
        if (::navigationController.isInitialized) navigationController.destroy()
        if (::gpsController.isInitialized) gpsController.destroy()
        if (::mapRenderer.isInitialized) mapRenderer.destroy()
        if (::repository.isInitialized) repository.stop()
        mapView.onDestroy()
        super.onDestroy()
    }
    private fun applyResponsiveChromeSizing() {
        if (!::btnLocate.isInitialized) return
        val compact = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val utilSize = dp(if (compact) 44 else 52)
        val locSize = dp(if (compact) 56 else 66)
        val utilPad = dp(if (compact) 9 else 11)
        val locPad = dp(if (compact) 14 else 17)
        val arcBox = dp(if (compact) 118 else 138)
        val arcCardOffset = dp(if (compact) 66 else 78)
        val arcDiagOffset = dp(if (compact) 50 else 59)

        fun sizeCircle(v: View, size: Int, padPx: Int, marginEndPx: Int? = null, marginBottomPx: Int? = null) {
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            lp.width = size
            lp.height = size
            if (marginEndPx != null) lp.marginEnd = marginEndPx
            if (marginBottomPx != null) lp.bottomMargin = marginBottomPx
            v.layoutParams = lp
            v.setPadding(padPx, padPx, padPx, padPx)
        }

        if (::rightArcCluster.isInitialized) {
            rightArcCluster.layoutParams = rightArcCluster.layoutParams.apply {
                width = arcBox
                height = arcBox
            }
        }
        if (::syncStatusContainer.isInitialized) {
            sizeCircle(syncStatusContainer, utilSize, 0)
            btnSyncStatus.setPadding(utilPad, utilPad, utilPad, utilPad)
        } else {
            sizeCircle(btnSyncStatus, utilSize, utilPad)
        }
        if (::btnCompass.isInitialized) sizeCircle(btnCompass, utilSize, dp(if (compact) 8 else 9))
        sizeCircle(btnStyleToggle, utilSize, utilPad, marginEndPx = 0, marginBottomPx = arcCardOffset)
        sizeCircle(btnRuler, utilSize, utilPad, marginEndPx = arcDiagOffset, marginBottomPx = arcDiagOffset)
        sizeCircle(btnStatusFilter, utilSize, utilPad, marginEndPx = arcCardOffset, marginBottomPx = 0)
        sizeCircle(btnLocate, locSize, locPad, marginEndPx = 0, marginBottomPx = 0)
        sizeCircle(btnLasso, utilSize, utilPad, marginBottomPx = dp(if (compact) 6 else 10))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveChromeSizing()
        updateScaleBar()
        if (::sheetBehavior.isInitialized && sheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            val maxSheetH = (resources.displayMetrics.heightPixels - statusBarInsetTop - dp(12)).coerceAtLeast(dp(240))
            sheetBehavior.maxHeight = maxSheetH
            val maxPeek = (resources.displayMetrics.heightPixels * 0.62f).toInt().coerceAtLeast(dp(180))
            sheetBehavior.peekHeight = minOf(dp(activeSheetPeekDp) + navBarInsetBottom, maxPeek)
        }
        if (DevicePrefs.getThemeMode(this) == "auto") {
            applyMapChromeTheme()
            if (mapStyleMode == MapStyleMode.VECTOR) loadMapStyle()
            if (::pageOverlayContainer.isInitialized && pageOverlayContainer.visibility == View.VISIBLE) {
                pageOverlayContainer.setBackgroundColor(currentPalette().pageBackdrop)
            }
            val curPinId = selectedPinId
            val curTapped = viewModel.tappedLocation
            if (sheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                if (curPinId != null) {
                    pinById[curPinId]?.let { showPinCard(it, animateSwap = false) }
                } else if (curTapped != null) {
                    showTappedPlaceCard(curTapped.title, curTapped.subtitle, curTapped.lat, curTapped.lng)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
}
