package com.terrasync.app

import android.content.Intent
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.geojson.Point
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Owns interactive map tools and selection workflows: Lasso (local-sent bulk draw),
 * multi-point Distance Ruler, filter pill chrome, map feature hit-testing, coordinate
 * reverse-geocoding, KML auto-zoom bounds, and incoming deep-link dispatch.
 */
class MapToolsController(
    private val activity: AppCompatActivity,
    private val viewModel: MapViewModel,
    private val renderer: MapLayerRenderer,
    private val gpsController: GpsLocationController,
    private val mapView: MapView,
    private val lassoOverlay: LassoOverlayView,
    private val lassoCard: LinearLayout,
    private val tvLassoTitle: TextView,
    private val tvLassoSubtitle: TextView,
    private val btnLassoModeToggle: TextView,
    private val btnLassoMarkSent: TextView,
    private val btnLassoRedraw: TextView,
    private val btnLassoUndo: TextView,
    private val btnLassoReset: TextView,
    private val rulerCard: LinearLayout,
    private val btnRulerUndo: ImageButton,
    private val tvRulerDistance: TextView,
    private val tvRulerSubtitle: TextView,
    private val btnRulerReset: TextView,
    private val btnSentToggle: TextView,
    private val bottomDock: LinearLayout,
    private val bottomNavDock: SwipeDockFrameLayout,
    private val pageOverlayContainer: FrameLayout,
    private val undoController: StatusUndoController,
    private val dp: (Int) -> Int,
    private val currentPalette: () -> ThemePalette,
    private val themedDialogBuilder: (String) -> AlertDialog.Builder,
    private val showThemedDialog: (AlertDialog, Boolean) -> AlertDialog,
    private val isActivityAlive: () -> Boolean,
    private val getInsetsTopBottom: () -> Pair<Int, Int>,
    private val getSpeedUnit: () -> SpeedUnit,
    private val updateFilterIconTint: () -> Unit,
    private val applyMapChromeTheme: () -> Unit,
    private val isFullScreenMapMode: () -> Boolean,
    private val setFullScreenMapMode: (Boolean, Boolean) -> Unit,
    private val hideInScreenSheet: () -> Unit,
    private val isSheetHidden: () -> Boolean,
    private val closeFullPage: () -> Unit,
    private val showPinCard: (PinItem, Boolean) -> Unit,
    private val showTappedPlaceCard: (String, String, Double, Double) -> Unit,
    private val onReverseGeocodeResult: (LatLng, String, String, String) -> Unit,
    private val onJoinShareCode: (String) -> Unit,
    private val onImportUri: (Uri) -> Unit
) {

    data class SharedPinTarget(
        val lat: Double,
        val lng: Double,
        val name: String,
        val pinId: String,
        val zoom: Double? = null,
        val sourceHint: String = "Shared via TerraSync",
        val hasExplicitLabel: Boolean = false
    )

    var pendingSharedPin: SharedPinTarget? = null
    private var pendingZoomPoints: List<LatLng>? = null
    private var lastSentTogglePillText: String = ""
    private var reverseGeocodeSeq: Int = 0
    private var pendingReverseGeocodeRunnable: Runnable? = null

    fun consumePendingSharedPinOnMapReady() {
        pendingSharedPin?.let { target ->
            pendingSharedPin = null
            openSharedPinTarget(target)
        }
    }

    fun handleSyncedSnapshotForDeepLinkAndAutoZoom(snap: FolderIndexSnapshot) {
        val waitingId = viewModel.pendingSharedPinId
        if (waitingId != null) {
            val matched = snap.pinById[waitingId]
            if (matched != null) {
                viewModel.pendingSharedPinId = null
                viewModel.hasAutoZoomedInitial = true
                ensurePinVisibleOnMap(matched)
                selectPinAndOpenCard(matched, zoomIfFar = true)
                return
            }
        }
        if (!viewModel.hasAutoZoomedInitial && (snap.pins.isNotEmpty() || snap.tracks.isNotEmpty())) {
            viewModel.hasAutoZoomedInitial = true
            zoomToKmlBounds(collectLayerLatLngs(snap.pins, snap.tracks))
        }
    }

    fun updateSentToggleUi() {
        val p = currentPalette()
        val hasStatusFilter = !viewModel.visibleStatuses.containsAll(MapViewModel.ALL_STATUSES)
        val hasAnyFilter = hasStatusFilter || viewModel.hideSentPins
        if (!hasAnyFilter) {
            lastSentTogglePillText = ""
            UiAnimations.animateSpeedBadgeVisibility(btnSentToggle, false)
            updateFilterIconTint()
            return
        }
        val parts = mutableListOf<String>()
        if (hasStatusFilter) {
            if (viewModel.visibleStatuses.isEmpty()) {
                parts.add("Hidden")
            } else {
                if ("pending" in viewModel.visibleStatuses) parts.add(AppStrings.pending(activity))
                if ("done" in viewModel.visibleStatuses) parts.add(AppStrings.done(activity))
                if ("issue" in viewModel.visibleStatuses) parts.add(AppStrings.issue(activity))
            }
        }
        if (viewModel.hideSentPins) parts.add("Unsent")
        val pillColor = when {
            viewModel.visibleStatuses == setOf("pending") && !viewModel.hideSentPins -> p.amber
            viewModel.visibleStatuses == setOf("done") && !viewModel.hideSentPins -> p.green
            viewModel.visibleStatuses == setOf("issue") && !viewModel.hideSentPins -> p.red
            else -> p.accentPrimary
        }
        val nextText = "${parts.joinToString(" • ")} ×"
        val wasVisible = btnSentToggle.visibility == View.VISIBLE
        btnSentToggle.text = nextText
        btnSentToggle.setTextColor(Color.WHITE)
        DesignSystem.applyFloatingControl(activity, btnSentToggle, p, 22f, 6f, activeFillColor = pillColor)
        if (!wasVisible) {
            lastSentTogglePillText = nextText
            UiAnimations.animateSpeedBadgeVisibility(btnSentToggle, true)
        } else if (nextText != lastSentTogglePillText) {
            lastSentTogglePillText = nextText
            UiAnimations.animateScalePop(btnSentToggle, 0.90f, 150L)
        }
        updateFilterIconTint()
    }

    fun activeLocalSentCount(): Int {
        val sentIds = DevicePrefs.getLocalSentPinIds(activity)
        if (sentIds.isEmpty()) return 0
        return viewModel.folderSnapshot.pins.count { it.id in sentIds }
    }

    fun toggleLassoMode(enabled: Boolean) {
        if (enabled && isFullScreenMapMode()) setFullScreenMapMode(false, false)
        if (enabled && viewModel.isRulerMode) toggleRulerMode(false)
        viewModel.applyLassoMode(enabled)
        if (enabled) {
            undoController.dismiss(animate = false)
            hideInScreenSheet()
            UiAnimations.fadeShow(bottomDock, 160L)
            UiAnimations.hideSlideBottom(bottomNavDock, 115L)
            UiAnimations.showSlideBottom(lassoCard)
            lassoOverlay.visibility = View.VISIBLE
            lassoOverlay.clearLoop()
            updateLassoUi()
        } else {
            lassoOverlay.clearLoop()
            lassoOverlay.visibility = View.GONE
            UiAnimations.hideSlideBottom(lassoCard)
            if (viewModel.activeNavRoute == null && !viewModel.isRulerMode) UiAnimations.showSlideBottom(bottomNavDock)
        }
        applyMapChromeTheme()
    }

    fun toggleLassoMoveMapMode() {
        if (!viewModel.isLassoMode) return
        viewModel.lassoMoveMapMode = !viewModel.lassoMoveMapMode
        if (viewModel.lassoMoveMapMode) {
            viewModel.clearLassoSelection()
            lassoOverlay.clearLoop()
            lassoOverlay.visibility = View.GONE
        } else {
            lassoOverlay.visibility = View.VISIBLE
        }
        updateLassoUi()
    }

    fun clearLassoSelection() {
        viewModel.clearLassoSelection()
        lassoOverlay.clearLoop()
        updateLassoUi()
    }

    fun handleLassoLoopCompleted(poly: List<PointF>) {
        val m = renderer.map ?: return
        val snap = viewModel.folderSnapshot
        val hiddenLayerIds = DevicePrefs.getHiddenLayerIds(activity)
        val sentIds = if (viewModel.hideSentPins) DevicePrefs.getLocalSentPinIds(activity) else emptySet()
        val geoPoly = DoubleArray(poly.size * 2)
        for (i in poly.indices) {
            val ll = m.projection.fromScreenLocation(poly[i])
            geoPoly[i * 2] = ll.longitude
            geoPoly[i * 2 + 1] = ll.latitude
        }
        val inside = RustCoreBridge.filterPinsInGeoPolygon(
            version = snap.dataVersion,
            pins = snap.pins,
            hiddenLayerIds = hiddenLayerIds,
            sentIds = sentIds,
            visibleStatuses = viewModel.visibleStatuses,
            geoPolyLngLat = geoPoly
        ) ?: renderer.getVisiblePins().filter { p ->
            val pt = m.projection.toScreenLocation(LatLng(p.lat, p.lng))
            LassoOverlayView.isPointInPolygon(pt.x, pt.y, poly)
        }
        if (inside.isEmpty()) {
            viewModel.clearLassoSelection()
            lassoOverlay.clearLoop()
            updateLassoUi()
            Toast.makeText(activity, "No visible pins inside circle", Toast.LENGTH_SHORT).show()
            return
        }
        val unsentInside = inside.filter { !DevicePrefs.isPinLocalSent(activity, it.id) }
        if (unsentInside.isEmpty()) {
            viewModel.clearLassoSelection()
            lassoOverlay.clearLoop()
            updateLassoUi()
            Toast.makeText(activity, "All ${inside.size} pins inside are already marked sent", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.lassoPendingPinIds = unsentInside.map { it.id }
        UiAnimations.hapticConfirm(lassoCard)
        updateLassoUi()
    }

    fun confirmLassoMarkSent() {
        val pendingIds = viewModel.lassoPendingPinIds
        if (pendingIds.isEmpty()) {
            Toast.makeText(activity, "Draw a circle around pins first", Toast.LENGTH_SHORT).show()
            return
        }
        val batch = pendingIds.toList()
        DevicePrefs.addLocalSentPinIds(activity, batch)
        viewModel.sentUndoStack.add(batch)
        viewModel.clearLassoSelection()
        lassoOverlay.clearLoop()
        updateSentToggleUi()
        updateLassoUi()
        renderer.renderMapData()
        Toast.makeText(activity, "Marked ${batch.size} pin${if (batch.size == 1) "" else "s"} as sent (local only)", Toast.LENGTH_SHORT).show()
    }

    fun undoLastLassoMark() {
        if (viewModel.sentUndoStack.isEmpty()) return
        val lastBatch = viewModel.sentUndoStack.removeAt(viewModel.sentUndoStack.lastIndex)
        DevicePrefs.removeLocalSentPinIds(activity, lastBatch)
        updateSentToggleUi()
        updateLassoUi()
        renderer.renderMapData()
        Toast.makeText(activity, "Undid ${lastBatch.size} sent pin${if (lastBatch.size == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
    }

    fun confirmResetAllSentPins() {
        val active = activeLocalSentCount()
        val stored = DevicePrefs.getLocalSentPinIds(activity).size
        if (active == 0 && stored == 0) {
            Toast.makeText(activity, "No sent pins to reset", Toast.LENGTH_SHORT).show()
            return
        }
        if (active == 0) {
            DevicePrefs.clearLocalSentPins(activity)
            viewModel.sentUndoStack.clear()
            updateLassoUi()
            Toast.makeText(activity, "Cleared old sent marks", Toast.LENGTH_SHORT).show()
            return
        }
        showThemedDialog(
            themedDialogBuilder("Reset Sent Marks?")
                .setMessage("Clear all $active local sent marks on this phone? All pins remain safely in your folders.")
                .setPositiveButton("Reset") { _, _ ->
                    DevicePrefs.clearLocalSentPins(activity)
                    viewModel.sentUndoStack.clear()
                    viewModel.clearLassoSelection()
                    lassoOverlay.clearLoop()
                    updateSentToggleUi()
                    updateLassoUi()
                    renderer.renderMapData()
                    Toast.makeText(activity, "Cleared all local sent marks", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .create(),
            true
        )
    }

    fun updateLassoUi() {
        val p = currentPalette()
        val sel = viewModel.lassoPendingPinIds.size
        val totalSent = activeLocalSentCount()
        if (viewModel.lassoMoveMapMode) {
            btnLassoModeToggle.text = "Draw"
            btnLassoModeToggle.setTextColor(Color.WHITE)
            UiAnimations.applyRoundedRipple(activity, btnLassoModeToggle, p.green, 10f, isDark = true)
            tvLassoTitle.text = "Move or zoom the map"
            tvLassoSubtitle.text = "Tap Draw when ready to circle pins"
            btnLassoMarkSent.text = "Mark Sent"
            btnLassoMarkSent.alpha = 0.45f
            btnLassoRedraw.alpha = 0.45f
        } else {
            btnLassoModeToggle.text = "Move Map"
            btnLassoModeToggle.setTextColor(p.accent)
            UiAnimations.applyRoundedRipple(activity, btnLassoModeToggle, p.accentSoft, 10f, p.isDark)
            if (sel > 0) {
                tvLassoTitle.text = "$sel pin${if (sel == 1) "" else "s"} selected"
                tvLassoSubtitle.text = "Tap Mark Sent (local only • $totalSent sent total)"
                btnLassoMarkSent.text = "Mark Sent ($sel)"
                btnLassoMarkSent.alpha = 1f
                btnLassoRedraw.alpha = 1f
            } else {
                tvLassoTitle.text = "Draw circle around sent pins"
                tvLassoSubtitle.text = if (totalSent > 0) "$totalSent marked sent on this phone" else "Local to this phone only"
                btnLassoMarkSent.text = "Mark Sent"
                btnLassoMarkSent.alpha = 0.45f
                btnLassoRedraw.alpha = 0.45f
            }
        }
        btnLassoUndo.alpha = if (viewModel.sentUndoStack.isNotEmpty()) 1f else 0.4f
        btnLassoReset.alpha = if (totalSent > 0) 1f else 0.4f
    }

    fun toggleRulerMode(enabled: Boolean) {
        if (enabled && isFullScreenMapMode()) setFullScreenMapMode(false, false)
        if (enabled && viewModel.isLassoMode) toggleLassoMode(false)
        viewModel.applyRulerMode(enabled)
        if (enabled) {
            undoController.dismiss(animate = false)
            hideInScreenSheet()
            UiAnimations.fadeShow(bottomDock, 160L)
            UiAnimations.hideSlideBottom(bottomNavDock, 115L)
            UiAnimations.showSlideBottom(rulerCard)
            updateRulerUi()
        } else {
            UiAnimations.hideSlideBottom(rulerCard)
            if (viewModel.activeNavRoute == null && !viewModel.isLassoMode) UiAnimations.showSlideBottom(bottomNavDock)
        }
        applyMapChromeTheme()
        renderer.renderRulerOverlay()
    }

    fun addRulerPoint(latLng: LatLng) {
        viewModel.addRulerPoint(latLng.latitude, latLng.longitude)
        UiAnimations.hapticTick(rulerCard)
        renderer.fireStatusPulse(latLng.latitude, latLng.longitude, "#F0651F")
        updateRulerUi()
        renderer.renderRulerOverlay()
    }

    fun undoRulerPoint() {
        if (viewModel.undoRulerPoint()) {
            updateRulerUi()
            renderer.renderRulerOverlay()
        }
    }

    fun resetRulerPoints() {
        viewModel.clearRulerPoints()
        updateRulerUi()
        renderer.renderRulerOverlay()
    }

    fun updateRulerUi() {
        val pts = viewModel.rulerPoints
        var totalMeters = 0.0
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            totalMeters += RoutingEngine.haversineMeters(a.lat, a.lng, b.lat, b.lng)
        }
        val unit = getSpeedUnit()
        tvRulerDistance.text = when {
            pts.size < 2 -> if (unit == SpeedUnit.IMPERIAL) "0 ft" else "0 m"
            unit == SpeedUnit.IMPERIAL -> {
                val ft = totalMeters * 3.28084
                val mi = totalMeters / 1609.344
                when {
                    ft < 1000.0 -> "${ft.roundToInt()} ft"
                    mi < 10.0 -> "${"%.2f".format(Locale.US, mi)} mi"
                    else -> "${"%.1f".format(Locale.US, mi)} mi"
                }
            }
            totalMeters < 1000.0 -> "${totalMeters.roundToInt()} m"
            totalMeters < 10000.0 -> "${"%.2f".format(Locale.US, totalMeters / 1000.0)} km"
            else -> "${"%.1f".format(Locale.US, totalMeters / 1000.0)} km"
        }
        tvRulerSubtitle.text = when (pts.size) {
            0 -> AppStrings.rulerTapPoint1(activity)
            1 -> AppStrings.rulerTapNext(activity)
            else -> AppStrings.rulerTotalPoints(activity, pts.size)
        }
        btnRulerUndo.alpha = if (pts.isNotEmpty()) 1.0f else 0.35f
        btnRulerReset.visibility = if (pts.isNotEmpty()) View.VISIBLE else View.GONE
    }

    fun handleMapTap(m: MapLibreMap, latLng: LatLng) {
        val screenPt = m.projection.toScreenLocation(latLng)
        val hitBox = RectF(
            screenPt.x - dp(24).toFloat(),
            screenPt.y - dp(36).toFloat(),
            screenPt.x + dp(24).toFloat(),
            screenPt.y + dp(14).toFloat()
        )

        val clustered = DevicePrefs.isClusterDensePins(activity)
        if (clustered) {
            val clusterHits = m.queryRenderedFeatures(hitBox, MapLayerRenderer.PINS_CLUSTER_CIRCLE_LAYER)
            val clusterPt = clusterHits.firstOrNull()?.geometry() as? Point
            if (clusterPt != null) {
                val targetZoom = (m.cameraPosition.zoom + 2.2).coerceAtMost(16.0)
                m.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(clusterPt.latitude(), clusterPt.longitude()),
                        targetZoom
                    ),
                    350
                )
                return
            }
        }

        val pinHits = if (clustered) {
            m.queryRenderedFeatures(
                hitBox,
                MapLayerRenderer.SELECTED_PIN_LAYER,
                MapLayerRenderer.PINS_CLUSTER_UNCLUSTERED_LAYER,
                MapLayerRenderer.PINS_CLUSTER_LABELS_LAYER
            )
        } else {
            m.queryRenderedFeatures(
                hitBox,
                MapLayerRenderer.SELECTED_PIN_LAYER,
                MapLayerRenderer.PINS_LAYER,
                MapLayerRenderer.PINS_LABELS_LAYER
            )
        }
        val pin = pinHits
            .mapNotNull { hit -> hit.getStringProperty("id")?.let { id -> viewModel.folderSnapshot.pinById[id] } }
            .distinctBy { it.id }
            .minByOrNull { candidate ->
                val pt = m.projection.toScreenLocation(LatLng(candidate.lat, candidate.lng))
                val dx = pt.x - screenPt.x
                val dy = (pt.y - dp(12)) - screenPt.y
                dx * dx + dy * dy
            }
        if (pin != null) {
            selectPinAndOpenCard(pin, zoomIfFar = false)
            return
        }

        if (isFullScreenMapMode()) {
            setFullScreenMapMode(false, true)
            return
        }

        val smallBox = RectF(
            screenPt.x - dp(14).toFloat(),
            screenPt.y - dp(14).toFloat(),
            screenPt.x + dp(14).toFloat(),
            screenPt.y + dp(14).toFloat()
        )
        val vectorFeatures = m.queryRenderedFeatures(smallBox)
        var detectedTitle = ""
        var detectedSub = ""
        val mapLang = DevicePrefs.getMapLabelLanguage(activity)
        for (f in vectorFeatures) {
            val props = f.properties() ?: continue
            if (props.has("icon") || props.has("isMajor") || props.has("bearing") || props.has("num")) continue
            val name = MapCartography.resolveFeatureDisplayName(props, mapLang)
            if (name != null) {
                detectedTitle = name
                val cls = if (props.has("class") && !props.get("class").isJsonNull) props.get("class").asString else ""
                if (cls.isNotBlank()) detectedSub = cls.replace("_", " ").replaceFirstChar { it.uppercase() }
                break
            }
        }

        selectCoordinateOnMap(
            latLng = latLng,
            explicitTitle = detectedTitle.ifBlank { null },
            fallbackSub = detectedSub,
            zoomTo = null
        )
    }

    fun selectPinAndOpenCard(pin: PinItem, zoomIfFar: Boolean = false) {
        val stacked = findExactStackedPins(pin)
        viewModel.selectPin(pin.id, stacked.map { it.id })
        viewModel.selectedMarkerGeoPoint = GeoPoint(pin.lat, pin.lng)
        renderer.renderSelectedMarker()
        val currentZoom = renderer.map?.cameraPosition?.zoom ?: 15.0
        val targetZoom = if (zoomIfFar) currentZoom.coerceAtLeast(15.8) else currentZoom
        centerUpperHalf(
            LatLng(pin.lat, pin.lng),
            targetZoom,
            bottomPadDp = if (stacked.size > 1) 340 else 285
        )
        showPinCard(pin, false)
    }

    fun findExactStackedPins(pin: PinItem): List<PinItem> {
        val hiddenLayerIds = DevicePrefs.getHiddenLayerIds(activity)
        val sentIds = if (viewModel.hideSentPins) DevicePrefs.getLocalSentPinIds(activity) else emptySet()
        return findExactStackedPinsFromSnapshot(
            snapshot = viewModel.folderSnapshot,
            pin = pin,
            hiddenLayerIds = hiddenLayerIds,
            visibleStatuses = viewModel.visibleStatuses,
            sentIds = sentIds
        )
    }

    fun clearSelectedMarker() {
        val hadSelection = viewModel.selectedPinId != null || viewModel.selectedMarkerGeoPoint != null
        viewModel.clearSelection()
        viewModel.pendingSharedPinId = null
        if (hadSelection) renderer.renderSelectedMarker()
    }

    fun centerUpperHalf(target: LatLng, zoom: Double, bottomPadDp: Int = 270) {
        val m = renderer.map ?: return
        val cam = CameraPosition.Builder()
            .target(target)
            .zoom(zoom)
            .padding(0.0, 0.0, 0.0, dp(bottomPadDp).toDouble())
            .build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 420)
    }

    fun resetMapPadding() {
        val m = renderer.map ?: return
        val cam = CameraPosition.Builder()
            .target(m.cameraPosition.target)
            .zoom(m.cameraPosition.zoom)
            .padding(0.0, 0.0, 0.0, 0.0)
            .build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 240)
    }

    fun handleDeepLinkIntent(intent: Intent?) {
        val data = intent?.data ?: return
        intent.data = null

        val scheme = data.scheme?.lowercase() ?: ""
        val host = data.host?.lowercase() ?: ""
        val path = data.path?.lowercase() ?: ""

        if (scheme == "content" || scheme == "file") {
            onImportUri(data)
            return
        }

        if ((scheme == "terrasync" && host == "pin") ||
            (scheme == "https" && host == "thamirakkurd.github.io" && path.startsWith("/terrasync/pin"))
        ) {
            val lat = data.getQueryParameter("lat")?.toDoubleOrNull()
            val lng = data.getQueryParameter("lng")?.toDoubleOrNull()
            val rawName = data.getQueryParameter("name")?.trim().orEmpty()
            val pinId = data.getQueryParameter("id")?.trim().orEmpty()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                viewModel.hasAutoZoomedInitial = true
                val target = SharedPinTarget(
                    lat = lat,
                    lng = lng,
                    name = rawName.ifBlank { "Selected Location" },
                    pinId = pinId,
                    sourceHint = "Shared via TerraSync",
                    hasExplicitLabel = rawName.isNotBlank()
                )
                if (renderer.map != null) openSharedPinTarget(target) else pendingSharedPin = target
            }
            return
        }

        if (GeoIntentParser.isSupportedLocationUri(data)) {
            viewModel.hasAutoZoomedInitial = true
            val parsed = GeoIntentParser.parse(data)
            if (parsed != null) {
                handleParsedLocationIntent(parsed)
            } else {
                Toast.makeText(activity, "Could not read coordinates from link", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val code = data.getQueryParameter("code")
        if (!code.isNullOrBlank()) {
            onJoinShareCode(code)
        }
    }

    private fun handleParsedLocationIntent(parsed: ParsedLocationIntent) {
        if (parsed.needsRedirectResolution && parsed.originalUrl.isNotBlank()) {
            Toast.makeText(activity, "Opening shared location...", Toast.LENGTH_SHORT).show()
            AppExecutors.batchIoExecutor.execute {
                val resolved = GeoIntentParser.resolveRedirectAndParse(parsed.originalUrl)
                AppExecutors.postMain {
                    if (!isActivityAlive()) return@postMain
                    if (resolved != null && !resolved.needsRedirectResolution) {
                        handleParsedLocationIntent(resolved)
                    } else {
                        Toast.makeText(activity, "Unable to resolve shared map link", Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }

        if (parsed.hasValidCoordinates) {
            val lat = parsed.lat!!
            val lng = parsed.lng!!
            val hasLabel = parsed.label.isNotBlank()
            val target = SharedPinTarget(
                lat = lat,
                lng = lng,
                name = if (hasLabel) parsed.label else "Selected Location",
                pinId = "",
                zoom = parsed.zoom,
                sourceHint = parsed.sourceHint,
                hasExplicitLabel = hasLabel
            )
            if (renderer.map != null) openSharedPinTarget(target) else pendingSharedPin = target
            return
        }

        if (parsed.searchQuery.isNotBlank()) {
            Toast.makeText(activity, "Searching \"${parsed.searchQuery}\"...", Toast.LENGTH_SHORT).show()
            val center = renderer.map?.cameraPosition?.target
            RoutingEngine.searchPlaces(parsed.searchQuery, center?.latitude, center?.longitude) { places, err ->
                AppExecutors.postMain {
                    if (!isActivityAlive()) return@postMain
                    val top = places.firstOrNull()
                    if (top != null) {
                        val resolvedName = top.title.ifBlank { parsed.searchQuery }
                        val target = SharedPinTarget(
                            lat = top.lat,
                            lng = top.lng,
                            name = resolvedName,
                            pinId = "",
                            zoom = parsed.zoom ?: 16.0,
                            sourceHint = top.subtitle.ifBlank { parsed.sourceHint },
                            hasExplicitLabel = resolvedName.isNotBlank()
                        )
                        if (renderer.map != null) openSharedPinTarget(target) else pendingSharedPin = target
                    } else {
                        Toast.makeText(activity, err ?: "No location found for \"${parsed.searchQuery}\"", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun ensurePinVisibleOnMap(pin: PinItem) {
        var changed = false
        if (!DevicePrefs.isLayerVisible(activity, pin.layerId)) {
            DevicePrefs.setLayerVisible(activity, pin.layerId, true)
            changed = true
        }
        val normStatus = PinStatus.fromRaw(pin.status).key
        if (normStatus !in viewModel.visibleStatuses) {
            viewModel.visibleStatuses.add(normStatus)
            DevicePrefs.setVisibleStatuses(activity, viewModel.visibleStatuses)
            updateFilterIconTint()
            changed = true
            Toast.makeText(activity, "Enabled ${statusLabel(normStatus)} filter to show pin", Toast.LENGTH_SHORT).show()
        }
        if (viewModel.hideSentPins && DevicePrefs.isPinLocalSent(activity, pin.id)) {
            viewModel.hideSentPins = false
            DevicePrefs.setHideSentPins(activity, false)
            updateSentToggleUi()
            changed = true
            Toast.makeText(activity, "Showing all pins to display sent pin", Toast.LENGTH_SHORT).show()
        }
        if (changed) renderer.renderMapData()
    }

    private fun openSharedPinTarget(target: SharedPinTarget) {
        viewModel.hasAutoZoomedInitial = true
        if (isFullScreenMapMode()) setFullScreenMapMode(false, false)
        if (viewModel.isFollowingGps) {
            viewModel.isFollowingGps = false
            gpsController.updateLocationIconTint()
        }
        if (pageOverlayContainer.visibility == View.VISIBLE) {
            closeFullPage()
        }
        if (viewModel.isRulerMode) toggleRulerMode(false)
        if (viewModel.isLassoMode) toggleLassoMode(false)

        val matched = if (target.pinId.isNotBlank()) viewModel.folderSnapshot.pinById[target.pinId] else null
        if (matched != null) {
            viewModel.pendingSharedPinId = null
            ensurePinVisibleOnMap(matched)
            selectPinAndOpenCard(matched, zoomIfFar = true)
            return
        }
        val destLatLng = LatLng(target.lat, target.lng)
        viewModel.pendingSharedPinId = target.pinId.ifBlank { null }
        val desiredZoom = (target.zoom ?: (renderer.map?.cameraPosition?.zoom ?: 15.0).coerceAtLeast(16.0)).coerceIn(4.0, 20.0)
        selectCoordinateOnMap(
            latLng = destLatLng,
            explicitTitle = if (target.hasExplicitLabel) target.name else null,
            fallbackSub = target.sourceHint,
            zoomTo = desiredZoom
        )
    }

    fun selectCoordinateOnMap(
        latLng: LatLng,
        explicitTitle: String?,
        fallbackSub: String = "",
        zoomTo: Double? = null
    ) {
        val cleanExplicit = explicitTitle?.trim().orEmpty()
        val initialTitle = cleanExplicit.ifBlank { "Selected Location" }
        viewModel.selectTappedLocation(latLng.latitude, latLng.longitude, initialTitle, fallbackSub)
        renderer.renderSelectedMarker()

        if (zoomTo != null) {
            mapView.post {
                if (isActivityAlive()) {
                    centerUpperHalf(latLng, zoomTo)
                }
            }
        }

        showTappedPlaceCard(initialTitle, fallbackSub, latLng.latitude, latLng.longitude)

        pendingReverseGeocodeRunnable?.let { mapView.removeCallbacks(it) }
        RoutingEngine.cancelPendingReverseGeocode()
        val geoSeq = ++reverseGeocodeSeq
        val r = Runnable {
            if (!isActivityAlive() || geoSeq != reverseGeocodeSeq) return@Runnable
            RoutingEngine.reverseGeocode(latLng.latitude, latLng.longitude) { place ->
                if (place != null) {
                    AppExecutors.postMain {
                        if (!isActivityAlive() || geoSeq != reverseGeocodeSeq) return@postMain
                        val curTapped = viewModel.tappedLocation
                        if (curTapped == null || curTapped.lat != latLng.latitude || curTapped.lng != latLng.longitude || viewModel.selectedPinId != null) {
                            return@postMain
                        }
                        if (isSheetHidden()) return@postMain
                        val bestTitle = if (cleanExplicit.isNotBlank()) cleanExplicit else place.title
                        val bestSub = place.subtitle.ifBlank { fallbackSub }
                        viewModel.tappedLocation = TappedLocationState(bestTitle, bestSub, latLng.latitude, latLng.longitude)
                        val unit = getSpeedUnit()
                        val distShort = gpsController.currentLocation?.let {
                            AppStrings.distanceAway(
                                activity,
                                formatDistanceShort(RoutingEngine.haversineMeters(it.latitude, it.longitude, latLng.latitude, latLng.longitude), unit)
                            )
                        } ?: ""
                        onReverseGeocodeResult(latLng, bestTitle, bestSub, distShort)
                    }
                }
            }
        }
        pendingReverseGeocodeRunnable = r
        mapView.postDelayed(r, 180L)
    }

    fun collectLayerLatLngs(layerPins: List<PinItem>, layerTracks: List<TrackItem>): List<LatLng> {
        val snap = viewModel.folderSnapshot
        if (RustCoreBridge.isAvailable()) {
            if (layerPins === snap.pins && layerTracks === snap.tracks) {
                RustCoreBridge.computeResidentBounds(snap.dataVersion, snap.pins.size, snap.tracks.size, "")?.let { return it }
            } else if (layerPins.isNotEmpty() || layerTracks.isNotEmpty()) {
                val singleLid = layerPins.firstOrNull()?.layerId ?: layerTracks.firstOrNull()?.layerId
                if (singleLid != null &&
                    layerPins.size == (snap.pinsByLayerId[singleLid]?.size ?: 0) &&
                    layerTracks.size == (snap.tracksByLayerId[singleLid]?.size ?: 0) &&
                    layerPins.all { it.layerId == singleLid } &&
                    layerTracks.all { it.layerId == singleLid }
                ) {
                    RustCoreBridge.computeResidentBounds(snap.dataVersion, snap.pins.size, snap.tracks.size, singleLid)?.let { return it }
                }
            }
        }
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLng = Double.POSITIVE_INFINITY
        var maxLng = Double.NEGATIVE_INFINITY
        var count = 0
        for (p in layerPins) {
            if (p.lat.isFinite() && p.lng.isFinite() && p.lat in -90.0..90.0 && p.lng in -180.0..180.0) {
                if (p.lat < minLat) minLat = p.lat
                if (p.lat > maxLat) maxLat = p.lat
                if (p.lng < minLng) minLng = p.lng
                if (p.lng > maxLng) maxLng = p.lng
                count++
            }
        }
        for (t in layerTracks) {
            val raw = t.coords
            var start = 0
            val len = raw.length
            while (start < len) {
                val semi = raw.indexOf(';', start).let { if (it < 0) len else it }
                val comma = raw.indexOf(',', start)
                if (comma in (start + 1) until semi) {
                    val lng = raw.substring(start, comma).trim().toDoubleOrNull()
                    val lat = raw.substring(comma + 1, semi).trim().toDoubleOrNull()
                    if (lat != null && lng != null && lat.isFinite() && lng.isFinite() &&
                        lat in -90.0..90.0 && lng in -180.0..180.0
                    ) {
                        if (lat < minLat) minLat = lat
                        if (lat > maxLat) maxLat = lat
                        if (lng < minLng) minLng = lng
                        if (lng > maxLng) maxLng = lng
                        count++
                    }
                }
                start = semi + 1
            }
        }
        if (count == 0) return emptyList()
        return if (count == 1 || (maxLat - minLat < 1e-7 && maxLng - minLng < 1e-7)) {
            listOf(LatLng(minLat, minLng))
        } else {
            listOf(LatLng(minLat, minLng), LatLng(maxLat, maxLng))
        }
    }

    fun zoomToKmlBounds(pts: List<LatLng>, layerIdToReveal: String? = null) {
        if (layerIdToReveal != null && !DevicePrefs.isLayerVisible(activity, layerIdToReveal)) {
            DevicePrefs.setLayerVisible(activity, layerIdToReveal, true)
            renderer.renderMapData()
        }
        val valid = pts.filter {
            it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }
        if (valid.isEmpty()) {
            if (layerIdToReveal != null) {
                Toast.makeText(activity, "No coordinates found in this KML folder", Toast.LENGTH_SHORT).show()
            }
            return
        }
        viewModel.hasAutoZoomedInitial = true
        if (viewModel.isFollowingGps) {
            viewModel.isFollowingGps = false
            gpsController.updateLocationIconTint()
        }
        pendingZoomPoints = valid
        flushPendingZoomIfReady()
    }

    fun flushPendingZoomIfReady() {
        val pts = pendingZoomPoints ?: return
        val m = renderer.map ?: return
        val style = m.style
        if (style == null || !style.isFullyLoaded) return
        if (mapView.width <= 0 || mapView.height <= 0) {
            mapView.post { flushPendingZoomIfReady() }
            return
        }
        pendingZoomPoints = null
        mapView.post {
            if (!isActivityAlive()) return@post
            val curMap = renderer.map ?: return@post
            var minLat = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var minLng = Double.POSITIVE_INFINITY
            var maxLng = Double.NEGATIVE_INFINITY
            pts.forEach { p ->
                if (p.latitude < minLat) minLat = p.latitude
                if (p.latitude > maxLat) maxLat = p.latitude
                if (p.longitude < minLng) minLng = p.longitude
                if (p.longitude > maxLng) maxLng = p.longitude
            }
            val center = LatLng((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0)
            val isSingleOrTight = pts.size == 1 || (maxLat - minLat < 1e-5 && maxLng - minLng < 1e-5)
            if (isSingleOrTight) {
                val cam = CameraPosition.Builder()
                    .target(center)
                    .zoom(16.5)
                    .padding(0.0, 0.0, 0.0, 0.0)
                    .build()
                curMap.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 550)
                return@post
            }
            val (topInset, bottomInset) = getInsetsTopBottom()
            val maxPadH = (mapView.width / 3).coerceAtLeast(16)
            val maxPadV = (mapView.height / 3).coerceAtLeast(16)
            val padLeft = dp(48).coerceAtMost(maxPadH)
            val padRight = dp(60).coerceAtMost(maxPadH)
            val padTop = (topInset + dp(84)).coerceAtMost(maxPadV)
            val padBottom = (bottomInset + dp(112)).coerceAtMost(maxPadV)
            runCatching {
                val bounds = LatLngBounds.Builder()
                    .include(LatLng(minLat, minLng))
                    .include(LatLng(maxLat, maxLng))
                    .build()
                curMap.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(bounds, padLeft, padTop, padRight, padBottom),
                    650
                )
            }.onFailure {
                curMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 15.5), 550)
            }
        }
    }

    fun destroy() {
        reverseGeocodeSeq++
        pendingReverseGeocodeRunnable?.let { mapView.removeCallbacks(it) }
        pendingReverseGeocodeRunnable = null
        pendingSharedPin = null
        pendingZoomPoints = null
    }
}
