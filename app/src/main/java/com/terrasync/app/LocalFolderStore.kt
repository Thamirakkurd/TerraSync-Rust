package com.terrasync.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.util.Log
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

data class LocalStoreSnapshot(
    val layers: List<LayerItem>,
    val pins: List<PinItem>,
    val tracks: List<TrackItem>
)

/**
 * ACID SQLite WAL storage engine for local KML/KMZ folders, pins, and tracks,
 * with idempotent lossless migration from legacy JSON (`local_folders_v1.json`).
 */
object LocalFolderStore {

    private const val TAG = "LocalFolderStore"
    private const val LEGACY_FILE_NAME = "local_folders_v1.json"
    private const val DB_NAME = "terrasync_local_store.db"
    private const val DB_VERSION = 1
    private const val META_LEGACY_MIGRATED = "legacy_json_v1_migrated"

    // Lock invariant: `lock` -> `diskLock` only; `ioExecutor` tasks may acquire `diskLock`
    // and NEVER acquire `lock`, guaranteeing deadlock-free FIFO async WAL writes.
    private val lock = Any()
    private val diskLock = Any()
    @Volatile private var ioThread: Thread? = null
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "terrasync-local-store").apply {
            isDaemon = true
            ioThread = this
        }
    }

    @Volatile private var cachedSnapshot: LocalStoreSnapshot? = null
    @Volatile private var dbHelper: LocalFolderDbHelper? = null

    private fun drainPendingWritesIfNotOnIoThread() {
        if (Thread.currentThread() === ioThread) return
        runCatching { ioExecutor.submit { }.get() }
    }

    private fun enqueueDiskWrite(
        ctx: Context,
        opName: String,
        count: Int,
        block: (SQLiteDatabase) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        ioExecutor.execute {
            val t0 = System.nanoTime()
            runCatching {
                synchronized(diskLock) {
                    val db = writableDb(appCtx)
                    db.beginTransactionNonExclusive()
                    try {
                        block(db)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }.onSuccess {
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                Log.i(TAG, "WAL_METRIC op=$opName count=$count ms=${"%.2f".format(Locale.US, elapsedMs)}")
            }.onFailure { err ->
                Log.e(TAG, "WAL_WRITE_ERROR op=$opName", err)
            }
        }
    }

    private class LocalFolderDbHelper(appCtx: Context) : SQLiteOpenHelper(
        appCtx.applicationContext,
        DB_NAME,
        null,
        DB_VERSION
    ) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            runCatching { db.enableWriteAheadLogging() }
            runCatching { db.setForeignKeyConstraintsEnabled(true) }
            runCatching { db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() } }
            runCatching { db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() } }
            runCatching { db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() } }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS local_layers (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    share_code TEXT NOT NULL DEFAULT '',
                    created_by TEXT NOT NULL DEFAULT '',
                    creator_device_id TEXT NOT NULL DEFAULT '',
                    creator_uid TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_local_layers_updated ON local_layers(updated_at DESC)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS local_pins (
                    id TEXT PRIMARY KEY NOT NULL,
                    layer_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    lat REAL NOT NULL DEFAULT 0.0,
                    lng REAL NOT NULL DEFAULT 0.0,
                    status TEXT NOT NULL DEFAULT 'pending',
                    issue_reason TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    is_edited INTEGER NOT NULL DEFAULT 0,
                    edited_by TEXT NOT NULL DEFAULT '',
                    edited_at INTEGER NOT NULL DEFAULT 0,
                    edit_history TEXT NOT NULL DEFAULT '',
                    folder_path TEXT NOT NULL DEFAULT '',
                    FOREIGN KEY(layer_id) REFERENCES local_layers(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_local_pins_layer ON local_pins(layer_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_local_pins_status ON local_pins(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_local_pins_coords ON local_pins(lat, lng)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS local_tracks (
                    id TEXT PRIMARY KEY NOT NULL,
                    layer_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    coords TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    folder_path TEXT NOT NULL DEFAULT '',
                    FOREIGN KEY(layer_id) REFERENCES local_layers(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_local_tracks_layer ON local_tracks(layer_id)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS store_meta (
                    meta_key TEXT PRIMARY KEY NOT NULL,
                    meta_val TEXT NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun helperFor(ctx: Context): LocalFolderDbHelper {
        dbHelper?.let { return it }
        return synchronized(diskLock) {
            dbHelper ?: LocalFolderDbHelper(ctx.applicationContext).also { dbHelper = it }
        }
    }

    private fun writableDb(ctx: Context): SQLiteDatabase {
        val db = helperFor(ctx).writableDatabase
        ensureMigratedFromLegacyJson(ctx.applicationContext, db)
        return db
    }

    fun isLocalLayerId(layerId: String): Boolean = layerId.startsWith("local_")

    fun newLocalLayerId(): String = "local_${UUID.randomUUID()}"

    fun getCachedOrNull(): LocalStoreSnapshot? = cachedSnapshot

    internal fun clearCacheForTest() = synchronized(lock) {
        cachedSnapshot = null
    }

    fun exportDatabaseSnapshot(ctx: Context, destFile: File): Boolean {
        val appCtx = ctx.applicationContext
        drainPendingWritesIfNotOnIoThread()
        return synchronized(diskLock) {
            runCatching {
                val db = writableDb(appCtx)
                runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
                if (destFile.exists()) destFile.delete()
                val vacuumed = runCatching {
                    val safePath = destFile.absolutePath.replace("'", "''")
                    db.execSQL("VACUUM INTO '$safePath'")
                    destFile.exists() && destFile.length() >= 100L
                }.getOrDefault(false)
                if (!vacuumed) {
                    val dbFile = appCtx.getDatabasePath(DB_NAME)
                    if (!dbFile.exists()) return@runCatching false
                    dbFile.copyTo(destFile, overwrite = true)
                }
                destFile.exists() && destFile.length() >= 100L
            }.getOrDefault(false)
        }
    }

    fun restoreDatabaseSnapshot(ctx: Context, srcFile: File): Boolean {
        val appCtx = ctx.applicationContext
        if (!srcFile.exists() || srcFile.length() < 100L) return false
        val valid = runCatching {
            val hdr = ByteArray(16)
            FileInputStream(srcFile).use { fis ->
                if (fis.read(hdr) < 16) return@runCatching false
            }
            if (String(hdr, 0, 15, Charsets.US_ASCII) != "SQLite format 3") return@runCatching false
            SQLiteDatabase.openDatabase(srcFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { checkDb ->
                val tables = HashSet<String>()
                checkDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                    while (c.moveToNext()) tables.add(c.getString(0))
                }
                "local_layers" in tables && "local_pins" in tables && "local_tracks" in tables
            }
        }.getOrDefault(false)
        if (!valid) return false

        drainPendingWritesIfNotOnIoThread()
        return synchronized(lock) {
            synchronized(diskLock) {
                runCatching {
                    cachedSnapshot = null
                    runCatching { dbHelper?.close() }
                    dbHelper = null
                    val dbFile = appCtx.getDatabasePath(DB_NAME)
                    dbFile.parentFile?.mkdirs()
                    listOf(
                        File(dbFile.path + "-wal"),
                        File(dbFile.path + "-shm"),
                        File(dbFile.path + "-journal")
                    ).forEach { runCatching { if (it.exists()) it.delete() } }
                    val tmp = File(dbFile.parentFile, "${DB_NAME}.restore.tmp")
                    srcFile.copyTo(tmp, overwrite = true)
                    if (dbFile.exists()) dbFile.delete()
                    if (!tmp.renameTo(dbFile)) {
                        tmp.copyTo(dbFile, overwrite = true)
                        tmp.delete()
                    }
                    true
                }.getOrDefault(false)
            }
        }
    }

    fun flushPendingWritesAsync(ctx: Context) {
        val appCtx = ctx.applicationContext
        ioExecutor.execute {
            runCatching {
                synchronized(diskLock) {
                    dbHelper?.writableDatabase?.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null)?.use { it.moveToFirst() }
                }
            }
        }
    }

    internal fun flushPendingWritesForTest(ctx: Context) {
        drainPendingWritesIfNotOnIoThread()
        synchronized(diskLock) {
            val db = writableDb(ctx)
            runCatching {
                db.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null).use { it.moveToFirst() }
            }
        }
    }

    internal fun resetForTest(ctx: Context) {
        drainPendingWritesIfNotOnIoThread()
        synchronized(lock) {
            synchronized(diskLock) {
                val appCtx = ctx.applicationContext
                cachedSnapshot = null
                runCatching { dbHelper?.close() }
                dbHelper = null
                val dbFile = appCtx.getDatabasePath(DB_NAME)
                listOf(
                    dbFile,
                    File(dbFile.path + "-wal"),
                    File(dbFile.path + "-shm"),
                    File(dbFile.path + "-journal"),
                    File(appCtx.filesDir, LEGACY_FILE_NAME),
                    File(appCtx.filesDir, "$LEGACY_FILE_NAME.tmp"),
                    File(appCtx.filesDir, "$LEGACY_FILE_NAME.tmp2"),
                    File(appCtx.filesDir, "$LEGACY_FILE_NAME.migrated.bak")
                ).forEach { runCatching { if (it.exists()) it.delete() } }
            }
        }
    }

    internal fun queryJournalMode(ctx: Context): String {
        drainPendingWritesIfNotOnIoThread()
        return synchronized(diskLock) {
            val db = writableDb(ctx)
            db.rawQuery("PRAGMA journal_mode", null).use { cur ->
                if (cur.moveToFirst()) cur.getString(0).orEmpty().lowercase(Locale.US) else ""
            }
        }
    }

    private fun JsonReader.nextStringOrEmpty(): String =
        if (peek() == JsonToken.NULL) { skipValue(); "" } else nextString()

    private fun JsonReader.nextLongOrZero(): Long =
        if (peek() == JsonToken.NULL) { skipValue(); 0L } else nextLong()

    private fun JsonReader.nextDoubleOrZero(): Double =
        if (peek() == JsonToken.NULL) { skipValue(); 0.0 } else nextDouble()

    private fun JsonReader.nextBooleanOrFalse(): Boolean =
        if (peek() == JsonToken.NULL) { skipValue(); false } else nextBoolean()

    internal fun readSnapshotFromStream(input: InputStream): LocalStoreSnapshot {
        val layers = mutableListOf<LayerItem>()
        val pins = mutableListOf<PinItem>()
        val tracks = mutableListOf<TrackItem>()
        JsonReader(InputStreamReader(BufferedInputStream(input, 32 * 1024), Charsets.UTF_8)).use { jr ->
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "layers" -> {
                        if (jr.peek() == JsonToken.BEGIN_ARRAY) {
                            jr.beginArray()
                            while (jr.hasNext()) {
                                var id = ""
                                var name = "Local Folder"
                                var createdBy = ""
                                var creatorDeviceId = ""
                                var updatedAt = 0L
                                jr.beginObject()
                                while (jr.hasNext()) {
                                    when (jr.nextName()) {
                                        "id" -> id = jr.nextStringOrEmpty()
                                        "name" -> name = jr.nextStringOrEmpty().ifBlank { "Local Folder" }
                                        "createdBy" -> createdBy = jr.nextStringOrEmpty()
                                        "creatorDeviceId" -> creatorDeviceId = jr.nextStringOrEmpty()
                                        "updatedAt" -> updatedAt = jr.nextLongOrZero()
                                        else -> jr.skipValue()
                                    }
                                }
                                jr.endObject()
                                layers += LayerItem(
                                    id = id,
                                    name = name,
                                    shareCode = "",
                                    createdBy = createdBy,
                                    creatorDeviceId = creatorDeviceId,
                                    updatedAt = updatedAt
                                )
                            }
                            jr.endArray()
                        } else jr.skipValue()
                    }
                    "pins" -> {
                        if (jr.peek() == JsonToken.BEGIN_ARRAY) {
                            jr.beginArray()
                            while (jr.hasNext()) {
                                var id = ""
                                var layerId = ""
                                var name = "Pin"
                                var description = ""
                                var lat = 0.0
                                var lng = 0.0
                                var status = "pending"
                                var issueReason = ""
                                var updatedAt = 0L
                                var isEdited = false
                                var editedBy = ""
                                var editedAt = 0L
                                var editHistory = ""
                                var folderPath = ""
                                jr.beginObject()
                                while (jr.hasNext()) {
                                    when (jr.nextName()) {
                                        "id" -> id = jr.nextStringOrEmpty()
                                        "layerId" -> layerId = jr.nextStringOrEmpty()
                                        "name" -> name = jr.nextStringOrEmpty().ifBlank { "Pin" }
                                        "description" -> description = jr.nextStringOrEmpty()
                                        "lat" -> lat = jr.nextDoubleOrZero()
                                        "lng" -> lng = jr.nextDoubleOrZero()
                                        "status" -> status = PinStatus.fromRaw(jr.nextStringOrEmpty()).key
                                        "issueReason" -> issueReason = jr.nextStringOrEmpty()
                                        "updatedAt" -> updatedAt = jr.nextLongOrZero()
                                        "isEdited" -> isEdited = jr.nextBooleanOrFalse()
                                        "editedBy" -> editedBy = jr.nextStringOrEmpty()
                                        "editedAt" -> editedAt = jr.nextLongOrZero()
                                        "editHistory" -> editHistory = jr.nextStringOrEmpty()
                                        "folderPath" -> folderPath = jr.nextStringOrEmpty()
                                        else -> jr.skipValue()
                                    }
                                }
                                jr.endObject()
                                pins += PinItem(
                                    id = id,
                                    layerId = layerId,
                                    name = name,
                                    description = description,
                                    lat = lat,
                                    lng = lng,
                                    status = status,
                                    issueReason = issueReason,
                                    updatedAt = updatedAt,
                                    isEdited = isEdited,
                                    editedBy = editedBy,
                                    editedAt = editedAt,
                                    editHistory = editHistory,
                                    folderPath = folderPath
                                )
                            }
                            jr.endArray()
                        } else jr.skipValue()
                    }
                    "tracks" -> {
                        if (jr.peek() == JsonToken.BEGIN_ARRAY) {
                            jr.beginArray()
                            while (jr.hasNext()) {
                                var id = ""
                                var layerId = ""
                                var name = "Track"
                                var coords = ""
                                var updatedAt = 0L
                                var folderPath = ""
                                jr.beginObject()
                                while (jr.hasNext()) {
                                    when (jr.nextName()) {
                                        "id" -> id = jr.nextStringOrEmpty()
                                        "layerId" -> layerId = jr.nextStringOrEmpty()
                                        "name" -> name = jr.nextStringOrEmpty().ifBlank { "Track" }
                                        "coords" -> coords = jr.nextStringOrEmpty()
                                        "updatedAt" -> updatedAt = jr.nextLongOrZero()
                                        "folderPath" -> folderPath = jr.nextStringOrEmpty()
                                        else -> jr.skipValue()
                                    }
                                }
                                jr.endObject()
                                tracks += TrackItem(
                                    id = id,
                                    layerId = layerId,
                                    name = name,
                                    coords = coords,
                                    updatedAt = updatedAt,
                                    folderPath = folderPath
                                )
                            }
                            jr.endArray()
                        } else jr.skipValue()
                    }
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
        }
        return LocalStoreSnapshot(layers.sortedByDescending { it.updatedAt }, pins, tracks)
    }

    private fun countTable(db: SQLiteDatabase, table: String): Int {
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cur ->
            return if (cur.moveToFirst()) cur.getInt(0) else 0
        }
    }

    private fun getMeta(db: SQLiteDatabase, key: String): String? {
        db.rawQuery("SELECT meta_val FROM store_meta WHERE meta_key = ? LIMIT 1", arrayOf(key)).use { cur ->
            return if (cur.moveToFirst()) cur.getString(0) else null
        }
    }

    private fun archiveLegacyJson(filesDir: File, legacyFile: File) {
        val bakFile = File(filesDir, "$LEGACY_FILE_NAME.migrated.bak")
        runCatching {
            if (bakFile.exists()) bakFile.delete()
            if (!legacyFile.renameTo(bakFile)) {
                legacyFile.copyTo(bakFile, overwrite = true)
                if (bakFile.length() == legacyFile.length()) {
                    legacyFile.delete()
                }
            }
        }.onFailure {
            Log.e(TAG, "archiveLegacyJson warning", it)
        }
    }

    private fun ensureMigratedFromLegacyJson(appCtx: Context, db: SQLiteDatabase) {
        val filesDir = appCtx.filesDir
        val staleTmp = File(filesDir, "$LEGACY_FILE_NAME.tmp")
        val staleTmp2 = File(filesDir, "$LEGACY_FILE_NAME.tmp2")
        if (staleTmp.exists()) runCatching { staleTmp.delete() }
        if (staleTmp2.exists()) runCatching { staleTmp2.delete() }

        val legacyFile = File(filesDir, LEGACY_FILE_NAME)
        if (!legacyFile.exists()) return
        if (legacyFile.length() <= 0L) {
            runCatching { legacyFile.delete() }
            return
        }

        if (getMeta(db, META_LEGACY_MIGRATED) == "1") {
            archiveLegacyJson(filesDir, legacyFile)
            return
        }

        val t0 = System.nanoTime()
        val parsed = runCatching {
            FileInputStream(legacyFile).use { fis -> readSnapshotFromStream(fis) }
        }.getOrElse { err ->
            Log.e(TAG, "Failed reading legacy JSON for WAL migration", err)
            return
        }

        val layersMap = LinkedHashMap<String, LayerItem>(parsed.layers.size + 4)
        for (l in parsed.layers) {
            val lid = l.id.ifBlank { newLocalLayerId() }
            layersMap[lid] = if (l.id == lid) l else l.copy(id = lid)
        }
        val fallbackLayerId = layersMap.keys.firstOrNull() ?: newLocalLayerId()
        fun ensureLayerExists(layerIdRaw: String, sampleTs: Long): String {
            val lid = layerIdRaw.ifBlank { fallbackLayerId }
            if (!layersMap.containsKey(lid)) {
                layersMap[lid] = LayerItem(
                    id = lid,
                    name = "Recovered Local Folder",
                    shareCode = "",
                    createdBy = "",
                    creatorDeviceId = "",
                    updatedAt = sampleTs
                )
            }
            return lid
        }

        val pinsMap = LinkedHashMap<String, PinItem>(parsed.pins.size + 4)
        for (p in parsed.pins) {
            val pid = p.id.ifBlank { UUID.randomUUID().toString() }
            val lid = ensureLayerExists(p.layerId, p.updatedAt)
            pinsMap[pid] = if (p.id == pid && p.layerId == lid) p else p.copy(id = pid, layerId = lid)
        }
        val tracksMap = LinkedHashMap<String, TrackItem>(parsed.tracks.size + 4)
        for (t in parsed.tracks) {
            val tid = t.id.ifBlank { UUID.randomUUID().toString() }
            val lid = ensureLayerExists(t.layerId, t.updatedAt)
            tracksMap[tid] = if (t.id == tid && t.layerId == lid) t else t.copy(id = tid, layerId = lid)
        }

        db.beginTransactionNonExclusive()
        try {
            db.compileStatement(SQL_UPDATE_LAYER_BY_ID).use { updStmt ->
                db.compileStatement(SQL_INSERT_LAYER).use { insStmt ->
                    for (l in layersMap.values) {
                        upsertLayer(updStmt, insStmt, l)
                    }
                }
            }
            db.compileStatement(SQL_INSERT_OR_REPLACE_PIN).use { stmt ->
                for (p in pinsMap.values) {
                    bindPinInsert(stmt, p)
                    stmt.executeInsert()
                }
            }
            db.compileStatement(SQL_INSERT_OR_REPLACE_TRACK).use { stmt ->
                for (t in tracksMap.values) {
                    bindTrackInsert(stmt, t)
                    stmt.executeInsert()
                }
            }

            val actualLayers = countTable(db, "local_layers")
            val actualPins = countTable(db, "local_pins")
            val actualTracks = countTable(db, "local_tracks")
            check(actualLayers >= layersMap.size && actualPins >= pinsMap.size && actualTracks >= tracksMap.size) {
                "Migration count verification failed: expected (${layersMap.size},${pinsMap.size},${tracksMap.size}), got ($actualLayers,$actualPins,$actualTracks)"
            }

            db.compileStatement("INSERT OR REPLACE INTO store_meta(meta_key, meta_val) VALUES(?, ?)").use { metaStmt ->
                fun putMeta(k: String, v: String) {
                    metaStmt.clearBindings()
                    metaStmt.bindString(1, k)
                    metaStmt.bindString(2, v)
                    metaStmt.executeInsert()
                }
                putMeta(META_LEGACY_MIGRATED, "1")
                putMeta("legacy_migrated_layers", layersMap.size.toString())
                putMeta("legacy_migrated_pins", pinsMap.size.toString())
                putMeta("legacy_migrated_tracks", tracksMap.size.toString())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        Log.i(
            TAG,
            "WAL_METRIC op=migrate_legacy_json layers=${layersMap.size} pins=${pinsMap.size} tracks=${tracksMap.size} ms=${"%.2f".format(Locale.US, elapsedMs)}"
        )
        archiveLegacyJson(filesDir, legacyFile)
    }

    private fun readSnapshotFromDb(db: SQLiteDatabase): LocalStoreSnapshot {
        val layers = ArrayList<LayerItem>()
        db.rawQuery(
            "SELECT id, name, share_code, created_by, creator_device_id, creator_uid, updated_at FROM local_layers ORDER BY updated_at DESC",
            null
        ).use { cur ->
            while (cur.moveToNext()) {
                layers.add(
                    LayerItem(
                        id = cur.getString(0),
                        name = cur.getString(1).ifBlank { "Local Folder" },
                        shareCode = cur.getString(2),
                        createdBy = cur.getString(3),
                        creatorDeviceId = cur.getString(4),
                        creatorUid = cur.getString(5),
                        updatedAt = cur.getLong(6)
                    )
                )
            }
        }

        val pins = ArrayList<PinItem>()
        db.rawQuery(
            "SELECT id, layer_id, name, description, lat, lng, status, issue_reason, updated_at, is_edited, edited_by, edited_at, edit_history, folder_path FROM local_pins ORDER BY rowid ASC",
            null
        ).use { cur ->
            while (cur.moveToNext()) {
                pins.add(readPinFromCursor(cur))
            }
        }

        val tracks = ArrayList<TrackItem>()
        db.rawQuery(
            "SELECT id, layer_id, name, coords, updated_at, folder_path FROM local_tracks ORDER BY rowid ASC",
            null
        ).use { cur ->
            while (cur.moveToNext()) {
                tracks.add(
                    TrackItem(
                        id = cur.getString(0),
                        layerId = cur.getString(1),
                        name = cur.getString(2).ifBlank { "Track" },
                        coords = cur.getString(3),
                        updatedAt = cur.getLong(4),
                        folderPath = cur.getString(5)
                    )
                )
            }
        }
        return LocalStoreSnapshot(layers, pins, tracks)
    }

    private fun readPinFromCursor(cur: android.database.Cursor): PinItem {
        return PinItem(
            id = cur.getString(0),
            layerId = cur.getString(1),
            name = cur.getString(2).ifBlank { "Pin" },
            description = cur.getString(3),
            lat = cur.getDouble(4),
            lng = cur.getDouble(5),
            status = PinStatus.fromRaw(cur.getString(6)).key,
            issueReason = cur.getString(7),
            updatedAt = cur.getLong(8),
            isEdited = cur.getInt(9) != 0,
            editedBy = cur.getString(10),
            editedAt = cur.getLong(11),
            editHistory = cur.getString(12),
            folderPath = cur.getString(13)
        )
    }

    fun preloadAsync(ctx: Context, onLoaded: ((LocalStoreSnapshot) -> Unit)? = null) {
        val existing = cachedSnapshot
        if (existing != null) {
            onLoaded?.invoke(existing)
            return
        }
        val appCtx = ctx.applicationContext
        ioExecutor.execute {
            val snap = loadAll(appCtx)
            onLoaded?.invoke(snap)
        }
    }

    fun loadAll(ctx: Context): LocalStoreSnapshot {
        cachedSnapshot?.let { return it }
        drainPendingWritesIfNotOnIoThread()
        return synchronized(lock) {
            cachedSnapshot?.let { return it }
            val t0 = System.nanoTime()
            val loaded = synchronized(diskLock) {
                val db = writableDb(ctx)
                readSnapshotFromDb(db)
            }
            cachedSnapshot = loaded
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            Log.i(
                TAG,
                "WAL_METRIC op=load_all layers=${loaded.layers.size} pins=${loaded.pins.size} tracks=${loaded.tracks.size} ms=${"%.2f".format(Locale.US, elapsedMs)}"
            )
            loaded
        }
    }

    fun getPinById(ctx: Context, pinId: String): PinItem? {
        cachedSnapshot?.pins?.firstOrNull { it.id == pinId }?.let { return it }
        drainPendingWritesIfNotOnIoThread()
        cachedSnapshot?.pins?.firstOrNull { it.id == pinId }?.let { return it }
        return synchronized(diskLock) {
            val db = writableDb(ctx)
            db.rawQuery(
                "SELECT id, layer_id, name, description, lat, lng, status, issue_reason, updated_at, is_edited, edited_by, edited_at, edit_history, folder_path FROM local_pins WHERE id = ? LIMIT 1",
                arrayOf(pinId)
            ).use { cur ->
                if (cur.moveToFirst()) readPinFromCursor(cur) else null
            }
        }
    }

    fun getPinsForLayer(ctx: Context, layerId: String): List<PinItem> {
        cachedSnapshot?.let { snap ->
            return snap.pins.filter { it.layerId == layerId }
        }
        drainPendingWritesIfNotOnIoThread()
        cachedSnapshot?.let { snap ->
            return snap.pins.filter { it.layerId == layerId }
        }
        return synchronized(diskLock) {
            val db = writableDb(ctx)
            val list = ArrayList<PinItem>()
            db.rawQuery(
                "SELECT id, layer_id, name, description, lat, lng, status, issue_reason, updated_at, is_edited, edited_by, edited_at, edit_history, folder_path FROM local_pins WHERE layer_id = ? ORDER BY rowid ASC",
                arrayOf(layerId)
            ).use { cur ->
                while (cur.moveToNext()) list.add(readPinFromCursor(cur))
            }
            list
        }
    }

    fun saveNewLayer(
        ctx: Context,
        layerId: String,
        layerName: String,
        createdBy: String,
        creatorDeviceId: String,
        parsed: ParsedKml
    ) = synchronized(lock) {
        val cur = loadAll(ctx)
        val now = System.currentTimeMillis()
        val layer = LayerItem(
            id = layerId,
            name = layerName,
            shareCode = "",
            createdBy = createdBy,
            creatorDeviceId = creatorDeviceId,
            updatedAt = now
        )
        val fixedPins = parsed.pins.map {
            it.copy(
                layerId = layerId,
                status = PinStatus.fromRaw(it.status).key,
                updatedAt = if (it.updatedAt > 0L) it.updatedAt else now
            )
        }
        val fixedTracks = parsed.tracks.map {
            it.copy(
                layerId = layerId,
                updatedAt = if (it.updatedAt > 0L) it.updatedAt else now
            )
        }

        cachedSnapshot = LocalStoreSnapshot(
            layers = (cur.layers.filterNot { it.id == layerId } + layer).sortedByDescending { it.updatedAt },
            pins = cur.pins.filterNot { it.layerId == layerId } + fixedPins,
            tracks = cur.tracks.filterNot { it.layerId == layerId } + fixedTracks
        )
        enqueueDiskWrite(ctx, "save_new_layer", fixedPins.size) { db ->
            upsertLayer(db, layer)
            db.execSQL("DELETE FROM local_pins WHERE layer_id = ?", arrayOf(layerId))
            db.execSQL("DELETE FROM local_tracks WHERE layer_id = ?", arrayOf(layerId))
            db.compileStatement(SQL_INSERT_OR_REPLACE_PIN).use { stmt ->
                for (p in fixedPins) {
                    bindPinInsert(stmt, p)
                    stmt.executeInsert()
                }
            }
            db.compileStatement(SQL_INSERT_OR_REPLACE_TRACK).use { stmt ->
                for (t in fixedTracks) {
                    bindTrackInsert(stmt, t)
                    stmt.executeInsert()
                }
            }
        }
    }

    fun appendToLayer(
        ctx: Context,
        layerId: String,
        newPins: List<PinItem>,
        newTracks: List<TrackItem>
    ) = synchronized(lock) {
        val cur = loadAll(ctx)
        val now = System.currentTimeMillis()
        val fixedPins = newPins.map { it.copy(id = UUID.randomUUID().toString(), layerId = layerId, updatedAt = now) }
        val fixedTracks = newTracks.map { it.copy(id = UUID.randomUUID().toString(), layerId = layerId, updatedAt = now) }

        val updatedLayers = cur.layers
            .map { if (it.id == layerId) it.copy(updatedAt = now) else it }
            .sortedByDescending { it.updatedAt }
        cachedSnapshot = LocalStoreSnapshot(updatedLayers, cur.pins + fixedPins, cur.tracks + fixedTracks)
        enqueueDiskWrite(ctx, "append_to_layer", fixedPins.size) { db ->
            db.execSQL("UPDATE local_layers SET updated_at = ? WHERE id = ?", arrayOf<Any>(now, layerId))
            db.compileStatement(SQL_INSERT_OR_REPLACE_PIN).use { stmt ->
                for (p in fixedPins) {
                    bindPinInsert(stmt, p)
                    stmt.executeInsert()
                }
            }
            db.compileStatement(SQL_INSERT_OR_REPLACE_TRACK).use { stmt ->
                for (t in fixedTracks) {
                    bindTrackInsert(stmt, t)
                    stmt.executeInsert()
                }
            }
        }
    }

    fun addManualPin(
        ctx: Context,
        name: String,
        description: String,
        lat: Double,
        lng: Double,
        deviceStamp: String,
        deviceId: String
    ): PinItem = synchronized(lock) {
        val cur = loadAll(ctx)
        val now = System.currentTimeMillis()
        val cleanId = deviceId.filter { it.isLetterOrDigit() }.uppercase(Locale.US).ifBlank { "LOCAL" }
        val layerId = "local_manual_$cleanId"
        val existingLayer = cur.layers.firstOrNull { it.id == layerId }
        val updatedLayer = existingLayer?.copy(updatedAt = now) ?: LayerItem(
            id = layerId,
            name = "My Field Pins",
            shareCode = "",
            createdBy = deviceStamp,
            creatorDeviceId = cleanId,
            updatedAt = now
        )
        val pin = PinItem(
            id = UUID.randomUUID().toString(),
            layerId = layerId,
            name = name.ifBlank { "New Pin" },
            description = description,
            lat = lat,
            lng = lng,
            status = "pending",
            issueReason = "",
            updatedAt = now,
            isEdited = false,
            editedBy = "",
            editedAt = 0L,
            editHistory = ""
        )

        val nextLayers = (cur.layers.filterNot { it.id == layerId } + updatedLayer).sortedByDescending { it.updatedAt }
        cachedSnapshot = cur.copy(layers = nextLayers, pins = cur.pins + pin)
        enqueueDiskWrite(ctx, "add_manual_pin", 1) { db ->
            // Safe UPDATE -> INSERT upsert avoids ON DELETE CASCADE wiping earlier manual pins.
            upsertLayer(db, updatedLayer)
            db.compileStatement(SQL_INSERT_OR_REPLACE_PIN).use { stmt ->
                bindPinInsert(stmt, pin)
                stmt.executeInsert()
            }
        }
        pin
    }

    private fun replaceSinglePin(pins: List<PinItem>, targetId: String, replacement: PinItem): List<PinItem> {
        val idx = pins.indexOfFirst { it.id == targetId }
        if (idx < 0) return pins
        val copy = ArrayList<PinItem>(pins)
        copy[idx] = replacement
        return copy
    }

    fun updatePin(ctx: Context, pin: PinItem): PinItem = synchronized(lock) {
        val cur = loadAll(ctx)
        cachedSnapshot = cur.copy(pins = replaceSinglePin(cur.pins, pin.id, pin))
        persistSinglePinToDb(ctx, pin, "update_pin")
        pin
    }

    fun updatePinStatus(
        ctx: Context,
        pin: PinItem,
        status: String,
        deviceStamp: String,
        issueReason: String = ""
    ): PinItem = synchronized(lock) {
        val cur = loadAll(ctx)
        val now = System.currentTimeMillis()
        val cleanReason = if (status.equals("issue", true) || status.equals("done", true)) {
            sanitizeHistoryToken(issueReason)
        } else ""
        val safeStamp = sanitizeHistoryToken(deviceStamp)
        val detail = if (cleanReason.isNotBlank()) "${statusLabel(status)}: $cleanReason" else statusLabel(status)
        val line = "${formatTime(now)} • $safeStamp → $detail"
        val history = PinEditHistoryAudit.prependHistoryLine(line, pin.editHistory)
        val updated = pin.copy(
            status = status,
            issueReason = cleanReason,
            updatedAt = now,
            isEdited = true,
            editedBy = deviceStamp,
            editedAt = now,
            editHistory = history
        )
        cachedSnapshot = cur.copy(pins = replaceSinglePin(cur.pins, pin.id, updated))
        persistSinglePinToDb(ctx, updated, "update_pin_status")
        updated
    }

    fun updatePinsStatusBatch(
        ctx: Context,
        targetPins: List<PinItem>,
        status: String,
        deviceStamp: String,
        issueReason: String = ""
    ): List<PinItem> = synchronized(lock) {
        if (targetPins.isEmpty()) return emptyList()
        val cur = loadAll(ctx)
        val now = System.currentTimeMillis()
        val cleanReason = if (status.equals("issue", true) || status.equals("done", true)) {
            sanitizeHistoryToken(issueReason)
        } else ""
        val safeStamp = sanitizeHistoryToken(deviceStamp)
        val detail = if (cleanReason.isNotBlank()) "${statusLabel(status)}: $cleanReason" else statusLabel(status)
        val line = "${formatTime(now)} • $safeStamp → $detail"
        val targetIds = targetPins.mapTo(HashSet(targetPins.size)) { it.id }
        val updatedById = LinkedHashMap<String, PinItem>(targetPins.size)
        val nextPins = cur.pins.map { existing ->
            if (existing.id in targetIds) {
                val history = PinEditHistoryAudit.prependHistoryLine(line, existing.editHistory)
                existing.copy(
                    status = status,
                    issueReason = cleanReason,
                    updatedAt = now,
                    isEdited = true,
                    editedBy = deviceStamp,
                    editedAt = now,
                    editHistory = history
                ).also { updatedById[it.id] = it }
            } else {
                existing
            }
        }
        cachedSnapshot = cur.copy(pins = nextPins)
        persistPinsBatchToDb(ctx, updatedById.values.toList(), "update_pins_status_batch")
        updatedById.values.toList()
    }

    fun updatePinsBatch(ctx: Context, updatedPins: List<PinItem>): List<PinItem> = synchronized(lock) {
        if (updatedPins.isEmpty()) return emptyList()
        val cur = loadAll(ctx)
        val nextPins = if (updatedPins.size == 1) {
            val single = updatedPins[0]
            replaceSinglePin(cur.pins, single.id, single)
        } else {
            val byId = updatedPins.associateBy { it.id }
            cur.pins.map { byId[it.id] ?: it }
        }
        cachedSnapshot = cur.copy(pins = nextPins)
        persistPinsBatchToDb(ctx, updatedPins.toList(), "update_pins_batch")
        updatedPins
    }

    fun updatePinText(
        ctx: Context,
        pin: PinItem,
        newName: String,
        newDesc: String,
        deviceStamp: String
    ): PinItem = synchronized(lock) {
        val cur = loadAll(ctx)
        val now = System.currentTimeMillis()
        val line = "${formatTime(now)} • ${sanitizeHistoryToken(deviceStamp)} edited text"
        val history = PinEditHistoryAudit.prependHistoryLine(line, pin.editHistory)
        val updated = pin.copy(
            name = newName.ifBlank { pin.name },
            description = newDesc,
            updatedAt = now,
            isEdited = true,
            editedBy = deviceStamp,
            editedAt = now,
            editHistory = history
        )
        cachedSnapshot = cur.copy(pins = replaceSinglePin(cur.pins, pin.id, updated))
        persistSinglePinToDb(ctx, updated, "update_pin_text")
        updated
    }

    fun deletePinHistoryEntry(ctx: Context, pin: PinItem, lineIndex: Int): PinItem = synchronized(lock) {
        val lines = pin.editHistory.lines().map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        if (lineIndex !in lines.indices) return pin
        lines.removeAt(lineIndex)
        val now = System.currentTimeMillis()
        val recomputed = PinEditHistoryAudit.recomputeFromHistoryLines(lines, now)
        val updated = pin.copy(
            status = recomputed.status,
            issueReason = recomputed.issueReason,
            updatedAt = now,
            isEdited = recomputed.isEdited,
            editedBy = recomputed.editedBy,
            editedAt = recomputed.editedAt,
            editHistory = recomputed.editHistory
        )
        val cur = loadAll(ctx)
        cachedSnapshot = cur.copy(pins = replaceSinglePin(cur.pins, pin.id, updated))
        persistSinglePinToDb(ctx, updated, "delete_pin_history_entry")
        updated
    }

    fun resetPinStatusToPending(ctx: Context, pin: PinItem): PinItem = synchronized(lock) {
        val now = System.currentTimeMillis()
        val updated = pin.copy(
            status = "pending",
            issueReason = "",
            updatedAt = now,
            isEdited = false,
            editedBy = "",
            editedAt = 0L,
            editHistory = ""
        )
        val cur = loadAll(ctx)
        cachedSnapshot = cur.copy(pins = replaceSinglePin(cur.pins, pin.id, updated))
        persistSinglePinToDb(ctx, updated, "reset_pin_status")
        updated
    }

    fun restorePinStatusSnapshot(ctx: Context, previousPin: PinItem): PinItem = synchronized(lock) {
        val cur = loadAll(ctx)
        val now = System.currentTimeMillis()
        val restored = previousPin.copy(updatedAt = now)
        cachedSnapshot = cur.copy(pins = replaceSinglePin(cur.pins, previousPin.id, restored))
        persistSinglePinToDb(ctx, restored, "restore_pin_snapshot")
        restored
    }

    fun renameLayer(ctx: Context, layerId: String, newName: String) = synchronized(lock) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        val cur = loadAll(ctx)
        val now = System.currentTimeMillis()
        cachedSnapshot = cur.copy(
            layers = cur.layers
                .map { if (it.id == layerId) it.copy(name = clean, updatedAt = now) else it }
                .sortedByDescending { it.updatedAt }
        )
        enqueueDiskWrite(ctx, "rename_layer", 1) { db ->
            db.execSQL(
                "UPDATE local_layers SET name = ?, updated_at = ? WHERE id = ?",
                arrayOf<Any>(clean, now, layerId)
            )
        }
    }

    fun deletePin(ctx: Context, pinId: String) = synchronized(lock) {
        val cur = loadAll(ctx)
        cachedSnapshot = cur.copy(pins = cur.pins.filterNot { it.id == pinId })
        enqueueDiskWrite(ctx, "delete_pin", 1) { db ->
            db.execSQL("DELETE FROM local_pins WHERE id = ?", arrayOf(pinId))
        }
    }

    fun deletePins(ctx: Context, pinIds: Collection<String>) = synchronized(lock) {
        if (pinIds.isEmpty()) return
        val drop = pinIds.toSet()
        val cur = loadAll(ctx)
        cachedSnapshot = cur.copy(pins = cur.pins.filterNot { it.id in drop })
        enqueueDiskWrite(ctx, "delete_pins", drop.size) { db ->
            db.compileStatement("DELETE FROM local_pins WHERE id = ?").use { stmt ->
                for (id in drop) {
                    stmt.clearBindings()
                    stmt.bindString(1, id)
                    stmt.executeUpdateDelete()
                }
            }
        }
    }

    fun deleteLayer(ctx: Context, layerId: String) = synchronized(lock) {
        val cur = loadAll(ctx)
        cachedSnapshot = LocalStoreSnapshot(
            layers = cur.layers.filterNot { it.id == layerId },
            pins = cur.pins.filterNot { it.layerId == layerId },
            tracks = cur.tracks.filterNot { it.layerId == layerId }
        )
        enqueueDiskWrite(ctx, "delete_layer", 1) { db ->
            // Cascade deletes all child local_pins and local_tracks via ON DELETE CASCADE.
            db.execSQL("DELETE FROM local_layers WHERE id = ?", arrayOf(layerId))
            db.execSQL("DELETE FROM local_pins WHERE layer_id = ?", arrayOf(layerId))
            db.execSQL("DELETE FROM local_tracks WHERE layer_id = ?", arrayOf(layerId))
        }
    }

    private fun persistSinglePinToDb(ctx: Context, pin: PinItem, opName: String) {
        enqueueDiskWrite(ctx, opName, 1) { db ->
            val rowsUpdated = db.compileStatement(SQL_UPDATE_PIN_BY_ID).use { stmt ->
                bindPinUpdateById(stmt, pin)
                stmt.executeUpdateDelete()
            }
            if (rowsUpdated == 0) {
                db.compileStatement(SQL_INSERT_OR_REPLACE_PIN).use { stmt ->
                    bindPinInsert(stmt, pin)
                    stmt.executeInsert()
                }
            }
        }
    }

    private fun persistPinsBatchToDb(ctx: Context, pinsToSave: List<PinItem>, opName: String) {
        enqueueDiskWrite(ctx, opName, pinsToSave.size) { db ->
            db.compileStatement(SQL_UPDATE_PIN_BY_ID).use { updStmt ->
                db.compileStatement(SQL_INSERT_OR_REPLACE_PIN).use { insStmt ->
                    for (p in pinsToSave) {
                        bindPinUpdateById(updStmt, p)
                        if (updStmt.executeUpdateDelete() == 0) {
                            bindPinInsert(insStmt, p)
                            insStmt.executeInsert()
                        }
                    }
                }
            }
        }
    }

    private fun upsertLayer(db: SQLiteDatabase, l: LayerItem) {
        db.compileStatement(SQL_UPDATE_LAYER_BY_ID).use { updStmt ->
            db.compileStatement(SQL_INSERT_LAYER).use { insStmt ->
                upsertLayer(updStmt, insStmt, l)
            }
        }
    }

    private fun upsertLayer(updStmt: SQLiteStatement, insStmt: SQLiteStatement, l: LayerItem) {
        bindLayerUpdateById(updStmt, l)
        if (updStmt.executeUpdateDelete() == 0) {
            bindLayerInsert(insStmt, l)
            insStmt.executeInsert()
        }
    }

    private const val SQL_UPDATE_LAYER_BY_ID =
        "UPDATE local_layers SET name = ?, share_code = ?, created_by = ?, creator_device_id = ?, creator_uid = ?, updated_at = ? WHERE id = ?"

    private const val SQL_INSERT_LAYER =
        "INSERT INTO local_layers(id, name, share_code, created_by, creator_device_id, creator_uid, updated_at) VALUES(?, ?, ?, ?, ?, ?, ?)"

    private const val SQL_INSERT_OR_REPLACE_PIN =
        "INSERT OR REPLACE INTO local_pins(id, layer_id, name, description, lat, lng, status, issue_reason, updated_at, is_edited, edited_by, edited_at, edit_history, folder_path) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

    private const val SQL_UPDATE_PIN_BY_ID =
        "UPDATE local_pins SET layer_id = ?, name = ?, description = ?, lat = ?, lng = ?, status = ?, issue_reason = ?, updated_at = ?, is_edited = ?, edited_by = ?, edited_at = ?, edit_history = ?, folder_path = ? WHERE id = ?"

    private const val SQL_INSERT_OR_REPLACE_TRACK =
        "INSERT OR REPLACE INTO local_tracks(id, layer_id, name, coords, updated_at, folder_path) VALUES(?, ?, ?, ?, ?, ?)"

    private fun bindLayerUpdateById(stmt: SQLiteStatement, l: LayerItem) {
        stmt.clearBindings()
        stmt.bindString(1, l.name)
        stmt.bindString(2, l.shareCode)
        stmt.bindString(3, l.createdBy)
        stmt.bindString(4, l.creatorDeviceId)
        stmt.bindString(5, l.creatorUid)
        stmt.bindLong(6, l.updatedAt)
        stmt.bindString(7, l.id)
    }

    private fun bindLayerInsert(stmt: SQLiteStatement, l: LayerItem) {
        stmt.clearBindings()
        stmt.bindString(1, l.id)
        stmt.bindString(2, l.name)
        stmt.bindString(3, l.shareCode)
        stmt.bindString(4, l.createdBy)
        stmt.bindString(5, l.creatorDeviceId)
        stmt.bindString(6, l.creatorUid)
        stmt.bindLong(7, l.updatedAt)
    }

    private fun bindPinInsert(stmt: SQLiteStatement, p: PinItem) {
        stmt.clearBindings()
        stmt.bindString(1, p.id)
        stmt.bindString(2, p.layerId)
        stmt.bindString(3, p.name)
        stmt.bindString(4, p.description)
        stmt.bindDouble(5, if (p.lat.isFinite()) p.lat else 0.0)
        stmt.bindDouble(6, if (p.lng.isFinite()) p.lng else 0.0)
        stmt.bindString(7, p.status)
        stmt.bindString(8, p.issueReason)
        stmt.bindLong(9, p.updatedAt)
        stmt.bindLong(10, if (p.isEdited) 1L else 0L)
        stmt.bindString(11, p.editedBy)
        stmt.bindLong(12, p.editedAt)
        stmt.bindString(13, p.editHistory)
        stmt.bindString(14, p.folderPath)
    }

    private fun bindPinUpdateById(stmt: SQLiteStatement, p: PinItem) {
        stmt.clearBindings()
        stmt.bindString(1, p.layerId)
        stmt.bindString(2, p.name)
        stmt.bindString(3, p.description)
        stmt.bindDouble(4, if (p.lat.isFinite()) p.lat else 0.0)
        stmt.bindDouble(5, if (p.lng.isFinite()) p.lng else 0.0)
        stmt.bindString(6, p.status)
        stmt.bindString(7, p.issueReason)
        stmt.bindLong(8, p.updatedAt)
        stmt.bindLong(9, if (p.isEdited) 1L else 0L)
        stmt.bindString(10, p.editedBy)
        stmt.bindLong(11, p.editedAt)
        stmt.bindString(12, p.editHistory)
        stmt.bindString(13, p.folderPath)
        stmt.bindString(14, p.id)
    }

    private fun bindTrackInsert(stmt: SQLiteStatement, t: TrackItem) {
        stmt.clearBindings()
        stmt.bindString(1, t.id)
        stmt.bindString(2, t.layerId)
        stmt.bindString(3, t.name)
        stmt.bindString(4, t.coords)
        stmt.bindLong(5, t.updatedAt)
        stmt.bindString(6, t.folderPath)
    }

    private fun sanitizeHistoryToken(raw: String): String {
        return raw.replace(Regex("[\\r\\n]+"), " ").replace("→", "-").trim()
    }

    private fun formatTime(ts: Long): String = TimeFormatters.canonicalHistory(ts)
}
