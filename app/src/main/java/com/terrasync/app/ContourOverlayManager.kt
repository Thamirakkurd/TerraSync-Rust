package com.terrasync.app

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.Request
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Lightweight, battery-friendly elevation contour line generator (100m minor + 500m/1000m index
 * lines with crisp "500" / "1000" elevation labels) derived on-demand from AWS Terrarium DEM tiles.
 * Only executes after camera idle at zoom >= 10.5 and LRU-caches decoded elevation grids in RAM.
 */
class ContourOverlayManager {

    companion object {
        const val CONTOURS_SOURCE = "terrasync-contours-source"
        const val CONTOURS_MINOR_LAYER = "terrasync-contours-minor-layer"
        const val CONTOURS_MAJOR_LAYER = "terrasync-contours-major-layer"
        const val CONTOURS_LABEL_LAYER = "terrasync-contours-label-layer"

        private const val GRID_N = 36
        private const val MAX_TILES_PER_VIEW = 4
        private const val MIN_CONTOUR_ZOOM = 10.5
        private const val DEBOUNCE_MS = 450L
        private const val DISK_CACHE_MAX_BYTES = 8L * 1024L * 1024L
    }

    private data class TileGrid(val z: Int, val x: Int, val y: Int, val n: Int, val elev: FloatArray)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val contourExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "terrasync-contours").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }
    private val tileCache = LruCache<String, TileGrid>(18)
    @Volatile private var diskCacheDir: File? = null
    private val diskWriteLock = Any()
    private val seq = AtomicInteger(0)

    fun initDiskCache(cacheRoot: File) {
        diskCacheDir = File(cacheRoot, "terrarium_dem").apply { mkdirs() }
    }
    private var pendingRunnable: Runnable? = null
    private var lastViewportKey: String = ""

    fun attachLayers(style: Style, isDark: Boolean, enabled: Boolean) {
        val vis = if (enabled) Property.VISIBLE else Property.NONE
        val minorColor = if (isDark) "#465D4C" else "#8FA57D"
        val majorColor = if (isDark) "#5E7B64" else "#6E885B"
        val labelColor = if (isDark) "#8DA891" else "#5A7249"
        val haloColor = if (isDark) "#14181F" else "#F7F6F2"

        if (style.getSource(CONTOURS_SOURCE) == null) {
            style.addSource(GeoJsonSource(CONTOURS_SOURCE))
        }

        val belowLayer = style.layers.firstOrNull { l ->
            val id = l.id.lowercase()
            id.contains("road") || id.contains("highway") || id.contains("building") || id.contains("tunnel")
        }

        fun addLayerAppropriately(layer: org.maplibre.android.style.layers.Layer) {
            if (belowLayer != null) style.addLayerBelow(layer, belowLayer.id) else style.addLayer(layer)
        }

        if (style.getLayer(CONTOURS_MINOR_LAYER) == null) {
            addLayerAppropriately(
                LineLayer(CONTOURS_MINOR_LAYER, CONTOURS_SOURCE).apply {
                    setFilter(Expression.eq(Expression.get("isMajor"), Expression.literal(false)))
                    setProperties(
                        PropertyFactory.lineColor(minorColor),
                        PropertyFactory.lineWidth(0.75f),
                        PropertyFactory.lineOpacity(if (isDark) 0.34f else 0.36f),
                        PropertyFactory.visibility(vis)
                    )
                }
            )
        } else {
            style.getLayer(CONTOURS_MINOR_LAYER)?.setProperties(
                PropertyFactory.lineColor(minorColor),
                PropertyFactory.visibility(vis)
            )
        }

        if (style.getLayer(CONTOURS_MAJOR_LAYER) == null) {
            addLayerAppropriately(
                LineLayer(CONTOURS_MAJOR_LAYER, CONTOURS_SOURCE).apply {
                    setFilter(Expression.eq(Expression.get("isMajor"), Expression.literal(true)))
                    setProperties(
                        PropertyFactory.lineColor(majorColor),
                        PropertyFactory.lineWidth(1.25f),
                        PropertyFactory.lineOpacity(if (isDark) 0.50f else 0.52f),
                        PropertyFactory.visibility(vis)
                    )
                }
            )
        } else {
            style.getLayer(CONTOURS_MAJOR_LAYER)?.setProperties(
                PropertyFactory.lineColor(majorColor),
                PropertyFactory.visibility(vis)
            )
        }

        if (style.getLayer(CONTOURS_LABEL_LAYER) == null) {
            addLayerAppropriately(
                SymbolLayer(CONTOURS_LABEL_LAYER, CONTOURS_SOURCE).apply {
                    setFilter(Expression.eq(Expression.get("isMajor"), Expression.literal(true)))
                    setProperties(
                        PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                        PropertyFactory.symbolSpacing(280f),
                        PropertyFactory.textField(Expression.get("ele")),
                        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                        PropertyFactory.textSize(9.5f),
                        PropertyFactory.textColor(labelColor),
                        PropertyFactory.textHaloColor(haloColor),
                        PropertyFactory.textHaloWidth(1.4f),
                        PropertyFactory.textAllowOverlap(false),
                        PropertyFactory.visibility(vis)
                    )
                }
            )
        } else {
            style.getLayer(CONTOURS_LABEL_LAYER)?.setProperties(
                PropertyFactory.textColor(labelColor),
                PropertyFactory.textHaloColor(haloColor),
                PropertyFactory.visibility(vis)
            )
        }
    }

    fun setVisible(style: Style?, enabled: Boolean) {
        if (style == null || !style.isFullyLoaded) return
        val vis = if (enabled) Property.VISIBLE else Property.NONE
        style.getLayer(CONTOURS_MINOR_LAYER)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(CONTOURS_MAJOR_LAYER)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(CONTOURS_LABEL_LAYER)?.setProperties(PropertyFactory.visibility(vis))
        if (!enabled) {
            cancelPending()
            lastViewportKey = ""
            style.getSourceAs<GeoJsonSource>(CONTOURS_SOURCE)
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }

    fun cancelPending() {
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRunnable = null
        seq.incrementAndGet()
    }

    fun destroy() {
        cancelPending()
        tileCache.evictAll()
        contourExecutor.shutdownNow()
    }

    fun scheduleUpdate(map: MapLibreMap?, enabled: Boolean, isVectorMode: Boolean) {
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRunnable = null
        if (!enabled || !isVectorMode || map == null) return
        val r = Runnable { triggerUpdate(map) }
        pendingRunnable = r
        mainHandler.postDelayed(r, DEBOUNCE_MS)
    }

    private fun triggerUpdate(map: MapLibreMap) {
        val style = map.style ?: return
        if (!style.isFullyLoaded) return
        val zoom = map.cameraPosition.zoom
        if (zoom < MIN_CONTOUR_ZOOM) {
            if (lastViewportKey.isNotEmpty()) {
                lastViewportKey = ""
                style.getSourceAs<GeoJsonSource>(CONTOURS_SOURCE)
                    ?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            }
            return
        }

        val bounds: LatLngBounds = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull() ?: return
        val tileZ = if (zoom >= 12.6) 11 else 10
        val minX = lngToTileX(bounds.southWest.longitude, tileZ)
        val maxX = lngToTileX(bounds.northEast.longitude, tileZ)
        val minY = latToTileY(bounds.northEast.latitude, tileZ)
        val maxY = latToTileY(bounds.southWest.latitude, tileZ)

        val tileCoords = mutableListOf<Triple<Int, Int, Int>>()
        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                tileCoords.add(Triple(tileZ, x, y))
            }
        }
        val selectedTiles = tileCoords
            .sortedBy { (_, tx, ty) -> (tx - centerX) * (tx - centerX) + (ty - centerY) * (ty - centerY) }
            .take(MAX_TILES_PER_VIEW)

        val stepMeters = if (zoom >= 13.0) 100 else 200
        val majorStep = 500
        val vpKey = "${selectedTiles.joinToString(",")}|$stepMeters"
        if (vpKey == lastViewportKey) return

        val mySeq = seq.incrementAndGet()
        contourExecutor.execute {
            if (seq.get() != mySeq) return@execute
            val features = ArrayList<Feature>(256)
            for ((z, x, y) in selectedTiles) {
                if (seq.get() != mySeq) return@execute
                val grid = fetchOrCachedGrid(z, x, y) ?: continue
                extractContoursForGrid(grid, stepMeters, majorStep, features)
            }
            if (seq.get() != mySeq) return@execute
            val fc = FeatureCollection.fromFeatures(features)
            mainHandler.post {
                if (seq.get() != mySeq) return@post
                runCatching {
                    val curStyle = map.style ?: return@runCatching
                    if (!curStyle.isFullyLoaded) return@runCatching
                    lastViewportKey = vpKey
                    curStyle.getSourceAs<GeoJsonSource>(CONTOURS_SOURCE)?.setGeoJson(fc)
                }
            }
        }
    }

    private fun readDiskTile(z: Int, x: Int, y: Int): ByteArray? {
        val dir = diskCacheDir ?: return null
        val file = File(dir, "${z}_${x}_${y}.png")
        if (!file.exists() || !file.isFile || file.length() <= 64L) return null
        return runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        }.getOrNull()
    }

    private fun writeDiskTile(z: Int, x: Int, y: Int, bytes: ByteArray) {
        val dir = diskCacheDir ?: return
        synchronized(diskWriteLock) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val dst = File(dir, "${z}_${x}_${y}.png")
                val tmp = File(dir, "${z}_${x}_${y}.png.tmp")
                FileOutputStream(tmp).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                }
                if (dst.exists()) dst.delete()
                if (!tmp.renameTo(dst)) {
                    tmp.copyTo(dst, overwrite = true)
                    tmp.delete()
                }
                val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return@runCatching
                var total = files.sumOf { it.length() }
                if (total > DISK_CACHE_MAX_BYTES) {
                    for (old in files.sortedBy { it.lastModified() }) {
                        if (total <= (DISK_CACHE_MAX_BYTES * 3L) / 4L) break
                        val len = old.length()
                        if (old.delete()) total -= len
                    }
                }
            }
        }
    }

    private fun fetchOrCachedGrid(z: Int, x: Int, y: Int): TileGrid? {
        val key = "$z/$x/$y"
        synchronized(tileCache) {
            tileCache.get(key)?.let { return it }
        }
        val diskBytes = readDiskTile(z, x, y)
        val bytes = diskBytes ?: run {
            if (!NetworkState.hasValidatedInternet(RoutingEngine.appContext)) return null
            val url = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png"
            val req = Request.Builder().url(url).build()
            runCatching {
                RoutingEngine.sharedOkHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    resp.body?.bytes()
                }
            }.getOrNull()?.also { fresh ->
                if (fresh.size > 64) writeDiskTile(z, x, y, fresh)
            }
        } ?: return null

        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val n = GRID_N
            val elev = FloatArray((n + 1) * (n + 1))
            for (gy in 0..n) {
                val py = ((gy * (h - 1)) / n).coerceIn(0, h - 1)
                val rowOff = py * w
                for (gx in 0..n) {
                    val px = ((gx * (w - 1)) / n).coerceIn(0, w - 1)
                    val pixel = pixels[rowOff + px]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    elev[gy * (n + 1) + gx] = (r * 256f + g + b / 256f) - 32768f
                }
            }
            TileGrid(z, x, y, n, elev).also { grid ->
                synchronized(tileCache) { tileCache.put(key, grid) }
            }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun extractContoursForGrid(
        grid: TileGrid,
        step: Int,
        majorStep: Int,
        out: MutableList<Feature>
    ) {
        if (RustCoreBridge.extractContours(grid.elev, grid.z, grid.x, grid.y, grid.n, step, majorStep, out)) {
            return
        }
        val n = grid.n
        val stride = n + 1
        var minE = Float.POSITIVE_INFINITY
        var maxE = Float.NEGATIVE_INFINITY
        for (v in grid.elev) {
            if (v < minE) minE = v
            if (v > maxE) maxE = v
        }
        if (!minE.isFinite() || !maxE.isFinite() || maxE - minE < step * 0.5f) return

        val startLevel = (floor(minE / step.toDouble()).toInt() + 1) * step
        val endLevel = floor(maxE / step.toDouble()).toInt() * step
        if (endLevel < startLevel) return

        val west = tileXToLng(grid.x.toDouble(), grid.z)
        val east = tileXToLng((grid.x + 1).toDouble(), grid.z)
        val lngSpan = east - west

        // Precompute latitudes for each grid row
        val rowLats = DoubleArray(stride) { gy ->
            tileYToLat(grid.y + gy.toDouble() / n.toDouble(), grid.z)
        }

        fun interp(v0: Float, v1: Float, level: Float): Double {
            val d = v1 - v0
            return if (kotlin.math.abs(d) < 1e-4f) 0.5 else ((level - v0) / d).toDouble().coerceIn(0.0, 1.0)
        }

        // Quantized key to stitch adjacent segments into clean polylines for smooth line labels
        fun ptKey(lng: Double, lat: Double): Long {
            val qx = (lng * 100000.0).roundToLong()
            val qy = (lat * 100000.0).roundToLong()
            return (qx shl 32) xor (qy and 0xFFFFFFFFL)
        }

        var segBuf = DoubleArray(512)
        var segCount = 0
        fun addSegment(ax: Double, ay: Double, bx: Double, by: Double) {
            val base = segCount * 4
            if (base + 4 > segBuf.size) {
                segBuf = segBuf.copyOf(segBuf.size * 2)
            }
            segBuf[base] = ax
            segBuf[base + 1] = ay
            segBuf[base + 2] = bx
            segBuf[base + 3] = by
            segCount++
        }

        var level = startLevel
        while (level <= endLevel) {
            if (level <= 0) {
                level += step
                continue
            }
            val lf = level.toFloat()
            val isMajor = (level % majorStep == 0)
            segCount = 0

            for (gy in 0 until n) {
                val latTop = rowLats[gy]
                val latBot = rowLats[gy + 1]
                for (gx in 0 until n) {
                    val vTL = grid.elev[gy * stride + gx]
                    val vTR = grid.elev[gy * stride + gx + 1]
                    val vBR = grid.elev[(gy + 1) * stride + gx + 1]
                    val vBL = grid.elev[(gy + 1) * stride + gx]

                    var mask = 0
                    if (vTL >= lf) mask = mask or 8
                    if (vTR >= lf) mask = mask or 4
                    if (vBR >= lf) mask = mask or 2
                    if (vBL >= lf) mask = mask or 1
                    if (mask == 0 || mask == 15) continue

                    val lngL = west + lngSpan * (gx.toDouble() / n.toDouble())
                    val lngR = west + lngSpan * ((gx + 1).toDouble() / n.toDouble())

                    fun topLng(): Double = lngL + (lngR - lngL) * interp(vTL, vTR, lf)
                    fun rightLat(): Double = latTop + (latBot - latTop) * interp(vTR, vBR, lf)
                    fun botLng(): Double = lngL + (lngR - lngL) * interp(vBL, vBR, lf)
                    fun leftLat(): Double = latTop + (latBot - latTop) * interp(vTL, vBL, lf)

                    when (mask) {
                        1, 14 -> addSegment(lngL, leftLat(), botLng(), latBot)
                        2, 13 -> addSegment(botLng(), latBot, lngR, rightLat())
                        3, 12 -> addSegment(lngL, leftLat(), lngR, rightLat())
                        4, 11 -> addSegment(topLng(), latTop, lngR, rightLat())
                        5 -> {
                            addSegment(lngL, leftLat(), topLng(), latTop)
                            addSegment(botLng(), latBot, lngR, rightLat())
                        }
                        6, 9 -> addSegment(topLng(), latTop, botLng(), latBot)
                        7, 8 -> addSegment(lngL, leftLat(), topLng(), latTop)
                        10 -> {
                            addSegment(lngL, leftLat(), botLng(), latBot)
                            addSegment(topLng(), latTop, lngR, rightLat())
                        }
                    }
                }
            }

            if (segCount > 0) {
                val adj = HashMap<Long, MutableList<Int>>(segCount * 2)
                for (idx in 0 until segCount) {
                    val b = idx * 4
                    adj.getOrPut(ptKey(segBuf[b], segBuf[b + 1])) { mutableListOf() }.add(idx)
                    adj.getOrPut(ptKey(segBuf[b + 2], segBuf[b + 3])) { mutableListOf() }.add(idx)
                }
                val used = BooleanArray(segCount)
                for (i in 0 until segCount) {
                    if (used[i]) continue
                    used[i] = true
                    val b0 = i * 4
                    val chain = ArrayDeque<Point>()
                    chain.addLast(Point.fromLngLat(segBuf[b0], segBuf[b0 + 1]))
                    chain.addLast(Point.fromLngLat(segBuf[b0 + 2], segBuf[b0 + 3]))

                    while (true) {
                        val tail = chain.last()
                        val tailKey = ptKey(tail.longitude(), tail.latitude())
                        val candidates = adj[tailKey] ?: break
                        val nextIdx = candidates.firstOrNull { !used[it] } ?: break
                        used[nextIdx] = true
                        val b = nextIdx * 4
                        val ax = segBuf[b]
                        val ay = segBuf[b + 1]
                        val bx = segBuf[b + 2]
                        val by = segBuf[b + 3]
                        chain.addLast(if (ptKey(ax, ay) == tailKey) Point.fromLngLat(bx, by) else Point.fromLngLat(ax, ay))
                    }
                    while (true) {
                        val head = chain.first()
                        val headKey = ptKey(head.longitude(), head.latitude())
                        val candidates = adj[headKey] ?: break
                        val prevIdx = candidates.firstOrNull { !used[it] } ?: break
                        used[prevIdx] = true
                        val b = prevIdx * 4
                        val ax = segBuf[b]
                        val ay = segBuf[b + 1]
                        val bx = segBuf[b + 2]
                        val by = segBuf[b + 3]
                        chain.addFirst(if (ptKey(ax, ay) == headKey) Point.fromLngLat(bx, by) else Point.fromLngLat(ax, ay))
                    }

                    if (chain.size >= 2) {
                        val feature = Feature.fromGeometry(LineString.fromLngLats(chain.toList())).apply {
                            addBooleanProperty("isMajor", isMajor)
                            addStringProperty("ele", level.toString())
                        }
                        out.add(feature)
                    }
                }
            }

            level += step
        }
    }

    private fun lngToTileX(lng: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lng + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun latToTileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun tileXToLng(x: Double, z: Int): Double = x / (1 shl z).toDouble() * 360.0 - 180.0

    private fun tileYToLat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl z).toDouble()
        return Math.toDegrees(atan(sinh(n)))
    }
}
