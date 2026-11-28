package com.terrasync.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.StatFs
import android.os.SystemClock
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class OfflineRoadRegion(
    val fileName: String,
    val name: String,
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double,
    val nodeCount: Int,
    val edgeCount: Int,
    val sizeBytes: Long,
    val downloadedAt: Long
) {
    fun contains(lat: Double, lng: Double, padDeg: Double = 0.015): Boolean {
        return lat in (minLat - padDeg)..(maxLat + padDeg) &&
            lng in (minLng - padDeg)..(maxLng + padDeg)
    }

    fun areaDegSq(): Double {
        return ((maxLat - minLat).coerceAtLeast(0.0001)) * ((maxLng - minLng).coerceAtLeast(0.0001))
    }
}

object OfflineRoutingEngine {

    private const val MAGIC = 0x54535231 // TSR1
    private const val FLAG_CAR = 1
    private const val FLAG_BIKE = 2
    private const val FLAG_WALK = 4
    internal const val PREBUILT_ROAD_PACK_BASE_URL =
        "https://github.com/Thamirakkurd/TerraSync/releases/download/road-packs"

    internal fun prebuiltPackUrl(packId: String): String =
        "$PREBUILT_ROAD_PACK_BASE_URL/${packFileName(packId)}"

    private class SpatialNodeGrid(
        val minLat: Double,
        val minLng: Double,
        val cellLat: Double,
        val cellLng: Double,
        val rows: Int,
        val cols: Int,
        val head: IntArray,
        val next: IntArray
    )

    private class RoadGraph(
        val meta: OfflineRoadRegion,
        val lats: FloatArray,
        val lngs: FloatArray,
        val adjHead: IntArray,
        val edgeTo: IntArray,
        val edgeNext: IntArray,
        val edgeDist: FloatArray,
        val edgeSpeed: FloatArray,
        val edgeFlags: ByteArray,
        val edgeNameIdx: IntArray,
        val nodeOutFlags: ByteArray,
        val nodeInFlags: ByteArray,
        val names: Array<String>,
        val spatialGrid: SpatialNodeGrid
    )

    private class AStarScratch {
        var epoch: Int = 1
        var seenEpoch = IntArray(0)
        var closedEpoch = IntArray(0)
        var gScore = DoubleArray(0)
        var prevNode = IntArray(0)
        var prevEdge = IntArray(0)

        fun prepare(n: Int): Int {
            if (seenEpoch.size < n) {
                val cap = maxOf(n, 256)
                seenEpoch = IntArray(cap)
                closedEpoch = IntArray(cap)
                gScore = DoubleArray(cap)
                prevNode = IntArray(cap)
                prevEdge = IntArray(cap)
                epoch = 1
            } else if (epoch >= Int.MAX_VALUE - 4) {
                seenEpoch.fill(0)
                closedEpoch.fill(0)
                epoch = 1
            } else {
                epoch++
            }
            return epoch
        }
    }

    private val aStarScratch = ThreadLocal.withInitial { AStarScratch() }

