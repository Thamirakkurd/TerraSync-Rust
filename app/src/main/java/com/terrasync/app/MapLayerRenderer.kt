package com.terrasync.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import com.google.gson.JsonParser
import java.io.File
import okhttp3.Request
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.TileData
import org.maplibre.android.style.sources.CustomVectorTileProvider
import org.maplibre.android.style.sources.CustomVectorSource
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Owns MapLibre Style initialization, vector/satellite cartography, hillshading,
 * GeoJson pin/track rendering (off UI thread), route/ruler/selection overlays, and pulse animations.
 */
class MapLayerRenderer(
    private val context: Context,
    private val viewModel: MapViewModel,
    private val contourManager: ContourOverlayManager,
    private val isDarkMapUi: () -> Boolean,
    private val isActivityAlive: () -> Boolean,
    private val isActivityStarted: () -> Boolean,
    private val isActivityResumed: () -> Boolean,
    private val getRulerGeoPoints: () -> List<GeoPoint>,
    private val getGpsDisplayPose: () -> Triple<Double, Double, Float>?,
    private val getActiveNavTrimmedGeom: () -> List<Pair<Double, Double>>?,
    private val onUpdateSyncBadge: () -> Unit,
    private val onFlushPendingZoom: () -> Unit
) {

    companion object {
        const val HILLSHADE_SOURCE = "terrasync-dem-source"
        const val HILLSHADE_LAYER = "terrasync-hillshade-layer"
        const val TRACKS_SOURCE = "terrasync-tracks-source"
        const val TRACKS_LAYER = "terrasync-tracks-layer"
        const val ROUTE_SOURCE = "terrasync-route-source"
        const val ROUTE_LAYER = "terrasync-route-layer"
        const val ROUTE_CASING_LAYER = "terrasync-route-casing-layer"
        const val ROUTE_WALK_CASING_LAYER = "terrasync-route-walk-casing-layer"
        const val ROUTE_WALK_LAYER = "terrasync-route-walk-layer"
        const val ROUTE_PILL_SOURCE = "terrasync-route-pill-source"
        const val ROUTE_PILL_LAYER = "terrasync-route-pill-layer"
        const val ROUTE_PILL_ICON = "route-time-pill"
        const val RULER_LINE_SOURCE = "terrasync-ruler-line-source"
        const val RULER_LINE_CASING_LAYER = "terrasync-ruler-line-casing-layer"
        const val RULER_LINE_LAYER = "terrasync-ruler-line-layer"
        const val RULER_POINTS_SOURCE = "terrasync-ruler-points-source"
        const val RULER_POINTS_CIRCLE_LAYER = "terrasync-ruler-points-circle-layer"
        const val RULER_POINTS_NUM_LAYER = "terrasync-ruler-points-num-layer"
        const val PINS_SOURCE = "terrasync-pins-source"
        const val PINS_MVT_SOURCE = "terrasync-pins-mvt-source"
        const val PINS_MVT_SOURCE_LAYER = "pins"
        const val TRACKS_MVT_SOURCE_LAYER = "tracks"
        const val PINS_CLUSTER_SOURCE = "terrasync-pins-cluster-source"
        const val PINS_LAYER = "terrasync-pins-layer"
        const val PINS_LABELS_LAYER = "terrasync-pins-labels-layer"
        const val PINS_CLUSTER_UNCLUSTERED_LAYER = "terrasync-pins-cluster-unclustered"
        const val PINS_CLUSTER_LABELS_LAYER = "terrasync-pins-cluster-labels"
        const val PINS_CLUSTER_CIRCLE_LAYER = "terrasync-pins-cluster-circles"
        const val PINS_CLUSTER_COUNT_LAYER = "terrasync-pins-cluster-count"
        const val SELECTED_SOURCE = "terrasync-selected-source"
        const val SELECTED_HALO_LAYER = "terrasync-selected-halo-layer"
        const val SELECTED_PIN_SOURCE = "terrasync-selected-pin-source"
        const val SELECTED_PIN_LAYER = "terrasync-selected-pin-layer"
        const val SELECTED_COORD_SOURCE = "terrasync-selected-coord-source"
        const val SELECTED_COORD_LAYER = "terrasync-selected-coord-layer"
        const val PULSE_SOURCE = "terrasync-pulse-source"
        const val PULSE_LAYER = "terrasync-pulse-layer"
        const val USER_LOC_SOURCE = "terrasync-userloc-source"
        const val USER_LOC_LAYER = "terrasync-userloc-layer"

        private const val STYLE_CACHE_MAX_AGE_MS = 24L * 3600L * 1000L

        /**
         * Strips root-level "font-faces" from cached or fetched style JSON.
         * MapLibre Native's HarfBuzz font-faces shaper hardcodes HB_DIRECTION_LTR and skips BiDi
         * (upstream maplibre-native#4513), which reverses/scrambles Arabic & Kurdish labels.
         * Standard PBF glyph rendering + ICU BiDi preserves correct RTL word order.
         */
        internal fun sanitizeStyleJson(styleJson: String): String = runCatching {
            val root = JsonParser.parseString(styleJson).asJsonObject
            if (root.has("font-faces")) {
                root.remove("font-faces")
                root.toString()
            } else {
                styleJson
            }
        }.getOrDefault(styleJson)

        internal fun shapeKurdishAeForMapLibre(text: String): String =
            KurdishArabicShaper.shape(text)

        internal const val STYLE_SATELLITE = """
        {
          "version": 8,
          "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
          "sources": {
            "sat": {
              "type": "raster",
              "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
              "tileSize": 256,
              "maxzoom": 17
            },
            "roads": {
              "type": "raster",
              "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"],
              "tileSize": 256,
              "maxzoom": 17
            },
            "places": {
              "type": "raster",
              "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"],
              "tileSize": 256,
              "maxzoom": 17
            }
          },
          "layers": [
            { "id": "sat-layer", "type": "raster", "source": "sat", "minzoom": 0, "maxzoom": 22 },
            { "id": "roads-layer", "type": "raster", "source": "roads", "minzoom": 0, "maxzoom": 22 },
            { "id": "places-layer", "type": "raster", "source": "places", "minzoom": 0, "maxzoom": 22 }
          ]
        }
        """
    }

    var map: MapLibreMap? = null
    var lastPinsRenderKey: String? = null
    private var lastTracksRenderKey: String? = null
    private var lastRoutePillKey: String? = null
    @Volatile private var pinRenderSeq: Long = 0L
    @Volatile private var trackRenderSeq: Long = 0L
    @Volatile private var styleLoadSeq: Long = 0L
    private val trackPointCache = HashMap<String, Pair<Long, List<Point>>>()
    private var pulseAnimator: ValueAnimator? = null
    private var cameraIdleMap: MapLibreMap? = null
    private val mvtTileScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requestedMvtTiles = ConcurrentHashMap.newKeySet<Long>()
    private var lastMvtFilterSignature: String? = null

    private fun ensureMvtPinsSource(style: Style): Boolean {
        if (!RustCoreBridge.isAvailable()) return false
        if (style.getSource(PINS_MVT_SOURCE) != null) return true
        requestedMvtTiles.clear()
        val provider = object : CustomVectorTileProvider {
            override suspend fun fetchTile(z: Int, x: Int, y: Int): TileData {
                requestedMvtTiles.add(packTileKey(z, x, y))
                return TileData.Mvt(RustCoreBridge.renderPinsMvtTile(z, x, y))
            }
        }
        style.addSource(
            CustomVectorSource(
                id = PINS_MVT_SOURCE,
                provider = provider,
                scope = mvtTileScope,
                minZoom = 0,
                maxZoom = 17
            )
        )
        return true
    }

    private fun packTileKey(z: Int, x: Int, y: Int): Long =
        ((z.toLong() and 0x3FL) shl 48) or ((x.toLong() and 0xFFFFFFL) shl 24) or (y.toLong() and 0xFFFFFFL)

    private fun invalidateTrackedMvtTiles(style: Style, dataVersion: Long, allowDelta: Boolean) {
        val mvtSrc = style.getSourceAs<CustomVectorSource>(PINS_MVT_SOURCE) ?: return
        val allTracked = requestedMvtTiles.toLongArray()
        if (allTracked.isEmpty()) return
        val toInvalidate = if (allowDelta) {
            RustCoreBridge.filterDirtyMvtTiles(dataVersion, allTracked) ?: allTracked
        } else {
            allTracked
        }
        if (toInvalidate === allTracked) {
            requestedMvtTiles.clear()
        } else {
            for (k in toInvalidate) requestedMvtTiles.remove(k)
        }
        for (k in toInvalidate) {
            val z = ((k ushr 48) and 0x3FL).toInt()
            val x = ((k ushr 24) and 0xFFFFFFL).toInt()
            val y = (k and 0xFFFFFFL).toInt()
            mvtSrc.invalidateTile(z, x, y)
        }
    }
    private var hasViewportCull: Boolean = false
    private var lastVpSouth: Double = -90.0
    private var lastVpNorth: Double = 90.0
    private var lastVpWest: Double = -180.0
    private var lastVpEast: Double = 180.0
    private var lastVpZoom: Double = 0.0
    private val viewportIdleListener = MapLibreMap.OnCameraIdleListener {
        onMapCameraIdleForViewport()
    }

    private fun ensureCameraIdleListener(m: MapLibreMap) {
        if (cameraIdleMap === m) return
        cameraIdleMap?.removeOnCameraIdleListener(viewportIdleListener)
        cameraIdleMap = m
        m.addOnCameraIdleListener(viewportIdleListener)
    }

    private fun onMapCameraIdleForViewport() {
        if (!isActivityAlive() || !RustCoreBridge.isAvailable()) return
        val snap = viewModel.folderSnapshot
        val m = map ?: return
        val style = m.style ?: return
        if (!style.isFullyLoaded) return
        val usingMvt = style.getSource(PINS_MVT_SOURCE) != null
        if (usingMvt) return
        if (!usingMvt && snap.pins.size <= 1500) return
        val bounds = runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull() ?: return
        val zoom = m.cameraPosition.zoom
        if (hasViewportCull &&
            bounds.latitudeSouth >= lastVpSouth &&
            bounds.latitudeNorth <= lastVpNorth &&
            bounds.longitudeWest >= lastVpWest &&
            bounds.longitudeEast <= lastVpEast &&
            kotlin.math.abs(zoom - lastVpZoom) < 0.55
        ) {
            return
        }
        if (!usingMvt) lastPinsRenderKey = null
        lastTracksRenderKey = null
            lastMvtFilterSignature = null
        renderPinsAndTracks(style)
    }

    fun retainTrackPointCache(activeTrackIds: Set<String>) {
        synchronized(trackPointCache) {
            trackPointCache.keys.retainAll(activeTrackIds)
        }
    }

    fun pause() {
        pulseAnimator?.cancel()
        contourManager.cancelPending()
    }

    fun destroy() {
        pinRenderSeq++
        trackRenderSeq++
        styleLoadSeq++
        pulseAnimator?.cancel()
        pulseAnimator = null
        synchronized(trackPointCache) {
            trackPointCache.clear()
            mvtTileScope.cancel()
        requestedMvtTiles.clear()
        RustCoreBridge.clearGeoPinsCache()
        }
        map = null
    }

    private fun styleCacheFile(night: Boolean): File {
        val dir = File(context.filesDir, "styles").apply { mkdirs() }
        return File(dir, if (night) "vector_night.json" else "vector_day.json")
    }

    private fun fetchAndCacheVectorStyleJson(styleUrl: String, cacheFile: File): String? {
        return runCatching {
            val req = Request.Builder().url(styleUrl).get().build()
            RoutingEngine.mapTileOkHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return@use null
                val cleaned = sanitizeStyleJson(raw)
                runCatching {
                    val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                    tmp.writeText(cleaned, Charsets.UTF_8)
                    if (!tmp.renameTo(cacheFile)) {
                        tmp.copyTo(cacheFile, overwrite = true)
                        tmp.delete()
                    }
                }
                cleaned
            }
        }.getOrNull()
    }

    private fun applyMapStyleBuilder(m: MapLibreMap, builder: Style.Builder, night: Boolean, seq: Long) {
        if (!isActivityAlive() || seq != styleLoadSeq || map !== m) return
        ensureCameraIdleListener(m)
        m.setStyle(builder) { style ->
            if (!isActivityAlive() || seq != styleLoadSeq || map !== m) return@setStyle
            lastRoutePillKey = null
            lastPinsRenderKey = null
            lastTracksRenderKey = null
            apply3dBuildingsToggle(style)
            MapCartography.registerMapBitmaps(context, style, night)
            if (viewModel.mapStyleMode == MapStyleMode.VECTOR) {
                MapCartography.applyOrganicCartography(context, style, night)
                attachTerrainHillshade(style)
                contourManager.attachLayers(style, night, DevicePrefs.isContoursEnabled(context))
            }
            attachOverlayLayers(style)
            renderMapData()
            onFlushPendingZoom()
            contourManager.scheduleUpdate(m, DevicePrefs.isContoursEnabled(context), viewModel.mapStyleMode == MapStyleMode.VECTOR)
        }
    }

    fun loadMapStyle() {
        val m = map ?: return
        pulseAnimator?.cancel()
        pulseAnimator = null
        contourManager.cancelPending()
        val night = isDarkMapUi()
        val seq = ++styleLoadSeq
        if (viewModel.mapStyleMode == MapStyleMode.SATELLITE) {
            applyMapStyleBuilder(m, Style.Builder().fromJson(STYLE_SATELLITE), night, seq)
            return
        }
        val styleUrl = if (night) OfflineMapManager.STYLE_DARK_URL else OfflineMapManager.STYLE_STREET_URL
        AppExecutors.batchIoExecutor.execute {
            val cacheFile = styleCacheFile(night)
            val cachedRaw = if (cacheFile.exists() && cacheFile.length() > 128L) {
                runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
            } else null

            if (cachedRaw != null) {
                val cleaned = sanitizeStyleJson(cachedRaw)
                if (cleaned != cachedRaw) {
                    runCatching { cacheFile.writeText(cleaned, Charsets.UTF_8) }
                }
                AppExecutors.postMain {
                    applyMapStyleBuilder(m, Style.Builder().fromJson(cleaned), night, seq)
                }
                if (System.currentTimeMillis() - cacheFile.lastModified() > STYLE_CACHE_MAX_AGE_MS) {
                    fetchAndCacheVectorStyleJson(styleUrl, cacheFile)
                }
                return@execute
            }

            val fetched = fetchAndCacheVectorStyleJson(styleUrl, cacheFile)
            val builder = if (fetched != null) {
                Style.Builder().fromJson(fetched)
            } else {
                Style.Builder().fromUri(styleUrl)
            }
            AppExecutors.postMain {
                applyMapStyleBuilder(m, builder, night, seq)
            }
        }
    }

    fun apply3dBuildingsToggle(style: Style) {
        val show3d = DevicePrefs.is3dBuildingsEnabled(context)
        val vis = if (show3d) Property.VISIBLE else Property.NONE
        for (layer in MapCartography.getBuilding3dLayers(style)) {
            layer.setProperties(PropertyFactory.visibility(vis))
        }
    }

    fun applyHillshadeSettings(style: Style) {
        val enabled = DevicePrefs.isHillshadeEnabled(context) && viewModel.mapStyleMode == MapStyleMode.VECTOR
        val intensity = DevicePrefs.getHillshadeIntensity(context)
        val night = isDarkMapUi()
        val existing = style.getLayerAs<HillshadeLayer>(HILLSHADE_LAYER)
        if (existing != null) {
            existing.setProperties(
                PropertyFactory.visibility(if (enabled) Property.VISIBLE else Property.NONE),
                PropertyFactory.hillshadeExaggeration(if (night) intensity.exaggeration * 0.55f else intensity.exaggeration),
                PropertyFactory.hillshadeShadowColor(Color.parseColor(if (night) "#0A140D" else "#3C5E2B")),
                PropertyFactory.hillshadeHighlightColor(Color.parseColor(if (night) "#2A3E31" else "#FFFFFF")),
                PropertyFactory.hillshadeAccentColor(Color.parseColor(if (night) "#1F3527" else "#5D8346"))
            )
        } else if (enabled) {
            attachTerrainHillshade(style)
        }
    }

    private fun attachTerrainHillshade(style: Style) {
        if (viewModel.mapStyleMode != MapStyleMode.VECTOR || !DevicePrefs.isHillshadeEnabled(context)) return
        runCatching {
            if (map?.style !== style || !style.isFullyLoaded) return
            if (style.getSource(HILLSHADE_SOURCE) == null) {
                val tileSet = TileSet(
                    "2.2.0",
                    "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
                ).apply {
                    encoding = "terrarium"
                    minZoom = 0f
                    maxZoom = 14f
                }
                style.addSource(RasterDemSource(HILLSHADE_SOURCE, tileSet, 256))
            }
            if (style.getLayer(HILLSHADE_LAYER) == null) {
                val night = isDarkMapUi()
                val intensity = DevicePrefs.getHillshadeIntensity(context)
                val hill = HillshadeLayer(HILLSHADE_LAYER, HILLSHADE_SOURCE).withProperties(
                    PropertyFactory.hillshadeExaggeration(if (night) intensity.exaggeration * 0.55f else intensity.exaggeration),
                    PropertyFactory.hillshadeShadowColor(Color.parseColor(if (night) "#0A140D" else "#3C5E2B")),
                    PropertyFactory.hillshadeHighlightColor(Color.parseColor(if (night) "#2A3E31" else "#FFFFFF")),
                    PropertyFactory.hillshadeAccentColor(Color.parseColor(if (night) "#1F3527" else "#5D8346")),
                    PropertyFactory.hillshadeIlluminationDirection(315f)
                )
                val belowLayer = style.layers.firstOrNull { l ->
                    val id = l.id.lowercase()
                    id.contains("water") || id.contains("road") || id.contains("highway") || id.contains("building") || id.contains("contours")
                }
                if (belowLayer != null) style.addLayerBelow(hill, belowLayer.id) else style.addLayer(hill)
            } else {
                applyHillshadeSettings(style)
            }
        }
    }

    private fun attachOverlayLayers(style: Style) {
        if (style.getLayer(TRACKS_LAYER) == null) {
            if (ensureMvtPinsSource(style)) {
                style.addLayer(
                    LineLayer(TRACKS_LAYER, PINS_MVT_SOURCE).apply {
                        setSourceLayer(TRACKS_MVT_SOURCE_LAYER)
                        setProperties(
                            PropertyFactory.lineColor("#06B6D4"),
                            PropertyFactory.lineWidth(4f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                        )
                    }
                )
            } else {
                if (style.getSource(TRACKS_SOURCE) == null) {
                    style.addSource(GeoJsonSource(TRACKS_SOURCE))
                }
                style.addLayer(
                    LineLayer(TRACKS_LAYER, TRACKS_SOURCE).withProperties(
                        PropertyFactory.lineColor("#06B6D4"),
                        PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )
            }
        }

        if (style.getSource(PULSE_SOURCE) == null) {
            style.addSource(GeoJsonSource(PULSE_SOURCE))
            style.addLayer(
                CircleLayer(PULSE_LAYER, PULSE_SOURCE).withProperties(
                    PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleStrokeColor(Expression.get("color")),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeOpacity(0f),
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleOpacity(0f)
                )
            )
        }

        attachPinsSourceAndLayers(style)

        if (style.getSource(SELECTED_SOURCE) == null) {
            style.addSource(GeoJsonSource(SELECTED_SOURCE))
            val haloLayer = CircleLayer(SELECTED_HALO_LAYER, SELECTED_SOURCE).withProperties(
                PropertyFactory.circleColor("#5AA8E8"),
                PropertyFactory.circleOpacity(0.90f),
                PropertyFactory.circleRadius(15.5f),
                PropertyFactory.circleStrokeWidth(0f),
                PropertyFactory.circleStrokeOpacity(0f)
            )
            if (style.getLayer(PINS_LAYER) != null) {
                style.addLayerBelow(haloLayer, PINS_LAYER)
            } else {
                style.addLayer(haloLayer)
            }
        }
        if (style.getSource(ROUTE_SOURCE) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE))
            style.addLayer(
                LineLayer(ROUTE_CASING_LAYER, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#FFFFFF"),
                    PropertyFactory.lineWidth(9.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
            style.addLayer(
                LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(viewModel.travelMode.routeColorHex),
                    PropertyFactory.lineWidth(6.2f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
            style.addLayer(
                LineLayer(ROUTE_WALK_CASING_LAYER, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#FFFFFF"),
                    PropertyFactory.lineWidth(7.2f),
                    PropertyFactory.lineDasharray(arrayOf(1.6f, 1.3f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.visibility(Property.NONE)
                )
            )
            style.addLayer(
                LineLayer(ROUTE_WALK_LAYER, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(TravelMode.WALKING.routeColorHex),
                    PropertyFactory.lineWidth(4.8f),
                    PropertyFactory.lineDasharray(arrayOf(2.4f, 1.8f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.visibility(Property.NONE)
                )
            )
        }

        if (style.getSource(ROUTE_PILL_SOURCE) == null) {
            style.addSource(GeoJsonSource(ROUTE_PILL_SOURCE))
            style.addLayer(
                SymbolLayer(ROUTE_PILL_LAYER, ROUTE_PILL_SOURCE).withProperties(
                    PropertyFactory.iconImage(ROUTE_PILL_ICON),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true)
                )
            )
        }

        if (style.getSource(SELECTED_PIN_SOURCE) == null) {
            style.addSource(GeoJsonSource(SELECTED_PIN_SOURCE))
            val selectedPinLayer = SymbolLayer(SELECTED_PIN_LAYER, SELECTED_PIN_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconOffset(arrayOf(0f, MapBitmaps.BOOKMARK_PIN_TIP_BOTTOM_PAD_DP)),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
            when {
                style.getLayer(ROUTE_PILL_LAYER) != null ->
                    style.addLayerAbove(selectedPinLayer, ROUTE_PILL_LAYER)
                style.getLayer(PINS_CLUSTER_COUNT_LAYER) != null ->
                    style.addLayerAbove(selectedPinLayer, PINS_CLUSTER_COUNT_LAYER)
                style.getLayer(PINS_LAYER) != null ->
                    style.addLayerAbove(selectedPinLayer, PINS_LAYER)
                else -> style.addLayer(selectedPinLayer)
            }
        }
        if (style.getSource(SELECTED_COORD_SOURCE) == null) {
            style.addSource(GeoJsonSource(SELECTED_COORD_SOURCE))
            val coordLayer = CircleLayer(SELECTED_COORD_LAYER, SELECTED_COORD_SOURCE).withProperties(
                PropertyFactory.circleColor("#5AA8E8"),
                PropertyFactory.circleOpacity(0.90f),
                PropertyFactory.circleRadius(14.5f),
                PropertyFactory.circleStrokeWidth(0f),
                PropertyFactory.circleStrokeOpacity(0f)
            )
            if (style.getLayer(SELECTED_PIN_LAYER) != null) {
                style.addLayerAbove(coordLayer, SELECTED_PIN_LAYER)
            } else {
                style.addLayer(coordLayer)
            }
        }

        if (style.getSource(RULER_LINE_SOURCE) == null) {
            style.addSource(GeoJsonSource(RULER_LINE_SOURCE))
            style.addLayer(
                LineLayer(RULER_LINE_CASING_LAYER, RULER_LINE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#FFFFFF"),
                    PropertyFactory.lineOpacity(0.90f),
                    PropertyFactory.lineWidth(6.4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
            style.addLayer(
                LineLayer(RULER_LINE_LAYER, RULER_LINE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#F0651F"),
                    PropertyFactory.lineWidth(3.8f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }
        if (style.getSource(RULER_POINTS_SOURCE) == null) {
            style.addSource(GeoJsonSource(RULER_POINTS_SOURCE))
            style.addLayer(
                CircleLayer(RULER_POINTS_CIRCLE_LAYER, RULER_POINTS_SOURCE).withProperties(
                    PropertyFactory.circleColor("#FFFFFF"),
                    PropertyFactory.circleRadius(12.5f),
                    PropertyFactory.circleStrokeColor("#F0651F"),
                    PropertyFactory.circleStrokeWidth(2.8f)
                )
            )
            style.addLayer(
                SymbolLayer(RULER_POINTS_NUM_LAYER, RULER_POINTS_SOURCE).withProperties(
                    PropertyFactory.textField(Expression.get("num")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textColor("#B8440E"),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true)
                )
            )
        }

        if (style.getSource(USER_LOC_SOURCE) == null) {
            style.addSource(GeoJsonSource(USER_LOC_SOURCE))
            style.addLayer(
                SymbolLayer(USER_LOC_LAYER, USER_LOC_SOURCE).withProperties(
                    PropertyFactory.iconImage("gps-arrow"),
                    PropertyFactory.iconRotate(Expression.get("bearing")),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true)
                )
            )
        }
    }

    private fun attachPinsSourceAndLayers(style: Style) {
        val useRustMvt = RustCoreBridge.isAvailable()
        if (style.getLayer(PINS_LAYER) != null) return
        val darkLabels = isDarkMapUi() || viewModel.mapStyleMode == MapStyleMode.SATELLITE
        val labelColor = if (darkLabels) "#F8FAFC" else "#0F172A"
        val haloColor = if (darkLabels) "#0F172A" else "#FFFFFF"
        val showTitles = DevicePrefs.isShowBookmarkTitles(context)
        val clustered = DevicePrefs.isClusterDensePins(context)

        val unclusteredSrcId: String
        val clusteredSrcId: String
        if (useRustMvt) {
            ensureMvtPinsSource(style)
            unclusteredSrcId = PINS_MVT_SOURCE
            clusteredSrcId = PINS_MVT_SOURCE
        } else {
            style.addSource(GeoJsonSource(PINS_SOURCE, FeatureCollection.fromFeatures(emptyList<Feature>())))
            style.addSource(
                GeoJsonSource(
                    PINS_CLUSTER_SOURCE,
                    FeatureCollection.fromFeatures(emptyList<Feature>()),
                    GeoJsonOptions()
                        .withCluster(true)
                        .withClusterMaxZoom(15)
                        .withClusterRadius(48)
                        .withTolerance(0.5f)
                )
            )
            unclusteredSrcId = PINS_SOURCE
            clusteredSrcId = PINS_CLUSTER_SOURCE
        }

        fun makePinLabelLayer(layerId: String, srcId: String) = SymbolLayer(layerId, srcId).apply {
            if (useRustMvt) setSourceLayer(PINS_MVT_SOURCE_LAYER)
            setProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(10.5f),
                PropertyFactory.textMaxWidth(9.0f),
                PropertyFactory.textLineHeight(1.05f),
                PropertyFactory.textPadding(6f),
                PropertyFactory.textColor(labelColor),
                PropertyFactory.textHaloColor(haloColor),
                PropertyFactory.textHaloWidth(1.8f),
                PropertyFactory.textOffset(arrayOf(0f, 0.25f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textIgnorePlacement(false),
                PropertyFactory.textOptional(true)
            )
            minZoom = 13.5f
        }

        fun makePinIconLayer(layerId: String, srcId: String) = SymbolLayer(layerId, srcId).apply {
            if (useRustMvt) setSourceLayer(PINS_MVT_SOURCE_LAYER)
            setProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconOffset(arrayOf(0f, MapBitmaps.BOOKMARK_PIN_TIP_BOTTOM_PAD_DP)),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        }

        val labelLayer = makePinLabelLayer(PINS_LABELS_LAYER, unclusteredSrcId)
        val clusterLabelLayer = makePinLabelLayer(PINS_CLUSTER_LABELS_LAYER, clusteredSrcId).apply {
            setFilter(Expression.not(Expression.has("point_count")))
        }
        val pinLayer = makePinIconLayer(PINS_LAYER, unclusteredSrcId)
        val clusterPinLayer = makePinIconLayer(PINS_CLUSTER_UNCLUSTERED_LAYER, clusteredSrcId).apply {
            setFilter(Expression.not(Expression.has("point_count")))
        }
        val clusterCircleLayer = CircleLayer(PINS_CLUSTER_CIRCLE_LAYER, clusteredSrcId).apply {
            if (useRustMvt) setSourceLayer(PINS_MVT_SOURCE_LAYER)
            setFilter(Expression.has("point_count"))
            setProperties(
                PropertyFactory.circleColor(Color.parseColor("#18794E")),
                PropertyFactory.circleRadius(
                    Expression.switchCase(
                        Expression.gte(Expression.toNumber(Expression.get("point_count")), Expression.literal(250)),
                        Expression.literal(25f),
                        Expression.gte(Expression.toNumber(Expression.get("point_count")), Expression.literal(50)),
                        Expression.literal(21f),
                        Expression.literal(17f)
                    )
                ),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circleOpacity(0.95f)
            )
        }
        val clusterCountLayer = SymbolLayer(PINS_CLUSTER_COUNT_LAYER, clusteredSrcId).apply {
            if (useRustMvt) setSourceLayer(PINS_MVT_SOURCE_LAYER)
            setFilter(Expression.has("point_count"))
            setProperties(
                PropertyFactory.textField(
                    Expression.coalesce(
                        Expression.get("point_count_abbreviated"),
                        Expression.toString(Expression.get("point_count"))
                    )
                ),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(12.5f),
                PropertyFactory.textColor(Color.WHITE),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true)
            )
        }

        if (style.getLayer(SELECTED_HALO_LAYER) != null) {
            style.addLayerBelow(labelLayer, SELECTED_HALO_LAYER)
            style.addLayerBelow(clusterLabelLayer, SELECTED_HALO_LAYER)
            style.addLayerAbove(pinLayer, SELECTED_HALO_LAYER)
        } else {
            style.addLayer(labelLayer)
            style.addLayer(clusterLabelLayer)
            style.addLayer(pinLayer)
        }
        style.addLayerAbove(clusterPinLayer, PINS_LAYER)
        style.addLayerAbove(clusterCircleLayer, PINS_CLUSTER_UNCLUSTERED_LAYER)
        style.addLayerAbove(clusterCountLayer, PINS_CLUSTER_CIRCLE_LAYER)

        applyPinClusterModeVisibility(style, clustered, showTitles)
    }

    private fun applyPinClusterModeVisibility(style: Style, clustered: Boolean, showTitles: Boolean) {
        val visUnclustered = if (!clustered) Property.VISIBLE else Property.NONE
        val visUnclusteredLabels = if (!clustered && showTitles) Property.VISIBLE else Property.NONE
        val visClustered = if (clustered) Property.VISIBLE else Property.NONE
        val visClusteredLabels = if (clustered && showTitles) Property.VISIBLE else Property.NONE
        style.getLayer(PINS_LAYER)?.setProperties(PropertyFactory.visibility(visUnclustered))
        style.getLayer(PINS_LABELS_LAYER)?.setProperties(PropertyFactory.visibility(visUnclusteredLabels))
        style.getLayer(PINS_CLUSTER_UNCLUSTERED_LAYER)?.setProperties(PropertyFactory.visibility(visClustered))
        style.getLayer(PINS_CLUSTER_LABELS_LAYER)?.setProperties(PropertyFactory.visibility(visClusteredLabels))
        style.getLayer(PINS_CLUSTER_CIRCLE_LAYER)?.setProperties(PropertyFactory.visibility(visClustered))
        style.getLayer(PINS_CLUSTER_COUNT_LAYER)?.setProperties(PropertyFactory.visibility(visClustered))
    }

    fun getVisiblePins(): List<PinItem> {
        val hiddenLayerIds = DevicePrefs.getHiddenLayerIds(context)
        val sentIds = if (viewModel.hideSentPins) DevicePrefs.getLocalSentPinIds(context) else emptySet()
        return viewModel.folderSnapshot.pins.filter { p ->
            val normStatus = PinStatus.fromRaw(p.status).key
            p.layerId !in hiddenLayerIds && normStatus in viewModel.visibleStatuses && p.id !in sentIds
        }
    }

    private fun parseTrackPointsCached(t: TrackItem): List<Point> = synchronized(trackPointCache) {
        val cached = trackPointCache[t.id]
        if (cached != null && cached.first == t.updatedAt) return cached.second
        val pts = ArrayList<Point>()
        val raw = t.coords
        var start = 0
        val len = raw.length
        while (start < len) {
            val semi = raw.indexOf(';', start).let { if (it < 0) len else it }
            val comma = raw.indexOf(',', start)
            if (comma in (start + 1) until semi) {
                val lng = raw.substring(start, comma).trim().toDoubleOrNull()
                val lat = raw.substring(comma + 1, semi).trim().toDoubleOrNull()
                if (lng != null && lat != null && lat.isFinite() && lng.isFinite()) {
                    pts.add(Point.fromLngLat(lng, lat))
                }
            }
            start = semi + 1
        }
        trackPointCache[t.id] = t.updatedAt to pts
        pts
    }

    private fun renderPinsAndTracks(style: Style) {
        val curMap = map
        if (curMap != null) ensureCameraIdleListener(curMap)
        val snap = viewModel.folderSnapshot
        val hiddenLayerIds = DevicePrefs.getHiddenLayerIds(context)
        val sentIds = if (viewModel.hideSentPins) DevicePrefs.getLocalSentPinIds(context) else emptySet()
        val statusesSnap = viewModel.visibleStatuses.toSet()
        val clustered = DevicePrefs.isClusterDensePins(context)
        val showTitles = DevicePrefs.isShowBookmarkTitles(context)

        applyPinClusterModeVisibility(style, clustered, showTitles)

        val useViewport = RustCoreBridge.isAvailable() && snap.pins.size > 1500
        var vpS = -90.0
        var vpW = -180.0
        var vpN = 90.0
        var vpE = 180.0
        var vpZoom = 14.0
        var validVp = false
        if (useViewport && curMap != null) {
            val bounds = runCatching { curMap.projection.visibleRegion.latLngBounds }.getOrNull()
            vpZoom = curMap.cameraPosition.zoom
            if (bounds != null) {
                val latSpan = (bounds.latitudeNorth - bounds.latitudeSouth).coerceAtLeast(0.002)
                val lngSpan = (bounds.longitudeEast - bounds.longitudeWest).coerceAtLeast(0.002)
                vpS = (bounds.latitudeSouth - latSpan * 0.35).coerceAtLeast(-90.0)
                vpN = (bounds.latitudeNorth + latSpan * 0.35).coerceAtMost(90.0)
                vpW = (bounds.longitudeWest - lngSpan * 0.35).coerceAtLeast(-180.0)
                vpE = (bounds.longitudeEast + lngSpan * 0.35).coerceAtMost(180.0)
                validVp = vpN > vpS && vpE > vpW
            }
        }
        hasViewportCull = validVp
        if (validVp) {
            lastVpSouth = vpS
            lastVpNorth = vpN
            lastVpWest = vpW
            lastVpEast = vpE
            lastVpZoom = vpZoom
        }

        val useMvtPins = RustCoreBridge.isAvailable() && style.getSource(PINS_MVT_SOURCE) != null
        val pinsKey = "${snap.dataVersion}|$clustered|$showTitles|${statusesSnap.hashCode()}|${statusesSnap.size}|${hiddenLayerIds.hashCode()}|${hiddenLayerIds.size}|${viewModel.hideSentPins}|${sentIds.hashCode()}|${sentIds.size}"
        if (pinsKey != lastPinsRenderKey) {
            lastPinsRenderKey = pinsKey
            val seq = ++pinRenderSeq
            val pinsSnap = snap.pins
            if (useMvtPins) {
                val tracksSnap = snap.tracks
                val filterSig = "$clustered|$showTitles|${statusesSnap.hashCode()}|${statusesSnap.size}|${hiddenLayerIds.hashCode()}|${hiddenLayerIds.size}|${viewModel.hideSentPins}|${sentIds.hashCode()}|${sentIds.size}|${tracksSnap.size}"
                val allowDelta = (lastMvtFilterSignature == filterSig)
                lastMvtFilterSignature = filterSig
                lastTracksRenderKey = "${snap.dataVersion}|${hiddenLayerIds.hashCode()}|${hiddenLayerIds.size}"
                AppExecutors.batchIoExecutor.execute {
                    RustCoreBridge.ensureGeoPinsSynced(snap.dataVersion, pinsSnap)
                    RustCoreBridge.ensureGeoTracksSynced(snap.dataVersion, tracksSnap)
                    RustCoreBridge.setMvtPinsFilter(statusesSnap, clustered, showTitles, hiddenLayerIds, sentIds)
                    AppExecutors.postMain {
                        if (isActivityAlive() && seq == pinRenderSeq && map?.style === style && style.isFullyLoaded) {
                            applyPinClusterModeVisibility(style, clustered, showTitles)
                            invalidateTrackedMvtTiles(style, snap.dataVersion, allowDelta)
                        }
                    }
                }
            } else {
            val selPinId = viewModel.selectedPinId
            val capS = vpS; val capW = vpW; val capN = vpN; val capE = vpE
            val capZoom = vpZoom; val capUseVp = validVp
            AppExecutors.batchIoExecutor.execute {
                val nativePinsJson = RustCoreBridge.buildPinsGeoJsonViewport(
                    version = snap.dataVersion,
                    pins = pinsSnap,
                    hiddenLayerIds = hiddenLayerIds,
                    sentIds = sentIds,
                    visibleStatuses = statusesSnap,
                    useViewport = capUseVp,
                    south = capS,
                    west = capW,
                    north = capN,
                    east = capE,
                    zoom = capZoom,
                    clustered = clustered,
                    maxUnclusteredPins = 2400,
                    selectedPinId = selPinId
                )
                if (nativePinsJson != null) {
                    val emptyJson = "{\"type\":\"FeatureCollection\",\"features\":[]}"
                    AppExecutors.postMain {
                        if (isActivityAlive() && seq == pinRenderSeq && map?.style === style && style.isFullyLoaded) {
                            applyPinClusterModeVisibility(style, clustered, showTitles)
                            if (clustered) {
                                style.getSourceAs<GeoJsonSource>(PINS_CLUSTER_SOURCE)?.setGeoJson(nativePinsJson)
                                style.getSourceAs<GeoJsonSource>(PINS_SOURCE)?.setGeoJson(emptyJson)
                            } else {
                                style.getSourceAs<GeoJsonSource>(PINS_SOURCE)?.setGeoJson(nativePinsJson)
                                style.getSourceAs<GeoJsonSource>(PINS_CLUSTER_SOURCE)?.setGeoJson(emptyJson)
                            }
                        }
                    }
                    return@execute
                }

                val doneFeatures = ArrayList<Feature>(pinsSnap.size / 2)
                val issueFeatures = ArrayList<Feature>(pinsSnap.size / 4)
                val pendingFeatures = ArrayList<Feature>(pinsSnap.size / 4)
                fun toPinFeature(pin: PinItem, st: PinStatus): Feature {
                    val trimmed = pin.name.trim()
                    val shortLabel = if (trimmed.length > 22) trimmed.take(21).trimEnd() + "…" else trimmed
                    return Feature.fromGeometry(Point.fromLngLat(pin.lng, pin.lat)).apply {
                        addStringProperty("id", pin.id)
                        addStringProperty("name", pin.name)
                        addStringProperty("label", shapeKurdishAeForMapLibre(shortLabel))
                        addStringProperty("icon", st.iconId)
                    }
                }
                for (pin in pinsSnap) {
                    if (pin.layerId in hiddenLayerIds || pin.id in sentIds) continue
                    val st = PinStatus.fromRaw(pin.status)
                    if (st.key !in statusesSnap) continue
                    val f = toPinFeature(pin, st)
                    when (st) {
                        PinStatus.DONE -> doneFeatures.add(f)
                        PinStatus.ISSUE -> issueFeatures.add(f)
                        PinStatus.PENDING -> pendingFeatures.add(f)
                    }
                }
                val pinFeatures = ArrayList<Feature>(doneFeatures.size + issueFeatures.size + pendingFeatures.size)
                pinFeatures.addAll(doneFeatures)
                pinFeatures.addAll(issueFeatures)
                pinFeatures.addAll(pendingFeatures)
                val fc = FeatureCollection.fromFeatures(pinFeatures)
                val emptyFc = FeatureCollection.fromFeatures(emptyList<Feature>())
                AppExecutors.postMain {
                    if (isActivityAlive() && seq == pinRenderSeq && map?.style === style && style.isFullyLoaded) {
                        applyPinClusterModeVisibility(style, clustered, showTitles)
                        if (clustered) {
                            style.getSourceAs<GeoJsonSource>(PINS_CLUSTER_SOURCE)?.setGeoJson(fc)
                            style.getSourceAs<GeoJsonSource>(PINS_SOURCE)?.setGeoJson(emptyFc)
                        } else {
                            style.getSourceAs<GeoJsonSource>(PINS_SOURCE)?.setGeoJson(fc)
                            style.getSourceAs<GeoJsonSource>(PINS_CLUSTER_SOURCE)?.setGeoJson(emptyFc)
                        }
                    }
                }
            }
                    }
        }
        val tracksKey = "${snap.dataVersion}|${hiddenLayerIds.hashCode()}|${hiddenLayerIds.size}"
        if (!useMvtPins && tracksKey != lastTracksRenderKey) {
            lastTracksRenderKey = tracksKey
            val seqT = ++trackRenderSeq
            val tracksSnap = snap.tracks
            val capS = vpS; val capW = vpW; val capN = vpN; val capE = vpE
            val capZoom = vpZoom; val capUseVp = validVp
            AppExecutors.batchIoExecutor.execute {
                val nativeTracksJson = RustCoreBridge.buildTracksGeoJsonViewport(
                    tracks = tracksSnap,
                    hiddenLayerIds = hiddenLayerIds,
                    useViewport = capUseVp,
                    south = capS,
                    west = capW,
                    north = capN,
                    east = capE,
                    zoom = capZoom
                )
                if (nativeTracksJson != null) {
                    AppExecutors.postMain {
                        if (isActivityAlive() && seqT == trackRenderSeq && map?.style === style && style.isFullyLoaded) {
                            style.getSourceAs<GeoJsonSource>(TRACKS_SOURCE)?.setGeoJson(nativeTracksJson)
                        }
                    }
                    return@execute
                }
                val trackFeatures = ArrayList<Feature>(tracksSnap.size)
                for (t in tracksSnap) {
                    if (t.layerId in hiddenLayerIds) continue
                    val pts = parseTrackPointsCached(t)
                    if (pts.size >= 2) trackFeatures.add(Feature.fromGeometry(LineString.fromLngLats(pts)))
                }
                val fcT = FeatureCollection.fromFeatures(trackFeatures)
                AppExecutors.postMain {
                    if (isActivityAlive() && seqT == trackRenderSeq && map?.style === style && style.isFullyLoaded) {
                        style.getSourceAs<GeoJsonSource>(TRACKS_SOURCE)?.setGeoJson(fcT)
                    }
                }
            }
        }
    }

    fun renderRouteOverlay(styleArg: Style? = null) {
        val style = styleArg ?: map?.style ?: return
        if (!style.isFullyLoaded) return
        val activeRoute = viewModel.activeNavRoute
        val prevRoute = viewModel.previewRoute
        val routeToShow = activeRoute ?: prevRoute
        val trimmedGeom = getActiveNavTrimmedGeom()
        val displayGeom = if (activeRoute != null && (trimmedGeom?.size ?: 0) >= 2) {
            trimmedGeom!!
        } else {
            routeToShow?.geometry ?: emptyList()
        }
        val routeFeatures = if (routeToShow != null && displayGeom.size >= 2) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(displayGeom.map { Point.fromLngLat(it.first, it.second) })))
        } else emptyList()
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(routeFeatures))
        if (routeToShow != null) {
            val isWalk = routeToShow.mode == TravelMode.WALKING
            style.getLayerAs<LineLayer>(ROUTE_CASING_LAYER)?.setProperties(
                PropertyFactory.lineWidth(9.5f),
                PropertyFactory.lineOpacity(1.0f),
                PropertyFactory.visibility(if (isWalk) Property.NONE else Property.VISIBLE)
            )
            style.getLayerAs<LineLayer>(ROUTE_LAYER)?.setProperties(
                PropertyFactory.lineColor(routeToShow.mode.routeColorHex),
                PropertyFactory.visibility(if (isWalk) Property.NONE else Property.VISIBLE)
            )
            style.getLayerAs<LineLayer>(ROUTE_WALK_CASING_LAYER)?.setProperties(
                PropertyFactory.visibility(if (isWalk) Property.VISIBLE else Property.NONE)
            )
            style.getLayerAs<LineLayer>(ROUTE_WALK_LAYER)?.setProperties(
                PropertyFactory.lineColor(routeToShow.mode.routeColorHex),
                PropertyFactory.visibility(if (isWalk) Property.VISIBLE else Property.NONE)
            )
        }

        val pillRoute = if (activeRoute == null) prevRoute else null
        val pillFeatures = if (pillRoute != null && pillRoute.geometry.size >= 2) {
            val pillLabel = formatEtaDuration(pillRoute.totalDurationSeconds)
            val pillKey = "$pillLabel|${pillRoute.mode.routeColorHex}"
            if (lastRoutePillKey != pillKey) {
                val pillBmp = MapBitmaps.createRoutePill(context, pillLabel, pillRoute.mode.routeColorHex)
                try {
                    style.addImage(ROUTE_PILL_ICON, pillBmp)
                } finally {
                    if (!pillBmp.isRecycled) pillBmp.recycle()
                }
                lastRoutePillKey = pillKey
            }
            val (midLng, midLat) = routeMidpoint(pillRoute.geometry)
            listOf(Feature.fromGeometry(Point.fromLngLat(midLng, midLat)))
        } else emptyList()
        style.getSourceAs<GeoJsonSource>(ROUTE_PILL_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(pillFeatures))
    }

    fun renderRulerOverlay(styleArg: Style? = null) {
        val style = styleArg ?: map?.style ?: return
        if (!style.isFullyLoaded) return
        val rulerPts = getRulerGeoPoints()
        val rulerLineFeatures = if (rulerPts.size >= 2) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(rulerPts.map { Point.fromLngLat(it.lng, it.lat) })))
        } else emptyList()
        style.getSourceAs<GeoJsonSource>(RULER_LINE_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(rulerLineFeatures))

        val rulerPointFeatures = rulerPts.mapIndexed { idx, pt ->
            Feature.fromGeometry(Point.fromLngLat(pt.lng, pt.lat)).apply {
                addStringProperty("num", "${idx + 1}")
            }
        }
        style.getSourceAs<GeoJsonSource>(RULER_POINTS_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(rulerPointFeatures))
    }

    fun renderSelectedMarker(styleArg: Style? = null) {
        val style = styleArg ?: map?.style ?: return
        if (!style.isFullyLoaded) return
        val selId = viewModel.selectedPinId
        val selPin = selId?.let { viewModel.folderSnapshot.pinById[it] }?.takeIf { pin ->
            val hiddenLayerIds = DevicePrefs.getHiddenLayerIds(context)
            val st = PinStatus.fromRaw(pin.status)
            pin.layerId !in hiddenLayerIds &&
                st.key in viewModel.visibleStatuses &&
                (!viewModel.hideSentPins || !DevicePrefs.isPinLocalSent(context, pin.id))
        }
        val haloFeatures: List<Feature>
        val topPinFeatures: List<Feature>
        val coordFeatures: List<Feature>
        if (selPin != null) {
            val pt = Point.fromLngLat(selPin.lng, selPin.lat)
            haloFeatures = listOf(Feature.fromGeometry(pt))
            topPinFeatures = listOf(
                Feature.fromGeometry(pt).apply {
                    addStringProperty("id", selPin.id)
                    addStringProperty("name", selPin.name)
                    addStringProperty("icon", PinStatus.fromRaw(selPin.status).iconId)
                }
            )
            coordFeatures = emptyList()
        } else {
            val selLoc = viewModel.selectedMarkerGeoPoint
            haloFeatures = emptyList()
            topPinFeatures = emptyList()
            coordFeatures = if (selLoc != null) {
                listOf(Feature.fromGeometry(Point.fromLngLat(selLoc.lng, selLoc.lat)))
            } else emptyList()
        }
        style.getSourceAs<GeoJsonSource>(SELECTED_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(haloFeatures))
        style.getSourceAs<GeoJsonSource>(SELECTED_PIN_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(topPinFeatures))
        style.getSourceAs<GeoJsonSource>(SELECTED_COORD_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(coordFeatures))
    }

    fun renderMapData() {
        onUpdateSyncBadge()

        val style = map?.style ?: return
        if (!style.isFullyLoaded) return

        renderPinsAndTracks(style)
        renderRouteOverlay(style)
        renderRulerOverlay(style)
        renderSelectedMarker(style)

        getGpsDisplayPose()?.let { (lat, lng, bearing) ->
            updateGpsLayerFeature(lat, lng, bearing)
        }
    }

    private fun routeMidpoint(geom: List<Pair<Double, Double>>): Pair<Double, Double> {
        if (geom.size <= 2) return geom.first()
        var total = 0.0
        val segs = DoubleArray(geom.size - 1)
        for (i in 0 until geom.size - 1) {
            val d = RoutingEngine.haversineMeters(geom[i].second, geom[i].first, geom[i + 1].second, geom[i + 1].first)
            segs[i] = d
            total += d
        }
        val half = total * 0.5
        var acc = 0.0
        for (i in segs.indices) {
            if (acc + segs[i] >= half && segs[i] > 0.0) {
                val t = ((half - acc) / segs[i]).coerceIn(0.0, 1.0)
                val lng = geom[i].first + (geom[i + 1].first - geom[i].first) * t
                val lat = geom[i].second + (geom[i + 1].second - geom[i].second) * t
                return lng to lat
            }
            acc += segs[i]
        }
        return geom[geom.size / 2]
    }

    fun updateActiveRouteLine(geom: List<Pair<Double, Double>>) {
        if (!isActivityStarted()) return
        val style = map?.style ?: return
        if (!style.isFullyLoaded || geom.size < 2) return
        val feature = Feature.fromGeometry(LineString.fromLngLats(geom.map { Point.fromLngLat(it.first, it.second) }))
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(listOf(feature)))
    }

    fun updateGpsLayerFeature(lat: Double, lng: Double, bearing: Float) {
        if (!isActivityStarted()) return
        val style = map?.style ?: return
        if (!style.isFullyLoaded) return
        val f = Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply {
            addNumberProperty("bearing", bearing)
        }
        style.getSourceAs<GeoJsonSource>(USER_LOC_SOURCE)
            ?.setGeoJson(FeatureCollection.fromFeatures(listOf(f)))
    }

    fun fireStatusPulse(lat: Double, lng: Double, statusHex: String) {
        if (!isActivityResumed() || UiAnimations.isReducedMotion(context)) return
        val style = map?.style ?: return
        if (!style.isFullyLoaded) return
        val f = Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply {
            addStringProperty("color", statusHex)
        }
        style.getSourceAs<GeoJsonSource>(PULSE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(listOf(f)))
        if (style.getLayerAs<CircleLayer>(PULSE_LAYER) == null) return

        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520L
            interpolator = UiAnimations.EMPHASIZED_DECELERATE
            addUpdateListener { anim ->
                val curStyle = map?.style
                if (!isActivityStarted() || curStyle !== style || !curStyle.isFullyLoaded) {
                    anim.cancel()
                    return@addUpdateListener
                }
                val v = anim.animatedValue as Float
                val fade = 1f - v
                runCatching {
                    curStyle.getLayerAs<CircleLayer>(PULSE_LAYER)?.setProperties(
                        PropertyFactory.circleRadius(8f + 30f * v),
                        PropertyFactory.circleOpacity(0.42f * fade),
                        PropertyFactory.circleStrokeWidth(2.4f - 1.6f * v),
                        PropertyFactory.circleStrokeOpacity(0.85f * fade)
                    )
                }
            }
            start()
        }
    }
}
