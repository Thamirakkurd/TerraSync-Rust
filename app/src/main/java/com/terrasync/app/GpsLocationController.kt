package com.terrasync.app

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns GPS listener registration across adaptive polling profiles, cross-provider fix
 * scoring, smooth arrow interpolation, and driving speedometer HUD fallback updates.
 */
class GpsLocationController(
    private val context: Context,
    private val viewModel: MapViewModel,
    private val renderer: MapLayerRenderer,
    private val speedBadge: LinearLayout,
    private val tvSpeedValue: TextView,
    private val tvSpeedUnit: TextView,
    private val btnLocate: ImageButton,
    private val isOverlayVisible: () -> Boolean,
    private val isActivityStarted: () -> Boolean,
    private val isActivityResumed: () -> Boolean,
    private val currentPalette: () -> ThemePalette,
    private val themedDialogBuilder: (String) -> AlertDialog.Builder,
    private val showThemedDialog: (AlertDialog, Boolean) -> AlertDialog,
    private val getCachedSpeedUnit: () -> SpeedUnit
) {

    enum class GpsPollProfile { OFF, ACTIVE_MAP, FOLLOW_LOCATING, NAVIGATION }

    var onPendingRouteCheck: ((Location) -> Unit)? = null
    var onActiveNavigationLocation: ((Location, NavRoute, LatLng) -> Unit)? = null
    var hasPendingRouteRequest: () -> Boolean = { false }

    var activeGpsProfile: GpsPollProfile = GpsPollProfile.OFF
        private set
    var currentLocation: Location? = null
        private set
    var displayedLat: Double = 0.0
        private set
    var displayedLng: Double = 0.0
        private set
    var displayedBearing: Float = 0f
        private set
    var lastLiveLocationFixMs: Long = 0L
        private set

    private var speedSampleState = SpeedFallbackEstimator.SpeedSampleState()
    private var gpsAnim: ValueAnimator? = null
    private var gpsTrackingActive = false
    private var prevLocationElapsedMs: Long = 0L
    private var lastGpsFixTimeMs: Long = 0L

    private val gpsListener = LocationListener { loc -> onNewLocation(loc) }

    fun currentPoseOrNull(): Triple<Double, Double, Float>? =
        if (currentLocation != null) Triple(displayedLat, displayedLng, displayedBearing) else null

    fun hasLocationPermission(): Boolean {
        val f = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val c = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return f == PackageManager.PERMISSION_GRANTED || c == PackageManager.PERMISSION_GRANTED
    }

    fun ensureLocationServiceOn(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (LocationManagerCompat.isLocationEnabled(lm)) return true

        showThemedDialog(
            themedDialogBuilder("Location is turned off")
                .setMessage("Turn on your phone's Location / GPS to find your position, measure speed, and start navigation.")
                .setPositiveButton("Turn On") { _, _ ->
                    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }
                .setNegativeButton("Cancel", null)
                .create(),
            false
        )
        return false
    }

    fun locationAgeMs(loc: Location): Long {
        return if (loc.elapsedRealtimeNanos > 0L) {
            ((SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
        } else {
            (System.currentTimeMillis() - loc.time).coerceAtLeast(0L)
        }
    }

    private fun desiredGpsProfile(): GpsPollProfile {
        if (viewModel.activeNavRoute != null) return GpsPollProfile.NAVIGATION
        if (!isActivityResumed()) return GpsPollProfile.OFF
        if (isOverlayVisible()) return GpsPollProfile.OFF
        if (viewModel.isFollowingGps || hasPendingRouteRequest()) return GpsPollProfile.FOLLOW_LOCATING
        return GpsPollProfile.ACTIVE_MAP
    }

    @SuppressLint("MissingPermission")
    fun syncGpsPollingProfile(forceRestart: Boolean = false) {
        val desired = desiredGpsProfile()
        if (desired == GpsPollProfile.OFF) {
            stopLiveGpsUpdates()
            return
        }
        if (!hasLocationPermission()) return
        if (gpsTrackingActive && activeGpsProfile == desired && !forceRestart) return

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val specs = when (desired) {
            GpsPollProfile.NAVIGATION -> buildList {
                add(Triple(LocationManager.GPS_PROVIDER, 600L, 0f))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Triple(LocationManager.FUSED_PROVIDER, 1200L, 0f))
                }
            }
            GpsPollProfile.FOLLOW_LOCATING -> buildList {
                add(Triple(LocationManager.GPS_PROVIDER, 1000L, 0f))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Triple(LocationManager.FUSED_PROVIDER, 1500L, 0f))
                }
                add(Triple(LocationManager.NETWORK_PROVIDER, 5000L, 5f))
            }
            GpsPollProfile.ACTIVE_MAP -> buildList {
                add(Triple(LocationManager.GPS_PROVIDER, 2500L, 2f))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Triple(LocationManager.FUSED_PROVIDER, 3000L, 3f))
                }
                add(Triple(LocationManager.NETWORK_PROVIDER, 15_000L, 15f))
            }
            GpsPollProfile.OFF -> emptyList()
        }

        val wasTracking = gpsTrackingActive
        if (!wasTracking) {
            val bestLast = specs
                .mapNotNull { (p, _, _) -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .filter { locationAgeMs(it) <= 120_000L }
                .minByOrNull { locationAgeMs(it) }
            if (bestLast != null && (currentLocation == null || locationAgeMs(bestLast) < locationAgeMs(currentLocation!!))) {
                currentLocation = bestLast
                displayedLat = bestLast.latitude
                displayedLng = bestLast.longitude
                if (bestLast.hasBearing()) displayedBearing = bestLast.bearing
                renderer.updateGpsLayerFeature(displayedLat, displayedLng, displayedBearing)
                updateSpeedBadge(bestLast)
            }
        } else {
            runCatching { lm.removeUpdates(gpsListener) }
        }

        var registeredAny = false
        specs.forEach { (provider, minTimeMs, minDistM) ->
            runCatching {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, minTimeMs, minDistM, gpsListener)
                    registeredAny = true
                }
            }
        }
        gpsTrackingActive = registeredAny
        activeGpsProfile = if (registeredAny) desired else GpsPollProfile.OFF
    }

    @SuppressLint("MissingPermission")
    fun startLiveGpsUpdates(forceRestart: Boolean = false) {
        syncGpsPollingProfile(forceRestart)
    }

    fun stopLiveGpsUpdates() {
        speedSampleState = SpeedFallbackEstimator.SpeedSampleState()
        activeGpsProfile = GpsPollProfile.OFF
        if (!gpsTrackingActive) return
        runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(gpsListener)
        }
        gpsTrackingActive = false
    }

    private fun isBetterLocation(candidate: Location, current: Location?): Boolean {
        val now = System.currentTimeMillis()
        val isGps = candidate.provider == LocationManager.GPS_PROVIDER
        val isFused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            candidate.provider == LocationManager.FUSED_PROVIDER
        if (isGps) {
            lastGpsFixTimeMs = now
        } else if (!isFused && now - lastGpsFixTimeMs < 10_000L) {
            return false
        } else if (isFused && now - lastGpsFixTimeMs < 3_000L && candidate.hasAccuracy() && candidate.accuracy > 25f) {
            return false
        }
        if (current == null) return true
        val candAge = locationAgeMs(candidate)
        val currAge = locationAgeMs(current)
        if (candAge > currAge + 3_000L) return false
        val isSignificantlyNewer = currAge - candAge > 5_000L
        if (!isSignificantlyNewer && candidate.hasAccuracy() && current.hasAccuracy()) {
            if (!isGps && !isFused && candidate.accuracy > current.accuracy + 25f) return false
            if ((isGps || isFused) && candidate.accuracy > 80f && current.accuracy < 25f) return false
        }
        return true
    }

    fun updateSpeedBadge(loc: Location?) {
        if (!DevicePrefs.isSpeedIndicatorEnabled(context)) {
            UiAnimations.animateSpeedBadgeVisibility(speedBadge, false)
            return
        }
        val unit = getCachedSpeedUnit()
        val accurateFix = loc != null &&
            loc.provider != LocationManager.NETWORK_PROVIDER &&
            (!loc.hasAccuracy() || loc.accuracy <= 35f)
        val rawMps = when {
            accurateFix && loc!!.hasSpeed() && loc.speed > 0.1f -> loc.speed
            accurateFix && speedSampleState.derivedSpeedMps > 0f -> speedSampleState.derivedSpeedMps
            else -> 0f
        }
        val rawKmh = (rawMps * 3.6f).roundToInt().coerceAtLeast(0)
        val kmh = if (rawKmh < 2) 0 else rawKmh
        val displayVal = if (unit == SpeedUnit.IMPERIAL) (kmh * 0.621371f).roundToInt() else kmh
        val shouldShow = viewModel.activeNavRoute != null || kmh >= 4
        if (shouldShow) {
            tvSpeedValue.text = "$displayVal"
            tvSpeedUnit.text = unit.speedSuffix
        }
        UiAnimations.animateSpeedBadgeVisibility(speedBadge, shouldShow)
    }

    private fun onNewLocation(loc: Location) {
        if (!isBetterLocation(loc, currentLocation)) return
        lastLiveLocationFixMs = System.currentTimeMillis()
        speedSampleState = SpeedFallbackEstimator.update(
            prev = speedSampleState,
            lat = loc.latitude,
            lng = loc.longitude,
            provider = loc.provider.orEmpty(),
            hasSpeed = loc.hasSpeed(),
            speedMps = loc.speed,
            accurate = !loc.hasAccuracy() || loc.accuracy <= 35f,
            elapsedMs = SystemClock.elapsedRealtime()
        )
        val prevLoc = currentLocation
        val prevLat = if (prevLoc == null) loc.latitude else displayedLat
        val prevLng = if (prevLoc == null) loc.longitude else displayedLng
        val prevBearing = displayedBearing
        val targetBearing = when {
            loc.hasBearing() && loc.speed > 0.8f -> loc.bearing
            prevLoc != null && prevLoc.distanceTo(loc) > 3.5f -> (prevLoc.bearingTo(loc) + 360f) % 360f
            else -> prevBearing
        }
        var deltaBearing = (targetBearing - prevBearing) % 360f
        if (deltaBearing > 180f) deltaBearing -= 360f
        if (deltaBearing < -180f) deltaBearing += 360f

        currentLocation = loc
        updateSpeedBadge(loc)

        onPendingRouteCheck?.invoke(loc)

        gpsAnim?.cancel()
        val nowElapsed = SystemClock.elapsedRealtime()
        val fixDeltaMs = if (prevLocationElapsedMs > 0L) (nowElapsed - prevLocationElapsedMs) else 450L
        prevLocationElapsedMs = nowElapsed
        val movedMeters = if (prevLoc == null) Double.MAX_VALUE else {
            RoutingEngine.haversineMeters(prevLat, prevLng, loc.latitude, loc.longitude)
        }
        val shouldAnimateGps = isActivityResumed() &&
            !UiAnimations.isReducedMotion(context) &&
            (movedMeters >= 0.35 || abs(deltaBearing) >= 1.5f)
        if (shouldAnimateGps) {
            val continuousMotion = (loc.hasSpeed() && loc.speed >= 1.5f) || movedMeters >= 2.5
            var lastAnimEmitMs = 0L
            gpsAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (continuousMotion) fixDeltaMs.coerceIn(420L, 1100L) else 450L
                interpolator = if (continuousMotion) LinearInterpolator() else DecelerateInterpolator()
                addUpdateListener { anim ->
                    if (!isActivityStarted()) {
                        anim.cancel()
                        return@addUpdateListener
                    }
                    val t = anim.animatedValue as Float
                    val nowAnim = SystemClock.elapsedRealtime()
                    if (t < 0.999f && nowAnim - lastAnimEmitMs < 33L) {
                        return@addUpdateListener
                    }
                    lastAnimEmitMs = nowAnim
                    displayedLat = prevLat + (loc.latitude - prevLat) * t
                    displayedLng = prevLng + (loc.longitude - prevLng) * t
                    displayedBearing = (prevBearing + deltaBearing * t + 360f) % 360f
                    renderer.updateGpsLayerFeature(displayedLat, displayedLng, displayedBearing)
                }
                start()
            }
        } else {
            displayedLat = loc.latitude
            displayedLng = loc.longitude
            displayedBearing = targetBearing
            if (prevLoc == null || movedMeters >= 0.1 || abs(deltaBearing) >= 0.5f) {
                renderer.updateGpsLayerFeature(displayedLat, displayedLng, displayedBearing)
            }
        }

        val activeRoute = viewModel.activeNavRoute
        val destPt = viewModel.activeNavDest
        if (activeRoute != null && destPt != null) {
            onActiveNavigationLocation?.invoke(loc, activeRoute, LatLng(destPt.lat, destPt.lng))
        } else if (isActivityResumed() && viewModel.isFollowingGps && DevicePrefs.isAutoFollow(context)) {
            renderer.map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(loc.latitude, loc.longitude)), 600)
        }
    }

    fun updateLocationIconTint() {
        val p = currentPalette()
        if (viewModel.isFollowingGps) {
            DesignSystem.applyFloatingControl(context, btnLocate, p, 33f, 10f, activeFillColor = p.accentPrimary)
            btnLocate.setColorFilter(Color.WHITE)
        } else if (p.isDark) {
            DesignSystem.applyFloatingControl(
                context,
                btnLocate,
                p,
                33f,
                10f,
                activeFillColor = Color.parseColor("#242926")
            )
            btnLocate.setColorFilter(p.accentPrimary)
        } else {
            DesignSystem.applyFloatingControl(context, btnLocate, p, 33f, 10f, activeFillColor = p.actionDark)
            btnLocate.setColorFilter(p.onActionDark)
        }
    }

    fun pause() {
        gpsAnim?.cancel()
        if (viewModel.activeNavRoute == null) stopLiveGpsUpdates()
    }

    fun destroy() {
        stopLiveGpsUpdates()
        gpsAnim?.cancel()
        gpsAnim = null
        onPendingRouteCheck = null
        onActiveNavigationLocation = null
    }
}
