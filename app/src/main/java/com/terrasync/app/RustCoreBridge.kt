package com.terrasync.app

import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        "KML I/O, TSR1 Routing, MVT Kurdish, PMTiles v3, Contours"

    @JvmStatic
    private external fun nativePing(): Int

    @JvmStatic
    private external fun nativeParseKmlBytes(data: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeParseKmlFile(path: String): ByteArray?

    @JvmStatic
    private external fun nativeBuildKmlBytes(payload: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeExportKmlFile(payload: ByteArray, outPath: String): Boolean

    @JvmStatic
    private external fun nativeRouteOffline(
        tsrPath: String,
        lastModified: Long,
        fileSize: Long,
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        modeCode: Int
    ): ByteArray?

    @JvmStatic
    private external fun nativeEvictRoadGraph(tsrPath: String)

    @JvmStatic
    private external fun nativePatchMvtKurdish(rawMvt: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeGunzipAndPatchMvt(rawOrGz: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeWarmPmTiles(path: String, lastModified: Long, fileSize: Long): Boolean

    @JvmStatic
    private external fun nativeLookupPmTile(
        path: String,
        lastModified: Long,
        fileSize: Long,
        tileId: Long
    ): ByteArray?

    @JvmStatic
    private external fun nativeLookupAndPatchPmTile(
        path: String,
        lastModified: Long,
        fileSize: Long,
        tileId: Long,
        tileIsGzipped: Boolean
    ): ByteArray?

    @JvmStatic
    private external fun nativeEvictPmTiles(path: String)

    @JvmStatic
    private external fun nativeExtractContours(
        elev: FloatArray,
        z: Int,
        x: Int,
        y: Int,
        n: Int,
        step: Int,
        majorStep: Int
    ): ByteArray?

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
        tsrFile: File,
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        mode: TravelMode
    ): Pair<NavRoute?, String?>? {
        if (!loaded || !tsrFile.exists()) return null
        val modeCode = when (mode) {
            TravelMode.CAR -> 0
            TravelMode.BICYCLE -> 1
            TravelMode.WALKING -> 2
        }
        val encoded = runCatching {
            nativeRouteOffline(
                tsrFile.absolutePath,
                tsrFile.lastModified(),
                tsrFile.length(),
                startLat,
                startLng,
                endLat,
                endLng,
                modeCode
            )
        }.getOrNull() ?: return null
        return runCatching { decodeRouteResult(encoded, mode) }.getOrNull()
    }

    fun evictRoadGraph(tsrPath: String) {
        if (!loaded) return
        runCatching { nativeEvictRoadGraph(tsrPath) }
    }

    fun patchMvtKurdish(rawMvt: ByteArray): ByteArray? {
        if (!loaded) return null
        return runCatching { nativePatchMvtKurdish(rawMvt) }.getOrNull()
    }

    /** Returns inflated+patched bytes, empty ByteArray if unmodified plain MVT, or null if native unavailable. */
    fun gunzipAndPatchMvt(rawOrGz: ByteArray): ByteArray? {
        if (!loaded) return null
        return runCatching { nativeGunzipAndPatchMvt(rawOrGz) }.getOrNull()
    }

    fun warmPmTilesArchive(file: File): Boolean {
        if (!loaded || !file.exists()) return false
        return runCatching {
            nativeWarmPmTiles(file.absolutePath, file.lastModified(), file.length())
        }.getOrDefault(false)
    }

    fun lookupPmTile(file: File, tileId: Long): ByteArray? {
        if (!loaded) return null
        return runCatching {
            nativeLookupPmTile(file.absolutePath, file.lastModified(), file.length(), tileId)
        }.getOrNull()
    }

    /** Fused lookup + inflate + Kurdish MVT patch. Returns tile bytes, empty on valid miss, or null on fallback. */
    fun lookupAndPatchPmTile(file: File, tileId: Long, tileIsGzipped: Boolean): ByteArray? {
        if (!loaded) return null
        return runCatching {
            nativeLookupAndPatchPmTile(file.absolutePath, file.lastModified(), file.length(), tileId, tileIsGzipped)
        }.getOrNull()
    }

    fun evictPmTilesArchive(path: String) {
        if (!loaded) return
        runCatching { nativeEvictPmTiles(path) }
    }

    fun extractContours(
        elev: FloatArray,
        z: Int,
        x: Int,
        y: Int,
        n: Int,
        step: Int,
        majorStep: Int,
        out: MutableList<Feature>
    ): Boolean {
        if (!loaded) return false
        val payload = runCatching {
            nativeExtractContours(elev, z, x, y, n, step, majorStep)
        }.getOrNull() ?: return false
        return runCatching {
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val lineCount = buf.int.coerceAtLeast(0)
            repeat(lineCount) {
                val isMajor = buf.get().toInt() != 0
                val level = buf.int
                val ptCount = buf.int.coerceAtLeast(0)
                val pts = ArrayList<Point>(ptCount)
                repeat(ptCount) {
                    val lng = buf.double
                    val lat = buf.double
                    pts.add(Point.fromLngLat(lng, lat))
                }
                if (pts.size >= 2) {
                    val feature = Feature.fromGeometry(LineString.fromLngLats(pts)).apply {
                        addBooleanProperty("isMajor", isMajor)
                        addStringProperty("ele", level.toString())
                    }
                    out.add(feature)
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun encodeKmlExportPayload(
        exportTitle: String,
        pins: List<PinItem>,
        tracks: List<TrackItem>
    ): ByteArray {
        val out = ByteArrayOutputStream((pins.size * 160 + tracks.size * 96 + 256).coerceAtMost(4 * 1024 * 1024))
        val numBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun putInt(v: Int) {
            numBuf.clear(); numBuf.putInt(v); out.write(numBuf.array(), 0, 4)
        }
        fun putLong(v: Long) {
            numBuf.clear(); numBuf.putLong(v); out.write(numBuf.array(), 0, 8)
        }
        fun putDouble(v: Double) {
            numBuf.clear(); numBuf.putDouble(v); out.write(numBuf.array(), 0, 8)
        }
        fun putStr(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            putInt(b.size)
            if (b.isNotEmpty()) out.write(b)
        }

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
        for (t in tracks) {
            putStr(t.name)
            putStr(t.coords)
            putStr(t.folderPath)
        }
        return out.toByteArray()
    }

    internal fun decodeRouteResult(payload: ByteArray, mode: TravelMode): Pair<NavRoute?, String?> {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val ok = buf.get().toInt() != 0
        if (!ok) {
            return null to readUtf8(buf)
        }
        val totalDist = buf.double
        val totalDur = buf.double
        val geomCount = buf.int.coerceAtLeast(0)
        val geom = ArrayList<Pair<Double, Double>>(geomCount)
        repeat(geomCount) {
            val lng = buf.double
            val lat = buf.double
            geom.add(lng to lat)
        }
        val stepCount = buf.int.coerceAtLeast(0)
        val steps = ArrayList<NavStep>(stepCount)
        repeat(stepCount) {
            val instruction = readUtf8(buf)
            val arrow = readUtf8(buf)
            val dist = buf.double
            val lat = buf.double
            val lng = buf.double
            val cumDist = buf.double
            steps.add(NavStep(instruction, arrow, dist, lat, lng, cumDist))
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
            val name = readUtf8(buf)
            val description = readUtf8(buf)
            val lat = buf.double
            val lng = buf.double
            val status = readUtf8(buf)
            val issueReason = readUtf8(buf)
            val isEdited = buf.get().toInt() != 0
            val editedBy = readUtf8(buf)
            val editedAt = buf.long
            val editHistory = readUtf8(buf)
            val folderPath = readUtf8(buf)
            pins.add(
                PinItem(
                    id = UUID.randomUUID().toString(),
                    layerId = layerId,
                    name = name,
                    description = description,
                    lat = lat,
                    lng = lng,
                    status = status,
                    issueReason = issueReason,
                    updatedAt = now,
                    isEdited = isEdited,
                    editedBy = editedBy,
                    editedAt = editedAt,
                    editHistory = editHistory,
                    folderPath = folderPath
                )
            )
        }

        val trackCount = buf.int.coerceAtLeast(0)
        val tracks = ArrayList<TrackItem>(trackCount)
        repeat(trackCount) {
            val name = readUtf8(buf)
            val coords = readUtf8(buf)
            val folderPath = readUtf8(buf)
            tracks.add(
                TrackItem(
                    id = UUID.randomUUID().toString(),
                    layerId = layerId,
                    name = name,
                    coords = coords,
                    updatedAt = now,
                    folderPath = folderPath
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
