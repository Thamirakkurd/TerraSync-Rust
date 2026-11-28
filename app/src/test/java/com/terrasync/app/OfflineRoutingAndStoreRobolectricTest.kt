package com.terrasync.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineRoutingAndStoreRobolectricTest {

    @Test
    fun testOfflineRoutingSqliteStagingToTsrAndRouteOffline() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val chunkFile = File.createTempFile("qa_robo_overpass_", ".json", ctx.cacheDir)
        val packId = "qa_robo_test"
        try {
            chunkFile.writeText(
                """
                {
                  "version": 0.6,
                  "elements": [
                    {"type":"node","id":1,"lat":36.8500,"lon":42.9500},
                    {"type":"node","id":2,"lat":36.8530,"lon":42.9500},
                    {"type":"node","id":3,"lat":36.8560,"lon":42.9540},
                    {"type":"node","id":4,"lat":36.8560,"lon":42.9580},
                    {"type":"node","id":5,"lat":36.8520,"lon":42.9580},
                    {"type":"way","id":101,"nodes":[1,2,3,4],"tags":{"highway":"residential","name":"Zakho Main St"}},
                    {"type":"way","id":102,"nodes":[2,5,4],"tags":{"highway":"secondary","name":"Asia Bypass"}}
                  ]
                }
                """.trimIndent(),
                Charsets.UTF_8
            )

            val region = OfflineRoutingEngine.compileLocalOverpassChunksToTsr(
                ctx = ctx,
                regionName = "QA Robolectrics",
                packId = packId,
                minLat = 36.80,
                minLng = 42.90,
                maxLat = 36.90,
                maxLng = 43.00,
                chunkFiles = listOf(chunkFile)
            )
            assertNotNull("Expected compiled .tsr region from SQLite staging", region)
            assertTrue("Expected >= 5 nodes, got ${region!!.nodeCount}", region.nodeCount >= 5)
            assertTrue("Expected >= 8 bidirectional edges, got ${region.edgeCount}", region.edgeCount >= 8)

            val listed = OfflineRoutingEngine.listRegions(ctx)
            assertTrue("Expected region in listRegions", listed.any { it.fileName == OfflineRoutingEngine.packFileName(packId) })

            val (route, err) = OfflineRoutingEngine.routeOffline(
                ctx = ctx,
                startLat = 36.8501,
                startLng = 42.9501,
                endLat = 36.8559,
                endLng = 42.9579,
                mode = TravelMode.CAR
            )
            assertNotNull("Expected valid offline route (err=$err)", route)
            assertTrue("Expected multi-point route geometry", route!!.geometry.size >= 3)
            assertTrue("Expected route distance > 300m, got ${route.totalDistanceMeters}", route.totalDistanceMeters > 300.0)
            assertTrue("Expected turn-by-turn steps", route.steps.isNotEmpty())
            assertTrue("Expected Offline engine label, got ${route.engineLabel}", route.engineLabel.contains("Offline", ignoreCase = true))
        } finally {
            OfflineRoutingEngine.deletePackRegion(ctx, packId)
            chunkFile.delete()
        }
    }

    @Test
    fun testLocalFolderStoreStreamingRoundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = ctx.getDatabasePath("terrasync_local_store.db")
        LocalFolderStore.resetForTest(ctx)

        val pins = (1..250).map { i ->
            val st = when (i % 3) {
                0 -> "done"
                1 -> "issue"
                else -> "pending"
            }
            PinItem(
                id = "pin_$i",
                layerId = "local_robo_1",
                name = "Feeder #$i - گەرەکا سەرهلدان",
                description = "Box $i \"quoted\" notes",
                lat = 36.85 + i * 0.0001,
                lng = 42.94 + i * 0.0001,
                status = st,
                issueReason = if (st == "issue") "Need Electricity" else if (st == "done") "Meter Linked" else "",
                updatedAt = 1_764_150_000_000L + i,
                isEdited = st != "pending",
                editedBy = if (st == "pending") "" else "Team 1 (#A1B2)",
                editedAt = if (st == "pending") 0L else 1_764_150_000_000L + i,
                editHistory = if (st == "pending") "" else "26 Nov 10:15 • Team 1 → Done: Meter Linked"
            )
        }
        val tracks = (1..5).map { k ->
            TrackItem(
                id = "trk_$k",
                layerId = "local_robo_1",
                name = "Line $k",
                coords = "42.94,36.85;42.95,36.86",
                updatedAt = 1_764_150_000_000L + k
            )
        }

        LocalFolderStore.saveNewLayer(
            ctx = ctx,
            layerId = "local_robo_1",
            layerName = "Robolectric Folder",
            createdBy = "Team 1 (#A1B2)",
            creatorDeviceId = "A1B2",
            parsed = ParsedKml(pins = pins, tracks = tracks, documentName = "Robolectric Folder")
        )
        LocalFolderStore.flushPendingWritesForTest(ctx)
        LocalFolderStore.clearCacheForTest()
        assertTrue("Expected persisted SQLite WAL DB on disk", dbFile.exists())
        assertEquals("wal", LocalFolderStore.queryJournalMode(ctx))

        // Simulate interrupted legacy atomic write leftovers (.tmp & .tmp2) and verify loadAll cleans both
        val staleTmp = File(ctx.filesDir, "local_folders_v1.json.tmp").apply { writeText("{}") }
        val staleTmp2 = File(ctx.filesDir, "local_folders_v1.json.tmp2").apply { writeText("{}") }
        val reloaded = LocalFolderStore.loadAll(ctx)
        assertTrue("Expected stale .tmp deleted", !staleTmp.exists())
        assertTrue("Expected stale .tmp2 deleted", !staleTmp2.exists())
        assertEquals(1, reloaded.layers.size)
        assertEquals("Robolectric Folder", reloaded.layers[0].name)
        assertEquals(250, reloaded.pins.size)
        assertEquals(5, reloaded.tracks.size)
        assertEquals("Feeder #1 - گەرەکا سەرهلدان", reloaded.pins[0].name)
        assertEquals("Box 1 \"quoted\" notes", reloaded.pins[0].description)
        assertEquals("issue", reloaded.pins[0].status)
        assertEquals("Feeder #250 - گەرەکا سەرهلدان", reloaded.pins[249].name)
        assertEquals("42.94,36.85;42.95,36.86", reloaded.tracks[0].coords)

        // Verify 5-second undo snapshot restore reverts status, note, and editHistory cleanly
        val original = reloaded.pins[1]
        val dirty = LocalFolderStore.updatePinStatus(ctx, original, "issue", "QA (#A1B2)", "No Bill")
        assertEquals("issue", dirty.status)
        assertTrue(dirty.editHistory.isNotBlank())
        val undone = LocalFolderStore.restorePinStatusSnapshot(ctx, original)
        LocalFolderStore.flushPendingWritesForTest(ctx)
        LocalFolderStore.clearCacheForTest()
        val afterUndo = LocalFolderStore.loadAll(ctx).pins.first { it.id == original.id }
        assertEquals(original.status, undone.status)
        assertEquals(original.status, afterUndo.status)
        assertEquals(original.issueReason, afterUndo.issueReason)
        assertEquals(original.isEdited, afterUndo.isEdited)
        assertEquals(original.editHistory, afterUndo.editHistory)

        LocalFolderStore.resetForTest(ctx)
    }

    @Test
    fun testLegacyJsonToSqliteWalMigrationLossless() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        LocalFolderStore.resetForTest(ctx)
        try {
            val legacyFile = File(ctx.filesDir, "local_folders_v1.json")
            val staleTmp = File(ctx.filesDir, "local_folders_v1.json.tmp").apply { writeText("corrupt") }
            val staleTmp2 = File(ctx.filesDir, "local_folders_v1.json.tmp2").apply { writeText("corrupt") }
            legacyFile.writeText(
                """
                {
                  "layers": [
                    {"id":"local_leg_1","name":"Feeder Duhok","createdBy":"Team 1 (#A1)","creatorDeviceId":"A1","updatedAt":1764100000000},
                    {"id":"local_leg_2","name":"جيايێ زاویتە","createdBy":"Team 2 (#B2)","creatorDeviceId":"B2","updatedAt":1764200000000}
                  ],
                  "pins": [
                    {"id":"leg_p1","layerId":"local_leg_1","name":"Feeder 104 - گەرەکا سەرهلدان","description":"المحطة الشمالية","lat":36.853238,"lng":42.952074,"status":"issue","issueReason":"Need Electricity","updatedAt":1764200001000,"isEdited":true,"editedBy":"Team 1 (#A1)","editedAt":1764200001000,"editHistory":"26 Nov 18:10 • Team 1 → Issue: Need Electricity","folderPath":"Sector 1 / Line A"},
                    {"id":"leg_p2","layerId":"local_leg_2","name":"Transformer #2","description":"","lat":36.8601,"lng":42.9602,"status":"done","issueReason":"Meter Linked","updatedAt":1764200002000,"isEdited":true,"editedBy":"Team 2 (#B2)","editedAt":1764200002000,"editHistory":"26 Nov 18:12 • Team 2 → Done: Meter Linked"},
                    {"id":"leg_p3","layerId":"local_orphan_layer","name":"Orphan Pin","description":"Recovered folder test","lat":36.87,"lng":42.97,"status":"pending","issueReason":"","updatedAt":1764200003000,"isEdited":false,"editedBy":"","editedAt":0,"editHistory":""}
                  ],
                  "tracks": [
                    {"id":"leg_t1","layerId":"local_leg_1","name":"Main Line","coords":"42.95,36.85;42.96,36.86","updatedAt":1764200001000,"folderPath":"Sector 1 / Line A"}
                  ]
                }
                """.trimIndent(),
                Charsets.UTF_8
            )

            assertEquals("wal", LocalFolderStore.queryJournalMode(ctx))
            val migrated = LocalFolderStore.loadAll(ctx)
            assertFalse("Expected .tmp removed", staleTmp.exists())
            assertFalse("Expected .tmp2 removed", staleTmp2.exists())
            assertFalse("Expected legacy JSON archived", legacyFile.exists())
            assertTrue("Expected .migrated.bak backup created", File(ctx.filesDir, "local_folders_v1.json.migrated.bak").exists())

            // Synthesizes recovery layer for leg_p3 plus the 2 declared folders => 3 layers, 3 pins, 1 track
            assertEquals(3, migrated.layers.size)
            assertEquals(3, migrated.pins.size)
            assertEquals(1, migrated.tracks.size)
            val p1 = migrated.pins.first { it.id == "leg_p1" }
            assertEquals("Feeder 104 - گەرەکا سەرهلدان", p1.name)
            assertEquals("issue", p1.status)
            assertEquals("Need Electricity", p1.issueReason)
            assertEquals("Sector 1 / Line A", p1.folderPath)
            assertEquals("26 Nov 18:10 • Team 1 → Issue: Need Electricity", p1.editHistory)
            assertEquals("Sector 1 / Line A", migrated.tracks.first().folderPath)

            // Idempotency across cold cache reset / restart
            LocalFolderStore.clearCacheForTest()
            val reloaded = LocalFolderStore.loadAll(ctx)
            assertEquals(3, reloaded.layers.size)
            assertEquals(3, reloaded.pins.size)
            assertEquals(1, reloaded.tracks.size)
        } finally {
            LocalFolderStore.resetForTest(ctx)
        }
    }

    @Test
    fun testSqliteWal1800PinBatchAndSinglePinUpdateLatency() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        LocalFolderStore.resetForTest(ctx)
        try {
            val pins = (1..1800).map { i ->
                PinItem(
                    id = "wal_p_$i",
                    layerId = "local_wal_stress",
                    name = "Feeder $i - گەرەکا سەرهلدان",
                    description = "Note $i",
                    lat = 36.80 + (i % 45) * 0.0005,
                    lng = 42.90 + (i / 45) * 0.0005,
                    status = "pending",
                    updatedAt = 1_764_150_000_000L
                )
            }
            val tBatch0 = System.nanoTime()
            LocalFolderStore.saveNewLayer(
                ctx = ctx,
                layerId = "local_wal_stress",
                layerName = "WAL Stress 1800",
                createdBy = "QA",
                creatorDeviceId = "QA1",
                parsed = ParsedKml(pins = pins, tracks = emptyList(), documentName = "WAL Stress 1800")
            )
            val batchMs = (System.nanoTime() - tBatch0) / 1_000_000.0

            val loaded = LocalFolderStore.loadAll(ctx)
            assertEquals(1800, loaded.pins.size)

            val editedIds = (0 until 10).map { idx -> "wal_p_${idx * 150 + 1}" }
            val latencies = mutableListOf<Double>()
            for (pid in editedIds) {
                val target = LocalFolderStore.getPinById(ctx, pid)!!
                val t0 = System.nanoTime()
                LocalFolderStore.updatePinStatus(ctx, target, "done", "QA (#1)", "Meter Linked")
                latencies.add((System.nanoTime() - t0) / 1_000_000.0)
            }
            val avgSingleMs = latencies.average()
            println(
                "[CI-STRESS] SQLite WAL 1800 pins: batchSave=${"%.2f".format(Locale.US, batchMs)} ms, " +
                    "singlePinAvg=${"%.2f".format(Locale.US, avgSingleMs)} ms, journal=${LocalFolderStore.queryJournalMode(ctx)}"
            )
            assertTrue("Expected single-pin WAL update avg < 30 ms, got $avgSingleMs", avgSingleMs < 30.0)

            LocalFolderStore.flushPendingWritesForTest(ctx)
            LocalFolderStore.clearCacheForTest()
            val reloaded = LocalFolderStore.loadAll(ctx)
            assertEquals(1800, reloaded.pins.size)
            for (pid in editedIds) {
                val rp = reloaded.pins.first { it.id == pid }
                assertEquals("done", rp.status)
                assertEquals("Meter Linked", rp.issueReason)
                assertTrue(rp.editHistory.contains("Meter Linked"))
            }
        } finally {
            LocalFolderStore.resetForTest(ctx)
        }
    }

    @Test
    fun testOfflineRoutingSqliteStagingMultiMbGraphStress() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val chunkFile = File.createTempFile("qa_robo_multimb_", ".json", ctx.cacheDir)
        val packId = "qa_robo_multimb"
        val rows = 180
        val cols = 180 // 32,400 nodes, 360 ways -> ~2.27 MB JSON, ~128,880 directed edges
        try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(chunkFile), StandardCharsets.UTF_8), 64 * 1024).use { w ->
                w.write("{\"version\":0.6,\"elements\":[")
                var first = true
                for (r in 0 until rows) {
                    val lat = 36.80 + r * 0.0008
                    for (c in 0 until cols) {
                        val id = r * cols + c + 1
                        val lon = 42.90 + c * 0.0009
                        if (!first) w.write(",")
                        first = false
                        w.write("{\"type\":\"node\",\"id\":")
                        w.write(id.toString())
                        w.write(",\"lat\":")
                        w.write(lat.toString())
                        w.write(",\"lon\":")
                        w.write(lon.toString())
                        w.write("}")
                    }
                }
                var wayId = 1_000_000
                // Horizontal roads
                for (r in 0 until rows) {
                    wayId++
                    w.write(",{\"type\":\"way\",\"id\":")
                    w.write(wayId.toString())
                    w.write(",\"nodes\":[")
                    for (c in 0 until cols) {
                        if (c > 0) w.write(",")
                        w.write((r * cols + c + 1).toString())
                    }
                    w.write("],\"tags\":{\"highway\":\"secondary\",\"name\":\"Akre East-West $r\"}}")
                }
                // Vertical roads
                for (c in 0 until cols) {
                    wayId++
                    w.write(",{\"type\":\"way\",\"id\":")
                    w.write(wayId.toString())
                    w.write(",\"nodes\":[")
                    for (r in 0 until rows) {
                        if (r > 0) w.write(",")
                        w.write((r * cols + c + 1).toString())
                    }
                    w.write("],\"tags\":{\"highway\":\"residential\",\"name\":\"Akre Cross $c\"}}")
                }
                w.write("]}")
            }

            assertTrue("Expected multi-MB chunk (>= 1.8 MB), got ${chunkFile.length()}", chunkFile.length() >= 1_800_000L)

            val runtime = Runtime.getRuntime()
            System.gc()
            val t0 = System.nanoTime()
            val region = OfflineRoutingEngine.compileLocalOverpassChunksToTsr(
                ctx = ctx,
                regionName = "QA Multi-MB Grid",
                packId = packId,
                minLat = 36.79,
                minLng = 42.89,
                maxLat = 36.91,
                maxLng = 43.03,
                chunkFiles = listOf(chunkFile)
            )
            val compileMs = (System.nanoTime() - t0) / 1_000_000L
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)

            assertNotNull("Expected compiled .tsr from multi-MB SQLite staging", region)
            assertEquals(rows * cols, region!!.nodeCount)
            assertTrue("Expected >= 59000 bidirectional edges, got ${region.edgeCount}", region.edgeCount >= 59_000)

            val (route, err) = OfflineRoutingEngine.routeOffline(
                ctx = ctx,
                startLat = 36.8002,
                startLng = 42.9002,
                endLat = 36.80 + (rows - 1) * 0.0008,
                endLng = 42.90 + (cols - 1) * 0.0009,
                mode = TravelMode.CAR
            )
            assertNotNull("Expected corner-to-corner offline route (err=$err)", route)
            assertTrue("Expected route distance > 15 km, got ${route!!.totalDistanceMeters}", route.totalDistanceMeters > 15_000.0)
            assertTrue("Expected turn-by-turn steps", route.steps.isNotEmpty())

            val tWarm1 = System.nanoTime()
            val (route2, err2) = OfflineRoutingEngine.routeOffline(
                ctx = ctx,
                startLat = 36.8002,
                startLng = 42.9002,
                endLat = 36.80 + (rows - 1) * 0.0008,
                endLng = 42.90 + (cols - 1) * 0.0009,
                mode = TravelMode.CAR
            )
            val warmRepeatMs = (System.nanoTime() - tWarm1) / 1_000_000L
            assertNotNull("Expected deterministic cached route (err=$err2)", route2)
            assertEquals(route.totalDistanceMeters, route2!!.totalDistanceMeters, 0.01)
            assertEquals(route.steps.size, route2.steps.size)

            val tWarm2 = System.nanoTime()
            val (route3, err3) = OfflineRoutingEngine.routeOffline(
                ctx = ctx,
                startLat = 36.805,
                startLng = 42.905,
                endLat = 36.80 + (rows - 6) * 0.0008,
                endLng = 42.90 + (cols - 6) * 0.0009,
                mode = TravelMode.CAR
            )
            val warmShiftedMs = (System.nanoTime() - tWarm2) / 1_000_000L
            assertNotNull("Expected shifted interior route via SpatialNodeGrid (err=$err3)", route3)
            assertTrue("Expected shifted route distance > 10 km, got ${route3!!.totalDistanceMeters}", route3.totalDistanceMeters > 10_000.0)

            println(
                "[CI-STRESS] SQLite staging multi-MB: chunk=${"%.2f".format(Locale.US, chunkFile.length() / (1024.0 * 1024.0))} MB, " +
                    "compile=${compileMs} ms, nodes=${region.nodeCount}, edges=${region.edgeCount}, " +
                    "tsr=${region.sizeBytes / 1024} KB, routeKm=${"%.2f".format(Locale.US, route.totalDistanceMeters / 1000.0)}, " +
                    "warmRepeat=${warmRepeatMs}ms, warmShifted=${warmShiftedMs}ms, heapUsed=${"%.1f".format(Locale.US, usedMb)} MB"
            )
        } finally {
            OfflineRoutingEngine.deletePackRegion(ctx, packId)
            chunkFile.delete()
        }
    }

    @Test
    fun testStreamingKmlNestedFoldersAndBareAmpersandRecovery() {
        val sb = StringBuilder(380 * 1024)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document><name>Substation & Grid</name>\n")
        sb.append("<Folder><name>Feeder 1 & Main</name>\n<Folder><name>Zone A</name>\n")
        for (i in 1..2000) {
            val lat = 36.84 + (i % 50) * 0.0002
            val lng = 42.94 + (i / 50) * 0.0002
            sb.append("<Placemark><name>Pole &amp; Tap #$i</name>")
            sb.append("<description>Feeder R&D check #$i — transformer & fuse bay inspectable payload</description>")
            sb.append("<Point><coordinates>$lng,$lat,0</coordinates></Point></Placemark>\n")
        }
        sb.append("<Placemark><name>MultiBay Junction</name><MultiGeometry>")
        sb.append("<Point><coordinates>42.9501,36.8501,0</coordinates></Point>")
        sb.append("<Point><coordinates>42.9505,36.8505,0</coordinates></Point>")
        sb.append("<LineString><coordinates>42.9501,36.8501,0 42.9505,36.8505,0</coordinates></LineString>")
        sb.append("</MultiGeometry></Placemark>\n")
        sb.append("</Folder></Folder></Document></kml>")

        val payloadBytes = sb.toString().toByteArray(Charsets.UTF_8)
        assertTrue("Expected KML > 256 KB to exercise temp-file spill, got ${payloadBytes.size}", payloadBytes.size > 300 * 1024)

        val parsed = KmlParser.parse(ByteArrayInputStream(payloadBytes), "layer_kml_test")
        assertEquals("Substation & Grid", parsed.documentName)
        assertTrue("Expected >= 2002 parsed pins, got ${parsed.pins.size}", parsed.pins.size >= 2002)
        assertEquals(1, parsed.tracks.size)
        assertEquals("Feeder 1 & Main / Zone A", parsed.pins.first().folderPath)
        assertEquals("Feeder 1 & Main / Zone A", parsed.tracks.first().folderPath)

        val exportedXml = KmlCsvExporter.buildKmlXml("Export", parsed.pins.take(10), parsed.tracks)
        val reparsed = KmlParser.parse(ByteArrayInputStream(exportedXml.toByteArray(Charsets.UTF_8)), "layer_kml_test")
        assertEquals(10, reparsed.pins.size)
        assertEquals(1, reparsed.tracks.size)
        assertEquals("Feeder 1 & Main / Zone A", reparsed.pins.first().folderPath)
        assertEquals("Feeder 1 & Main / Zone A", reparsed.tracks.first().folderPath)
    }

    @Test
    fun testDevicePrefsPruneOrphanedPinReferences() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        if (!DevicePrefs.isBookmarked(ctx, "p_keep_1")) DevicePrefs.toggleBookmark(ctx, "p_keep_1")
        if (!DevicePrefs.isBookmarked(ctx, "p_orphan_1")) DevicePrefs.toggleBookmark(ctx, "p_orphan_1")
        if (!DevicePrefs.isBookmarked(ctx, "p_orphan_2")) DevicePrefs.toggleBookmark(ctx, "p_orphan_2")

        if (!DevicePrefs.isPinLocalSent(ctx, "p_keep_1")) DevicePrefs.toggleLocalSentPin(ctx, "p_keep_1")
        if (!DevicePrefs.isPinLocalSent(ctx, "p_orphan_1")) DevicePrefs.toggleLocalSentPin(ctx, "p_orphan_1")
        if (!DevicePrefs.isPinLocalSent(ctx, "p_orphan_2")) DevicePrefs.toggleLocalSentPin(ctx, "p_orphan_2")

        DevicePrefs.prunePinReferencesKeeping(ctx, setOf("p_keep_1"))

        val bookmarks = DevicePrefs.getBookmarks(ctx)
        val localSent = DevicePrefs.getLocalSentPinIds(ctx)
        assertTrue(bookmarks.contains("p_keep_1"))
        assertFalse(bookmarks.contains("p_orphan_1"))
        assertFalse(bookmarks.contains("p_orphan_2"))
        assertTrue(localSent.contains("p_keep_1"))
        assertFalse(localSent.contains("p_orphan_1"))
        assertFalse(localSent.contains("p_orphan_2"))
    }

    @Test
    fun testManualPinsSurviveSqlRestartWithoutCascadeWipe() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        LocalFolderStore.resetForTest(ctx)
        try {
            val p1 = LocalFolderStore.addManualPin(ctx, "Pole A", "First pole", 36.851, 42.951, "Team 1 (#A1B2)", "A1B2")
            val p2 = LocalFolderStore.addManualPin(ctx, "Pole B", "Second pole", 36.852, 42.952, "Team 1 (#A1B2)", "A1B2")
            val p3 = LocalFolderStore.addManualPin(ctx, "Pole C", "Third pole", 36.853, 42.953, "Team 1 (#A1B2)", "A1B2")
            LocalFolderStore.flushPendingWritesForTest(ctx)
            LocalFolderStore.clearCacheForTest()

            val reloaded = LocalFolderStore.loadAll(ctx)
            assertEquals("Expected single My Field Pins parent layer", 1, reloaded.layers.size)
            assertEquals("Expected all 3 manual pins to survive without FK cascade wipe", 3, reloaded.pins.size)
            val ids = reloaded.pins.map { it.id }.toSet()
            assertTrue(ids.contains(p1.id))
            assertTrue(ids.contains(p2.id))
            assertTrue(ids.contains(p3.id))
            assertEquals("Pole A", reloaded.pins.first { it.id == p1.id }.name)
            assertEquals("Pole B", reloaded.pins.first { it.id == p2.id }.name)
            assertEquals("Pole C", reloaded.pins.first { it.id == p3.id }.name)
        } finally {
            LocalFolderStore.resetForTest(ctx)
        }
    }

    @Test
    fun testAsyncWriteOrderingAndRestartDurability() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        LocalFolderStore.resetForTest(ctx)
        try {
            val layerId = "local_async_order"
            val p1 = PinItem("async_1", layerId, "Pin 1", "Orig", 36.85, 42.95)
            val p2 = PinItem("async_2", layerId, "Pin 2", "Keep", 36.86, 42.96)
            val p3 = PinItem("async_3", layerId, "Pin 3", "Drop", 36.87, 42.97)
            LocalFolderStore.saveNewLayer(ctx, layerId, "Initial Layer", "QA", "Q1", ParsedKml(listOf(p1, p2, p3), emptyList(), "Initial Layer"))

            // Enqueue rapid back-to-back mutations without flushing in between
            val editedP1 = LocalFolderStore.updatePinStatus(ctx, p1, "issue", "QA (#Q1)", "Need Fuse")
            LocalFolderStore.updatePinText(ctx, editedP1, "Pin 1 Renamed", "Updated Desc", "QA (#Q1)")
            LocalFolderStore.renameLayer(ctx, layerId, "Renamed Layer")
            LocalFolderStore.deletePins(ctx, listOf(p3.id))

            // In-memory snapshot reflects every queued mutation immediately
            val inMem = LocalFolderStore.getCachedOrNull()!!
            assertEquals("Renamed Layer", inMem.layers.first().name)
            assertEquals(2, inMem.pins.size)

            LocalFolderStore.flushPendingWritesForTest(ctx)
            LocalFolderStore.clearCacheForTest()
            val disk = LocalFolderStore.loadAll(ctx)
            assertEquals(1, disk.layers.size)
            assertEquals("Renamed Layer", disk.layers.first().name)
            assertEquals(2, disk.pins.size)
            val rp1 = disk.pins.first { it.id == p1.id }
            assertEquals("Pin 1 Renamed", rp1.name)
            assertEquals("Updated Desc", rp1.description)
            assertEquals("issue", rp1.status)
            assertEquals("Need Fuse", rp1.issueReason)
            assertTrue(disk.pins.none { it.id == p3.id })
        } finally {
            LocalFolderStore.resetForTest(ctx)
        }
    }

    @Test
    fun testTerraSyncRepositoryMergeAndAsyncIndexCallbackOrdering() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        LocalFolderStore.resetForTest(ctx)
        try {
            val localLayerId = "local_repo_idx"
            val localPins = (1..150).map { i ->
                PinItem("lp_$i", localLayerId, "Local $i", "", 36.85 + i * 0.0001, 42.95)
            }
            LocalFolderStore.saveNewLayer(
                ctx,
                localLayerId,
                "Local 150",
                "QA",
                "Q1",
                ParsedKml(localPins, emptyList(), "Local 150")
            )
            LocalFolderStore.flushPendingWritesForTest(ctx)
            LocalFolderStore.clearCacheForTest()

            val repo = TerraSyncRepository(ctx)
            val cloudPins = (1..100).map { i ->
                PinItem("cp_$i", "cloud_repo_idx", "Cloud $i", "", 36.87 + i * 0.0001, 42.96)
            }
            repo.setCloudStateForTest(
                layers = listOf(LayerItem("cloud_repo_idx", "Cloud 100", updatedAt = 2000L)),
                pins = cloudPins,
                connected = true
            )

            val latch = java.util.concurrent.CountDownLatch(2)
            var cb1Snap: FolderIndexSnapshot? = null
            var cb2Snap: FolderIndexSnapshot? = null
            repo.applyMergedFolders(onIndexed = { s1 ->
                cb1Snap = s1
                latch.countDown()
            })
            repo.applyMergedFolders(onIndexed = { s2 ->
                cb2Snap = s2
                latch.countDown()
            })

            val deadline = System.currentTimeMillis() + 5_000L
            while (latch.count > 0 && System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                if (latch.count > 0) Thread.sleep(15)
            }
            assertTrue("Expected both coalesced async onIndexed callbacks to execute", latch.count == 0L)
            assertEquals(250, cb1Snap?.pins?.size)
            assertEquals(250, cb2Snap?.pins?.size)
            assertEquals(2, repo.snapshot.layers.size)
            assertNotNull(repo.snapshot.pinById["lp_150"])
            assertNotNull(repo.snapshot.pinById["cp_100"])
            assertTrue(repo.snapshot.stackedPinBuckets.isNotEmpty())
        } finally {
            LocalFolderStore.resetForTest(ctx)
        }
    }

    @Test
    fun testNightPaletteEmeraldCtaAndNeutralUndoBanner() {
        val night = DesignSystem.palette(true)
        assertEquals(android.graphics.Color.parseColor("#238E5E"), night.actionDark)
        assertEquals(android.graphics.Color.WHITE, night.onActionDark)
        assertEquals(android.graphics.Color.parseColor("#242724"), StatusUndoController.bannerFillColor(true))
        assertEquals(android.graphics.Color.parseColor("#2E292B"), StatusUndoController.bannerFillColor(false))
        assertTrue(StatusUndoController.bannerFillColor(true) != night.actionDark)
        assertTrue(night.surfaceFloating != night.surfaceTonal)
        assertTrue(night.surfaceTonal != night.surfaceCanvas)
        assertTrue(night.surfaceFloating != night.surfaceCanvas)
    }

}
