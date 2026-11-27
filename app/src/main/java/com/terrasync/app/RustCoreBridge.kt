package com.terrasync.app

import android.location.Location
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

object RustCoreBridge {

    private val loaded: Boolean = try {
        System.loadLibrary("terrasync_core")
        nativePing() == 1
    } catch (_: Throwable) {
        false
    }

    fun isAvailable(): Boolean = loaded

    fun activeModulesSummary(): String =
        "KML I/O, TSR1 Routing, MVT Kurdish, PMTiles v3, Contours, Search, Viewport GeoJSON, Clusters, Lasso"

    @JvmStatic private external fun nativePing(): Int
    @JvmStatic private external fun nativeParseKmlBytes(data: ByteArray): ByteArray?
    @JvmStatic private external fun nativeParseKmlFile(path: String): ByteArray?
    @JvmStatic private external fun nativeBuildKmlBytes(payload: ByteArray): ByteArray?
    @JvmStatic private external fun nativeExportKmlFile(payload: ByteArray, outPath: String): Boolean
    @JvmStatic private external fun nativeRouteOffline(
        tsrPath: String, lastModified: Long, fileSize: Long,
        startLat: Double, startLng: Double, endLat: Double, endLng: Double, modeCode: Int
    ): ByteArray?
    @JvmStatic private external fun nativeEvictRoadGraph(tsrPath: String)
    @JvmStatic private external fun nativePatchMvtKurdish(rawMvt: ByteArray): ByteArray?
    @JvmStatic private external fun nativeGunzipAndPatchMvt(rawOrGz: ByteArray): ByteArray?
    @JvmStatic private external fun nativeWarmPmTiles(path: String, lastModified: Long, fileSize: Long): Boolean
    @JvmStatic private external fun nativeLookupPmTile(path: String, lastModified: Long, fileSize: Long, tileId: Long): ByteArray?
    @JvmStatic private external fun nativeLookupAndPatchPmTile(
        path: String, lastModified: Long, fileSize: Long, tileId: Long, tileIsGzipped: Boolean
    ): ByteArray?
    @JvmStatic private external fun nativeEvictPmTiles(path: String)
    @JvmStatic private external fun nativeExtractContours(
        elev: FloatArray, z: Int, x: Int, y: Int, n: Int, step: Int, majorStep: Int
    ): ByteArray?
    @JvmStatic private external fun nativeLoadSearchIndex(version: Long, payload: ByteArray): Boolean
    @JvmStatic private external fun nativeQuerySearchIndex(version: Long, expectedLen: Int, queryPayload: ByteArray): ByteArray?
    @JvmStatic private external fun nativeClearSearchIndex()
    @JvmStatic private external fun nativeLoadGeoPins(version: Long, payload: ByteArray): Boolean
    @JvmStatic private external fun nativeBuildPinsGeoJsonViewport(version: Long, expectedLen: Int, filterPayload: ByteArray): ByteArray?
    @JvmStatic private external fun nativeClearGeoPins()
    @JvmStatic private external fun nativeBuildTracksGeoJsonViewport(payload: ByteArray): ByteArray?
    @JvmStatic private external fun nativeFilterPinsInPolygon(version: Long, expectedLen: Int, payload: ByteArray): ByteArray?
    @JvmStatic private external fun nativeAnalyzeSameCoordClusters(payload: ByteArray): ByteArray?
    @JvmStatic private external fun nativeSortAndScorePins(payload: ByteArray): ByteArray?

    fun parseKml(memBytes: ByteArray?, tempFile: File?, layerId: String): ParsedKml? {
        if (!loaded) return null
        val encoded = runCatching {
            when {
                memBytes != null -> nativeParseKmlBytes(memBytes)
                tempFile != null -> nativeParseKmlFile(tempFile.absolutePath)
                else -> null
            }
        }.getOrNull() ?: return null
        return runCatching { decodeParsedKml(encoded, layerId) }.getOrNull()
    }

    fun buildKmlXml(exportTitle: String, pins: List<PinItem>, tracks: List<TrackItem>): String? {
        if (!loaded) return null
        val payload = runCatching { encodeKmlExportPayload(exportTitle, pins, tracks) }.getOrNull() ?: return null
        val xmlBytes = runCatching { nativeBuildKmlBytes(payload) }.getOrNull() ?: return null
        return String(xmlBytes, Charsets.UTF_8)
    }

