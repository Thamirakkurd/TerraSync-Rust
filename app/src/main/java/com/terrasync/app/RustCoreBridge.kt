package com.terrasync.app

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

    @JvmStatic
    private external fun nativePing(): Int

    @JvmStatic
    private external fun nativeParseKmlBytes(data: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeParseKmlFile(path: String): ByteArray?

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
