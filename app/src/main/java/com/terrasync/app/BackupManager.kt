package com.terrasync.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupSummary(
    val appVersion: String,
    val exportedAt: Long,
    val layerCount: Int,
    val pinCount: Int,
    val trackCount: Int,
    val hasPrefs: Boolean
)

/**
 * Atomic single-file backup & restore (.tsbackup ZIP) containing:
 *  - terrasync_local_store.db (consistent SQLite snapshot)
 *  - device_prefs.json (all SharedPreferences keys & types)
 *  - manifest.json (metadata + counts for restore preview)
 */
object BackupManager {
    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_DB = "terrasync_local_store.db"
    private const val ENTRY_PREFS = "device_prefs.json"
    private const val PREFS_NAME = "terrasync_prefs_v2"

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "TerraSync_Backup_$stamp.tsbackup"
    }

    fun exportToUri(ctx: Context, destUri: Uri, appVersion: String): Result<BackupSummary> = runCatching {
        val appCtx = ctx.applicationContext
        val tmpDir = File(appCtx.cacheDir, "backup_staging").apply { mkdirs() }
        val dbSnap = File(tmpDir, "snap_${System.nanoTime()}.db")
        try {
            val ok = LocalFolderStore.exportDatabaseSnapshot(appCtx, dbSnap)
            if (!ok || !dbSnap.exists() || dbSnap.length() < 100L) {
                error("Could not create local database snapshot")
            }

            val snap = LocalFolderStore.getCachedOrNull() ?: LocalFolderStore.loadAll(appCtx)
            val prefsJson = serializePrefs(appCtx)
            val now = System.currentTimeMillis()
            val summary = BackupSummary(
                appVersion = appVersion,
                exportedAt = now,
                layerCount = snap.layers.size,
                pinCount = snap.pins.size,
                trackCount = snap.tracks.size,
                hasPrefs = true
            )
            val manifest = JSONObject().apply {
                put("format", 1)
                put("appVersion", appVersion)
                put("exportedAt", now)
                put("layerCount", summary.layerCount)
                put("pinCount", summary.pinCount)
                put("trackCount", summary.trackCount)
            }

            val rawOut = appCtx.contentResolver.openOutputStream(destUri, "w")
                ?: error("Unable to open destination file for backup")
            ZipOutputStream(BufferedOutputStream(rawOut, 32 * 1024)).use { zos ->
                zos.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(ENTRY_PREFS))
                zos.write(prefsJson.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(ENTRY_DB))
                FileInputStream(dbSnap).use { fis -> fis.copyTo(zos, 32 * 1024) }
                zos.closeEntry()
                zos.flush()
            }
            summary
        } finally {
            runCatching { dbSnap.delete() }
        }
    }

    fun inspectBackupUri(ctx: Context, srcUri: Uri): Result<BackupSummary> = runCatching {
        val appCtx = ctx.applicationContext
        var manifestJson: JSONObject? = null
        var hasDb = false
        var hasPrefs = false

        val rawIn = appCtx.contentResolver.openInputStream(srcUri)
            ?: error("Unable to read selected backup file")
        ZipInputStream(BufferedInputStream(rawIn, 32 * 1024)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                when (entry.name) {
                    ENTRY_MANIFEST -> {
                        val txt = zis.readBytes().toString(Charsets.UTF_8)
                        manifestJson = JSONObject(txt)
                    }
                    ENTRY_DB -> {
                        val hdr = ByteArray(16)
                        val read = zis.read(hdr)
                        if (read >= 16 && String(hdr, 0, 15, Charsets.US_ASCII) == "SQLite format 3") {
                            hasDb = true
                        }
                    }
                    ENTRY_PREFS -> hasPrefs = true
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (!hasDb) error("Selected file is not a valid TerraSync backup (.tsbackup)")
        val m = manifestJson
        BackupSummary(
            appVersion = m?.optString("appVersion").orEmpty().ifBlank { "Unknown" },
            exportedAt = m?.optLong("exportedAt", 0L) ?: 0L,
            layerCount = m?.optInt("layerCount", -1) ?: -1,
            pinCount = m?.optInt("pinCount", -1) ?: -1,
            trackCount = m?.optInt("trackCount", -1) ?: -1,
            hasPrefs = hasPrefs
        )
    }

    fun restoreFromUri(ctx: Context, srcUri: Uri): Result<BackupSummary> = runCatching {
        val appCtx = ctx.applicationContext
        val tmpDir = File(appCtx.cacheDir, "backup_restore_${System.nanoTime()}").apply { mkdirs() }
        val extractedDb = File(tmpDir, ENTRY_DB)
        var prefsJson: JSONObject? = null
        var manifestJson: JSONObject? = null
        try {
            val rawIn = appCtx.contentResolver.openInputStream(srcUri)
                ?: error("Unable to open backup file")
            ZipInputStream(BufferedInputStream(rawIn, 32 * 1024)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        ENTRY_MANIFEST -> manifestJson = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                        ENTRY_PREFS -> prefsJson = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                        ENTRY_DB -> {
                            FileOutputStream(extractedDb).use { fos ->
                                zis.copyTo(fos, 32 * 1024)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (!extractedDb.exists() || extractedDb.length() < 100L) {
                error("Backup archive is missing database data")
            }
            val restoredDb = LocalFolderStore.restoreDatabaseSnapshot(appCtx, extractedDb)
            if (!restoredDb) {
                error("Backup database failed integrity verification")
            }
            prefsJson?.let { restorePrefs(appCtx, it) }

            val loaded = LocalFolderStore.loadAll(appCtx)
            BackupSummary(
                appVersion = manifestJson?.optString("appVersion").orEmpty().ifBlank { "Restored" },
                exportedAt = manifestJson?.optLong("exportedAt", 0L) ?: 0L,
                layerCount = loaded.layers.size,
                pinCount = loaded.pins.size,
                trackCount = loaded.tracks.size,
                hasPrefs = prefsJson != null
            )
        } finally {
            runCatching { tmpDir.deleteRecursively() }
        }
    }

    private fun serializePrefs(ctx: Context): JSONObject {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val root = JSONObject()
        for ((k, v) in sp.all) {
            val item = JSONObject()
            when (v) {
                is String -> { item.put("t", "s"); item.put("v", v) }
                is Boolean -> { item.put("t", "b"); item.put("v", v) }
                is Int -> { item.put("t", "i"); item.put("v", v) }
                is Long -> { item.put("t", "l"); item.put("v", v) }
                is Float -> { item.put("t", "f"); item.put("v", v.toDouble()) }
                is Set<*> -> {
                    item.put("t", "set")
                    val arr = JSONArray()
                    v.forEach { if (it is String) arr.put(it) }
                    item.put("v", arr)
                }
                else -> continue
            }
            root.put(k, item)
        }
        return root
    }

    private fun restorePrefs(ctx: Context, root: JSONObject) {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ed = sp.edit()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val item = root.optJSONObject(k) ?: continue
            when (item.optString("t")) {
                "s" -> ed.putString(k, item.optString("v", ""))
                "b" -> ed.putBoolean(k, item.optBoolean("v", false))
                "i" -> ed.putInt(k, item.optInt("v", 0))
                "l" -> ed.putLong(k, item.optLong("v", 0L))
                "f" -> ed.putFloat(k, item.optDouble("v", 0.0).toFloat())
                "set" -> {
                    val arr = item.optJSONArray("v") ?: JSONArray()
                    val set = LinkedHashSet<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        set.add(arr.optString(i, ""))
                    }
                    ed.putStringSet(k, set)
                }
            }
        }
        ed.commit()
    }
}