    fun exportKmlToFile(outFile: File, exportTitle: String, pins: List<PinItem>, tracks: List<TrackItem>): Boolean {
        if (!loaded) return false
        val payload = runCatching { encodeKmlExportPayload(exportTitle, pins, tracks) }.getOrNull() ?: return false
        return runCatching { nativeExportKmlFile(payload, outFile.absolutePath) }.getOrDefault(false)
    }

    fun routeOffline(
        tsrFile: File, startLat: Double, startLng: Double, endLat: Double, endLng: Double, mode: TravelMode
    ): Pair<NavRoute?, String?>? {
        if (!loaded || !tsrFile.exists()) return null
        val modeCode = when (mode) {
            TravelMode.CAR -> 0
            TravelMode.BICYCLE -> 1
            TravelMode.WALKING -> 2
        }
        val encoded = runCatching {
            nativeRouteOffline(tsrFile.absolutePath, tsrFile.lastModified(), tsrFile.length(), startLat, startLng, endLat, endLng, modeCode)
        }.getOrNull() ?: return null
        return runCatching { decodeRouteResult(encoded, mode) }.getOrNull()
    }

    fun evictRoadGraph(tsrPath: String) {
        if (!loaded) return
        runCatching { nativeEvictRoadGraph(tsrPath) }
    }

    fun patchMvtKurdish(rawMvt: ByteArray): ByteArray? =
        if (!loaded) null else runCatching { nativePatchMvtKurdish(rawMvt) }.getOrNull()

    fun gunzipAndPatchMvt(rawOrGz: ByteArray): ByteArray? =
        if (!loaded) null else runCatching { nativeGunzipAndPatchMvt(rawOrGz) }.getOrNull()

    fun warmPmTilesArchive(file: File): Boolean =
        if (!loaded || !file.exists()) false else runCatching {
            nativeWarmPmTiles(file.absolutePath, file.lastModified(), file.length())
        }.getOrDefault(false)

    fun lookupPmTile(file: File, tileId: Long): ByteArray? =
        if (!loaded) null else runCatching {
            nativeLookupPmTile(file.absolutePath, file.lastModified(), file.length(), tileId)
        }.getOrNull()

    fun lookupAndPatchPmTile(file: File, tileId: Long, tileIsGzipped: Boolean): ByteArray? =
        if (!loaded) null else runCatching {
            nativeLookupAndPatchPmTile(file.absolutePath, file.lastModified(), file.length(), tileId, tileIsGzipped)
        }.getOrNull()

    fun evictPmTilesArchive(path: String) {
        if (!loaded) return
        runCatching { nativeEvictPmTiles(path) }
    }

    fun extractContours(
        elev: FloatArray, z: Int, x: Int, y: Int, n: Int, step: Int, majorStep: Int, out: MutableList<Feature>
    ): Boolean {
        if (!loaded) return false
        val payload = runCatching { nativeExtractContours(elev, z, x, y, n, step, majorStep) }.getOrNull() ?: return false
        return runCatching {
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val lineCount = buf.int.coerceAtLeast(0)
            repeat(lineCount) {
                val isMajor = buf.get().toInt() != 0
                val level = buf.int
                val ptCount = buf.int.coerceAtLeast(0)
                val pts = ArrayList<Point>(ptCount)
                repeat(ptCount) { pts.add(Point.fromLngLat(buf.double, buf.double)) }
                if (pts.size >= 2) {
                    out.add(Feature.fromGeometry(LineString.fromLngLats(pts)).apply {
                        addBooleanProperty("isMajor", isMajor)
                        addStringProperty("ele", level.toString())
                    })
                }
            }
            true
        }.getOrDefault(false)
    }

    fun syncSearchIndex(version: Long, entries: List<SearchIndexEntry>): Boolean {
        if (!loaded) return false
        return runCatching {
            val out = ByteArrayOutputStream((entries.size * 72 + 16).coerceAtMost(8 * 1024 * 1024))
            val nb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            fun putInt(v: Int) { nb.clear(); nb.putInt(v); out.write(nb.array(), 0, 4) }
            fun putDouble(v: Double) { nb.clear(); nb.putDouble(v); out.write(nb.array(), 0, 8) }
            fun putStr(s: String) { val b = s.toByteArray(Charsets.UTF_8); putInt(b.size); if (b.isNotEmpty()) out.write(b) }
            putInt(entries.size)
            for (e in entries) {
                putStr(e.searchBlob)
                putStr(e.coordsText)
                putDouble(e.pin.lat)
                putDouble(e.pin.lng)
            }
            nativeLoadSearchIndex(version, out.toByteArray())
        }.getOrDefault(false)
    }