    private fun buildSpatialGrid(lats: FloatArray, lngs: FloatArray): SpatialNodeGrid {
        val n = lats.size
        if (n == 0) {
            return SpatialNodeGrid(0.0, 0.0, 0.01, 0.01, 1, 1, intArrayOf(-1), IntArray(0))
        }
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLng = Double.POSITIVE_INFINITY
        var maxLng = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val la = lats[i].toDouble()
            val lo = lngs[i].toDouble()
            if (la < minLat) minLat = la
            if (la > maxLat) maxLat = la
            if (lo < minLng) minLng = lo
            if (lo > maxLng) maxLng = lo
        }
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-4)
        val lngSpan = (maxLng - minLng).coerceAtLeast(1e-4)
        val rows = ceil(latSpan / 0.01).toInt().coerceIn(1, 512)
        val cols = ceil(lngSpan / 0.01).toInt().coerceIn(1, 512)
        val cellLat = latSpan / rows
        val cellLng = lngSpan / cols
        val head = IntArray(rows * cols) { -1 }
        val next = IntArray(n) { -1 }
        for (i in 0 until n) {
            val r = ((lats[i].toDouble() - minLat) / cellLat).toInt().coerceIn(0, rows - 1)
            val c = ((lngs[i].toDouble() - minLng) / cellLng).toInt().coerceIn(0, cols - 1)
            val cellIdx = r * cols + c
            next[i] = head[cellIdx]
            head[cellIdx] = i
        }
        return SpatialNodeGrid(minLat, minLng, cellLat, cellLng, rows, cols, head, next)
    }

    private val graphCacheLock = Any()
    private val graphCache = android.util.LruCache<String, RoadGraph>(2)
    private data class CachedHeader(val lastModified: Long, val sizeBytes: Long, val region: OfflineRoadRegion)
    private val headerCache = ConcurrentHashMap<String, CachedHeader>()

    private class DownloadJob {
        val cancelled = AtomicBoolean(false)
        @Volatile var activeCall: Call? = null
        @Volatile var parallelHandle: ParallelFileDownloader.Handle? = null
        @Volatile var workerThread: Thread? = null

        fun cancelAllCalls() {
            val single = activeCall
            activeCall = null
            single?.cancel()
            parallelHandle?.cancelAll()
        }
    }

    private val overpassClient: OkHttpClient by lazy {
        ParallelFileDownloader.client.newBuilder()
            .readTimeout(70, TimeUnit.SECONDS)
            .build()
    }

    private val activeDownloads = ConcurrentHashMap<String, DownloadJob>()

    private fun dir(ctx: Context): File = File(ctx.filesDir, "offline_routes").apply { mkdirs() }

    private fun safeName(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(36).ifBlank { "region" }

    fun packFileName(packId: String): String = "pack_${safeName(packId)}.tsr"

    const val MIN_FREE_BYTES_ROADS = 120L * 1024L * 1024L

    fun availableStorageBytes(ctx: Context): Long {
        return runCatching { StatFs(ctx.filesDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
    }

    fun hasEnoughStorage(ctx: Context, requiredBytes: Long): Boolean {
        return availableStorageBytes(ctx) >= requiredBytes
    }

    fun cleanupOrphanedTempFiles(ctx: Context) {
        runCatching {
            val base = dir(ctx)
            val entries = base.listFiles() ?: return@runCatching
            val activeKeys = activeDownloads.entries
                .filter { !it.value.cancelled.get() }
                .map { safeName(it.key) }
                .toSet()
            for (entry in entries) {
                if (entry.isFile && (entry.name.endsWith(".part") || entry.name.endsWith(".tmp"))) {
                    val stem = entry.name.substringBefore(".tsr")
                    val belongsToActive = activeKeys.any { stem == "pack_$it" || stem.startsWith("${it}_") }
                    val hasResumeMeta = entry.name.endsWith(".part") && ParallelFileDownloader.metaFileFor(entry).exists()
                    if (!belongsToActive && !hasResumeMeta) entry.delete()
                } else if (entry.isDirectory && entry.name.startsWith("staging_")) {
                    val key = entry.name.removePrefix("staging_")
                    if (key !in activeKeys) {
                        for (child in entry.listFiles().orEmpty()) {
                            if (child.name.startsWith("stage.db") || child.name.endsWith(".part") || child.name.endsWith(".tmp")) {
                                child.delete()
                            }
                        }
                        if (entry.listFiles().isNullOrEmpty()) {
                            entry.delete()
                        }
                    }
                }
            }
        }
    }

    private fun stagingDir(ctx: Context, jobKey: String): File =
        File(dir(ctx), "staging_${safeName(jobKey)}")

    fun hasPartialPackData(ctx: Context, packId: String): Boolean {
        val base = dir(ctx)
        val fileName = packFileName(packId)
        if (File(base, "$fileName.part").exists() || File(base, "$fileName.tmp").exists()) return true
        val stage = stagingDir(ctx, packId)
        if (!stage.exists() || !stage.isDirectory) return false
        val entries = stage.listFiles()
        return !entries.isNullOrEmpty()
    }

    fun getPackRegion(ctx: Context, packId: String): OfflineRoadRegion? {
        val fileName = packFileName(packId)
        val f = File(dir(ctx), fileName)
        if (!f.exists() || !f.isFile) {
            headerCache.remove(fileName)
            return null
        }
        return readHeader(f)
    }

    fun isDownloading(packId: String): Boolean {
        val job = activeDownloads[packId] ?: return false
        return !job.cancelled.get()
    }

    fun cancelDownload(packId: String) {
        val job = activeDownloads[packId] ?: return
        job.cancelled.set(true)
        job.cancelAllCalls()
        job.workerThread?.interrupt()
    }

    fun deletePackRegion(ctx: Context, packId: String): Boolean {
        cancelDownload(packId)
        val fileName = packFileName(packId)
        val base = dir(ctx)
        ParallelFileDownloader.clearPartialFiles(File(base, "$fileName.part"))
        File(base, "$fileName.tmp").delete()
        stagingDir(ctx, packId).deleteRecursively()
        return deleteRegion(ctx, fileName)
    }

    fun listRegions(ctx: Context): List<OfflineRoadRegion> {
        val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".tsr") } ?: return emptyList()
        return files.mapNotNull { readHeader(it) }.sortedByDescending { it.downloadedAt }
    }

    fun deleteRegion(ctx: Context, fileName: String): Boolean {
        val f = File(dir(ctx), fileName)
        RustCoreBridge.evictRoadGraph(f.absolutePath)
        headerCache.remove(fileName)
        synchronized(graphCacheLock) {
            graphCache.remove(fileName)
        }
        return f.exists() && f.delete()
    }

    fun hasAnyRegion(ctx: Context): Boolean {
        val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".tsr") }
        return !files.isNullOrEmpty()
    }

    internal fun writeTsrHeader(
        dos: DataOutputStream,
        regionName: String,
        sLat: Double,
        wLng: Double,
        nLat: Double,
        eLng: Double,
        now: Long,
        nodeCount: Int,
        edgeCount: Int,
        nameCount: Int
    ) {
        dos.writeInt(MAGIC)
        dos.writeUTF(regionName)
        dos.writeDouble(sLat)
        dos.writeDouble(wLng)
        dos.writeDouble(nLat)
        dos.writeDouble(eLng)
        dos.writeLong(now)
        dos.writeInt(nodeCount)
        dos.writeInt(edgeCount)
        dos.writeInt(nameCount)
    }

    internal fun readTsrHeaderFromStream(
        fileName: String,
        sizeBytes: Long,
        input: InputStream
    ): OfflineRoadRegion? {
        return runCatching {
            DataInputStream(GZIPInputStream(BufferedInputStream(input, 8192))).use { dis ->
                val magic = dis.readInt()
                if (magic != MAGIC) return null
                val name = dis.readUTF()
                val minLat = dis.readDouble()
                val minLng = dis.readDouble()
                val maxLat = dis.readDouble()
                val maxLng = dis.readDouble()
                val downloadedAt = dis.readLong()
                val nodeCount = dis.readInt()
                val edgeCount = dis.readInt()
                OfflineRoadRegion(
                    fileName = fileName,
                    name = name,
                    minLat = minLat,
                    minLng = minLng,
                    maxLat = maxLat,
                    maxLng = maxLng,
                    nodeCount = nodeCount,
                    edgeCount = edgeCount,
                    sizeBytes = sizeBytes,
                    downloadedAt = downloadedAt
                )
            }
        }.getOrNull()
    }

    internal fun streamOverpassNodes(
        input: InputStream,
        onCheckCancel: () -> Unit = {},
        onNode: (id: Long, lat: Double, lon: Double) -> Unit
    ): Pair<Boolean, String?> {
        var parsedElements = false
        var remarkErr: String? = null
        JsonReader(InputStreamReader(BufferedInputStream(input, 32 * 1024), Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                onCheckCancel()
                when (reader.nextName()) {
                    "remark" -> remarkErr = if (reader.peek() == JsonToken.STRING) reader.nextString() else { reader.skipValue(); null }
                    "elements" -> {
                        parsedElements = true
                        reader.beginArray()
                        while (reader.hasNext()) {
                            onCheckCancel()
                            var elemType = ""
                            var id = 0L
                            var lat = Double.NaN
                            var lon = Double.NaN
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "type" -> elemType = if (reader.peek() == JsonToken.STRING) reader.nextString() else { reader.skipValue(); "" }
                                    "id" -> id = if (reader.peek() == JsonToken.NUMBER) reader.nextLong() else { reader.skipValue(); 0L }
                                    "lat" -> lat = if (reader.peek() == JsonToken.NUMBER) reader.nextDouble() else { reader.skipValue(); Double.NaN }
                                    "lon" -> lon = if (reader.peek() == JsonToken.NUMBER) reader.nextDouble() else { reader.skipValue(); Double.NaN }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (elemType == "node" && id != 0L && !lat.isNaN() && !lon.isNaN()) {
                                onNode(id, lat, lon)
                            }
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return parsedElements to remarkErr
    }

    internal fun streamOverpassWays(
        input: InputStream,
        onCheckCancel: () -> Unit = {},
        onWay: (wid: Long, hw: String, access: String, roadName: String, onewayTag: String, junction: String, nodeIds: List<Long>) -> Unit
    ) {
        val wayNodeIds = ArrayList<Long>(64)
        JsonReader(InputStreamReader(BufferedInputStream(input, 32 * 1024), Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                onCheckCancel()
                if (reader.nextName() == "elements") {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        onCheckCancel()
                        var elemType = ""
                        var wid = 0L
                        var hw = ""
                        var access = ""
                        var roadName = ""
                        var onewayTag = ""
                        var junction = ""
                        wayNodeIds.clear()

                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "type" -> elemType = if (reader.peek() == JsonToken.STRING) reader.nextString() else { reader.skipValue(); "" }
                                "id" -> wid = if (reader.peek() == JsonToken.NUMBER) reader.nextLong() else { reader.skipValue(); 0L }
                                "nodes" -> {
                                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            if (reader.peek() == JsonToken.NUMBER) wayNodeIds.add(reader.nextLong()) else reader.skipValue()
                                        }
                                        reader.endArray()
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                                "tags" -> {
                                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            when (reader.nextName()) {
                                                "highway" -> hw = if (reader.peek() == JsonToken.STRING) reader.nextString() else { reader.skipValue(); "" }
                                                "access" -> access = if (reader.peek() == JsonToken.STRING) reader.nextString() else { reader.skipValue(); "" }
                                                "name" -> roadName = if (reader.peek() == JsonToken.STRING) reader.nextString().trim() else { reader.skipValue(); "" }
                                                "oneway" -> onewayTag = if (reader.peek() == JsonToken.STRING) reader.nextString() else { reader.skipValue(); "" }
                                                "junction" -> junction = if (reader.peek() == JsonToken.STRING) reader.nextString() else { reader.skipValue(); "" }
                                                else -> reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (elemType == "way" && wid != 0L && hw.isNotEmpty() && wayNodeIds.size >= 2) {
                            onWay(wid, hw, access, roadName, onewayTag, junction, wayNodeIds)
                        }
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
    }

    private fun readHeader(file: File): OfflineRoadRegion? {
        val lm = file.lastModified()
        val len = file.length()
        val cached = headerCache[file.name]
        if (cached != null && cached.lastModified == lm && cached.sizeBytes == len) {
            return cached.region
        }
        return FileInputStream(file).use { fis ->
            readTsrHeaderFromStream(file.name, len, fis)?.also { reg ->
                headerCache[file.name] = CachedHeader(lm, len, reg)
            }
        }
    }

    private fun loadGraph(ctx: Context, fileName: String): RoadGraph? = synchronized(graphCacheLock) {
        graphCache.get(fileName)?.let { return it }
        val file = File(dir(ctx), fileName)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(GZIPInputStream(BufferedInputStream(FileInputStream(file)))).use { dis ->
                val magic = dis.readInt()
                if (magic != MAGIC) return null
                val name = dis.readUTF()
                val minLat = dis.readDouble()
                val minLng = dis.readDouble()
                val maxLat = dis.readDouble()
                val maxLng = dis.readDouble()
                val downloadedAt = dis.readLong()
                val nodeCount = dis.readInt()
                val edgeCount = dis.readInt()
                val nameCount = dis.readInt()
                val names = Array(nameCount) { dis.readUTF() }

                val lats = FloatArray(nodeCount)
                val lngs = FloatArray(nodeCount)
                for (i in 0 until nodeCount) {
                    lats[i] = dis.readFloat()
                    lngs[i] = dis.readFloat()
                }

                val adjHead = IntArray(nodeCount) { -1 }
                val edgeTo = IntArray(edgeCount)
                val edgeNext = IntArray(edgeCount)
                val edgeDist = FloatArray(edgeCount)
                val edgeSpeed = FloatArray(edgeCount)
                val edgeFlags = ByteArray(edgeCount)
                val edgeNameIdx = IntArray(edgeCount)
                val nodeOutFlags = ByteArray(nodeCount)
                val nodeInFlags = ByteArray(nodeCount)

                for (e in 0 until edgeCount) {
                    val from = dis.readInt()
                    val to = dis.readInt()
                    val dist = dis.readFloat()
                    val spd = dis.readFloat()
                    val flg = dis.readByte()
                    val nIdx = dis.readInt()
                    edgeTo[e] = to
                    edgeDist[e] = dist
                    edgeSpeed[e] = spd
                    edgeFlags[e] = flg
                    edgeNameIdx[e] = nIdx
                    edgeNext[e] = adjHead[from]
                    adjHead[from] = e
                    nodeOutFlags[from] = (nodeOutFlags[from].toInt() or flg.toInt()).toByte()
                    nodeInFlags[to] = (nodeInFlags[to].toInt() or flg.toInt()).toByte()
                }

                val meta = OfflineRoadRegion(
                    fileName = file.name,
                    name = name,
                    minLat = minLat,
                    minLng = minLng,
                    maxLat = maxLat,
                    maxLng = maxLng,
                    nodeCount = nodeCount,
                    edgeCount = edgeCount,
                    sizeBytes = file.length(),
                    downloadedAt = downloadedAt
                )
                RoadGraph(
                    meta, lats, lngs, adjHead, edgeTo, edgeNext, edgeDist, edgeSpeed,
                    edgeFlags, edgeNameIdx, nodeOutFlags, nodeInFlags, names,
                    buildSpatialGrid(lats, lngs)
                ).also {
                    graphCache.put(fileName, it)
                }
            }
        }.getOrNull()
    }

    private class CancelledSignal : Exception("Cancelled")

    private data class ChunkSpec(val label: String, val query: String)

    private data class NodePt(val idx: Int, val lat: Float, val lng: Float)

    fun downloadPackRoadGraph(
        ctx: Context,
        packId: String,
        regionName: String,
        minLat: Double,
        minLng: Double,
        maxLat: Double,
        maxLng: Double,
        onProgress: (String, Int) -> Unit,
        onDone: (OfflineRoadRegion?, String?) -> Unit
    ) {
        downloadRoadGraphInternal(ctx, packId, regionName, minLat, minLng, maxLat, maxLng, onProgress, onDone)
    }

    internal interface TsrCompilerBackend : AutoCloseable {
        fun streamChunkIntoDb(chunkFile: File, chunkLabel: String, basePct: Int, chunkCount: Int)
        fun clearChunkNodes()
        fun counts(): Triple<Int, Int, Int>
        fun estimatedOutputBytes(): Long
        fun writeTsr(
            partFile: File,
            outFile: File,
            regionName: String,
            sLat: Double,
            wLng: Double,
            nLat: Double,
            eLng: Double,
            now: Long,
            nodeCount: Int,
            edgeCount: Int,
            nameCount: Int
        ): OfflineRoadRegion
    }

    private class RustTsrStagingCompiler(
        private var handle: Long,
        private val checkCancelled: () -> Unit,
        private val emitProgress: (String, Int, Boolean) -> Unit
    ) : TsrCompilerBackend {
        override fun streamChunkIntoDb(chunkFile: File, chunkLabel: String, basePct: Int, chunkCount: Int) {
            checkCancelled()
            emitProgress("Compiling roads ($chunkLabel)...", basePct + (50 / chunkCount), true)
            val err = RustCoreBridge.tsrCompilerIngestChunk(handle, chunkFile.absolutePath)
            checkCancelled()
            if (err != null) {
                throw IllegalStateException(err)
            }
            emitProgress("Compiled roads ($chunkLabel)...", basePct + (72 / chunkCount), false)
        }

        override fun clearChunkNodes() {
            // Chunk node map is cleared automatically in Rust after each chunk
        }

        override fun counts(): Triple<Int, Int, Int> =
            RustCoreBridge.tsrCompilerCounts(handle) ?: Triple(0, 0, 0)

        override fun estimatedOutputBytes(): Long = 64L * 1024L * 1024L

        override fun writeTsr(
            partFile: File,
            outFile: File,
            regionName: String,
            sLat: Double,
            wLng: Double,
            nLat: Double,
            eLng: Double,
            now: Long,
            nodeCount: Int,
            edgeCount: Int,
            nameCount: Int
        ): OfflineRoadRegion {
            checkCancelled()
            emitProgress("Saving offline road graph (92%)...", 92, true)
            val (sizeBytes, err) = RustCoreBridge.tsrCompilerWriteTsr(
                handle = handle,
                partPath = partFile.absolutePath,
                outPath = outFile.absolutePath,
                regionName = regionName,
                sLat = sLat,
                wLng = wLng,
                nLat = nLat,
                eLng = eLng,
                now = now
            )
            if (err != null) throw IllegalStateException(err)
            checkCancelled()
            return OfflineRoadRegion(
                fileName = outFile.name,
                name = regionName,
                minLat = sLat,
                minLng = wLng,
                maxLat = nLat,
                maxLng = eLng,
                nodeCount = nodeCount,
                edgeCount = edgeCount,
                sizeBytes = if (sizeBytes > 0L) sizeBytes else outFile.length(),
                downloadedAt = now
            )
        }

        override fun close() {
            val h = handle
            if (h > 0L) {
                handle = -1L
                RustCoreBridge.tsrCompilerDestroy(h)
                RustCoreBridge.trimNativeMemory(aggressive = false)
            }
        }
    }

    private fun openTsrCompiler(
        stageDir: File,
        checkCancelled: () -> Unit,
        emitProgress: (String, Int, Boolean) -> Unit
    ): TsrCompilerBackend {
        val rustHandle = RustCoreBridge.tsrCompilerCreate()
        return if (rustHandle > 0L) {
            RustTsrStagingCompiler(rustHandle, checkCancelled, emitProgress)
        } else {
            RoadGraphStagingCompiler(stageDir, checkCancelled, emitProgress)
        }
    }

    internal class RoadGraphStagingCompiler(
        private val stageDir: File,
        private val checkCancelled: () -> Unit,
        private val emitProgress: (String, Int, Boolean) -> Unit
    ) : TsrCompilerBackend {
        val stageDbFile = File(stageDir, "stage.db")
        private val db: SQLiteDatabase
        private val openStmts = ArrayList<SQLiteStatement>(5)
        private val stmtChunkNode: SQLiteStatement
        private val stmtNodeInsert: SQLiteStatement
        private val stmtWayInsert: SQLiteStatement
        private val stmtNameInsert: SQLiteStatement
        private val stmtEdgeInsert: SQLiteStatement
        private val nodeCache = android.util.LruCache<Long, NodePt>(16384)
        private val nameCache = android.util.LruCache<String, Int>(2048).apply { put("", 0) }
        private var nextNodeIdx = 0
        private var nextNameIdx = 1

        init {
            stageDir.mkdirs()
            stageDbFile.delete()
            File(stageDir, "stage.db-journal").delete()
            File(stageDir, "stage.db-wal").delete()
            File(stageDir, "stage.db-shm").delete()

            db = SQLiteDatabase.openOrCreateDatabase(stageDbFile, null)
            db.rawQuery("PRAGMA journal_mode = OFF", null).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous = OFF")
            db.execSQL("PRAGMA temp_store = FILE")
            db.execSQL("PRAGMA cache_size = -4000")
            db.execSQL("CREATE TABLE chunk_nodes(osm_id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL)")
            db.execSQL("CREATE TABLE nodes(idx INTEGER PRIMARY KEY, osm_id INTEGER UNIQUE NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL)")
            db.execSQL("CREATE TABLE ways(way_id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE names(idx INTEGER PRIMARY KEY, name TEXT UNIQUE NOT NULL)")
            db.execSQL("CREATE TABLE edges(from_idx INTEGER NOT NULL, to_idx INTEGER NOT NULL, dist REAL NOT NULL, speed REAL NOT NULL, flags INTEGER NOT NULL, name_idx INTEGER NOT NULL)")
            db.execSQL("INSERT INTO names(idx, name) VALUES (0, '')")

            stmtChunkNode = db.compileStatement("INSERT OR REPLACE INTO chunk_nodes(osm_id, lat, lon) VALUES (?, ?, ?)").also { openStmts.add(it) }
            stmtNodeInsert = db.compileStatement("INSERT INTO nodes(idx, osm_id, lat, lon) VALUES (?, ?, ?, ?)").also { openStmts.add(it) }
            stmtWayInsert = db.compileStatement("INSERT OR IGNORE INTO ways(way_id) VALUES (?)").also { openStmts.add(it) }
            stmtNameInsert = db.compileStatement("INSERT OR IGNORE INTO names(idx, name) VALUES (?, ?)").also { openStmts.add(it) }
            stmtEdgeInsert = db.compileStatement("INSERT INTO edges(from_idx, to_idx, dist, speed, flags, name_idx) VALUES (?, ?, ?, ?, ?, ?)").also { openStmts.add(it) }
        }

        private fun resolveNameIdx(name: String): Int {
            if (name.isEmpty()) return 0
            nameCache.get(name)?.let { return it }
            db.rawQuery("SELECT idx FROM names WHERE name = ? LIMIT 1", arrayOf(name)).use { c ->
                if (c.moveToFirst()) {
                    val existing = c.getInt(0)
                    nameCache.put(name, existing)
                    return existing
                }
            }
            val idx = nextNameIdx++
            stmtNameInsert.clearBindings()
            stmtNameInsert.bindLong(1, idx.toLong())
            stmtNameInsert.bindString(2, name)
            stmtNameInsert.executeInsert()
            nameCache.put(name, idx)
            return idx
        }

        private fun resolveWayNodes(
            wayNodeIds: List<Long>,
            resolved: HashMap<Long, NodePt>,
            missing: ArrayList<Long>,
            stillMissing: ArrayList<Long>,
            seenMissing: HashSet<Long>,
            sb: StringBuilder
        ) {
            resolved.clear()
            missing.clear()
            stillMissing.clear()
            seenMissing.clear()

            for (i in 0 until wayNodeIds.size) {
                val id = wayNodeIds[i]
                if (resolved.containsKey(id)) continue
                val cached = nodeCache.get(id)
                if (cached != null) {
                    resolved[id] = cached
                } else if (seenMissing.add(id)) {
                    missing.add(id)
                }
            }
            if (missing.isEmpty()) return

            var offset = 0
            while (offset < missing.size) {
                val end = minOf(offset + 400, missing.size)
                sb.setLength(0)
                sb.append("SELECT osm_id, idx, lat, lon FROM nodes WHERE osm_id IN (")
                for (i in offset until end) {
                    if (i > offset) sb.append(',')
                    sb.append(missing[i])
                }
                sb.append(')')
                db.rawQuery(sb.toString(), null).use { c ->
                    while (c.moveToNext()) {
                        val osmId = c.getLong(0)
                        val pt = NodePt(c.getInt(1), c.getDouble(2).toFloat(), c.getDouble(3).toFloat())
                        resolved[osmId] = pt
                        nodeCache.put(osmId, pt)
                    }
                }
                offset = end
            }

            for (i in 0 until missing.size) {
                val id = missing[i]
                if (!resolved.containsKey(id)) {
                    stillMissing.add(id)
                }
            }
            if (stillMissing.isEmpty()) return

            offset = 0
            while (offset < stillMissing.size) {
                val end = minOf(offset + 400, stillMissing.size)
                sb.setLength(0)
                sb.append("SELECT osm_id, lat, lon FROM chunk_nodes WHERE osm_id IN (")
                for (i in offset until end) {
                    if (i > offset) sb.append(',')
                    sb.append(stillMissing[i])
                }
                sb.append(')')
                db.rawQuery(sb.toString(), null).use { c ->
                    while (c.moveToNext()) {
                        val osmId = c.getLong(0)
                        if (resolved.containsKey(osmId)) continue
                        val lat = c.getDouble(1)
                        val lon = c.getDouble(2)
                        val idx = nextNodeIdx++
                        stmtNodeInsert.clearBindings()
                        stmtNodeInsert.bindLong(1, idx.toLong())
                        stmtNodeInsert.bindLong(2, osmId)
                        stmtNodeInsert.bindDouble(3, lat)
                        stmtNodeInsert.bindDouble(4, lon)
                        stmtNodeInsert.executeInsert()
                        val pt = NodePt(idx, lat.toFloat(), lon.toFloat())
                        resolved[osmId] = pt
                        nodeCache.put(osmId, pt)
                    }
                }
                offset = end
            }
        }

        override fun streamChunkIntoDb(chunkFile: File, chunkLabel: String, basePct: Int, chunkCount: Int) {
            emitProgress("Indexing road nodes ($chunkLabel)...", basePct + (45 / chunkCount), true)
            var ops = 0
            var inTx = true
            db.beginTransaction()
            val (parsedElements, remarkErr) = try {
                FileInputStream(chunkFile).use { fis ->
                    streamOverpassNodes(fis, checkCancelled) { id, lat, lon ->
                        stmtChunkNode.clearBindings()
                        stmtChunkNode.bindLong(1, id)
                        stmtChunkNode.bindDouble(2, lat)
                        stmtChunkNode.bindDouble(3, lon)
                        stmtChunkNode.executeInsert()
                        ops++
                        if (ops >= 1000) {
                            db.setTransactionSuccessful()
                            db.endTransaction()
                            db.beginTransaction()
                            ops = 0
                            emitProgress("Indexing road nodes ($chunkLabel)...", basePct + (55 / chunkCount), false)
                        }
                    }
                }.also {
                    if (inTx) {
                        db.setTransactionSuccessful()
                        db.endTransaction()
                        inTx = false
                    }
                }
            } finally {
                if (inTx && db.inTransaction()) runCatching { db.endTransaction() }
            }
            if (!parsedElements) {
                throw IllegalStateException(remarkErr ?: "Overpass returned invalid JSON")
            }

            emitProgress("Processing roads ($chunkLabel)...", basePct + (62 / chunkCount), true)
            val wayNodeMap = HashMap<Long, NodePt>(64)
            val missingIds = ArrayList<Long>(64)
            val stillMissingIds = ArrayList<Long>(64)
            val seenMissing = HashSet<Long>(64)
            val inClauseSb = StringBuilder(256)
            ops = 0
            inTx = true
            db.beginTransaction()
            try {
                FileInputStream(chunkFile).use { fis ->
                    streamOverpassWays(fis, checkCancelled) { wid, hw, access, roadName, onewayTag, junction, wayNodeIds ->
                        if (access == "private" || access == "no") return@streamOverpassWays

                        stmtWayInsert.clearBindings()
                        stmtWayInsert.bindLong(1, wid)
                        if (stmtWayInsert.executeInsert() == -1L) return@streamOverpassWays

                        val (carOk, bikeOk, walkOk, carSpeed) = when (hw) {
                            "motorway", "motorway_link" -> Quad(true, false, false, 95f)
                            "trunk", "trunk_link" -> Quad(true, true, true, 80f)
                            "primary", "primary_link" -> Quad(true, true, true, 65f)
                            "secondary", "secondary_link" -> Quad(true, true, true, 55f)
                            "tertiary", "tertiary_link" -> Quad(true, true, true, 45f)
                            "unclassified", "road" -> Quad(true, true, true, 40f)
                            "residential" -> Quad(true, true, true, 30f)
                            "living_street", "service" -> Quad(true, true, true, 20f)
                            "track" -> Quad(true, true, true, 16f)
                            "cycleway" -> Quad(false, true, true, 15f)
                            "path", "footway" -> Quad(false, true, true, 10f)
                            else -> Quad(false, false, false, 15f)
                        }

                        val isOneWayFwd = onewayTag == "yes" || onewayTag == "true" || onewayTag == "1" ||
                            junction == "roundabout" || hw == "motorway" || hw == "motorway_link"
                        val isOneWayRev = onewayTag == "-1"
                        val fwdFlags = (if (carOk && !isOneWayRev) FLAG_CAR else 0) or
                            (if (bikeOk && !isOneWayRev) FLAG_BIKE else 0) or
                            (if (walkOk) FLAG_WALK else 0)
                        val revFlags = (if (carOk && !isOneWayFwd) FLAG_CAR else 0) or
                            (if (bikeOk && !isOneWayFwd) FLAG_BIKE else 0) or
                            (if (walkOk) FLAG_WALK else 0)
                        if (fwdFlags == 0 && revFlags == 0) return@streamOverpassWays

                        val nIdx = resolveNameIdx(roadName)
                        resolveWayNodes(wayNodeIds, wayNodeMap, missingIds, stillMissingIds, seenMissing, inClauseSb)
                        var prev: NodePt? = null
                        for (k in 0 until wayNodeIds.size) {
                            val curr = wayNodeMap[wayNodeIds[k]]
                            if (prev != null && curr != null && prev.idx != curr.idx) {
                                val d = RoutingEngine.haversineMeters(
                                    prev.lat.toDouble(), prev.lng.toDouble(),
                                    curr.lat.toDouble(), curr.lng.toDouble()
                                ).toFloat().coerceAtLeast(0.5f)
                                if (fwdFlags != 0) {
                                    stmtEdgeInsert.clearBindings()
                                    stmtEdgeInsert.bindLong(1, prev.idx.toLong())
                                    stmtEdgeInsert.bindLong(2, curr.idx.toLong())
                                    stmtEdgeInsert.bindDouble(3, d.toDouble())
                                    stmtEdgeInsert.bindDouble(4, carSpeed.toDouble())
                                    stmtEdgeInsert.bindLong(5, fwdFlags.toLong())
                                    stmtEdgeInsert.bindLong(6, nIdx.toLong())
                                    stmtEdgeInsert.executeInsert()
                                    ops++
                                }
                                if (revFlags != 0) {
                                    stmtEdgeInsert.clearBindings()
                                    stmtEdgeInsert.bindLong(1, curr.idx.toLong())
                                    stmtEdgeInsert.bindLong(2, prev.idx.toLong())
                                    stmtEdgeInsert.bindDouble(3, d.toDouble())
                                    stmtEdgeInsert.bindDouble(4, carSpeed.toDouble())
                                    stmtEdgeInsert.bindLong(5, revFlags.toLong())
                                    stmtEdgeInsert.bindLong(6, nIdx.toLong())
                                    stmtEdgeInsert.executeInsert()
                                    ops++
                                }
                            }
                            if (curr != null) prev = curr
                        }
                        if (ops >= 1000) {
                            db.setTransactionSuccessful()
                            db.endTransaction()
                            db.beginTransaction()
                            ops = 0
                            emitProgress("Processing roads ($chunkLabel)...", basePct + (72 / chunkCount), false)
                        }
                    }
                }
                if (inTx) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    inTx = false
                }
            } finally {
                if (inTx && db.inTransaction()) runCatching { db.endTransaction() }
            }
        }

        override fun clearChunkNodes() {
            db.execSQL("DELETE FROM chunk_nodes")
        }

        override fun estimatedOutputBytes(): Long = stageDbFile.length() * 2L

        override fun counts(): Triple<Int, Int, Int> {
            val nodeCount = db.rawQuery("SELECT COUNT(*) FROM nodes", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val edgeCount = db.rawQuery("SELECT COUNT(*) FROM edges", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val nameCount = db.rawQuery("SELECT COUNT(*) FROM names", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            return Triple(nodeCount, edgeCount, nameCount)
        }

        override fun writeTsr(
            partFile: File,
            outFile: File,
            regionName: String,
            sLat: Double,
            wLng: Double,
            nLat: Double,
            eLng: Double,
            now: Long,
            nodeCount: Int,
            edgeCount: Int,
            nameCount: Int
        ): OfflineRoadRegion {
            partFile.delete()
            FileOutputStream(partFile).use { fos ->
                DataOutputStream(GZIPOutputStream(BufferedOutputStream(fos, 32 * 1024))).use { dos ->
                    writeTsrHeader(dos, regionName, sLat, wLng, nLat, eLng, now, nodeCount, edgeCount, nameCount)

                    var lastNameIdx = -1
                    while (true) {
                        checkCancelled()
                        var count = 0
                        db.rawQuery(
                            "SELECT idx, name FROM names WHERE idx > ? ORDER BY idx ASC LIMIT 5000",
                            arrayOf(lastNameIdx.toString())
                        ).use { c ->
                            while (c.moveToNext()) {
                                lastNameIdx = c.getInt(0)
                                dos.writeUTF(c.getString(1) ?: "")
                                count++
                            }
                        }
                        if (count < 5000) break
                    }

                    var lastNodeIdx = -1
                    while (true) {
                        checkCancelled()
                        var count = 0
                        db.rawQuery(
                            "SELECT idx, lat, lon FROM nodes WHERE idx > ? ORDER BY idx ASC LIMIT 10000",
                            arrayOf(lastNodeIdx.toString())
                        ).use { c ->
                            while (c.moveToNext()) {
                                lastNodeIdx = c.getInt(0)
                                dos.writeFloat(c.getDouble(1).toFloat())
                                dos.writeFloat(c.getDouble(2).toFloat())
                                count++
                            }
                        }
                        if (count < 10000) break
                    }
                    emitProgress("Saving offline road graph (92%)...", 92, true)

                    var lastEdgeRowId = 0L
                    var writtenEdges = 0
                    while (true) {
                        checkCancelled()
                        var count = 0
                        db.rawQuery(
                            "SELECT rowid, from_idx, to_idx, dist, speed, flags, name_idx FROM edges WHERE rowid > ? ORDER BY rowid ASC LIMIT 10000",
                            arrayOf(lastEdgeRowId.toString())
                        ).use { c ->
                            while (c.moveToNext()) {
                                lastEdgeRowId = c.getLong(0)
                                dos.writeInt(c.getInt(1))
                                dos.writeInt(c.getInt(2))
                                dos.writeFloat(c.getDouble(3).toFloat())
                                dos.writeFloat(c.getDouble(4).toFloat())
                                dos.writeByte(c.getInt(5))
                                dos.writeInt(c.getInt(6))
                                count++
                            }
                        }
                        writtenEdges += count
                        val savePct = 92 + ((writtenEdges.toLong() * 7L) / edgeCount.coerceAtLeast(1)).toInt()
                        emitProgress("Saving offline road graph ($savePct%)...", savePct, false)
                        if (count < 10000) break
                    }
                    dos.flush()
                    runCatching { fos.fd.sync() }
                }
            }

            checkCancelled()
            if (outFile.exists()) outFile.delete()
            if (!partFile.renameTo(outFile)) {
                partFile.copyTo(outFile, overwrite = true)
                partFile.delete()
            }

            return OfflineRoadRegion(
                fileName = outFile.name,
                name = regionName,
                minLat = sLat,
                minLng = wLng,
                maxLat = nLat,
                maxLng = eLng,
                nodeCount = nodeCount,
                edgeCount = edgeCount,
                sizeBytes = outFile.length(),
                downloadedAt = now
            )
        }

        override fun close() {
            for (stmt in openStmts) runCatching { stmt.close() }
            runCatching { db.close() }
            stageDbFile.delete()
            File(stageDir, "stage.db-journal").delete()
            File(stageDir, "stage.db-wal").delete()
            File(stageDir, "stage.db-shm").delete()
        }
    }

    internal fun compileLocalOverpassChunksToTsr(
        ctx: Context,
        regionName: String,
        packId: String?,
        minLat: Double,
        minLng: Double,
        maxLat: Double,
        maxLng: Double,
        chunkFiles: List<File>,
        checkCancelled: () -> Unit = {}
    ): OfflineRoadRegion? {
        val jobKey = packId ?: safeName(regionName)
        val stageDir = stagingDir(ctx, "test_$jobKey")
        val sLat = minOf(minLat, maxLat)
        val nLat = maxOf(minLat, maxLat)
        val wLng = minOf(minLng, maxLng)
        val eLng = maxOf(minLng, maxLng)
        val fileName = if (packId != null) packFileName(packId) else "${safeName(regionName)}.tsr"
        val outFile = File(dir(ctx), fileName)
        val partFile = File(dir(ctx), "$fileName.part")
        var finished = false
        try {
            openTsrCompiler(stageDir, checkCancelled) { _, _, _ -> }.use { compiler ->
                val chunkCount = chunkFiles.size.coerceAtLeast(1)
                for ((idx, chunkFile) in chunkFiles.withIndex()) {
                    checkCancelled()
                    compiler.streamChunkIntoDb(chunkFile, chunkFile.name, (idx * 80) / chunkCount, chunkCount)
                    compiler.clearChunkNodes()
                }
                val (nodeCount, edgeCount, nameCount) = compiler.counts()
                if (nodeCount == 0 || edgeCount == 0) return null
                val meta = compiler.writeTsr(
                    partFile = partFile,
                    outFile = outFile,
                    regionName = regionName,
                    sLat = sLat,
                    wLng = wLng,
                    nLat = nLat,
                    eLng = eLng,
                    now = System.currentTimeMillis(),
                    nodeCount = nodeCount,
                    edgeCount = edgeCount,
                    nameCount = nameCount
                )
                synchronized(graphCacheLock) {
                    graphCache.remove(fileName)
                }
                headerCache[fileName] = CachedHeader(outFile.lastModified(), outFile.length(), meta)
                finished = true
                return meta
            }
        } finally {
            partFile.takeIf { it.exists() }?.delete()
            if (finished) stageDir.deleteRecursively() else stageDir.deleteRecursively()
        }
    }

    private fun downloadRoadGraphInternal(
        ctx: Context,
        packId: String?,
        regionName: String,
        minLat: Double,
        minLng: Double,
        maxLat: Double,
        maxLng: Double,
        onProgress: (String, Int) -> Unit,
        onDone: (OfflineRoadRegion?, String?) -> Unit
    ) {
        val jobKey = packId ?: safeName(regionName)
        val job = DownloadJob()
        val prevCancelledJob: DownloadJob? = synchronized(activeDownloads) {
            val existing = activeDownloads[jobKey]
            if (existing != null && !existing.cancelled.get()) {
                onDone(null, "Road download already running")
                return
            }
            activeDownloads[jobKey] = job
            existing
        }

        Thread {
            val stageDir = stagingDir(ctx, jobKey)
            var activePartFile: File? = null
            var compiler: TsrCompilerBackend? = null
            var finishedCleanly = false
            var resultRegion: OfflineRoadRegion? = null
            var resultError: String? = null
            var lastProgressMs = 0L

            fun emitProgress(msg: String, pct: Int, force: Boolean = false) {
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastProgressMs < 350L) return
                lastProgressMs = now
                onProgress(msg, pct.coerceIn(0, 100))
            }

            fun checkCancelled() {
                if (job.cancelled.get()) throw CancelledSignal()
            }

            fun sleepWithCancel(delayMs: Long) {
                var remaining = delayMs
                while (remaining > 0L) {
                    checkCancelled()
                    val step = minOf(remaining, 200L)
                    try {
                        Thread.sleep(step)
                    } catch (_: InterruptedException) {
                        checkCancelled()
                    }
                    remaining -= step
                }
            }

            fun formatTransferSize(bytes: Long): String {
                val kb = (bytes / 1024L).coerceAtLeast(1L)
                return if (kb >= 1024L) "${"%.1f".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))} MB" else "$kb KB"
            }

            try {
                prevCancelledJob?.workerThread?.let { prev ->
                    runCatching { prev.join(5000L) }
                }
                checkCancelled()
                if (!hasEnoughStorage(ctx, MIN_FREE_BYTES_ROADS)) {
                    resultError = "Not enough free storage to download offline roads"
                } else {
                    val sLat = minOf(minLat, maxLat)
                    val nLat = maxOf(minLat, maxLat)
                    val wLng = minOf(minLng, maxLng)
                    val eLng = maxOf(minLng, maxLng)
                    val maxSpan = maxOf(nLat - sLat, eLng - wLng)

                    // 1. Try pre-built TSR1 release asset first via shared ParallelFileDownloader
                    if (packId != null) {
                        val fileName = packFileName(packId)
                        val outFile = File(dir(ctx), fileName)
                        val partFile = File(dir(ctx), "$fileName.part")
                        activePartFile = partFile
                        val pHandle = ParallelFileDownloader.Handle()
                        job.parallelHandle = pHandle
                        try {
                            val downloadedOk = ParallelFileDownloader.downloadToFile(
                                url = prebuiltPackUrl(packId),
                                partFile = partFile,
                                label = "$regionName roads",
                                handle = pHandle
                            ) { _, _, pct, statusText, force ->
                                emitProgress(statusText, pct, force = force)
                            }
                            if (downloadedOk) {
                                checkCancelled()
                                emitProgress("Verifying $regionName roads...", 99, force = true)
                                val validated = FileInputStream(partFile).use { fis ->
                                    readTsrHeaderFromStream(fileName, partFile.length(), fis)
                                }
                                if (validated != null && validated.nodeCount > 0 && validated.edgeCount > 0) {
                                    if (outFile.exists()) outFile.delete()
                                    if (!partFile.renameTo(outFile)) {
                                        partFile.copyTo(outFile, overwrite = true)
                                        partFile.delete()
                                    }
                                    val finalMeta = validated.copy(sizeBytes = outFile.length())
                                    synchronized(graphCacheLock) { graphCache.remove(fileName) }
                                    headerCache[fileName] = CachedHeader(outFile.lastModified(), outFile.length(), finalMeta)
                                    emitProgress("Downloaded $regionName roads", 100, force = true)
                                    finishedCleanly = true
                                    resultRegion = finalMeta
                                } else {
                                    partFile.delete()
                                }
                            } else {
                                partFile.delete()
                            }
                        } catch (_: ParallelFileDownloader.CancelledException) {
                            throw CancelledSignal()
                        } catch (cs: CancelledSignal) {
                            throw cs
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            partFile.delete()
                            if (job.cancelled.get()) throw CancelledSignal()
                            // Fall back to Overpass below
                        } finally {
                            job.parallelHandle = null
                        }
                    }

                if (!finishedCleanly) {
                    val fullStreetRegex =
                        "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|track|path|footway|cycleway|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|road)$"
                    val arterialRegex =
                        "^(motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"
                    val highwayOnlyRegex =
                        "^(motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"

                    fun buildQuery(regex: String, aLat: Double, aLng: Double, bLat: Double, bLng: Double): String = """
                        [out:json][timeout:60];
                        way["highway"~"$regex"]($aLat,$aLng,$bLat,$bLng);
                        out body;
                        >;
                        out skel qt;
                    """.trimIndent()

                    val chunks: List<ChunkSpec> = when (packId) {
                        OfflineMapManager.PACK_KURDISTAN_ID -> listOf(
                            ChunkSpec("Kurdistan highways", buildQuery(arterialRegex, sLat, wLng, nLat, eLng)),
                            ChunkSpec("Duhok & Zakho streets", buildQuery(fullStreetRegex, 36.78, 42.55, 37.22, 43.30)),
                            ChunkSpec("Akre & Shekhan streets", buildQuery(fullStreetRegex, 36.65, 43.28, 37.10, 44.08)),
                            ChunkSpec("Erbil streets", buildQuery(fullStreetRegex, 36.05, 43.88, 36.32, 44.18)),
                            ChunkSpec("Sulaymaniyah streets", buildQuery(fullStreetRegex, 35.48, 45.32, 35.65, 45.55))
                        )
                        OfflineMapManager.PACK_SOUTHERN_IRAQ_ID -> {
                            val midLat = (sLat + nLat) * 0.5
                            listOf(
                                ChunkSpec("Southern Iraq North highways", buildQuery(highwayOnlyRegex, midLat - 0.02, wLng, nLat, eLng)),
                                ChunkSpec("Southern Iraq South highways", buildQuery(highwayOnlyRegex, sLat, wLng, midLat + 0.02, eLng))
                            )
                        }
                        else -> {
                            val reg = if (maxSpan <= 1.4) fullStreetRegex else highwayOnlyRegex
                            listOf(ChunkSpec("$regionName roads", buildQuery(reg, sLat, wLng, nLat, eLng)))
                        }
                    }

                    val endpoints = listOf(
                        "https://overpass-api.de/api/interpreter",
                        "https://overpass.private.coffee/api/interpreter"
                    )

                    val activeCompiler = openTsrCompiler(stageDir, ::checkCancelled, ::emitProgress).also {
                        compiler = it
                    }

                    fun downloadChunkToFile(cIdx: Int, chunk: ChunkSpec, outFile: File): String? {
                        val partFile = File(stageDir, "${outFile.name}.part")
                        var lastErr = "Could not download road data"
                        val basePct = (cIdx * 80) / chunks.size

                        for ((epIdx, ep) in endpoints.withIndex()) {
                            checkCancelled()
                            emitProgress("Downloading roads (${cIdx + 1}/${chunks.size}: ${chunk.label})...", basePct, force = true)
                            partFile.delete()
                            var call: Call? = null
                            try {
                                val form = FormBody.Builder()
                                    .add("data", chunk.query)
                                    .build()
                                val req = Request.Builder()
                                    .url(ep)
                                    .post(form)
                                    .build()
                                val activeCall = overpassClient.newCall(req)
                                call = activeCall
                                job.activeCall = activeCall
                                checkCancelled()
                                activeCall.execute().use { resp ->
                                    val code = resp.code
                                    val bodyStream = resp.body?.byteStream()
                                    if (code == 200 && bodyStream != null) {
                                        var bytesWritten = 0L
                                        val buf = ByteArray(32 * 1024)
                                        BufferedInputStream(bodyStream, 32 * 1024).use { input ->
                                            BufferedOutputStream(FileOutputStream(partFile), 32 * 1024).use { output ->
                                                while (true) {
                                                    checkCancelled()
                                                    val n = input.read(buf)
                                                    if (n <= 0) break
                                                    output.write(buf, 0, n)
                                                    bytesWritten += n
                                                    emitProgress(
                                                        "Downloading roads (${cIdx + 1}/${chunks.size}: ${chunk.label} • ${formatTransferSize(bytesWritten)})",
                                                        basePct + (40 / chunks.size)
                                                    )
                                                }
                                                output.flush()
                                            }
                                        }
                                        checkCancelled()
                                        if (bytesWritten > 64L) {
                                            outFile.delete()
                                            if (!partFile.renameTo(outFile)) {
                                                partFile.copyTo(outFile, overwrite = true)
                                                partFile.delete()
                                            }
                                            return null
                                        } else {
                                            lastErr = "Overpass returned empty response"
                                            partFile.delete()
                                        }
                                    } else {
                                        lastErr = "Overpass server HTTP $code"
                                    }
                                }
                            } catch (cs: CancelledSignal) {
                                partFile.delete()
                                throw cs
                            } catch (e: CancellationException) {
                                partFile.delete()
                                throw e
                            } catch (e: Exception) {
                                partFile.delete()
                                if (job.cancelled.get() || call?.isCanceled() == true) throw CancelledSignal()
                                lastErr = e.message ?: "Network error"
                            } finally {
                                if (job.activeCall === call) job.activeCall = null
                            }
                            if (epIdx < endpoints.lastIndex) {
                                sleepWithCancel(1500L * (epIdx + 1))
                            }
                        }
                        return lastErr
                    }

                    var fetchedAny = false
                    var lastErr = "Could not download road data"

                    for ((cIdx, chunk) in chunks.withIndex()) {
                        checkCancelled()
                        val basePct = (cIdx * 80) / chunks.size
                        val chunkFile = File(stageDir, "chunk_$cIdx.json")
                        var chunkProcessed = false

                        for (attempt in 0..1) {
                            checkCancelled()
                            if (!chunkFile.exists() || chunkFile.length() <= 128L) {
                                val dlErr = downloadChunkToFile(cIdx, chunk, chunkFile)
                                if (dlErr != null) {
                                    lastErr = dlErr
                                    break
                                }
                            }
                            try {
                                activeCompiler.streamChunkIntoDb(chunkFile, chunk.label, basePct, chunks.size)
                                activeCompiler.clearChunkNodes()
                                chunkFile.delete()
                                fetchedAny = true
                                chunkProcessed = true
                                break
                            } catch (cs: CancelledSignal) {
                                throw cs
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                chunkFile.delete()
                                activeCompiler.clearChunkNodes()
                                lastErr = e.message ?: "Malformed road chunk"
                                if (attempt == 1) break
                            }
                        }
                        if (!chunkProcessed) continue
                    }

                    checkCancelled()
                    val (nodeCount, edgeCount, nameCount) = activeCompiler.counts()

                    if (!fetchedAny || nodeCount == 0 || edgeCount == 0) {
                        resultError = if (!fetchedAny) lastErr else "No routable roads found in this area"
                    } else if (!hasEnoughStorage(ctx, maxOf(64L * 1024L * 1024L, activeCompiler.estimatedOutputBytes()))) {
                        resultError = "Not enough free storage to save offline road graph"
                    } else {
                        emitProgress("Saving offline roads ($nodeCount nodes)...", 85, force = true)
                        val fileName = if (packId != null) packFileName(packId) else "${safeName(regionName)}_${System.currentTimeMillis() % 10000}.tsr"
                        val outFile = File(dir(ctx), fileName)
                        val partFile = File(dir(ctx), "$fileName.part")
                        activePartFile = partFile
                        val now = System.currentTimeMillis()
                        val meta = activeCompiler.writeTsr(
                            partFile = partFile,
                            outFile = outFile,
                            regionName = regionName,
                            sLat = sLat,
                            wLng = wLng,
                            nLat = nLat,
                            eLng = eLng,
                            now = now,
                            nodeCount = nodeCount,
                            edgeCount = edgeCount,
                            nameCount = nameCount
                        )
                        synchronized(graphCacheLock) {
                            graphCache.remove(fileName)
                        }
                        headerCache[fileName] = CachedHeader(outFile.lastModified(), outFile.length(), meta)
                        finishedCleanly = true
                        resultRegion = meta
                    }
                    }
                }
            } catch (_: CancelledSignal) {
                resultError = "Cancelled"
            } catch (_: OutOfMemoryError) {
                resultError = "Not enough memory to process road data"
            } catch (t: Throwable) {
                resultError = t.message ?: "Failed to compile offline graph"
            } finally {
                runCatching { compiler?.close() }
                if (finishedCleanly) {
                    stageDir.deleteRecursively()
                }
                if (finishedCleanly) {
                    activePartFile?.let { ParallelFileDownloader.clearPartialFiles(it) }
                    if (packId != null) {
                        ParallelFileDownloader.clearPartialFiles(File(dir(ctx), "${packFileName(packId)}.part"))
                    }
                }
                job.activeCall = null
                activeDownloads.remove(jobKey, job)
            }
            onDone(resultRegion, resultError)
        }.apply {
            job.workerThread = this
            name = "terrasync-road-dl-$jobKey"
            isDaemon = true
            start()
        }
    }

    private data class Quad(val car: Boolean, val bike: Boolean, val walk: Boolean, val speed: Float)

    fun routeOffline(
        ctx: Context,
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        mode: TravelMode
    ): Pair<NavRoute?, String?> {
        val regions = listRegions(ctx)
        if (regions.isEmpty()) {
            return null to "No offline maps with routing downloaded (Settings → Offline Maps)"
        }
        // Prefer the smallest (most detailed) matching bounding box first
        val sortedByDetail = regions.sortedBy { it.areaDegSq() }
        val candidates = sortedByDetail.filter { it.contains(startLat, startLng) && it.contains(endLat, endLng) }
            .ifEmpty { sortedByDetail.filter { it.contains(startLat, startLng, 0.08) && it.contains(endLat, endLng, 0.08) } }
        if (candidates.isEmpty()) {
            return null to "Route outside downloaded offline map regions"
        }

        val modeFlag = when (mode) {
            TravelMode.CAR -> FLAG_CAR
            TravelMode.BICYCLE -> FLAG_BIKE
            TravelMode.WALKING -> FLAG_WALK
        }

        var lastFailure = "Could not compute offline route"
        for (region in candidates) {
            val tsrFile = File(dir(ctx), region.fileName)
            val nativeRes = RustCoreBridge.routeOffline(tsrFile, startLat, startLng, endLat, endLng, mode)
            if (nativeRes != null) {
                if (nativeRes.first != null) return nativeRes
                if (!nativeRes.second.isNullOrBlank()) lastFailure = nativeRes.second!!
                continue
            }
            val g = loadGraph(ctx, region.fileName) ?: continue
            val res = computeAStar(g, startLat, startLng, endLat, endLng, mode, modeFlag)
            if (res.first != null) return res
            if (!res.second.isNullOrBlank()) lastFailure = res.second!!
        }
        return null to lastFailure
    }

    private fun computeAStar(
        g: RoadGraph,
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        mode: TravelMode,
        modeFlag: Int
    ): Pair<NavRoute?, String?> {
        val startCandidates = findNearestNodes(g, startLat, startLng, modeFlag, needIncoming = false, maxCount = 3)
        val endCandidates = findNearestNodes(g, endLat, endLng, modeFlag, needIncoming = true, maxCount = 3)
        if (startCandidates.isEmpty() || endCandidates.isEmpty()) {
            return null to "No nearby road in offline map"
        }

        // Try closest pair first, then fall back to secondary nearby nodes if snapped onto a disconnected OSM stub
        val pairs = buildList {
            add(startCandidates[0] to endCandidates[0])
            for (s in startCandidates) {
                for (e in endCandidates) {
                    if (s == startCandidates[0] && e == endCandidates[0]) continue
                    add(s to e)
                }
            }
        }

        var lastError = "No offline road connection found between points"
        for ((startNode, endNode) in pairs) {
            val res = runAStarBetweenNodes(g, startNode, endNode, startLat, startLng, endLat, endLng, mode, modeFlag)
            if (res.first != null) return res
            if (!res.second.isNullOrBlank()) lastError = res.second!!
        }
        return null to lastError
    }

    private fun runAStarBetweenNodes(
        g: RoadGraph,
        startNode: Int,
        endNode: Int,
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        mode: TravelMode,
        modeFlag: Int
    ): Pair<NavRoute?, String?> {
        if (startNode == endNode) {
            val geom = listOf(startLng to startLat, endLng to endLat)
            val d = RoutingEngine.haversineMeters(startLat, startLng, endLat, endLng)
            val steps = listOf(NavStep("Arrive at destination", "🏁", d, endLat, endLng, d))
            return NavRoute(geom, d, d / 5.0, steps, mode, "Offline") to null
        }

        val n = g.lats.size
        val scratch = aStarScratch.get()!!
        val ep = scratch.prepare(n)
        val seenEpoch = scratch.seenEpoch
        val closedEpoch = scratch.closedEpoch
        val gScore = scratch.gScore
        val prevNode = scratch.prevNode
        val prevEdge = scratch.prevEdge

        val maxSpeedMps = when (mode) {
            TravelMode.CAR -> 100.0 / 3.6
            TravelMode.BICYCLE -> 22.0 / 3.6
            TravelMode.WALKING -> 5.5 / 3.6
        }
        val invMaxSpeed = 0.985 / maxSpeedMps // slight safety margin keeps equirectangular heuristic admissible

        class QNode(val u: Int, val f: Double) : Comparable<QNode> {
            override fun compareTo(other: QNode): Int = f.compareTo(other.f)
        }

        val endLatD = g.lats[endNode].toDouble()
        val endLngD = g.lngs[endNode].toDouble()
        val mPerDegLng = 111320.0 * cos(Math.toRadians((startLat + endLatD) * 0.5))

        fun fastHeuristicSec(lat: Float, lng: Float): Double {
            val dy = (lat - endLatD) * 111132.0
            val dx = (lng - endLngD) * mPerDegLng
            return sqrt(dx * dx + dy * dy) * invMaxSpeed
        }

        val pq = PriorityQueue<QNode>(256)
        seenEpoch[startNode] = ep
        gScore[startNode] = 0.0
        prevNode[startNode] = -1
        prevEdge[startNode] = -1
        pq.add(QNode(startNode, fastHeuristicSec(g.lats[startNode], g.lngs[startNode])))

        var found = false
        while (pq.isNotEmpty()) {
            val cur = pq.poll() ?: break
            val u = cur.u
            if (closedEpoch[u] == ep) continue
            closedEpoch[u] = ep
            if (u == endNode) {
                found = true
                break
            }
            var e = g.adjHead[u]
            while (e != -1) {
                if ((g.edgeFlags[e].toInt() and modeFlag) != 0) {
                    val v = g.edgeTo[e]
                    if (closedEpoch[v] != ep) {
                        val spdKmh = when (mode) {
                            TravelMode.CAR -> g.edgeSpeed[e].toDouble().coerceAtLeast(10.0)
                            TravelMode.BICYCLE -> 16.0
                            TravelMode.WALKING -> 4.8
                        }
                        val costSec = g.edgeDist[e].toDouble() / (spdKmh / 3.6)
                        val tentative = gScore[u] + costSec
                        val vG = if (seenEpoch[v] == ep) gScore[v] else Double.POSITIVE_INFINITY
                        if (tentative < vG) {
                            seenEpoch[v] = ep
                            gScore[v] = tentative
                            prevNode[v] = u
                            prevEdge[v] = e
                            pq.add(QNode(v, tentative + fastHeuristicSec(g.lats[v], g.lngs[v])))
                        }
                    }
                }
                e = g.edgeNext[e]
            }
        }

        if (!found) {
            return null to "No offline road connection found between points"
        }

        val pathNodes = ArrayList<Int>()
        val pathEdges = ArrayList<Int>()
        var cur = endNode
        while (cur != -1) {
            pathNodes.add(cur)
            val pe = prevEdge[cur]
            if (pe != -1) pathEdges.add(pe)
            cur = prevNode[cur]
        }
        pathNodes.reverse()
        pathEdges.reverse()

        val geom = ArrayList<Pair<Double, Double>>(pathNodes.size + 2)
        geom.add(startLng to startLat)
        for (idx in pathNodes) {
            geom.add(g.lngs[idx].toDouble() to g.lats[idx].toDouble())
        }
        geom.add(endLng to endLat)

        val approachSpeedMps = when (mode) {
            TravelMode.CAR -> 25.0 / 3.6
            TravelMode.BICYCLE -> 14.0 / 3.6
            TravelMode.WALKING -> 4.8 / 3.6
        }
        val firstNode = pathNodes.first()
        val lastNode = pathNodes.last()
        val startApproach = RoutingEngine.haversineMeters(
            startLat, startLng, g.lats[firstNode].toDouble(), g.lngs[firstNode].toDouble()
        )
        val endApproach = RoutingEngine.haversineMeters(
            g.lats[lastNode].toDouble(), g.lngs[lastNode].toDouble(), endLat, endLng
        )

        var totalDist = startApproach
        var totalDur = startApproach / approachSpeedMps
        val cumDists = DoubleArray(pathNodes.size)
        cumDists[0] = startApproach
        for (i in pathEdges.indices) {
            val e = pathEdges[i]
            val d = g.edgeDist[e].toDouble()
            val spdKmh = when (mode) {
                TravelMode.CAR -> g.edgeSpeed[e].toDouble().coerceAtLeast(10.0)
                TravelMode.BICYCLE -> 16.0
                TravelMode.WALKING -> 4.8
            }
            totalDist += d
            totalDur += d / (spdKmh / 3.6)
            cumDists[i + 1] = totalDist
        }
        totalDist += endApproach
        totalDur += endApproach / approachSpeedMps

        val steps = buildOfflineSteps(g, pathNodes, pathEdges, cumDists, totalDist, endLat, endLng)
        return NavRoute(geom, totalDist, totalDur, steps, mode, "Offline") to null
    }

    private fun findNearestNodes(
        g: RoadGraph,
        lat: Double,
        lng: Double,
        modeFlag: Int,
        needIncoming: Boolean = false,
        maxCount: Int = 3
    ): List<Int> {
        val maxDistSq = 3500.0 * 3500.0
        val cosLat = cos(Math.toRadians(lat))
        val mPerDegLng = 111320.0 * cosLat
        val flagsArr = if (needIncoming) g.nodeInFlags else g.nodeOutFlags
        val bestIdx = IntArray(maxCount) { -1 }
        val bestDistSq = DoubleArray(maxCount) { maxDistSq }
        var foundCount = 0
        var worstPos = 0

        fun considerNode(i: Int) {
            if ((flagsArr[i].toInt() and modeFlag) == 0) return
            val dLat = (g.lats[i] - lat) * 111132.0
            val dLatSq = dLat * dLat
            val limit = bestDistSq[worstPos]
            if (dLatSq >= limit) return
            val dLng = (g.lngs[i] - lng) * mPerDegLng
            val dSq = dLatSq + dLng * dLng
            if (dSq < limit) {
                if (bestIdx[worstPos] < 0) foundCount++
                bestIdx[worstPos] = i
                bestDistSq[worstPos] = dSq
                var maxD = bestDistSq[0]
                var maxP = 0
                for (k in 1 until maxCount) {
                    if (bestDistSq[k] > maxD) {
                        maxD = bestDistSq[k]
                        maxP = k
                    }
                }
                worstPos = maxP
            }
        }

        val grid = g.spatialGrid
        val tR = ((lat - grid.minLat) / grid.cellLat).toInt().coerceIn(0, grid.rows - 1)
        val tC = ((lng - grid.minLng) / grid.cellLng).toInt().coerceIn(0, grid.cols - 1)
        val maxRing = maxOf(tR, grid.rows - 1 - tR, tC, grid.cols - 1 - tC)

        for (ring in 0..maxRing) {
            val rMin = (tR - ring).coerceAtLeast(0)
            val rMax = (tR + ring).coerceAtMost(grid.rows - 1)
            val cMin = (tC - ring).coerceAtLeast(0)
            val cMax = (tC + ring).coerceAtMost(grid.cols - 1)

            for (r in rMin..rMax) {
                if (ring == 0 || r == rMin || r == rMax) {
                    for (c in cMin..cMax) {
                        var idx = grid.head[r * grid.cols + c]
                        while (idx != -1) {
                            considerNode(idx)
                            idx = grid.next[idx]
                        }
                    }
                } else {
                    for (c in intArrayOf(cMin, cMax)) {
                        if (c < cMin || c > cMax) continue
                        var idx = grid.head[r * grid.cols + c]
                        while (idx != -1) {
                            considerNode(idx)
                            idx = grid.next[idx]
                        }
                    }
                }
            }

            val dS = if (tR - ring > 0) ((lat - (grid.minLat + (tR - ring) * grid.cellLat)).coerceAtLeast(0.0) * 111132.0) else Double.POSITIVE_INFINITY
            val dN = if (tR + ring < grid.rows - 1) (((grid.minLat + (tR + ring + 1) * grid.cellLat) - lat).coerceAtLeast(0.0) * 111132.0) else Double.POSITIVE_INFINITY
            val dW = if (tC - ring > 0) ((lng - (grid.minLng + (tC - ring) * grid.cellLng)).coerceAtLeast(0.0) * mPerDegLng) else Double.POSITIVE_INFINITY
            val dE = if (tC + ring < grid.cols - 1) (((grid.minLng + (tC + ring + 1) * grid.cellLng) - lng).coerceAtLeast(0.0) * mPerDegLng) else Double.POSITIVE_INFINITY
            val minOut = minOf(dS, dN, dW, dE)
            if (minOut.isInfinite()) break
            val minOutSq = minOut * minOut
            if (minOutSq >= maxDistSq) break
            if (foundCount >= maxCount && minOutSq >= bestDistSq[worstPos]) break
        }

        return (0 until maxCount)
            .filter { bestIdx[it] >= 0 }
            .sortedBy { bestDistSq[it] }
            .map { bestIdx[it] }
    }

    private fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLam = Math.toRadians(lng2 - lng1)
        val y = sin(dLam) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLam)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun buildOfflineSteps(
        g: RoadGraph,
        nodes: List<Int>,
        edges: List<Int>,
        cumDists: DoubleArray,
        totalDist: Double,
        endLat: Double,
        endLng: Double
    ): List<NavStep> {
        val steps = ArrayList<NavStep>()
        if (edges.isEmpty()) {
            steps.add(NavStep("Arrive at destination", "🏁", totalDist, endLat, endLng, totalDist))
            return steps
        }
        val firstRoad = g.names.getOrElse(g.edgeNameIdx[edges.first()]) { "" }
        val ontoFirst = if (firstRoad.isNotBlank()) " onto $firstRoad" else ""
        steps.add(NavStep("Head out$ontoFirst", "↑", 0.0, g.lats[nodes[0]].toDouble(), g.lngs[nodes[0]].toDouble(), 0.0))

        var lastStepDist = 0.0
        for (i in 1 until nodes.size - 1) {
            val distHere = cumDists[i]
            if (distHere - lastStepDist < 30.0) continue

            // Look ~20m behind and ~20m ahead so curved OSM road segments produce accurate turn angles
            var backIdx = i - 1
            while (backIdx > 0 && (distHere - cumDists[backIdx]) < 20.0) backIdx--
            var fwdIdx = i + 1
            while (fwdIdx < nodes.size - 1 && (cumDists[fwdIdx] - distHere) < 20.0) fwdIdx++

            val bNode = nodes[backIdx]
            val cNode = nodes[i]
            val fNode = nodes[fwdIdx]
            val b1 = bearingDeg(g.lats[bNode].toDouble(), g.lngs[bNode].toDouble(), g.lats[cNode].toDouble(), g.lngs[cNode].toDouble())
            val b2 = bearingDeg(g.lats[cNode].toDouble(), g.lngs[cNode].toDouble(), g.lats[fNode].toDouble(), g.lngs[fNode].toDouble())
            val delta = (b2 - b1 + 540.0) % 360.0 - 180.0

            val prevRoad = g.names.getOrElse(g.edgeNameIdx[edges[i - 1]]) { "" }
            val nextRoad = g.names.getOrElse(g.edgeNameIdx[edges[i]]) { "" }
            val roadChanged = nextRoad.isNotBlank() && nextRoad != prevRoad

            if (abs(delta) >= 40.0 || roadChanged) {
                val onto = if (nextRoad.isNotBlank()) " onto $nextRoad" else ""
                val (arrow, instr) = when {
                    delta > 125.0 -> "↘" to "Sharp right$onto"
                    delta > 45.0 -> "↱" to "Turn right$onto"
                    delta > 25.0 -> "↗" to "Keep right$onto"
                    delta < -125.0 -> "↙" to "Sharp left$onto"
                    delta < -45.0 -> "↰" to "Turn left$onto"
                    delta < -25.0 -> "↖" to "Keep left$onto"
                    else -> "↑" to "Continue$onto"
                }
                steps.add(NavStep(instr, arrow, distHere - lastStepDist, g.lats[cNode].toDouble(), g.lngs[cNode].toDouble(), distHere))
                lastStepDist = distHere
            }
        }
        steps.add(NavStep("Arrive at destination", "🏁", (totalDist - lastStepDist).coerceAtLeast(0.0), endLat, endLng, totalDist))
        return steps
    }
}
