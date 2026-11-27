package com.terrasync.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

object RoutingEngine {

    @Volatile var userAgent: String = "TerraSync/${BuildConfig.VERSION_NAME} (https://github.com/Thamirakkurd/TerraSync)"
    @Volatile var appContext: Context? = null

    val uiQueryExecutor: ExecutorService
        get() = AppExecutors.uiQueryExecutor
    val batchIoExecutor: ExecutorService
        get() = AppExecutors.batchIoExecutor
    val ioExecutor: ExecutorService
        get() = AppExecutors.batchIoExecutor

    private const val NOMINATIM_CACHE_TTL_MS = 10L * 60L * 1000L
    private data class CachedSearch(val cachedAtMs: Long, val places: List<PlaceResult>)
    private data class CachedReverse(val cachedAtMs: Long, val place: PlaceResult)

    private val searchCache = LruCache<String, CachedSearch>(32)
    private val reverseCache = LruCache<String, CachedReverse>(96)
    private val activeRouteCall = AtomicReference<Call?>(null)
    private val activeSearchCall = AtomicReference<Call?>(null)
    private val activeReverseCall = AtomicReference<Call?>(null)

    fun cancelPendingRoute() {
        activeRouteCall.getAndSet(null)?.cancel()
    }

    fun cancelPendingSearch() {
        activeSearchCall.getAndSet(null)?.cancel()
    }

    fun cancelPendingReverseGeocode() {
        activeReverseCall.getAndSet(null)?.cancel()
    }

    val sharedOkHttpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 12
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(12, 60, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    /**
     * Dedicated high-concurrency HTTP/2 client for MapLibre live & offline tile/glyph downloads.
     * Matches MapLibre Native's DEFAULT_MAXIMUM_CONCURRENT_REQUESTS (20) and wires
     * [MapTileInterceptor] for local PMTiles serving, Kurdish MVT shaping, and bundled Kurdish SDF glyphs.
     */
    val mapTileOkHttpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 20
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(20, 60, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .build()
                chain.proceed(req)
            }
            .addInterceptor(MapTileInterceptor { appContext })
            .build()
    }

    private fun isNetworkAvailable(ctx: Context?): Boolean = NetworkState.hasValidatedInternet(ctx)

