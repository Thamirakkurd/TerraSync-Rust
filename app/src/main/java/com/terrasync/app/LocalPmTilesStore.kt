package com.terrasync.app

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Pure-Kotlin PMTiles v3 local archive reader for fast pre-built offline vector map packs.
 *
 * Uses lock-free positional [FileChannel.read] calls so MapLibre's concurrent HTTP worker pool
 * reads local tiles in <0.5 ms with zero SQLite lock contention.
 */
object LocalPmTilesStore {

    private const val HEADER_SIZE = 127
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_GZIP = 2
    private const val MAX_LEAF_CACHE = 32

    data class Header(
        val rootDirOffset: Long,
        val rootDirLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafDirsOffset: Long,
        val leafDirsLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val addressedTilesCount: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val minLng: Double,
        val minLat: Double,
        val maxLng: Double,
        val maxLat: Double
    )

    data class PackInfo(
        val packId: String,
        val fileName: String,
        val sizeBytes: Long,
        val header: Header
    )

    data class DirEntry(
        val tileId: Long,
        val offset: Long,
        val length: Int,
        val runLength: Int
    )

    sealed class TileLookup {
        class Found(val bytes: ByteArray, val isGzipped: Boolean) : TileLookup()
        object EmptyInBounds : TileLookup()
        object Miss : TileLookup()
    }

    private class OpenArchive(
        val packId: String,
        val file: File,
        val header: Header,
        val raf: RandomAccessFile,
        val channel: FileChannel,
        val rootEntries: List<DirEntry>
    ) {
        private val leafLock = Any()
        private val leafCache = object : LinkedHashMap<Long, List<DirEntry>>(MAX_LEAF_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<DirEntry>>?): Boolean {
                return size > MAX_LEAF_CACHE
            }
        }

        fun readSlice(offset: Long, length: Int): ByteArray? {
            if (offset < 0L || length <= 0 || length > 32 * 1024 * 1024) return null
            return runCatching {
                val buf = ByteBuffer.allocate(length)
                var pos = offset
                while (buf.hasRemaining()) {
                    val n = channel.read(buf, pos)
                    if (n <= 0) return@runCatching null
                    pos += n
                }
                buf.array()
            }.getOrNull()
        }

        fun getLeafDirectory(offset: Long, length: Int): List<DirEntry>? {
            synchronized(leafLock) {
                leafCache[offset]?.let { return it }
            }
            val raw = readSlice(header.leafDirsOffset + offset, length) ?: return null
            val decoded = parseDirectory(raw, header.internalCompression) ?: return null
            synchronized(leafLock) {
                leafCache[offset] = decoded
            }
            return decoded
        }

        fun lookupTile(tileId: Long): ByteArray? {
            RustCoreBridge.lookupPmTile(file, tileId)?.let { return if (it.isEmpty()) null else it }
            var entries = rootEntries
            for (depth in 0..3) {
                val entry = binarySearchEntry(entries, tileId) ?: return null
                if (entry.runLength == 0) {
                    entries = getLeafDirectory(entry.offset, entry.length) ?: return null
                } else if (tileId < entry.tileId + entry.runLength.toLong()) {
                    return readSlice(header.tileDataOffset + entry.offset, entry.length)
                } else {
                    return null
                }
            }
            return null
        }

