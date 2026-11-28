package com.terrasync.app

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * OkHttp interceptor for MapLibre HTTP traffic (tiles, glyphs, sprites, TileJSON):
 * 1. Serves bundled Kurdish-patched SDF glyph ranges locally with zero network latency.
 * 2. Serves vector tiles from downloaded local PMTiles archives ([LocalPmTilesStore]) first
 *    across all zoom levels (<0.5 ms, works 100% offline for Day & Night styles).
 * 3. Patches Kurdish Sorani labels inside MVT vector tiles via [MvtKurdishPatcher].
 * 4. Properly decompresses gzipped TileJSON, glyphs, and sprites before caching/returning,
 *    and responds cleanly offline for uncached secondary assets so tile rendering never stalls.
 */
class MapTileInterceptor(
    private val contextProvider: () -> Context?
) : Interceptor {

    companion object {
        private const val OPENFREEMAP_HOST = "tiles.openfreemap.org"
        private const val KURDISH_GLYPH_RANGE_SUFFIX = "/64256-64511.pbf"
        private const val KURDISH_GLYPH_ASSET_PATH = "fonts/64256-64511.pbf"
        private const val PUNCT_GLYPH_RANGE_SUFFIX = "/8192-8447.pbf"
        private const val PUNCT_GLYPH_ASSET_PATH = "fonts/8192-8447.pbf"

        private val TILE_ZXY_REGEX = Regex(""".*/(\d{1,2})/(\d+)/(\d+)\.pbf$""")
        private val PROTOBUF_MEDIA = "application/x-protobuf".toMediaType()
        private val JSON_MEDIA = "application/json".toMediaType()
        private val PNG_MEDIA = "image/png".toMediaType()
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()

        @Volatile private var cachedKurdishGlyphPbf: ByteArray? = null
        @Volatile private var cachedPunctGlyphPbf: ByteArray? = null

        internal fun buildOfflineTileJson(maxZoom: Int, minZoom: Int = 0): ByteArray {
            val clampedMax = maxZoom.coerceIn(0, 14)
            val clampedMin = minZoom.coerceIn(0, clampedMax)
            return """{
  "tilejson": "3.0.0",
  "name": "TerraSync Offline",
  "scheme": "xyz",
  "minzoom": $clampedMin,
  "maxzoom": $clampedMax,
  "bounds": [-180, -85.05112877980659, 180, 85.05112877980659],
  "tiles": ["https://tiles.openfreemap.org/planet/local/{z}/{x}/{y}.pbf"]
}""".toByteArray(Charsets.UTF_8)
        }

        internal fun webCacheDir(ctx: Context): File =
            File(LocalPmTilesStore.dir(ctx), "web_cache").apply { mkdirs() }

        internal fun cacheKeyForUrl(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(32)
            for (i in 0 until 16) {
                sb.append(String.format("%02x", digest[i]))
            }
            return sb.toString()
        }

        internal fun loadBundledKurdishGlyphPbf(ctx: Context?): ByteArray? {
            cachedKurdishGlyphPbf?.let { return it }
            val c = ctx ?: return null
            return runCatching {
                c.assets.open(KURDISH_GLYPH_ASSET_PATH).use { it.readBytes() }
            }.getOrNull()?.takeIf { it.size > 256 }?.also {
                cachedKurdishGlyphPbf = it
            }
        }

        internal fun loadBundledPunctGlyphPbf(ctx: Context?): ByteArray? {
            cachedPunctGlyphPbf?.let { return it }
            val c = ctx ?: return null
            return runCatching {
                c.assets.open(PUNCT_GLYPH_ASSET_PATH).use { it.readBytes() }
            }.getOrNull()?.takeIf { it.size > 256 }?.also {
                cachedPunctGlyphPbf = it
            }
        }

        internal fun isGzipBytes(bytes: ByteArray): Boolean {
            return bytes.size >= 2 &&
                (bytes[0].toInt() and 0xFF) == 0x1F &&
                (bytes[1].toInt() and 0xFF) == 0x8B
        }

        internal fun gunzipIfNeeded(bytes: ByteArray): ByteArray {
            if (!isGzipBytes(bytes)) return bytes
            return runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            }.getOrDefault(bytes)
        }

        private fun writeFileAtomic(target: File, bytes: ByteArray) {
            runCatching {
                val tmp = File(target.parentFile, "${target.name}.${Thread.currentThread().id}.${System.nanoTime()}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        val ctx = contextProvider()

        if (!url.host.equals(OPENFREEMAP_HOST, ignoreCase = true)) {
            if (!NetworkState.hasValidatedInternet(ctx)) {
                return buildEmptyResponse(req, OCTET_MEDIA)
            }
            return chain.proceed(req)
        }

        val path = url.encodedPath

        // 1. Patched Kurdish SDF glyph ranges
        if (path.contains("/fonts/") && path.endsWith(KURDISH_GLYPH_RANGE_SUFFIX)) {
            val bundled = loadBundledKurdishGlyphPbf(ctx)
            if (bundled != null) {
                return buildOkResponse(req, bundled, PROTOBUF_MEDIA, "public, max-age=86400")
            }
        }
        if (path.contains("/fonts/") && path.endsWith(PUNCT_GLYPH_RANGE_SUFFIX)) {
            val bundled = loadBundledPunctGlyphPbf(ctx)
            if (bundled != null) {
                return buildOkResponse(req, bundled, PROTOBUF_MEDIA, "public, max-age=86400")
            }
        }

        // 2. Vector tiles ({z}/{x}/{y}.pbf)
        val tileMatch = TILE_ZXY_REGEX.matchEntire(path)
        if (tileMatch != null) {
            val z = tileMatch.groupValues[1].toIntOrNull()
            val x = tileMatch.groupValues[2].toIntOrNull()
            val y = tileMatch.groupValues[3].toIntOrNull()
            if (z != null && x != null && y != null) {
                return handleVectorTile(chain, req, ctx, z, x, y)
            }
        }

        // 3. Other glyphs & sprites -> persistent disk cache
        if (path.contains("/fonts/") || path.contains("/sprites/")) {
            return handleCachedStaticAsset(chain, req, ctx, path)
        }

        // 4. TileJSON (/planet, /planet/latest, /backup_planet, *.json)
        if (path == "/planet" || path == "/backup_planet" || path.endsWith("/planet.json") ||
            path.startsWith("/planet/") || path.startsWith("/backup_planet/")
        ) {
            return handleTileJson(chain, req, ctx)
        }

        // 5. Secondary raster backdrops (e.g. ne2_shaded): skip immediately offline
        if (!NetworkState.hasValidatedInternet(ctx)) {
            return buildEmptyResponse(req, PNG_MEDIA)
        }
        return chain.proceed(req)
    }

    private fun handleVectorTile(
        chain: Interceptor.Chain,
        req: Request,
        ctx: Context?,
        z: Int,
        x: Int,
        y: Int
    ): Response {
        val online = NetworkState.hasValidatedInternet(ctx)

        // Always serve from local PMTiles first across every zoom level (0.3ms, zero data)
        when (val local = LocalPmTilesStore.readTile(ctx, z, x, y)) {
            is LocalPmTilesStore.TileLookup.Found -> {
                val patched = if (local.alreadyPatched) {
                    local.bytes
                } else {
                    val raw = if (local.isGzipped) gunzipIfNeeded(local.bytes) else local.bytes
                    MvtKurdishPatcher.patchTile(raw)
                }
                return buildOkResponse(req, patched, PROTOBUF_MEDIA, "public, max-age=3600")
            }
            LocalPmTilesStore.TileLookup.EmptyInBounds -> {
                if (!online) return buildEmptyResponse(req, PROTOBUF_MEDIA)
            }
            LocalPmTilesStore.TileLookup.Miss -> {
                // Fall through to network if online
            }
        }

        if (!online) {
            val installedMaxZ = LocalPmTilesStore.getInstalledMaxZoom(ctx)
            if (installedMaxZ != null && z > installedMaxZ) {
                return buildOverzoomUnavailableResponse(req, PROTOBUF_MEDIA)
            }
            return buildEmptyResponse(req, PROTOBUF_MEDIA)
        }

        val resp = chain.proceed(req)
        return if (resp.code == 200) {
            val mediaType = resp.body?.contentType() ?: PROTOBUF_MEDIA
            val bodyBytes = resp.body?.bytes()
            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                val patched = RustCoreBridge.gunzipAndPatchMvt(bodyBytes)?.let { if (it.isEmpty()) bodyBytes else it } ?: run {
                    val raw = gunzipIfNeeded(bodyBytes)
                    MvtKurdishPatcher.patchTile(raw)
                }
                resp.newBuilder()
                    .removeHeader("Content-Encoding")
                    .removeHeader("Content-Length")
                    .body(patched.toResponseBody(mediaType))
                    .build()
            } else {
                resp
            }
        } else {
            resp
        }
    }

    private fun handleCachedStaticAsset(
        chain: Interceptor.Chain,
        req: Request,
        ctx: Context?,
        path: String
    ): Response {
        val mediaType = when {
            path.endsWith(".json") -> JSON_MEDIA
            path.endsWith(".png") -> PNG_MEDIA
            path.endsWith(".pbf") -> PROTOBUF_MEDIA
            else -> OCTET_MEDIA
        }
        val cacheFile = ctx?.let { File(webCacheDir(it), cacheKeyForUrl(req.url.toString())) }
        if (cacheFile != null && cacheFile.exists() && cacheFile.length() > 0L) {
            val raw = runCatching { cacheFile.readBytes() }.getOrNull()
            if (raw != null && raw.isNotEmpty()) {
                val plain = gunzipIfNeeded(raw)
                if (plain !== raw) writeFileAtomic(cacheFile, plain)
                return buildOkResponse(req, plain, mediaType, "public, max-age=604800")
            }
        }

        if (!NetworkState.hasValidatedInternet(ctx)) {
            return when {
                path.endsWith(".json") -> buildOkResponse(req, "{}".toByteArray(Charsets.UTF_8), JSON_MEDIA, "no-store")
                path.endsWith(".pbf") -> buildOkResponse(req, ByteArray(0), PROTOBUF_MEDIA, "no-store")
                else -> buildEmptyResponse(req, mediaType)
            }
        }

        val resp = chain.proceed(req)
        if (resp.isSuccessful) {
            val respMedia = resp.body?.contentType() ?: mediaType
            val bodyBytes = resp.body?.bytes()
            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                val plain = gunzipIfNeeded(bodyBytes)
                if (cacheFile != null) writeFileAtomic(cacheFile, plain)
                return resp.newBuilder()
                    .removeHeader("Content-Encoding")
                    .removeHeader("Content-Length")
                    .body(plain.toResponseBody(respMedia))
                    .build()
            }
        }
        return resp
    }

    private fun handleTileJson(chain: Interceptor.Chain, req: Request, ctx: Context?): Response {
        val cacheFile = ctx?.let { File(webCacheDir(it), "tilejson_${cacheKeyForUrl(req.url.toString())}.json") }

        fun offlineFallbackResponse(): Response? {
            if (LocalPmTilesStore.hasAnyInstalledPack(ctx)) {
                val minZ = LocalPmTilesStore.getInstalledMinZoom(ctx) ?: 0
                val maxZ = LocalPmTilesStore.getInstalledMaxZoom(ctx) ?: 13
                return buildOkResponse(req, buildOfflineTileJson(maxZ, minZ), JSON_MEDIA, "no-store")
            }
            if (cacheFile != null && cacheFile.exists() && cacheFile.length() > 32L) {
                val raw = runCatching { cacheFile.readBytes() }.getOrNull()
                if (raw != null && raw.isNotEmpty()) {
                    val plain = gunzipIfNeeded(raw)
                    if (plain !== raw) writeFileAtomic(cacheFile, plain)
                    return buildOkResponse(req, plain, JSON_MEDIA, "no-store")
                }
            }
            return null
        }

        if (!NetworkState.hasValidatedInternet(ctx)) {
            return offlineFallbackResponse() ?: throw IOException("Offline and no local TileJSON")
        }

        return try {
            val resp = chain.proceed(req)
            if (resp.isSuccessful) {
                val bodyBytes = resp.body?.bytes()
                if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                    val plain = gunzipIfNeeded(bodyBytes)
                    if (cacheFile != null) writeFileAtomic(cacheFile, plain)
                    return resp.newBuilder()
                        .removeHeader("Content-Encoding")
                        .removeHeader("Content-Length")
                        .header("Cache-Control", "no-store")
                        .body(plain.toResponseBody(JSON_MEDIA))
                        .build()
                }
                resp
            } else {
                val fallback = offlineFallbackResponse()
                if (fallback != null) {
                    resp.close()
                    fallback
                } else {
                    resp
                }
            }
        } catch (ioe: IOException) {
            offlineFallbackResponse() ?: throw ioe
        }
    }

    private fun buildOkResponse(
        req: Request,
        bytes: ByteArray,
        mediaType: okhttp3.MediaType,
        cacheControl: String
    ): Response {
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Cache-Control", cacheControl)
            .body(bytes.toResponseBody(mediaType))
            .build()
    }

    private fun buildEmptyResponse(req: Request, mediaType: okhttp3.MediaType): Response {
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(204)
            .message("No Content")
            .header("Cache-Control", "no-store")
            .body(ByteArray(0).toResponseBody(mediaType))
            .build()
    }

    private fun buildOverzoomUnavailableResponse(req: Request, mediaType: okhttp3.MediaType): Response {
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("Overzoom Offline")
            .header("Cache-Control", "no-store")
            .body(ByteArray(0).toResponseBody(mediaType))
            .build()
    }
}
