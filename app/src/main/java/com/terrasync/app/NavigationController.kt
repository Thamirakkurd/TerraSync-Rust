package com.terrasync.app

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.util.Locale

/**
 * Owns Route-From-Origin selection, multi-mode route preview cards, turn-by-turn
 * navigation progress tracking, auto-reroute, and TextToSpeech guidance.
 */
class NavigationController(
    private val activity: AppCompatActivity,
    private val viewModel: MapViewModel,
    private val renderer: MapLayerRenderer,
    private val gpsController: GpsLocationController,
    private val topCluster: LinearLayout,
    private val routeFromBanner: LinearLayout,
    private val tvRouteFromText: TextView,
    private val navBanner: LinearLayout,
    private val tvNavArrow: TextView,
    private val tvNavStepDist: TextView,
    private val tvNavInstruction: TextView,
    private val navBottomBar: LinearLayout,
    private val tvNavEta: TextView,
    private val tvNavMeta: TextView,
    private val bottomDock: LinearLayout,
    private val bottomNavDock: SwipeDockFrameLayout,
    private val btnRuler: ImageButton,
    private val btnLasso: ImageButton,
    private val sheetContent: LinearLayout,
    private val sheetBehavior: BottomSheetBehavior<LinearLayout>,
    private val undoController: StatusUndoController,
    private val dp: (Int) -> Int,
    private val currentPalette: () -> ThemePalette,
    private val isActivityAlive: () -> Boolean,
    private val isActivityResumed: () -> Boolean,
    private val getCachedSpeedUnit: () -> SpeedUnit,
    private val launchLocationPermission: () -> Unit,
    private val clearSheetStickyHeader: () -> Unit,
    private val clearQuickSelectPinId: () -> Unit,
    private val showInScreenSheet: (Int, Boolean, Boolean) -> Unit,
    private val hideInScreenSheet: () -> Unit,
    private val safeStartActivity: (Intent, String) -> Unit,
    private val applyKeepAwakeFlag: () -> Unit,
    private val applyExtraMapToolsVisibility: () -> Unit,
    private val applyZoomButtonsVisibility: () -> Unit,
    private val dismissToolsForNav: () -> Unit
) {

    data class PendingRouteReq(
        val destLat: Double,
        val destLng: Double,
        val destTitle: String,
        val mode: TravelMode,
        val requestedAtMs: Long
    )

    var pendingRouteReq: PendingRouteReq? = null
    var activeNavTrimmedGeom: List<Pair<Double, Double>>? = null
        private set

    private var routeRequestSeq: Int = 0
    private var navLastSegIdx: Int = 0
    private var isRerouting: Boolean = false
    private var navCurrentStepIdx: Int = 0
    private var lastSpokenStepIdx: Int = -1
    private var lastRerouteTime: Long = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun initTts() {
        tts = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }
    }

    fun cancelPreviewOnSheetClosed() {
        pendingRouteReq = null
        if (viewModel.previewRoute != null && viewModel.activeNavRoute == null) {
            routeRequestSeq++
            viewModel.previewRoute = null
            renderer.renderRouteOverlay()
        }
    }

    fun handlePendingRouteOnFix(loc: Location) {
        val queuedRoute = pendingRouteReq ?: return
        if (gpsController.locationAgeMs(loc) <= 120_000L) {
            pendingRouteReq = null
            if (System.currentTimeMillis() - queuedRoute.requestedAtMs <= 20_000L && viewModel.activeNavRoute == null) {
                openRoutePreview(queuedRoute.destLat, queuedRoute.destLng, queuedRoute.destTitle, queuedRoute.mode)
            }
        }
    }

    fun handleLocationPermissionGranted() {
        val queued = pendingRouteReq
        val loc = gpsController.currentLocation
        if (queued != null && loc != null && gpsController.locationAgeMs(loc) <= 120_000L &&
            System.currentTimeMillis() - queued.requestedAtMs <= 30_000L && viewModel.activeNavRoute == null
        ) {
            pendingRouteReq = null
            openRoutePreview(queued.destLat, queued.destLng, queued.destTitle, queued.mode)
        } else if (queued == null) {
            viewModel.isFollowingGps = true
            gpsController.updateLocationIconTint()
            loc?.let {
                renderer.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 16.5))
            }
        }
    }

    fun onLocationPermissionDenied() {
        pendingRouteReq = null
    }

    fun setRouteOrigin(lat: Double, lng: Double, label: String) {
        val resolved = label.ifBlank { "${"%.5f".format(Locale.US, lat)}, ${"%.5f".format(Locale.US, lng)}" }
        viewModel.setRouteOrigin(lat, lng, resolved)
        tvRouteFromText.text = "From: $resolved"
        UiAnimations.showSlideTop(routeFromBanner)
        hideInScreenSheet()
        Toast.makeText(activity, "Start set: $resolved — tap any pin or place & choose Route To", Toast.LENGTH_LONG).show()
    }

    fun clearRouteOrigin() {
        viewModel.clearRouteOrigin()
        UiAnimations.hideSlideTop(routeFromBanner)
    }

    fun openRoutePreview(
        destLat: Double,
        destLng: Double,
        destTitle: String,
        modeOverride: TravelMode = viewModel.travelMode
    ) {
        val customStart = viewModel.routeOrigin
        val startLat: Double
        val startLng: Double
        val startLabel: String

        var startBearing: Float? = null
        if (customStart != null) {
            startLat = customStart.lat
            startLng = customStart.lng
            startLabel = customStart.label
        } else {
            if (!gpsController.hasLocationPermission()) {
                pendingRouteReq = PendingRouteReq(destLat, destLng, destTitle, modeOverride, System.currentTimeMillis())
                launchLocationPermission()
                return
            }
            if (!gpsController.ensureLocationServiceOn()) return
            val staleStream = System.currentTimeMillis() - gpsController.lastLiveLocationFixMs > 10_000L
            gpsController.startLiveGpsUpdates(forceRestart = staleStream)
            val loc = gpsController.currentLocation
            if (loc == null || gpsController.locationAgeMs(loc) > 120_000L) {
                pendingRouteReq = PendingRouteReq(destLat, destLng, destTitle, modeOverride, System.currentTimeMillis())
                gpsController.syncGpsPollingProfile()
                Toast.makeText(activity, "Acquiring fresh GPS fix — route will build automatically...", Toast.LENGTH_SHORT).show()
                return
            }
            pendingRouteReq = null
            startLat = loc.latitude
            startLng = loc.longitude
            startLabel = "My Location"
            if (loc.hasBearing() && loc.speed > 1.5f) startBearing = loc.bearing
        }

        viewModel.travelMode = modeOverride
        DevicePrefs.setTravelMode(activity, modeOverride)
        val reqId = ++routeRequestSeq
        Toast.makeText(activity, "Building ${modeOverride.label.lowercase()} route...", Toast.LENGTH_SHORT).show()

        RoutingEngine.fetchRoute(
            startLat, startLng, destLat, destLng, modeOverride, startBearing,
            isCancelled = { reqId != routeRequestSeq }
        ) { route, err ->
            AppExecutors.postMain {
                if (!isActivityAlive() || reqId != routeRequestSeq) return@postMain
                if (route == null) {
                    Toast.makeText(activity, err ?: "Route failed", Toast.LENGTH_LONG).show()
                    return@postMain
                }
                viewModel.previewRoute = route
                viewModel.activeNavDest = GeoPoint(destLat, destLng)
                viewModel.activeNavDestTitle = destTitle
                renderer.renderRouteOverlay()

                val pts = route.geometry.map { LatLng(it.second, it.first) }
                if (pts.size >= 2) {
                    runCatching {
                        val b = LatLngBounds.Builder()
                        pts.forEach { b.include(it) }
                        renderer.map?.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(b.build(), dp(72), dp(96), dp(72), dp(310)),
                            600
                        )
                    }
                }
                showRoutePreviewCard(route, startLabel, destLat, destLng, destTitle)
            }
        }
    }

    private fun travelModeIconRes(mode: TravelMode): Int = when (mode) {
        TravelMode.CAR -> R.drawable.ic_mode_car
        TravelMode.WALKING -> R.drawable.ic_mode_walk
        TravelMode.BICYCLE -> R.drawable.ic_mode_bike
    }

    private fun showRoutePreviewCard(route: NavRoute, startLabel: String, destLat: Double, destLng: Double, destTitle: String) {
        clearQuickSelectPinId()
        val p = currentPalette()
        val txtPrimary = p.textPrimary
        val txtSub = p.textSecondary

        clearSheetStickyHeader()
        sheetContent.removeAllViews()

        val etaText = formatEtaDuration(route.totalDurationSeconds)
        val distStr = formatDistanceShort(route.totalDistanceMeters, DevicePrefs.getSpeedUnit(activity))

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val metaRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val etaBadge = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                setColor(p.accentPrimary)
                cornerRadius = dp(18).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        }
        etaBadge.addView(ImageView(activity).apply {
            setImageResource(travelModeIconRes(route.mode))
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(6) }
        })
        etaBadge.addView(TextView(activity).apply {
            text = etaText
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            DesignSystem.applyTabularNumerals(this)
        })
        metaRow.addView(etaBadge)
        metaRow.addView(TextView(activity).apply {
            text = distStr
            textSize = 15.5f
            setTextColor(txtPrimary)
            setTypeface(null, android.graphics.Typeface.BOLD)
            DesignSystem.applyTabularNumerals(this)
        })
        val isOfflineRoute = route.engineLabel.contains("Offline", true)
        val engTextColor = if (isOfflineRoute) p.amber else p.accentPrimary
        val engBgColor = if (isOfflineRoute) p.amberSoft else p.accentPrimarySoft
        metaRow.addView(TextView(activity).apply {
            text = if (isOfflineRoute) "● Offline Roads" else "● Online"
            textSize = 11f
            setTextColor(engTextColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = GradientDrawable().apply {
                setColor(engBgColor)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        })
        titleCol.addView(metaRow)
        titleCol.addView(TextView(activity).apply {
            text = "$startLabel → $destTitle"
            textSize = 13f
            setTextColor(txtSub)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        })
        header.addView(titleCol)

        header.addView(DesignSystem.createSheetCloseButton(activity, p) {
            routeRequestSeq++
            viewModel.previewRoute = null
            renderer.renderRouteOverlay()
            hideInScreenSheet()
        })
        sheetContent.addView(header)

        val modeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            }
        }
        TravelMode.entries.forEachIndexed { idx, mode ->
            val active = mode == route.mode
            val fgColor = if (active) p.onActionDark else p.textPrimary
            val bgColor = if (active) p.actionDark else p.surfaceTonal
            modeRow.addView(MaterialButton(activity).apply {
                text = mode.label
                textSize = 13f
                setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                isAllCaps = false
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                elevation = 0f
                stateListAnimator = null
                setPadding(dp(8), 0, dp(10), 0)
                setIconResource(travelModeIconRes(mode))
                iconSize = dp(18)
                iconPadding = dp(6)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconTint = ColorStateList.valueOf(fgColor)
                setTextColor(fgColor)
                backgroundTintList = ColorStateList.valueOf(bgColor)
                strokeWidth = if (active) 0 else dp(1)
                strokeColor = ColorStateList.valueOf(if (active) Color.TRANSPARENT else p.borderSubtle)
                cornerRadius = dp(22)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (idx < TravelMode.entries.lastIndex) marginEnd = dp(8)
                }
                UiAnimations.attachSnappyPress(this)
                setOnClickListener {
                    if (mode != route.mode) openRoutePreview(destLat, destLng, destTitle, mode)
                }
            })
        }
        sheetContent.addView(modeRow)

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4)
            }
        }
        btnRow.addView(MaterialButton(activity).apply {
            text = if (viewModel.routeOrigin != null) "Navigate from GPS" else "Start Navigation"
            textSize = 14f
            isAllCaps = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), 0, dp(12), 0)
            cornerRadius = dp(24)
            DesignSystem.applyPrimaryCtaButton(this, p)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1.4f).apply { marginEnd = dp(8) }
            UiAnimations.attachSnappyPress(this)
            setOnClickListener {
                if (viewModel.routeOrigin != null) {
                    clearRouteOrigin()
                    openRoutePreview(destLat, destLng, destTitle, route.mode)
                } else {
                    confirmStartNavigation(route, destLat, destLng, destTitle)
                }
            }
        })
        btnRow.addView(MaterialButton(activity).apply {
            text = "External"
            textSize = 14f
            isAllCaps = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), 0, dp(12), 0)
            cornerRadius = dp(24)
            DesignSystem.applySecondaryTonalButton(this, p)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 0.8f)
            UiAnimations.attachSnappyPress(this)
            setOnClickListener {
                val label = Uri.encode(destTitle)
                val geoUri = Uri.parse("geo:$destLat,$destLng?q=$destLat,$destLng($label)")
                safeStartActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, geoUri), "Open in"), "No external map app installed")
            }
        })
        sheetContent.addView(btnRow)

        UiAnimations.animateSheetContentReveal(sheetContent)
        showInScreenSheet(310, true, true)
    }

    fun moveCameraForNavigation(loc: Location, durationMs: Int = 500) {
        if (!isActivityResumed()) return
        val m = renderer.map ?: return
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        val targetZoom = when {
            speedKmh >= 75f -> 15.6
            speedKmh >= 45f -> 16.3
            else -> 17.0
        }
        val camBearing = when {
            loc.hasBearing() && loc.speed > 1.0f -> loc.bearing.toDouble()
            gpsController.displayedBearing != 0f -> gpsController.displayedBearing.toDouble()
            else -> m.cameraPosition.bearing
        }
        val cam = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .zoom(targetZoom)
            .tilt(45.0)
            .bearing(camBearing)
            .padding(0.0, dp(165).toDouble(), 0.0, dp(75).toDouble())
            .build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(cam), durationMs)
    }

    private fun formatNavInstructionBidi(raw: String): String {
        val markers = arrayOf(" onto ", " towards ", " toward ", " on ", " at ")
        for (marker in markers) {
            val idx = raw.indexOf(marker, ignoreCase = true)
            if (idx >= 0) {
                val splitPos = idx + marker.length
                val head = raw.substring(0, splitPos)
                val tail = raw.substring(splitPos).trim()
                if (tail.isNotEmpty()) return "$head\u2068$tail\u2069"
            }
        }
        return raw
    }

    fun confirmStartNavigation(route: NavRoute, destLat: Double, destLng: Double, destTitle: String) {
        if (!gpsController.ensureLocationServiceOn()) return
        gpsController.startLiveGpsUpdates(forceRestart = true)
        val loc = gpsController.currentLocation
        if (loc == null) {
            Toast.makeText(activity, "Waiting for GPS fix before starting navigation...", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.startNavigation(route, destLat, destLng, destTitle)
        activeNavTrimmedGeom = route.geometry
        navLastSegIdx = 0
        isRerouting = false
        navCurrentStepIdx = if (route.steps.size > 1) 1 else 0
        lastSpokenStepIdx = -1

        hideInScreenSheet()
        applyNavigationChrome(true)
        applyKeepAwakeFlag()
        gpsController.updateLocationIconTint()
        gpsController.syncGpsPollingProfile(forceRestart = true)
        gpsController.updateSpeedBadge(loc)
        renderer.renderRouteOverlay()
        NavigationForegroundService.onStopRequested = {
            if (isActivityAlive() && viewModel.activeNavRoute != null) {
                stopNavigation()
            }
        }
        NavigationForegroundService.start(
            activity,
            "Navigating to ${destTitle.ifBlank { "destination" }}",
            "Turn-by-turn guidance active"
        )
        updateNavigationProgress(loc, route, LatLng(destLat, destLng))
        moveCameraForNavigation(loc, 650)
    }

    fun updateNavigationProgress(loc: Location, route: NavRoute, dest: LatLng) {
        val directToDest = RoutingEngine.haversineMeters(loc.latitude, loc.longitude, dest.latitude, dest.longitude)
        if (directToDest < 28.0) {
            val title = viewModel.activeNavDestTitle
            speakPrompt("You have arrived at $title")
            Toast.makeText(activity, "Arrived at $title", Toast.LENGTH_LONG).show()
            stopNavigation()
            return
        }

        val prog = RoutingEngine.trackRouteProgress(loc.latitude, loc.longitude, route, navLastSegIdx)
        navLastSegIdx = prog.segmentIndex
        activeNavTrimmedGeom = prog.remainingGeometry
        renderer.updateActiveRouteLine(prog.remainingGeometry)

        val remainingRoadMeters = prog.remainingMeters
        if (route.steps.isNotEmpty()) {
            val nextIdx = route.steps.indexOfFirst { it.routeDistMeters > prog.traveledMeters + 18.0 }
            navCurrentStepIdx = when {
                nextIdx >= 0 -> nextIdx.coerceAtLeast(if (route.steps.size > 1) 1 else 0)
                else -> route.steps.lastIndex
            }
        }

        val step = route.steps.getOrElse(navCurrentStepIdx) {
            NavStep("Continue to destination", "↑", remainingRoadMeters, dest.latitude, dest.longitude, prog.traveledMeters + remainingRoadMeters)
        }
        val distToStep = if (step.routeDistMeters > 0.0) {
            (step.routeDistMeters - prog.traveledMeters).coerceAtLeast(0.0)
        } else {
            RoutingEngine.haversineMeters(loc.latitude, loc.longitude, step.lat, step.lng)
        }

        val etaSec = if (route.totalDistanceMeters > 10.0 && route.totalDurationSeconds > 0.0) {
            (remainingRoadMeters / route.totalDistanceMeters) * route.totalDurationSeconds
        } else {
            (remainingRoadMeters / 1000.0) / 45.0 * 3600.0
        }

        val unit = getCachedSpeedUnit()
        val nextInstr = if (isRerouting) "Recalculating route..." else step.instruction
        UiAnimations.animateCrossfadeText(tvNavArrow, step.arrow, slideDp = 4f)
        tvNavStepDist.text = if (isRerouting) "Rerouting" else formatDistanceShort(distToStep, unit)
        UiAnimations.animateCrossfadeText(tvNavInstruction, formatNavInstructionBidi(nextInstr), slideDp = 4f)
        tvNavEta.text = formatEtaDuration(etaSec)
        val modeTag = if (route.engineLabel.contains("Offline", ignoreCase = true)) "Offline" else "Online"
        tvNavMeta.text = "${formatDistanceShort(remainingRoadMeters, unit)} • $modeTag"
        val navNotifTitle = if (isRerouting) "Rerouting..." else "${step.arrow} ${formatDistanceShort(distToStep, unit)} • $nextInstr"
        val navNotifBody = "${formatEtaDuration(etaSec)} (${formatDistanceShort(remainingRoadMeters, unit)}) • ${viewModel.activeNavDestTitle.ifBlank { modeTag }}"
        NavigationForegroundService.updateProgress(activity, navNotifTitle, navNotifBody)

        val speakDist = if (loc.hasSpeed() && loc.speed > 16f) 450.0 else 280.0
        if (!isRerouting && navCurrentStepIdx != lastSpokenStepIdx && distToStep < speakDist) {
            lastSpokenStepIdx = navCurrentStepIdx
            speakPrompt("In ${formatDistanceShort(distToStep, unit)}, ${step.instruction}")
        }

        if (viewModel.isFollowingGps) {
            moveCameraForNavigation(loc, 450)
        }

        val now = System.currentTimeMillis()
        val rerouteThreshold = if (loc.hasAccuracy() && loc.accuracy > 20f) {
            (45.0 + loc.accuracy * 0.5).coerceAtMost(75.0)
        } else 45.0
        if (prog.offRouteMeters > rerouteThreshold && !isRerouting && now - lastRerouteTime > 7000L) {
            isRerouting = true
            lastRerouteTime = now
            tvNavStepDist.text = "Rerouting"
            tvNavInstruction.text = "Recalculating route..."
            speakPrompt("Recalculating route")
            val heading = if (loc.hasBearing() && loc.speed > 1.5f) loc.bearing else null
            val navReqId = ++routeRequestSeq
            RoutingEngine.fetchRoute(
                loc.latitude, loc.longitude, dest.latitude, dest.longitude, route.mode, heading,
                isCancelled = { viewModel.activeNavRoute == null || navReqId != routeRequestSeq }
            ) { newRoute, _ ->
                AppExecutors.postMain {
                    if (!isActivityAlive() || navReqId != routeRequestSeq) return@postMain
                    isRerouting = false
                    val active = viewModel.activeNavRoute ?: return@postMain
                    if (newRoute == null) {
                        val fallbackStep = active.steps.getOrNull(navCurrentStepIdx)
                        tvNavInstruction.text = formatNavInstructionBidi(fallbackStep?.instruction ?: "Continue to destination")
                        return@postMain
                    }
                    viewModel.activeNavRoute = newRoute
                    activeNavTrimmedGeom = newRoute.geometry
                    navLastSegIdx = 0
                    navCurrentStepIdx = if (newRoute.steps.size > 1) 1 else 0
                    lastSpokenStepIdx = -1
                    renderer.renderRouteOverlay()
                    gpsController.currentLocation?.let { latest ->
                        updateNavigationProgress(latest, newRoute, dest)
                    }
                }
            }
        }
    }

    private fun applyNavigationChrome(inNav: Boolean) {
        if (inNav) {
            dismissToolsForNav()
            undoController.dismiss(animate = false)
            UiAnimations.hideSlideTop(topCluster)
            UiAnimations.showSlideTop(navBanner)
            UiAnimations.hideSlideBottom(bottomNavDock, 110L)
            UiAnimations.animateSpeedBadgeVisibility(btnRuler, false)
            UiAnimations.animateSpeedBadgeVisibility(btnLasso, false)
            UiAnimations.showSlideBottom(navBottomBar)
            UiAnimations.fadeShow(bottomDock, 180L)
        } else {
            UiAnimations.hideSlideTop(navBanner)
            UiAnimations.hideSlideBottom(navBottomBar, 135L)
            UiAnimations.showSlideTop(topCluster)
            UiAnimations.animateSpeedBadgeVisibility(btnRuler, true)
            applyExtraMapToolsVisibility()
            applyZoomButtonsVisibility()
            if (!viewModel.isRulerMode && !viewModel.isLassoMode && sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
                UiAnimations.showSlideBottom(bottomNavDock)
            }
        }
    }

    fun stopNavigation() {
        NavigationForegroundService.onStopRequested = null
        NavigationForegroundService.stop(activity)
        routeRequestSeq++
        viewModel.stopNavigation()
        activeNavTrimmedGeom = null
        navLastSegIdx = 0
        isRerouting = false
        viewModel.previewRoute = null
        navCurrentStepIdx = 0
        applyKeepAwakeFlag()
        applyNavigationChrome(false)
        gpsController.syncGpsPollingProfile()
        gpsController.updateSpeedBadge(gpsController.currentLocation)
        renderer.renderRouteOverlay()
        renderer.map?.let { m ->
            val cam = CameraPosition.Builder()
                .target(m.cameraPosition.target)
                .zoom(m.cameraPosition.zoom)
                .tilt(0.0)
                .bearing(0.0)
                .padding(0.0, 0.0, 0.0, 0.0)
                .build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 400)
        }
    }

    private fun speakPrompt(text: String) {
        if (ttsReady && DevicePrefs.isVoiceNav(activity)) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_prompt")
        }
    }

    fun destroy() {
        NavigationForegroundService.onStopRequested = null
        NavigationForegroundService.stop(activity)
        routeRequestSeq++
        pendingRouteReq = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