    fun fetchRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        mode: TravelMode = TravelMode.CAR,
        startBearing: Float? = null,
        isCancelled: () -> Boolean = { false },
        onResult: (NavRoute?, String?) -> Unit
    ) {
        uiQueryExecutor.execute {
            if (isCancelled()) return@execute
            val ctx = appContext
            val online = isNetworkAvailable(ctx)

            // 1. If device is offline, go straight to local offline routing engine
            if (!online && ctx != null) {
                val (offRoute, offErr) = OfflineRoutingEngine.routeOffline(ctx, startLat, startLng, endLat, endLng, mode)
                if (isCancelled()) return@execute
                if (offRoute != null) {
                    onResult(offRoute, null)
                    return@execute
                }
                onResult(null, offErr ?: "Offline and no local road graph found")
                return@execute
            }

            // 2. Primary Online Engine: Valhalla (valhalla1.openstreetmap.de)
            val valhallaHeading = if (startBearing != null && mode != TravelMode.WALKING) {
                ((startBearing.roundToInt() % 360) + 360) % 360
            } else null

            val valhallaAttempts = if (valhallaHeading != null) listOf<Int?>(valhallaHeading, null) else listOf<Int?>(null)
            var lastError = "Route request failed"
            for (heading in valhallaAttempts) {
                if (isCancelled()) return@execute
                val (vRoute, vErr) = fetchValhallaRoute(startLat, startLng, endLat, endLng, mode, heading, isCancelled)
                if (isCancelled()) return@execute
                if (vRoute != null) {
                    onResult(vRoute, null)
                    return@execute
                }
                if (!vErr.isNullOrBlank()) lastError = vErr
            }

            // 3. Secondary Online Fallback: OSRM
            if (isCancelled()) return@execute
            val (osrmRoute, osrmErr) = fetchOsrmRoute(startLat, startLng, endLat, endLng, mode, startBearing, isCancelled)
            if (isCancelled()) return@execute
            if (osrmRoute != null) {
                onResult(osrmRoute, null)
                return@execute
            }
            if (!osrmErr.isNullOrBlank()) lastError = osrmErr

            // 4. Local Offline Fallback if online servers failed
            if (!isCancelled() && ctx != null && OfflineRoutingEngine.hasAnyRegion(ctx)) {
                val (offRoute, _) = OfflineRoutingEngine.routeOffline(ctx, startLat, startLng, endLat, endLng, mode)
                if (isCancelled()) return@execute
                if (offRoute != null) {
                    onResult(offRoute, null)
                    return@execute
                }
            }

            if (!isCancelled()) onResult(null, lastError)
        }
    }

    private fun executeCancellable(
        request: Request,
        slot: AtomicReference<Call?>,
        cancelPrevious: Boolean,
        isCancelled: () -> Boolean = { false }
    ): Pair<Int, String?> {
        if (isCancelled()) throw IOException("Cancelled")
        val call = sharedOkHttpClient.newCall(request)
        val prev = slot.getAndSet(call)
        if (cancelPrevious) prev?.cancel()
        try {
            if (isCancelled()) {
                call.cancel()
                throw IOException("Cancelled")
            }
            call.execute().use { resp ->
                val body = resp.body?.string()
                return resp.code to body
            }
        } finally {
            slot.compareAndSet(call, null)
        }
    }

    private fun fetchValhallaRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        mode: TravelMode,
        headingDeg: Int?,
        isCancelled: () -> Boolean
    ): Pair<NavRoute?, String?> {
        return try {
            val costing = when (mode) {
                TravelMode.CAR -> "auto"
                TravelMode.BICYCLE -> "bicycle"
                TravelMode.WALKING -> "pedestrian"
            }
            val orig = JSONObject().apply {
                put("lat", startLat)
                put("lon", startLng)
                if (headingDeg != null) {
                    put("heading", headingDeg)
                    put("heading_tolerance", 60)
                }
            }
            val dest = JSONObject().apply {
                put("lat", endLat)
                put("lon", endLng)
            }
            val payload = JSONObject().apply {
                put("locations", JSONArray().apply {
                    put(orig)
                    put(dest)
                })
                put("costing", costing)
                put("directions_options", JSONObject().apply {
                    put("units", "kilometers")
                    put("language", "en-US")
                })
            }

            val req = Request.Builder()
                .url("https://valhalla1.openstreetmap.de/route")
                .post(payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()
            val (code, body) = executeCancellable(req, activeRouteCall, cancelPrevious = true, isCancelled = isCancelled)
            if (code != 200 || body.isNullOrEmpty()) {
                return null to "Valhalla HTTP $code"
            }
            val trip = JSONObject(body).optJSONObject("trip") ?: return null to "Invalid Valhalla response"
            val legs = trip.optJSONArray("legs")
            if (legs == null || legs.length() == 0) return null to "No Valhalla route found"
            val leg0 = legs.getJSONObject(0)
            val shape = leg0.optString("shape", "")
            val geom = decodePolyline6(shape)
            if (geom.size < 2) return null to "Empty Valhalla geometry"

            val cumGeom = DoubleArray(geom.size)
            for (i in 0 until geom.size - 1) {
                cumGeom[i + 1] = cumGeom[i] + haversineMeters(
                    geom[i].second, geom[i].first,
                    geom[i + 1].second, geom[i + 1].first
                )
            }
            val geomTotal = cumGeom.last()
            val summary = leg0.optJSONObject("summary")
            val totalDist = (summary?.optDouble("length", 0.0) ?: 0.0) * 1000.0
            val finalDist = if (totalDist > 1.0) totalDist else geomTotal
            val totalDur = summary?.optDouble("time", 0.0) ?: 0.0

            val steps = mutableListOf<NavStep>()
            val mans = leg0.optJSONArray("maneuvers") ?: JSONArray()
            for (i in 0 until mans.length()) {
                val m = mans.getJSONObject(i)
                val mType = m.optInt("type", 8)
                val instr = m.optString("instruction", "Continue").trim().removeSuffix(".")
                val distM = m.optDouble("length", 0.0) * 1000.0
                val beginIdx = m.optInt("begin_shape_index", 0).coerceIn(0, geom.lastIndex)
                val pt = geom[beginIdx]
                val arrow = valhallaArrow(mType)
                steps.add(NavStep(instr, arrow, distM, pt.second, pt.first, cumGeom[beginIdx]))
            }
            NavRoute(geom, finalDist, totalDur, steps, mode, "Valhalla") to null
        } catch (e: Exception) {
            null to (e.message ?: "Valhalla error")
        }
    }

    private fun valhallaArrow(type: Int): String = when (type) {
        4, 5, 6 -> "🏁"
        9, 18, 20, 23 -> "↗"
        10 -> "↱"
        11 -> "↘"
        12, 13 -> "↺"
        14 -> "↙"
        15 -> "↰"
        16, 19, 21, 24 -> "↖"
        26, 27 -> "⟳"
        else -> "↑"
    }

    private fun decodePolyline6(encoded: String): List<Pair<Double, Double>> {
        RustCoreBridge.decodePolyline6(encoded)?.let { return it }
        val poly = ArrayList<Pair<Double, Double>>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            if (index >= len) break
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            poly.add((lng / 1e6) to (lat / 1e6))
        }
        return poly
    }

    private fun fetchOsrmRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        mode: TravelMode,
        startBearing: Float?,
        isCancelled: () -> Boolean
    ): Pair<NavRoute?, String?> {
        val profile = when (mode) {
            TravelMode.CAR -> "driving"
            TravelMode.BICYCLE -> "bike"
            TravelMode.WALKING -> "foot"
        }
        val extra = if (mode == TravelMode.WALKING) "&alternatives=true&continue_straight=false" else ""
        val bearingParam = if (startBearing != null && mode != TravelMode.WALKING) {
            val deg = ((startBearing.roundToInt() % 360) + 360) % 360
            "&bearings=$deg,60;&continue_straight=true"
        } else ""

        val basePrimary = "https://routing.openstreetmap.de/${mode.osrmPrefix}/route/v1/$profile/$startLng,$startLat;$endLng,$endLat?overview=full&geometries=geojson&steps=true$extra"
        val urls = buildList {
            if (bearingParam.isNotEmpty()) add("$basePrimary$bearingParam")
            add(basePrimary)
            if (mode == TravelMode.CAR) {
                val fallbackCar = "https://router.project-osrm.org/route/v1/driving/$startLng,$startLat;$endLng,$endLat?overview=full&geometries=geojson&steps=true"
                if (bearingParam.isNotEmpty()) add("$fallbackCar$bearingParam")
                add(fallbackCar)
            }
        }

        var lastError = "OSRM route request failed"
        for (urlStr in urls) {
            if (isCancelled()) return null to "Cancelled"
            try {
                val req = Request.Builder().url(urlStr).get().build()
                val (code, body) = executeCancellable(req, activeRouteCall, cancelPrevious = true, isCancelled = isCancelled)
                if (code != 200 || body.isNullOrEmpty()) {
                    lastError = "OSRM error ($code)"
                    continue
                }
                val root = JSONObject(body)
                val routes = root.optJSONArray("routes")
                if (routes == null || routes.length() == 0) {
                    lastError = "No ${mode.label.lowercase()} route found"
                    continue
                }
                var r0 = routes.getJSONObject(0)
                if (mode == TravelMode.WALKING && routes.length() > 1) {
                    var bestDist = r0.optDouble("distance", Double.MAX_VALUE)
                    for (idx in 1 until routes.length()) {
                        val cand = routes.getJSONObject(idx)
                        val d = cand.optDouble("distance", Double.MAX_VALUE)
                        if (d < bestDist) {
                            bestDist = d
                            r0 = cand
                        }
                    }
                }
                val totalDist = r0.optDouble("distance", 0.0)
                val totalDur = r0.optDouble("duration", 0.0)

                val coordsArr = r0.getJSONObject("geometry").getJSONArray("coordinates")
                val geom = mutableListOf<Pair<Double, Double>>()
                for (i in 0 until coordsArr.length()) {
                    val pt = coordsArr.getJSONArray(i)
                    geom.add(pt.getDouble(0) to pt.getDouble(1))
                }
                if (geom.size < 2) {
                    lastError = "Empty OSRM geometry"
                    continue
                }
                var geomTotal = 0.0
                for (i in 0 until geom.size - 1) {
                    geomTotal += haversineMeters(geom[i].second, geom[i].first, geom[i + 1].second, geom[i + 1].first)
                }

                val steps = mutableListOf<NavStep>()
                val legs = r0.optJSONArray("legs")
                if (legs != null && legs.length() > 0) {
                    val stepsArr = legs.getJSONObject(0).optJSONArray("steps") ?: JSONArray()
                    var cumDist = 0.0
                    for (i in 0 until stepsArr.length()) {
                        val s = stepsArr.getJSONObject(i)
                        val dist = s.optDouble("distance", 0.0)
                        val roadName = s.optString("name", "").trim()
                        val man = s.optJSONObject("maneuver") ?: JSONObject()
                        val type = man.optString("type", "")
                        val mod = man.optString("modifier", "")
                        val loc = man.optJSONArray("location")
                        val lng = loc?.optDouble(0) ?: endLng
                        val lat = loc?.optDouble(1) ?: endLat

                        val stepRouteDist = when {
                            i == 0 -> 0.0
                            i == stepsArr.length() - 1 -> geomTotal
                            totalDist > 1.0 && geomTotal > 1.0 -> (cumDist / totalDist) * geomTotal
                            else -> cumDist
                        }
                        val (arrow, instr) = formatStep(type, mod, roadName)
                        steps.add(NavStep(instr, arrow, dist, lat, lng, stepRouteDist))
                        cumDist += dist
                    }
                }
                return NavRoute(geom, totalDist, totalDur, steps, mode, "OSRM") to null
            } catch (e: Exception) {
                lastError = e.message ?: "OSRM request failed"
            }
        }
        return null to lastError
    }

    private fun formatStep(type: String, modifier: String, roadName: String): Pair<String, String> {
        val onto = if (roadName.isNotBlank()) " onto $roadName" else ""
        return when (type) {
            "arrive" -> "🏁" to "Arrive at destination"
            "depart" -> "↑" to "Head out$onto"
            "roundabout", "rotary" -> "⟳" to "Enter roundabout$onto"
            "merge" -> "↑" to "Merge$onto"
            "off ramp" -> "↗" to "Take exit$onto"
            "on ramp" -> "↗" to "Take ramp$onto"
            else -> when {
                modifier.contains("sharp left") -> "↙" to "Sharp left$onto"
                modifier.contains("slight left") -> "↖" to "Keep left$onto"
                modifier.contains("left") -> "↰" to "Turn left$onto"
                modifier.contains("sharp right") -> "↘" to "Sharp right$onto"
                modifier.contains("slight right") -> "↗" to "Keep right$onto"
                modifier.contains("right") -> "↱" to "Turn right$onto"
                modifier.contains("uturn") -> "↺" to "Make a U-turn$onto"
                else -> "↑" to "Continue straight$onto"
            }
        }
    }

    private fun activeAcceptLanguage(): String = appContext?.let { ctx ->
        when (DevicePrefs.getMapLabelLanguage(ctx)) {
            MapLabelLanguage.KURDISH -> "ku,ckb,en,ar"
            MapLabelLanguage.ARABIC -> "ar,en,ku"
            MapLabelLanguage.ENGLISH -> "en,ku,ar"
            MapLabelLanguage.LOCAL -> "ku,ar,en"
            MapLabelLanguage.BILINGUAL -> "en,ku,ar"
        }
    } ?: "en,ku,ar"

    fun searchPlaces(
        query: String,
        biasLat: Double? = null,
        biasLng: Double? = null,
        onResult: (List<PlaceResult>, String?) -> Unit
    ) {
        val normQ = query.trim().lowercase(java.util.Locale.ROOT)
        val acceptLang = activeAcceptLanguage()
        val biasKey = if (biasLat != null && biasLng != null) {
            "${(biasLat * 10.0).roundToLong()},${(biasLng * 10.0).roundToLong()}"
        } else "none"
        val cacheKey = "$acceptLang|$normQ|$biasKey"
        val nowMs = SystemClock.elapsedRealtime()
        synchronized(searchCache) {
            searchCache.get(cacheKey)?.let { cached ->
                if (nowMs - cached.cachedAtMs <= NOMINATIM_CACHE_TTL_MS) {
                    onResult(cached.places, null)
                    return
                }
            }
        }

        uiQueryExecutor.execute {
            val out = mutableListOf<PlaceResult>()
            var errMsg: String? = null
            if (!isNetworkAvailable(appContext)) {
                onResult(emptyList(), "Offline — web place search requires internet")
                return@execute
            }
            try {
                val q = Uri.encode(query)
                val biasPart = if (biasLat != null && biasLng != null) {
                    val left = (biasLng - 1.5).coerceAtLeast(-180.0)
                    val right = (biasLng + 1.5).coerceAtMost(180.0)
                    val top = (biasLat + 1.5).coerceAtMost(90.0)
                    val bottom = (biasLat - 1.5).coerceAtLeast(-90.0)
                    "&viewbox=$left,$top,$right,$bottom"
                } else ""
                val urlStr = "https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=8$biasPart"
                val req = Request.Builder()
                    .url(urlStr)
                    .header("Accept-Language", acceptLang)
                    .get()
                    .build()
                val (code, body) = executeCancellable(req, activeSearchCall, cancelPrevious = true)
                if (code == 200 && body != null) {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val display = obj.optString("display_name", "Place")
                        val parts = display.split(",").map { it.trim() }
                        val title = parts.firstOrNull() ?: display
                        val sub = parts.drop(1).take(3).joinToString(", ")
                        val lat = obj.optString("lat").toDoubleOrNull()
                        val lng = obj.optString("lon").toDoubleOrNull()
                        if (lat != null && lng != null) {
                            out.add(PlaceResult(title, sub, lat, lng))
                        }
                    }
                    synchronized(searchCache) {
                        searchCache.put(cacheKey, CachedSearch(SystemClock.elapsedRealtime(), out.toList()))
                    }
                } else {
                    errMsg = "Search server error (HTTP $code)"
                }
            } catch (e: Exception) {
                errMsg = e.message ?: "Search request failed"
            }
            onResult(out, errMsg)
        }
    }

    fun reverseGeocode(lat: Double, lng: Double, onResult: (PlaceResult?) -> Unit) {
        val acceptLang = activeAcceptLanguage()
        val qLat = (lat * 10000.0).roundToLong() // ~11 m cell bucket
        val qLng = (lng * 10000.0).roundToLong()
        val cacheKey = "$acceptLang|$qLat|$qLng"
        val nowMs = SystemClock.elapsedRealtime()
        synchronized(reverseCache) {
            reverseCache.get(cacheKey)?.let { cached ->
                if (nowMs - cached.cachedAtMs <= NOMINATIM_CACHE_TTL_MS) {
                    onResult(cached.place)
                    return
                }
            }
        }

        uiQueryExecutor.execute {
            if (!isNetworkAvailable(appContext)) {
                onResult(null)
                return@execute
            }
            val result = runCatching {
                val urlStr = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=json&zoom=18&addressdetails=1"
                val req = Request.Builder()
                    .url(urlStr)
                    .header("Accept-Language", acceptLang)
                    .get()
                    .build()
                val (code, body) = executeCancellable(req, activeReverseCall, cancelPrevious = true)
                if (code == 200 && body != null) {
                    val obj = JSONObject(body)
                    val name = obj.optString("name", "").trim()
                    val display = obj.optString("display_name", "").trim()
                    val parts = display.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val title = name.ifBlank { parts.firstOrNull() ?: "Selected Location" }
                    val sub = parts.filterNot { it.equals(title, ignoreCase = true) }.take(3).joinToString(", ")
                    PlaceResult(title, sub, lat, lng).also { res ->
                        synchronized(reverseCache) {
                            reverseCache.put(cacheKey, CachedReverse(SystemClock.elapsedRealtime(), res))
                        }
                    }
                } else null
            }.getOrNull()
            onResult(result)
        }
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = (sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2))
            .coerceIn(0.0, 1.0)
        return 2 * r * atan2(sqrt(a), sqrt(1.0 - a))
    }

    fun trackRouteProgress(
        lat: Double,
        lng: Double,
        route: NavRoute,
        lastSegIdx: Int = 0
    ): RouteProgress {
        RustCoreBridge.trackRouteProgress(lat, lng, route, lastSegIdx)?.let { return it }
        val (segLens, cumLens) = route.segmentMetrics
        return trackRouteProgressInternal(lat, lng, route.geometry, segLens, cumLens, lastSegIdx)
    }

    fun trackRouteProgress(
        lat: Double,
        lng: Double,
        geom: List<Pair<Double, Double>>,
        lastSegIdx: Int = 0
    ): RouteProgress {
        if (geom.size < 2) {
            return RouteProgress(0.0, 0.0, 0.0, lat, lng, 0, geom)
        }
        val segCount = geom.size - 1
        val segLens = DoubleArray(segCount)
        val cumLens = DoubleArray(geom.size)
        for (i in 0 until segCount) {
            val len = haversineMeters(geom[i].second, geom[i].first, geom[i + 1].second, geom[i + 1].first)
            segLens[i] = len
            cumLens[i + 1] = cumLens[i] + len
        }
        return trackRouteProgressInternal(lat, lng, geom, segLens, cumLens, lastSegIdx)
    }

    private fun trackRouteProgressInternal(
        lat: Double,
        lng: Double,
        geom: List<Pair<Double, Double>>,
        segLens: DoubleArray,
        cumLens: DoubleArray,
        lastSegIdx: Int
    ): RouteProgress {
        if (geom.size < 2) {
            return RouteProgress(0.0, 0.0, 0.0, lat, lng, 0, geom)
        }
        val cosLat = cos(Math.toRadians(lat))
        val mPerDegLat = 111132.0
        val mPerDegLng = 111320.0 * cosLat
        val segCount = geom.size - 1

        fun scanRange(startIdx: Int, endIdx: Int): Triple<Int, Double, Double> {
            var bestIdx = startIdx
            var bestDist = Double.MAX_VALUE
            var bestT = 0.0
            for (i in startIdx..endIdx) {
                val aLng = geom[i].first
                val aLat = geom[i].second
                val bLng = geom[i + 1].first
                val bLat = geom[i + 1].second

                val ax = (aLng - lng) * mPerDegLng
                val ay = (aLat - lat) * mPerDegLat
                val bx = (bLng - lng) * mPerDegLng
                val by = (bLat - lat) * mPerDegLat

                val dx = bx - ax
                val dy = by - ay
                val lenSq = dx * dx + dy * dy
                val t = if (lenSq > 1e-6) (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0) else 0.0
                val px = ax + t * dx
                val py = ay + t * dy
                val d = hypot(px, py)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = i
                    bestT = t
                }
            }
            return Triple(bestIdx, bestT, bestDist)
        }

        val safeLast = lastSegIdx.coerceIn(0, segCount - 1)
        val winStart = (safeLast - 3).coerceAtLeast(0)
        var winEnd = safeLast
        while (winEnd < segCount - 1) {
            val aheadDist = cumLens[winEnd + 1] - cumLens[safeLast]
            if (aheadDist >= 2000.0 && winEnd - safeLast >= 35) break
            winEnd++
        }

        var (bestSegIdx, bestT, bestDist) = scanRange(winStart, winEnd)
        if (bestDist > 45.0) {
            val (fullIdx, fullT, fullDist) = scanRange(0, segCount - 1)
            if (fullDist < bestDist - 12.0) {
                bestSegIdx = fullIdx
                bestT = fullT
                bestDist = fullDist
            }
        }

        val segStart = geom[bestSegIdx]
        val segEnd = geom[bestSegIdx + 1]
        val projLng = segStart.first + bestT * (segEnd.first - segStart.first)
        val projLat = segStart.second + bestT * (segEnd.second - segStart.second)

        val traveled = cumLens[bestSegIdx] + segLens[bestSegIdx] * bestT
        val remaining = (cumLens.last() - traveled).coerceAtLeast(0.0)

        val remGeom = ArrayList<Pair<Double, Double>>(geom.size - bestSegIdx + 1)
        remGeom.add(projLng to projLat)
        for (i in (bestSegIdx + 1) until geom.size) {
            remGeom.add(geom[i])
        }

        return RouteProgress(
            offRouteMeters = bestDist,
            traveledMeters = traveled,
            remainingMeters = remaining,
            snappedLat = projLat,
            snappedLng = projLng,
            segmentIndex = bestSegIdx,
            remainingGeometry = remGeom
        )
    }
}
