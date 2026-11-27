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
        require(len <= buf.remaining()) { "Truncated native KML buffer" }
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