        fun closeQuietly() {
            RustCoreBridge.evictPmTilesArchive(file.absolutePath)
            runCatching { channel.close() }
            runCatching { raf.close() }
        }
    }

    private val openArchives = ConcurrentHashMap<String, OpenArchive>()
    @Volatile private var sortedArchives: List<OpenArchive> = emptyList()
    @Volatile private var loadedForDir: String? = null

    private fun rebuildSortedArchives() {
        sortedArchives = openArchives.values.sortedBy {
            (it.header.maxLat - it.header.minLat) * (it.header.maxLng - it.header.minLng)
        }
    }

    fun getInstalledMinZoom(ctx: Context?): Int? {
        if (ctx != null && loadedForDir == null) ensureLoaded(ctx)
        return sortedArchives.minOfOrNull { it.header.minZoom }
    }

    fun getInstalledMaxZoom(ctx: Context?): Int? {
        if (ctx != null && loadedForDir == null) ensureLoaded(ctx)
        return sortedArchives.maxOfOrNull { it.header.maxZoom }
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, "offline_maps").apply { mkdirs() }

    fun packFileName(packId: String): String {
        val safe = packId.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(36).ifBlank { "region" }
        return "pack_$safe.pmtiles"
    }

    fun packFile(ctx: Context, packId: String): File = File(dir(ctx), packFileName(packId))

    fun hasAnyInstalledPack(ctx: Context?): Boolean {
        if (ctx != null && loadedForDir == null) ensureLoaded(ctx)
        return openArchives.isNotEmpty()
    }

    fun ensureLoaded(ctx: Context) {
        val base = dir(ctx)
        val key = base.absolutePath
        if (loadedForDir == key) return
        reloadAll(ctx)
    }

    @Synchronized
    fun reloadAll(ctx: Context) {
        val base = dir(ctx)
        val existingIds = openArchives.keys.toSet()
        val foundIds = HashSet<String>()
        val files = base.listFiles { f -> f.isFile && f.name.startsWith("pack_") && f.name.endsWith(".pmtiles") }
            .orEmpty()
        for (f in files) {
            val packId = f.name.removePrefix("pack_").removeSuffix(".pmtiles")
            foundIds.add(packId)
            val cur = openArchives[packId]
            if (cur == null || cur.file.length() != f.length() || cur.file.lastModified() != f.lastModified()) {
                openPackInternal(packId, f)?.let { opened ->
                    openArchives.put(packId, opened)?.closeQuietly()
                }
            }
        }
        for (oldId in existingIds) {
            if (oldId !in foundIds) {
                openArchives.remove(oldId)?.closeQuietly()
            }
        }
        rebuildSortedArchives()
        loadedForDir = base.absolutePath
    }

    @Synchronized
    fun registerPack(ctx: Context, packId: String): PackInfo? {
        val f = packFile(ctx, packId)
        if (!f.exists() || !f.isFile) {
            removePack(ctx, packId)
            return null
        }
        val opened = openPackInternal(packId, f) ?: return null
        openArchives.put(packId, opened)?.closeQuietly()
        rebuildSortedArchives()
        loadedForDir = dir(ctx).absolutePath
        return PackInfo(packId, f.name, f.length(), opened.header)
    }

    @Synchronized
    fun removePack(ctx: Context, packId: String): Boolean {
        openArchives.remove(packId)?.closeQuietly()
        rebuildSortedArchives()
        val f = packFile(ctx, packId)
        ParallelFileDownloader.clearPartialFiles(File(dir(ctx), "${f.name}.part"))
        return f.exists() && f.delete()
    }

    fun getPackInfo(ctx: Context, packId: String): PackInfo? {
        ensureLoaded(ctx)
        val cur = openArchives[packId]
        if (cur != null && cur.file.exists()) {
            return PackInfo(packId, cur.file.name, cur.file.length(), cur.header)
        }
        val f = packFile(ctx, packId)
        if (!f.exists() || !f.isFile) return null
        return registerPack(ctx, packId)
    }

    fun hasPartialPackData(ctx: Context, packId: String): Boolean {
        return File(dir(ctx), "${packFileName(packId)}.part").exists()
    }

    fun validateHeader(file: File): Header? {
        if (!file.exists() || !file.isFile || file.length() < HEADER_SIZE) return null
        return runCatching {
            val raw = ByteArray(HEADER_SIZE)
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(raw)
            }
            parseHeaderBytes(raw)
        }.getOrNull()
    }

    internal fun parseHeaderBytes(raw: ByteArray): Header? {
        if (raw.size < HEADER_SIZE) return null
        if (raw[0] != 'P'.code.toByte() ||
            raw[1] != 'M'.code.toByte() ||
            raw[2] != 'T'.code.toByte() ||
            raw[3] != 'i'.code.toByte() ||
            raw[4] != 'l'.code.toByte() ||
            raw[5] != 'e'.code.toByte() ||
            raw[6] != 's'.code.toByte() ||
            (raw[7].toInt() and 0xFF) != 3
        ) {
            return null
        }
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val rootDirOffset = bb.getLong(8)
        val rootDirLength = bb.getLong(16)
        val metadataOffset = bb.getLong(24)
        val metadataLength = bb.getLong(32)
        val leafDirsOffset = bb.getLong(40)
        val leafDirsLength = bb.getLong(48)
        val tileDataOffset = bb.getLong(56)
        val tileDataLength = bb.getLong(64)
        val addressedTilesCount = bb.getLong(72)
        val internalCompression = raw[97].toInt() and 0xFF
        val tileCompression = raw[98].toInt() and 0xFF
        val tileType = raw[99].toInt() and 0xFF
        val minZoom = raw[100].toInt() and 0xFF
        val maxZoom = raw[101].toInt() and 0xFF
        val minLng = bb.getInt(102) / 1e7
        val minLat = bb.getInt(106) / 1e7
        val maxLng = bb.getInt(110) / 1e7
        val maxLat = bb.getInt(114) / 1e7
        if (rootDirLength <= 0L || tileDataOffset < HEADER_SIZE) return null
        if (internalCompression != COMPRESSION_NONE && internalCompression != COMPRESSION_GZIP) return null
        if (tileCompression != COMPRESSION_NONE && tileCompression != COMPRESSION_GZIP) return null
        return Header(
            rootDirOffset = rootDirOffset,
            rootDirLength = rootDirLength,
            metadataOffset = metadataOffset,
            metadataLength = metadataLength,
            leafDirsOffset = leafDirsOffset,
            leafDirsLength = leafDirsLength,
            tileDataOffset = tileDataOffset,
            tileDataLength = tileDataLength,
            addressedTilesCount = addressedTilesCount,
            internalCompression = internalCompression,
            tileCompression = tileCompression,
            tileType = tileType,
            minZoom = minZoom,
            maxZoom = maxZoom,
            minLng = minLng,
            minLat = minLat,
            maxLng = maxLng,
            maxLat = maxLat
        )
    }

    private fun openPackInternal(packId: String, file: File): OpenArchive? {
        val header = validateHeader(file) ?: return null
        var raf: RandomAccessFile? = null
        return try {
            raf = RandomAccessFile(file, "r")
            val ch = raf.channel
            if (RustCoreBridge.warmPmTilesArchive(file)) {
                return OpenArchive(packId, file, header, raf, ch, emptyList())
            }
            val rootLen = header.rootDirLength.toInt()
            if (rootLen <= 0 || rootLen > 16 * 1024 * 1024) {
                raf.close()
                return null
            }
            val rootRaw = ByteArray(rootLen)
            val bb = ByteBuffer.wrap(rootRaw)
            var pos = header.rootDirOffset
            while (bb.hasRemaining()) {
                val n = ch.read(bb, pos)
                if (n <= 0) {
                    raf.close()
                    return null
                }
                pos += n
            }
            val rootEntries = parseDirectory(rootRaw, header.internalCompression)
            if (rootEntries == null) {
                raf.close()
                return null
            }
            OpenArchive(packId, file, header, raf, ch, rootEntries)
        } catch (_: Exception) {
            runCatching { raf?.close() }
            null
        }
    }

    internal fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        if (z == 0) return 0L
        val baseId = ((1L shl (2 * z)) - 1L) / 3L
        val n = 1L shl z
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = n ushr 1
        while (s > 0L) {
            val rx = if ((tx and s) != 0L) 1L else 0L
            val ry = if ((ty and s) != 0L) 1L else 0L
            d += s * s * ((3L * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) {
                    tx = n - 1L - tx
                    ty = n - 1L - ty
                }
                val tmp = tx
                tx = ty
                ty = tmp
            }
            s = s ushr 1
        }
        return baseId + d
    }

    internal fun parseDirectory(raw: ByteArray, compression: Int): List<DirEntry>? = runCatching {
        val data = when (compression) {
            COMPRESSION_NONE -> raw
            COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
            else -> return null
        }
        var pos = 0
        fun readVarint(): Long {
            var res = 0L
            var shift = 0
            while (pos < data.size && shift < 64) {
                val b = data[pos++].toInt() and 0xFF
                res = res or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return res
                shift += 7
            }
            throw IllegalArgumentException("Truncated PMTiles varint")
        }

        val numEntries = readVarint().toInt()
        if (numEntries < 0 || numEntries > 2_000_000) return null
        val tileIds = LongArray(numEntries)
        var lastId = 0L
        for (i in 0 until numEntries) {
            lastId += readVarint()
            tileIds[i] = lastId
        }
        val runLengths = IntArray(numEntries) { readVarint().toInt() }
        val lengths = IntArray(numEntries) { readVarint().toInt() }
        val offsets = LongArray(numEntries)
        for (i in 0 until numEntries) {
            val tmp = readVarint()
            offsets[i] = if (i > 0 && tmp == 0L) {
                offsets[i - 1] + lengths[i - 1].toLong()
            } else {
                tmp - 1L
            }
        }
        List(numEntries) { i ->
            DirEntry(tileIds[i], offsets[i], lengths[i], runLengths[i])
        }
    }.getOrNull()

    private fun binarySearchEntry(entries: List<DirEntry>, tileId: Long): DirEntry? {
        var lo = 0
        var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = entries[mid].tileId.compareTo(tileId)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return entries[mid]
            }
        }
        return if (hi >= 0) entries[hi] else null
    }

    private fun tileBounds(z: Int, x: Int, y: Int): DoubleArray {
        val n = (1L shl z).toDouble()
        val west = (x / n) * 360.0 - 180.0
        val east = ((x + 1) / n) * 360.0 - 180.0
        val north = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * y / n))))
        val south = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * (y + 1) / n))))
        return doubleArrayOf(south, west, north, east)
    }

    fun readTile(ctx: Context?, z: Int, x: Int, y: Int): TileLookup {
        if (ctx != null && loadedForDir == null) ensureLoaded(ctx)
        if (openArchives.isEmpty() || z < 0 || z > 22) return TileLookup.Miss

        val bounds = tileBounds(z, x, y)
        val tSouth = bounds[0]
        val tWest = bounds[1]
        val tNorth = bounds[2]
        val tEast = bounds[3]

        val tileId = zxyToTileId(z, x, y)
        var strictlyInsideAny = false

        // Prefer smaller (higher-detail) packs first (pre-sorted on pack load/change)
        val archives = sortedArchives
        for (arc in archives) {
            val h = arc.header
            if (z < h.minZoom || z > h.maxZoom) continue
            val overlaps = tEast >= h.minLng && tWest <= h.maxLng && tNorth >= h.minLat && tSouth <= h.maxLat
            if (!overlaps) continue
            val bytes = arc.lookupTile(tileId)
            if (bytes != null) {
                return TileLookup.Found(bytes, h.tileCompression == COMPRESSION_GZIP)
            }
            if (tWest >= h.minLng && tEast <= h.maxLng && tSouth >= h.minLat && tNorth <= h.maxLat) {
                strictlyInsideAny = true
            }
        }
        return if (strictlyInsideAny) TileLookup.EmptyInBounds else TileLookup.Miss
    }
}
