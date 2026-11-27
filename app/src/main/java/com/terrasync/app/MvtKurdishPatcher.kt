package com.terrasync.app

import java.io.ByteArrayOutputStream

/**
 * Lightweight, zero-dependency Mapbox Vector Tile (MVT) protobuf string-table patcher.
 *
 * Scans raw (uncompressed) MVT bytes for Kurdish UTF-8 codepoints (ە 0x06D5, ێ 0x06CE,
 * ڵ 0x06B5, ڕ 0x0695, ڤ 0x06A4). Non-Kurdish tiles return immediately with zero allocation.
 * Tiles containing Kurdish labels have only Layer.values[i].string_value rewritten via
 * [KurdishArabicShaper.shape], preserving all geometries, tags, and numeric values verbatim.
 */
object MvtKurdishPatcher {

    /** Fast raw UTF-8 scan for {ڕ D9 95, ڤ DA A4, ڵ DA B5, ێ DB 8E, ە DB 95}. */
    fun containsKurdishUtf8(data: ByteArray): Boolean {
        val limit = data.size - 1
        var i = 0
        while (i < limit) {
            val b0 = data[i].toInt() and 0xFF
            if (b0 == 0xDB) {
                val b1 = data[i + 1].toInt() and 0xFF
                if (b1 == 0x95 || b1 == 0x8E) return true
            } else if (b0 == 0xDA) {
                val b1 = data[i + 1].toInt() and 0xFF
                if (b1 == 0xB5 || b1 == 0xA4) return true
            } else if (b0 == 0xD9) {
                val b1 = data[i + 1].toInt() and 0xFF
                if (b1 == 0x95) return true
            }
            i++
        }
        return false
    }

    fun patchTile(rawMvt: ByteArray): ByteArray {
        if (rawMvt.size < 4 || !containsKurdishUtf8(rawMvt)) return rawMvt
        RustCoreBridge.patchMvtKurdish(rawMvt)?.let { return if (it.isEmpty()) rawMvt else it }
        return runCatching { patchTileInternal(rawMvt) }.getOrDefault(rawMvt)
    }

    private fun patchTileInternal(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 64)
        var pos = 0
        val end = data.size
        var modified = false

        while (pos < end) {
            val fieldStart = pos
            val (tag, nextPos) = readVarint(data, pos)
            pos = nextPos
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 7L).toInt()

            if (wireType == 2) {
                val (lenLong, bodyStart) = readVarint(data, pos)
                val len = lenLong.toInt()
                val bodyEnd = bodyStart + len
                if (len < 0 || bodyEnd > end) return data
                pos = bodyEnd

                if (fieldNum == 3) {
                    val patchedLayer = patchLayer(data, bodyStart, bodyEnd)
                    if (patchedLayer != null) {
                        modified = true
                        writeVarint(out, tag)
                        writeVarint(out, patchedLayer.size.toLong())
                        out.write(patchedLayer)
                    } else {
                        out.write(data, fieldStart, pos - fieldStart)
                    }
                } else {
                    out.write(data, fieldStart, pos - fieldStart)
                }
            } else {
                pos = skipScalar(data, pos, wireType)
                if (pos > end) return data
                out.write(data, fieldStart, pos - fieldStart)
            }
        }
        return if (modified) out.toByteArray() else data
    }

    private fun patchLayer(data: ByteArray, start: Int, end: Int): ByteArray? {
        val out = ByteArrayOutputStream((end - start) + 32)
        var pos = start
        var modified = false

        while (pos < end) {
            val fieldStart = pos
            val (tag, nextPos) = readVarint(data, pos)
            pos = nextPos
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 7L).toInt()

            if (wireType == 2) {
                val (lenLong, bodyStart) = readVarint(data, pos)
                val len = lenLong.toInt()
                val bodyEnd = bodyStart + len
                if (len < 0 || bodyEnd > end) return null
                pos = bodyEnd

                if (fieldNum == 4) {
                    val patchedValue = patchValue(data, bodyStart, bodyEnd)
                    if (patchedValue != null) {
                        modified = true
                        writeVarint(out, tag)
                        writeVarint(out, patchedValue.size.toLong())
                        out.write(patchedValue)
                    } else {
                        out.write(data, fieldStart, pos - fieldStart)
                    }
                } else {
                    out.write(data, fieldStart, pos - fieldStart)
                }
            } else {
                pos = skipScalar(data, pos, wireType)
                if (pos > end) return null
                out.write(data, fieldStart, pos - fieldStart)
            }
        }
        return if (modified) out.toByteArray() else null
    }

    private fun patchValue(data: ByteArray, start: Int, end: Int): ByteArray? {
        // Fast check: MVT Value.string_value is field 1, wireType 2 -> tag byte 0x0A
        if (start >= end || (data[start].toInt() and 0xFF) != 0x0A) return null
        val (lenLong, strStart) = readVarint(data, start + 1)
        val len = lenLong.toInt()
        val strEnd = strStart + len
        if (len <= 0 || strEnd != end) return null

        val original = String(data, strStart, len, Charsets.UTF_8)
        if (!KurdishArabicShaper.needsKurdishShaping(original)) return null
        val shaped = KurdishArabicShaper.shape(original)
        if (shaped == original) return null

        val shapedBytes = shaped.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(shapedBytes.size + 6)
        out.write(0x0A)
        writeVarint(out, shapedBytes.size.toLong())
        out.write(shapedBytes)
        return out.toByteArray()
    }

    private fun readVarint(buf: ByteArray, startPos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = startPos
        while (pos < buf.size && shift < 64) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result to pos
            shift += 7
        }
        throw IllegalArgumentException("Malformed varint")
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
    }

    private fun skipScalar(buf: ByteArray, pos: Int, wireType: Int): Int = when (wireType) {
        0 -> readVarint(buf, pos).second
        1 -> pos + 8
        5 -> pos + 4
        else -> throw IllegalArgumentException("Unsupported wireType $wireType")
    }
}