    fun querySearchIndex(
        version: Long, entries: List<SearchIndexEntry>, tokens: List<String>,
        hasDigits: Boolean, hasLoc: Boolean, locLat: Double, locLng: Double
    ): Pair<IntArray, DoubleArray?>? {
        if (!loaded) return null
        if (tokens.isEmpty() || entries.isEmpty()) return IntArray(0) to null
        return runCatching {
            val qOut = ByteArrayOutputStream(128)
            val nb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            fun putInt(v: Int) { nb.clear(); nb.putInt(v); qOut.write(nb.array(), 0, 4) }
            fun putDouble(v: Double) { nb.clear(); nb.putDouble(v); qOut.write(nb.array(), 0, 8) }
            fun putStr(s: String) { val b = s.toByteArray(Charsets.UTF_8); putInt(b.size); if (b.isNotEmpty()) qOut.write(b) }
            putInt(tokens.size)
            for (t in tokens) putStr(t)
            qOut.write(if (hasDigits) 1 else 0)
            qOut.write(if (hasLoc) 1 else 0)
            if (hasLoc) { putDouble(locLat); putDouble(locLng) }
            val qBytes = qOut.toByteArray()
            val resBytes = nativeQuerySearchIndex(version, entries.size, qBytes) ?: run {
                if (!syncSearchIndex(version, entries)) return@runCatching null
                nativeQuerySearchIndex(version, entries.size, qBytes) ?: return@runCatching null
            }
            val bb = ByteBuffer.wrap(resBytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = bb.int.coerceAtLeast(0)
            val hasDist = bb.get().toInt() != 0
            val indices = IntArray(count)
            val dists = if (hasDist) DoubleArray(count) else null
            for (i in 0 until count) {
                indices[i] = bb.int
                if (dists != null) dists[i] = bb.double
            }
            indices to dists
        }.getOrNull()
    }

    fun clearSearchIndex() {
        if (!loaded) return
        runCatching { nativeClearSearchIndex() }
    }

    fun syncGeoPins(version: Long, pins: List<PinItem>): Boolean {
        if (!loaded) return false
        return runCatching {
            val out = ByteArrayOutputStream((pins.size * 72 + 64).coerceAtMost(8 * 1024 * 1024))
            val nb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            fun putInt(v: Int) { nb.clear(); nb.putInt(v); out.write(nb.array(), 0, 4) }
            fun putDouble(v: Double) { nb.clear(); nb.putDouble(v); out.write(nb.array(), 0, 8) }
            fun putStr(s: String) { val b = s.toByteArray(Charsets.UTF_8); putInt(b.size); if (b.isNotEmpty()) out.write(b) }
            putStr(PinStatus.DONE.iconId)
            putStr(PinStatus.ISSUE.iconId)
            putStr(PinStatus.PENDING.iconId)
            putInt(pins.size)
            for (p in pins) {
                putStr(p.id)
                putStr(p.layerId)
                putStr(p.name)
                val code = when (PinStatus.fromRaw(p.status)) {
                    PinStatus.DONE -> 0
                    PinStatus.ISSUE -> 1
                    PinStatus.PENDING -> 2
                }
                out.write(code)
                putDouble(p.lng)
                putDouble(p.lat)
            }
            nativeLoadGeoPins(version, out.toByteArray())
        }.getOrDefault(false)
    }

    fun buildPinsGeoJsonViewport(
        version: Long,
        pins: List<PinItem>,
        hiddenLayerIds: Set<String>,
        sentIds: Set<String>,
        visibleStatuses: Set<String>,
        useViewport: Boolean,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        zoom: Double,
        clustered: Boolean,
        maxUnclusteredPins: Int,
        selectedPinId: String?
    ): String? {
        if (!loaded) return null
        return runCatching {
            val flt = ByteArrayOutputStream(128 + hiddenLayerIds.size * 24 + sentIds.size * 24)
            val nb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            fun putInt(v: Int) { nb.clear(); nb.putInt(v); flt.write(nb.array(), 0, 4) }
            fun putDouble(v: Double) { nb.clear(); nb.putDouble(v); flt.write(nb.array(), 0, 8) }
            fun putStr(s: String) { val b = s.toByteArray(Charsets.UTF_8); putInt(b.size); if (b.isNotEmpty()) flt.write(b) }

            var mask = 0
            if (PinStatus.DONE.key in visibleStatuses) mask = mask or 1
            if (PinStatus.ISSUE.key in visibleStatuses) mask = mask or 2
            if (PinStatus.PENDING.key in visibleStatuses) mask = mask or 4
            flt.write(mask)
            putInt(hiddenLayerIds.size)
            for (id in hiddenLayerIds) putStr(id)
            putInt(sentIds.size)
            for (id in sentIds) putStr(id)
            flt.write(if (useViewport) 1 else 0)
            putDouble(south)
            putDouble(west)
            putDouble(north)
            putDouble(east)
            putDouble(zoom)
            flt.write(if (clustered) 1 else 0)
            putInt(maxUnclusteredPins.coerceAtLeast(0))
            putStr(selectedPinId.orEmpty())
            val fltBytes = flt.toByteArray()

            val jsonBytes = nativeBuildPinsGeoJsonViewport(version, pins.size, fltBytes) ?: run {
                if (!syncGeoPins(version, pins)) return@runCatching null
                nativeBuildPinsGeoJsonViewport(version, pins.size, fltBytes) ?: return@runCatching null
            }
            String(jsonBytes, Charsets.UTF_8)
        }.getOrNull()
    }

    fun clearGeoPinsCache() {
        if (!loaded) return
        runCatching { nativeClearGeoPins() }
    }

    fun buildTracksGeoJsonViewport(
        tracks: List<TrackItem>,
        hiddenLayerIds: Set<String>,
        useViewport: Boolean,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        zoom: Double
    ): String? {
        if (!loaded) return null
        return runCatching {
            val visible = ArrayList<TrackItem>(tracks.size)
            for (t in tracks) {
                if (t.layerId !in hiddenLayerIds) visible.add(t)
            }
            val out = ByteArrayOutputStream((visible.size * 256 + 64).coerceAtMost(4 * 1024 * 1024))
            val nb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            fun putInt(v: Int) { nb.clear(); nb.putInt(v); out.write(nb.array(), 0, 4) }
            fun putDouble(v: Double) { nb.clear(); nb.putDouble(v); out.write(nb.array(), 0, 8) }
            out.write(if (useViewport) 1 else 0)
            putDouble(south)
            putDouble(west)
            putDouble(north)
            putDouble(east)
            putDouble(zoom)
            putInt(visible.size)
            for (t in visible) {
                val b = t.coords.toByteArray(Charsets.UTF_8)
                putInt(b.size)
                if (b.isNotEmpty()) out.write(b)
            }
            val jsonBytes = nativeBuildTracksGeoJsonViewport(out.toByteArray()) ?: return@runCatching null
            String(jsonBytes, Charsets.UTF_8)
        }.getOrNull()
    }

    fun filterPinsInGeoPolygon(
        version: Long,
        pins: List<PinItem>,
        hiddenLayerIds: Set<String>,
        sentIds: Set<String>,
        visibleStatuses: Set<String>,
        geoPolyLngLat: DoubleArray
    ): List<PinItem>? {
        if (!loaded) return null
        val polyCount = geoPolyLngLat.size / 2
        if (polyCount < 3 || pins.isEmpty()) return emptyList()
        return runCatching {
            val out = ByteArrayOutputStream(128 + hiddenLayerIds.size * 24 + sentIds.size * 24 + polyCount * 16)
            val nb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            fun putInt(v: Int) { nb.clear(); nb.putInt(v); out.write(nb.array(), 0, 4) }
            fun putDouble(v: Double) { nb.clear(); nb.putDouble(v); out.write(nb.array(), 0, 8) }
            fun putStr(s: String) { val b = s.toByteArray(Charsets.UTF_8); putInt(b.size); if (b.isNotEmpty()) out.write(b) }

            var mask = 0
            if (PinStatus.DONE.key in visibleStatuses) mask = mask or 1
            if (PinStatus.ISSUE.key in visibleStatuses) mask = mask or 2
            if (PinStatus.PENDING.key in visibleStatuses) mask = mask or 4
            out.write(mask)
            putInt(hiddenLayerIds.size)
            for (id in hiddenLayerIds) putStr(id)
            putInt(sentIds.size)
            for (id in sentIds) putStr(id)
            putInt(polyCount)
            for (i in 0 until polyCount) {
                putDouble(geoPolyLngLat[i * 2])
                putDouble(geoPolyLngLat[i * 2 + 1])
            }
            val payload = out.toByteArray()
            val res = nativeFilterPinsInPolygon(version, pins.size, payload) ?: run {
                if (!syncGeoPins(version, pins)) return@runCatching null
                nativeFilterPinsInPolygon(version, pins.size, payload) ?: return@runCatching null
            }
            val bb = ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN)
            val cnt = bb.int.coerceAtLeast(0)
            val hits = ArrayList<PinItem>(cnt)
            repeat(cnt) { pins.getOrNull(bb.int)?.let { hits.add(it) } }
            hits
        }.getOrNull()
    }

    fun analyzeSameCoordClusters(list: List<PinItem>): Pair<Set<String>, Map<String, Int>>? {
        if (!loaded) return null
        if (list.size < 2) return emptySet<String>() to emptyMap()
        return runCatching {
            val out = ByteArrayOutputStream((list.size * 72 + 16).coerceAtMost(8 * 1024 * 1024))
            val nb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            fun putInt(v: Int) { nb.clear(); nb.putInt(v); out.write(nb.array(), 0, 4) }
            fun putLong(v: Long) { nb.clear(); nb.putLong(v); out.write(nb.array(), 0, 8) }
            fun putDouble(v: Double) { nb.clear(); nb.putDouble(v); out.write(nb.array(), 0, 8) }
            putInt(list.size)
            for (p in list) {
                putDouble(p.lat)
                putDouble(p.lng)
                val rank = when (PinStatus.fromRaw(p.status)) {
                    PinStatus.DONE -> 3
                    PinStatus.ISSUE -> 2
                    PinStatus.PENDING -> if (p.isEdited) 1 else 0
                }
                out.write(rank)
                putLong(p.editedAt)
                putLong(p.updatedAt)
                val idBytes = p.id.toByteArray(Charsets.UTF_8)
                putInt(idBytes.size)
                if (idBytes.isNotEmpty()) out.write(idBytes)
            }
            val res = nativeAnalyzeSameCoordClusters(out.toByteArray()) ?: return@runCatching null
            val bb = ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN)
            val extraCount = bb.int.coerceAtLeast(0)
            val extras = LinkedHashSet<String>(extraCount)
            repeat(extraCount) { list.getOrNull(bb.int)?.let { extras.add(it.id) } }
            val sizedCount = bb.int.coerceAtLeast(0)
            val sizes = HashMap<String, Int>(sizedCount)
            repeat(sizedCount) {
                val idx = bb.int
                val sz = bb.int
                list.getOrNull(idx)?.let { sizes[it.id] = sz }
            }
            extras to sizes
        }.getOrNull()
    }

    fun sortAndScorePins(
        pins: List<PinItem>, selectionMode: Boolean, selectedIds: Set<String>,
        loc: Location?, useNearest: Boolean, unit: SpeedUnit
    ): Pair<List<PinItem>, Map<String, String>>? {
        if (!loaded) return null
        if (pins.isEmpty()) return emptyList<PinItem>() to emptyMap()
        return runCatching {
            val hasLoc = loc != null
            val sortNearest = useNearest && hasLoc
            val out = ByteArrayOutputStream((pins.size * (if (sortNearest) 20 else 48) + 32).coerceAtMost(8 * 1024 * 1024))
            val nb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            fun putInt(v: Int) { nb.clear(); nb.putInt(v); out.write(nb.array(), 0, 4) }
            fun putDouble(v: Double) { nb.clear(); nb.putDouble(v); out.write(nb.array(), 0, 8) }
            out.write(if (hasLoc) 1 else 0)
            if (loc != null) { putDouble(loc.latitude); putDouble(loc.longitude) }
            out.write(if (sortNearest) 1 else 0)
            putInt(pins.size)
            val hasSel = selectionMode && selectedIds.isNotEmpty()
            for (p in pins) {
                out.write(if (hasSel && p.id in selectedIds) 0 else 1)
                putDouble(p.lat)
                putDouble(p.lng)
                if (!sortNearest) {
                    val b = p.name.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8)
                    putInt(b.size)
                    if (b.isNotEmpty()) out.write(b)
                }
            }
            val res = nativeSortAndScorePins(out.toByteArray()) ?: return@runCatching null
            val bb = ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN)
            val count = bb.int.coerceAtLeast(0)
            val resHasLoc = bb.get().toInt() != 0
            val sorted = ArrayList<PinItem>(count)
            val distMap = if (resHasLoc) HashMap<String, String>(count) else emptyMap<String, String>()
            val mutDist = distMap as? HashMap<String, String>
            repeat(count) {
                val origIdx = bb.int
                val distM = if (resHasLoc) bb.double else 0.0
                val pin = pins.getOrNull(origIdx)
                if (pin != null) {
                    sorted.add(pin)
                    if (mutDist != null) mutDist[pin.id] = formatDistanceShort(distM, unit)
                }
            }
            sorted to distMap
        }.getOrNull()
    }

    private fun encodeKmlExportPayload(exportTitle: String, pins: List<PinItem>, tracks: List<TrackItem>): ByteArray {
        val out = ByteArrayOutputStream((pins.size * 160 + tracks.size * 96 + 256).coerceAtMost(4 * 1024 * 1024))
        val nb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun putInt(v: Int) { nb.clear(); nb.putInt(v); out.write(nb.array(), 0, 4) }
        fun putLong(v: Long) { nb.clear(); nb.putLong(v); out.write(nb.array(), 0, 8) }
        fun putDouble(v: Double) { nb.clear(); nb.putDouble(v); out.write(nb.array(), 0, 8) }
        fun putStr(s: String) { val b = s.toByteArray(Charsets.UTF_8); putInt(b.size); if (b.isNotEmpty()) out.write(b) }
        putStr(exportTitle)
        putInt(pins.size)
        for (p in pins) {
            val normStatus = PinStatus.fromRaw(p.status).key
            putStr(p.name)
            putStr(p.description)
            putDouble(p.lat)
            putDouble(p.lng)
            putStr(normStatus)
            putStr(statusLabel(normStatus))
            putStr(p.issueReason)
            out.write(if (p.isEdited) 1 else 0)
            putStr(p.editedBy)
            putLong(p.editedAt)
            putStr(p.editHistory)
            putStr(p.folderPath)
        }
        putInt(tracks.size)
        for (t in tracks) { putStr(t.name); putStr(t.coords); putStr(t.folderPath) }
        return out.toByteArray()
    }

    internal fun decodeRouteResult(payload: ByteArray, mode: TravelMode): Pair<NavRoute?, String?> {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.get().toInt() == 0) return null to readUtf8(buf)
        val totalDist = buf.double
        val totalDur = buf.double
        val geomCount = buf.int.coerceAtLeast(0)
        val geom = ArrayList<Pair<Double, Double>>(geomCount)
        repeat(geomCount) { geom.add(buf.double to buf.double) }
        val stepCount = buf.int.coerceAtLeast(0)
        val steps = ArrayList<NavStep>(stepCount)
        repeat(stepCount) {
            steps.add(NavStep(readUtf8(buf), readUtf8(buf), buf.double, buf.double, buf.double, buf.double))
        }
        return NavRoute(geom, totalDist, totalDur, steps, mode, "Offline") to null
    }

    internal fun decodeParsedKml(payload: ByteArray, layerId: String): ParsedKml {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val now = System.currentTimeMillis()
        val docName = readUtf8(buf)
        val pinCount = buf.int.coerceAtLeast(0)
        val pins = ArrayList<PinItem>(pinCount)
        repeat(pinCount) {
            pins.add(
                PinItem(
                    id = UUID.randomUUID().toString(), layerId = layerId,
                    name = readUtf8(buf), description = readUtf8(buf),
                    lat = buf.double, lng = buf.double,
                    status = readUtf8(buf), issueReason = readUtf8(buf),
                    updatedAt = now, isEdited = buf.get().toInt() != 0,
                    editedBy = readUtf8(buf), editedAt = buf.long,
                    editHistory = readUtf8(buf), folderPath = readUtf8(buf)
                )
            )
        }
        val trackCount = buf.int.coerceAtLeast(0)
        val tracks = ArrayList<TrackItem>(trackCount)
        repeat(trackCount) {
            tracks.add(
                TrackItem(
                    id = UUID.randomUUID().toString(), layerId = layerId,
                    name = readUtf8(buf), coords = readUtf8(buf),
                    updatedAt = now, folderPath = readUtf8(buf)
                )
            )
        }
        return ParsedKml(pins = pins, tracks = tracks, documentName = docName)
    }

    private fun readUtf8(buf: ByteBuffer): String {
        val len = buf.int
        if (len <= 0) return ""
        require(len <= buf.remaining()) { "Truncated native buffer" }
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
