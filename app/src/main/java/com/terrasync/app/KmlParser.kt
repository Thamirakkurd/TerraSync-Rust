package com.terrasync.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

data class ParsedKml(
    val pins: List<PinItem>,
    val tracks: List<TrackItem>,
    val documentName: String = ""
)

object KmlParser {

    private const val MAX_KML_BYTES = 25 * 1024 * 1024
    private const val IN_MEMORY_THRESHOLD_BYTES = 256 * 1024
    private val HTML_BR_REGEX = Regex("(?i)<br\\s*/?>")
    private val HTML_BLOCK_END_REGEX = Regex("(?i)</(p|div|li|tr)>")
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val BARE_AMP_REGEX = Regex("&(?!amp;|lt;|gt;|quot;|apos;|#\\d+;|#x[0-9a-fA-F]+;)")
    private val CTRL_CHAR_REGEX = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")
    private val IMPLICIT_REASON_KEYS = setOf(
        "meter linked",
        "inaccessible",
        "no bill",
        "need electricity",
        "customer refused / no access",
        "wrong location / coordinates",
        "closed / nobody home",
        "safety hazard / network fault"
    )
    private val GENERIC_META_KEYS = setOf(
        "status",
        "pin_status",
        "issue",
        "issue_reason",
        "done_note",
        "status_note",
        "edited_by",
        "edited_at",
        "edit_history"
    )

    private class BoundedPayload(
        val memBytes: ByteArray? = null,
        val tempFile: File? = null
    ) : AutoCloseable {
        fun isZip(): Boolean {
            val b = memBytes
            if (b != null) return b.size >= 2 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()
            val f = tempFile ?: return false
            if (f.length() < 2L) return false
            return FileInputStream(f).use { fis ->
                val b0 = fis.read()
                val b1 = fis.read()
                b0 == 0x50 && b1 == 0x4B
            }
        }

        fun openStream(): InputStream {
            memBytes?.let { return ByteArrayInputStream(it) }
            val f = tempFile ?: return ByteArrayInputStream(ByteArray(0))
            return BufferedInputStream(FileInputStream(f), 32 * 1024)
        }

        override fun close() {
            tempFile?.let { runCatching { it.delete() } }
        }
    }

    /** Streaming character Reader that escapes bare '&' and removes invalid XML control characters without buffering the full file. */
    private class SanitizingKmlReader(private val src: Reader) : Reader() {
        private val rawBuf = CharArray(16 * 1024)
        private val carry = StringBuilder(32)
        private var outStr: String = ""
        private var outPos: Int = 0
        private var eofReached: Boolean = false

        private fun refill(): Boolean {
            if (outPos < outStr.length) return true
            if (eofReached) return false
            while (true) {
                val n = src.read(rawBuf)
                if (n < 0) {
                    eofReached = true
                    if (carry.isEmpty()) return false
                    outStr = sanitizeChunk(carry.toString())
                    carry.setLength(0)
                    outPos = 0
                    if (outStr.isEmpty()) return false
                    return true
                }
                if (n == 0) continue
                val combined = if (carry.isEmpty()) {
                    String(rawBuf, 0, n)
                } else {
                    carry.append(rawBuf, 0, n)
                    val s = carry.toString()
                    carry.setLength(0)
                    s
                }
                // Hold back trailing potential incomplete entity reference (e.g. "&amp" cut on buffer edge)
                val searchFrom = (combined.length - 12).coerceAtLeast(0)
                val lastAmp = combined.lastIndexOf('&')
                val cutIdx = if (lastAmp >= searchFrom && combined.indexOf(';', lastAmp) == -1) {
                    lastAmp
                } else {
                    combined.length
                }
                if (cutIdx < combined.length) {
                    carry.append(combined, cutIdx, combined.length)
                }
                val safeSlice = combined.substring(0, cutIdx)
                if (safeSlice.isNotEmpty()) {
                    outStr = sanitizeChunk(safeSlice)
                    outPos = 0
                    if (outStr.isNotEmpty()) return true
                }
            }
        }

        private fun sanitizeChunk(s: String): String {
            var res = s
            for (ch in s) {
                val c = ch.code
                if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D) {
                    res = res.replace(CTRL_CHAR_REGEX, "")
                    break
                }
            }
            if (res.indexOf('&') >= 0) {
                res = res.replace(BARE_AMP_REGEX, "&amp;")
            }
            return res
        }

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!refill()) return -1
            val avail = outStr.length - outPos
            val count = minOf(len, avail)
            outStr.toCharArray(cbuf, off, outPos, outPos + count)
            outPos += count
            return count
        }

        override fun close() {
            src.close()
        }
    }

    private fun readBoundedPayload(input: InputStream, maxBytes: Int = MAX_KML_BYTES): BoundedPayload? {
        val memOut = ByteArrayOutputStream(minOf(32 * 1024, maxBytes))
        var tmpFile: File? = null
        var fileOut: BufferedOutputStream? = null
        val buf = ByteArray(16 * 1024)
        var total = 0
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                total += n
                if (total > maxBytes) {
                    fileOut?.close()
                    tmpFile?.delete()
                    return null
                }
                if (fileOut == null) {
                    if (memOut.size() + n <= IN_MEMORY_THRESHOLD_BYTES) {
                        memOut.write(buf, 0, n)
                    } else {
                        tmpFile = File.createTempFile("ts_kml_", ".tmp")
                        fileOut = BufferedOutputStream(FileOutputStream(tmpFile), 32 * 1024)
                        val existing = memOut.toByteArray()
                        if (existing.isNotEmpty()) fileOut.write(existing)
                        fileOut.write(buf, 0, n)
                    }
                } else {
                    fileOut.write(buf, 0, n)
                }
            }
            fileOut?.flush()
            fileOut?.close()
            fileOut = null
            return if (tmpFile != null) {
                BoundedPayload(tempFile = tmpFile)
            } else {
                BoundedPayload(memBytes = memOut.toByteArray())
            }
        } catch (t: Throwable) {
            runCatching { fileOut?.close() }
            tmpFile?.delete()
            throw t
        }
    }

    fun parse(inputStream: InputStream, layerId: String): ParsedKml {
        val initialPayload = readBoundedPayload(inputStream) ?: return ParsedKml(emptyList(), emptyList(), "")
        return initialPayload.use { src ->
            val xmlPayload: BoundedPayload = if (src.isZip()) {
                val extracted = extractKmlFromKmz(src) ?: return@use ParsedKml(emptyList(), emptyList(), "")
                src.close() // release zip container before parsing XML
                extracted
            } else {
                src
            }
            xmlPayload.use { kml ->
                RustCoreBridge.parseKml(kml.memBytes, kml.tempFile, layerId)?.let { return@use it }
                runCatching {
                    kml.openStream().use { s -> parseKmlXmlStream(skipUtf8Bom(s), layerId, sanitizeStream = false) }
                }.getOrElse {
                    // Resilient streaming recovery for real-world KMLs containing unescaped '&' or stray control chars
                    runCatching {
                        kml.openStream().use { s -> parseKmlXmlStream(skipUtf8Bom(s), layerId, sanitizeStream = true) }
                    }.getOrElse { ParsedKml(emptyList(), emptyList(), "") }
                }
            }
        }
    }

    private fun skipUtf8Bom(input: InputStream): InputStream {
        val bis = if (input is BufferedInputStream) input else BufferedInputStream(input, 32 * 1024)
        bis.mark(4)
        val b0 = bis.read()
        val b1 = bis.read()
        val b2 = bis.read()
        if (b0 != 0xEF || b1 != 0xBB || b2 != 0xBF) {
            bis.reset()
        }
        return bis
    }

    private fun extractKmlFromKmz(zipPayload: BoundedPayload): BoundedPayload? {
        var fallbackKml: BoundedPayload? = null
        zipPayload.openStream().use { rawStream ->
            ZipInputStream(rawStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".kml")) {
                        val base = entry.name.substringAfterLast('/').lowercase(Locale.ROOT)
                        val data = readBoundedPayload(zis)
                        if (data != null) {
                            if (base == "doc.kml") {
                                fallbackKml?.close()
                                return data
                            }
                            if (fallbackKml == null) {
                                fallbackKml = data
                            } else {
                                data.close()
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return fallbackKml
    }

    private fun normalizeStatus(raw: String): String? {
        val clean = raw.trim().lowercase()
        if (clean.isBlank()) return null
        return when {
            clean == "done" || clean.startsWith("done:") || clean.startsWith("done ") ||
                clean == "completed" || clean == "complete" || clean == "finished" ||
                clean == "meter linked" || clean == "inaccessible" || clean == "no bill" -> "done"
            clean == "issue" || clean.startsWith("issue:") || clean.startsWith("issue ") ||
                clean == "problem" || clean == "error" ||
                clean == "need electricity" ||
                clean == "customer refused / no access" ||
                clean == "wrong location / coordinates" ||
                clean == "closed / nobody home" ||
                clean == "safety hazard / network fault" -> "issue"
            clean == "pending" || clean.startsWith("pending:") || clean.startsWith("pending ") ||
                clean == "todo" || clean == "open" -> "pending"
            else -> null
        }
    }

    private fun extractImplicitReasonFromStatus(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        val low = trimmed.lowercase(Locale.ROOT)
        return when {
            low.startsWith("done:") || low.startsWith("issue:") -> trimmed.substringAfter(":").trim()
            low in IMPLICIT_REASON_KEYS -> trimmed
            else -> ""
        }
    }

    private fun statusFromStyleUrl(styleUrl: String): String? {
        val low = styleUrl.trim().lowercase()
        if (low.isBlank()) return null
        return when {
            low.contains("green") || low.contains("grn") || low.contains("done") ||
                low.contains("0f9d58") || low.contains("22c55e") -> "done"
            low.contains("red") || low.contains("issue") ||
                low.contains("db4436") || low.contains("ef4444") -> "issue"
            low.contains("orange") || low.contains("yellow") || low.contains("ylw") ||
                low.contains("amber") || low.contains("pending") -> "pending"
            else -> null
        }
    }

    private fun stripHtml(raw: String): String {
        if (!raw.contains('<') && !raw.contains('&')) return raw
        return raw
            .replace(HTML_BR_REGEX, "\n")
            .replace(HTML_BLOCK_END_REGEX, "\n")
            .replace(HTML_TAG_REGEX, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
    }

    private fun getAttrIgnoreCase(parser: XmlPullParser, name: String): String {
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i)?.substringAfterLast(':') ?: continue
            if (attrName.equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(i)?.trim().orEmpty()
            }
        }
        return ""
    }

    private fun parseKmlXmlStream(input: InputStream, layerId: String, sanitizeStream: Boolean): ParsedKml {
        val pins = mutableListOf<PinItem>()
        val tracks = mutableListOf<TrackItem>()
        var docName = ""
        val now = System.currentTimeMillis()

        val parser = runCatching { Xml.newPullParser() }.getOrNull()
            ?: XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        var closeReader: SanitizingKmlReader? = null
        if (sanitizeStream) {
            val sr = SanitizingKmlReader(InputStreamReader(input, Charsets.UTF_8).buffered(32 * 1024))
            closeReader = sr
            parser.setInput(sr)
        } else {
            parser.setInput(input, null)
        }

        try {
            var inPlacemark = false
            var inStyle = false
            var inPoint = false
            var lineDepth = 0
            var inDescription = false
            var inExtendedData = false
            var currentDataKey = ""
            var activeTag = ""
            val textBuf = StringBuilder()
            val descBuf = StringBuilder()
            val folderStack = ArrayList<String>()
            val folderNamed = ArrayList<Boolean>()

            var pmName = ""
            var pmDesc = ""
            var pmStyleUrl = ""
            val pmExtra = mutableListOf<String>()
            val pmMeta = mutableMapOf<String, String>()
            val pmGeneric = mutableMapOf<String, String>()
            val pmPoints = mutableListOf<Pair<Double, Double>>()
            val pmLines = mutableListOf<List<Pair<Double, Double>>>()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.substringAfterLast(':').lowercase()
                        if (inDescription) {
                            if (tag == "br" || tag == "p" || tag == "div" || tag == "li" || tag == "tr") {
                                descBuf.append("\n")
                            }
                        } else {
                            activeTag = tag
                            textBuf.setLength(0)
                            when (tag) {
                                "style", "stylemap" -> inStyle = true
                                "folder" -> {
                                    folderStack.add("")
                                    folderNamed.add(false)
                                }
                                "placemark" -> {
                                    inPlacemark = true
                                    inPoint = false
                                    lineDepth = 0
                                    inDescription = false
                                    inExtendedData = false
                                    currentDataKey = ""
                                    pmName = ""
                                    pmDesc = ""
                                    pmStyleUrl = ""
                                    descBuf.setLength(0)
                                    pmExtra.clear()
                                    pmMeta.clear()
                                    pmGeneric.clear()
                                    pmPoints.clear()
                                    pmLines.clear()
                                }
                                "description" -> if (inPlacemark && !inExtendedData) {
                                    inDescription = true
                                    descBuf.setLength(0)
                                }
                                "point" -> inPoint = true
                                "linestring", "polygon", "linearring" -> lineDepth++
                                "extendeddata" -> inExtendedData = true
                                "data", "simpledata" -> if (inPlacemark) {
                                    currentDataKey = getAttrIgnoreCase(parser, "name")
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (inDescription) {
                            descBuf.append(parser.text ?: "")
                        } else if ((!inPlacemark && !inStyle && activeTag == "name") ||
                            (inPlacemark && (activeTag == "name" || activeTag == "styleurl" ||
                                activeTag == "coordinates" || activeTag == "value" || activeTag == "simpledata"))
                        ) {
                            textBuf.append(parser.text ?: "")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.substringAfterLast(':').lowercase()
                        if (inDescription) {
                            if (tag == "description") {
                                inDescription = false
                                if (pmDesc.isBlank()) pmDesc = descBuf.toString().trim()
                                activeTag = ""
                            } else if (tag == "p" || tag == "div" || tag == "li" || tag == "tr") {
                                descBuf.append("\n")
                            }
                        } else if (tag == "style" || tag == "stylemap") {
                            inStyle = false
                        } else if (tag == "folder" && !inPlacemark) {
                            if (folderStack.isNotEmpty()) folderStack.removeAt(folderStack.lastIndex)
                            if (folderNamed.isNotEmpty()) folderNamed.removeAt(folderNamed.lastIndex)
                        } else if (!inPlacemark && !inStyle && tag == "name") {
                            val nm = textBuf.toString().trim()
                            if (folderStack.isNotEmpty() && folderNamed.lastOrNull() == false) {
                                folderStack[folderStack.lastIndex] = nm
                                folderNamed[folderNamed.lastIndex] = true
                            } else if (docName.isBlank()) {
                                docName = nm
                            }
                        } else if (inPlacemark) {
                            when (tag) {
                                "name" -> if (!inExtendedData && pmName.isBlank()) pmName = textBuf.toString().trim()
                                "styleurl" -> if (!inExtendedData && pmStyleUrl.isBlank()) pmStyleUrl = textBuf.toString().trim()
                                "value", "simpledata" -> {
                                    val v = textBuf.toString().trim()
                                    if (currentDataKey.isNotBlank()) {
                                        val keyLow = currentDataKey.lowercase().replace(" ", "_")
                                        if (keyLow == "ts_raw_description") {
                                            pmMeta[keyLow] = v
                                        } else if (v.isNotBlank()) {
                                            when {
                                                keyLow.startsWith("ts_") -> pmMeta[keyLow] = v
                                                keyLow in GENERIC_META_KEYS -> pmGeneric[keyLow] = v
                                                else -> pmExtra.add("$currentDataKey: $v")
                                            }
                                        }
                                    }
                                    if (tag == "simpledata") currentDataKey = ""
                                }
                                "data" -> currentDataKey = ""
                                "extendeddata" -> inExtendedData = false
                                "coordinates" -> {
                                    val pts = parseCoordinates(textBuf.toString())
                                    if (inPoint && pts.isNotEmpty()) {
                                        pmPoints.add(pts.first())
                                    } else if (lineDepth > 0 && pts.size >= 2) {
                                        pmLines.add(pts)
                                    }
                                }
                                "point" -> inPoint = false
                                "linestring", "polygon", "linearring" -> lineDepth = (lineDepth - 1).coerceAtLeast(0)
                                "placemark" -> {
                                    val rawStatusField = pmMeta["ts_status"]
                                        ?: pmGeneric["status"]
                                        ?: pmGeneric["pin_status"]
                                    var restoredStatus = rawStatusField?.let { normalizeStatus(it) }

                                    var restoredReason = pmMeta["ts_issue_reason"]
                                        ?: pmGeneric["done_note"]
                                        ?: pmGeneric["issue_reason"]
                                        ?: pmGeneric["status_note"]
                                        ?: pmGeneric["issue"]
                                        ?: extractImplicitReasonFromStatus(rawStatusField)

                                    if (restoredStatus == null) {
                                        restoredStatus = when {
                                            !pmGeneric["done_note"].isNullOrBlank() -> "done"
                                            !pmGeneric["issue_reason"].isNullOrBlank() || !pmGeneric["issue"].isNullOrBlank() -> "issue"
                                            else -> null
                                        }
                                    }

                                    var restoredEditedBy = pmMeta["ts_edited_by"] ?: pmGeneric["edited_by"] ?: ""
                                    val restoredEditedAt = pmMeta["ts_edited_at"]?.toLongOrNull()
                                        ?: pmGeneric["edited_at"]?.toLongOrNull()
                                        ?: 0L
                                    val restoredHistory = pmMeta["ts_edit_history"] ?: pmGeneric["edit_history"] ?: ""
                                    var restoredIsEdited = pmMeta["ts_is_edited"]?.equals("true", true) == true
                                    val rawDescFromMeta = pmMeta["ts_raw_description"]
                                    val resolvedFolderPath = pmMeta["ts_folder_path"]?.takeIf { it.isNotBlank() }
                                        ?: folderStack.filter { it.isNotBlank() }.joinToString(" / ")

                                    val cleanedDescLines = mutableListOf<String>()
                                    if (rawDescFromMeta != null) {
                                        if (rawDescFromMeta.isNotBlank()) cleanedDescLines.add(rawDescFromMeta)
                                    } else if (pmDesc.isNotBlank()) {
                                        stripHtml(pmDesc).lines().forEach { line ->
                                            val trimmed = line.trim()
                                            val low = trimmed.lowercase()
                                            when {
                                                low.startsWith("status:") -> {
                                                    if (restoredStatus == null) {
                                                        restoredStatus = normalizeStatus(trimmed.substringAfter(":"))
                                                    }
                                                }
                                                low.startsWith("done note:") -> {
                                                    val noteVal = trimmed.substringAfter(":").trim()
                                                    if (restoredReason.isBlank() && noteVal.isNotBlank()) {
                                                        restoredReason = noteVal
                                                    }
                                                    if (restoredStatus == null) {
                                                        restoredStatus = "done"
                                                    }
                                                }
                                                low.startsWith("issue reason:") || low.startsWith("issue:") -> {
                                                    val issueVal = trimmed.substringAfter(":").trim()
                                                    if (restoredReason.isBlank() && issueVal.isNotBlank()) {
                                                        restoredReason = issueVal
                                                    }
                                                    if (restoredStatus == null) {
                                                        restoredStatus = "issue"
                                                    }
                                                }
                                                low.startsWith("edited by:") -> {
                                                    if (restoredEditedBy.isBlank()) {
                                                        restoredEditedBy = trimmed.substringAfter(":").trim()
                                                        restoredIsEdited = true
                                                    }
                                                }
                                                else -> {
                                                    if (trimmed.isNotBlank()) cleanedDescLines.add(line)
                                                }
                                            }
                                        }
                                    }

                                    if (restoredStatus == null) {
                                        restoredStatus = statusFromStyleUrl(pmStyleUrl)
                                    }

                                    val finalStatus = restoredStatus ?: "pending"
                                    if (finalStatus != "pending" || restoredReason.isNotBlank() ||
                                        restoredEditedBy.isNotBlank() || restoredHistory.isNotBlank()
                                    ) {
                                        restoredIsEdited = true
                                    }

                                    val fullDesc = buildString {
                                        val baseDesc = cleanedDescLines.joinToString("\n").trim()
                                        if (baseDesc.isNotBlank()) append(baseDesc)
                                        if (pmExtra.isNotEmpty()) {
                                            if (isNotEmpty()) append("\n")
                                            append(pmExtra.joinToString("\n"))
                                        }
                                    }

                                    val basePinName = pmName.ifBlank { "Pin ${pins.size + 1}" }
                                    pmPoints.forEachIndexed { ptIdx, (lng, lat) ->
                                        val ptName = if (pmPoints.size > 1) "$basePinName (${ptIdx + 1}/${pmPoints.size})" else basePinName
                                        pins.add(
                                            PinItem(
                                                id = UUID.randomUUID().toString(),
                                                layerId = layerId,
                                                name = ptName,
                                                description = fullDesc,
                                                lat = lat,
                                                lng = lng,
                                                status = finalStatus,
                                                issueReason = restoredReason,
                                                updatedAt = now,
                                                isEdited = restoredIsEdited,
                                                editedBy = restoredEditedBy,
                                                editedAt = restoredEditedAt,
                                                editHistory = restoredHistory,
                                                folderPath = resolvedFolderPath
                                            )
                                        )
                                    }
                                    val baseTrackName = pmName.ifBlank { "Track ${tracks.size + 1}" }
                                    pmLines.forEachIndexed { lineIdx, pts ->
                                        val trName = if (pmLines.size > 1) "$baseTrackName (${lineIdx + 1}/${pmLines.size})" else baseTrackName
                                        val encoded = pts.joinToString(";") { "${it.first},${it.second}" }
                                        tracks.add(
                                            TrackItem(
                                                id = UUID.randomUUID().toString(),
                                                layerId = layerId,
                                                name = trName,
                                                coords = encoded,
                                                updatedAt = now,
                                                folderPath = resolvedFolderPath
                                            )
                                        )
                                    }
                                    inPlacemark = false
                                    inPoint = false
                                    lineDepth = 0
                                    inDescription = false
                                    inExtendedData = false
                                    currentDataKey = ""
                                }
                            }
                        }
                        if (!inDescription) activeTag = ""
                    }
                }
                event = parser.next()
            }
            return ParsedKml(pins, tracks, docName)
        } finally {
            runCatching { closeReader?.close() }
        }
    }

    private fun parseCoordinates(raw: String): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        val len = raw.length
        var i = 0
        while (i < len) {
            while (i < len && raw[i].isWhitespace()) i++
            if (i >= len) break
            var end = i
            while (end < len && !raw[end].isWhitespace()) end++
            val c1 = raw.indexOf(',', i)
            if (c1 in (i + 1) until end) {
                val c2 = raw.indexOf(',', c1 + 1)
                val latEnd = if (c2 in (c1 + 1) until end) c2 else end
                val lng = raw.substring(i, c1).toDoubleOrNull()
                val lat = raw.substring(c1 + 1, latEnd).toDoubleOrNull()
                if (lng != null && lat != null && lat.isFinite() && lng.isFinite() &&
                    lat in -90.0..90.0 && lng in -180.0..180.0
                ) {
                    out.add(lng to lat)
                }
            }
            i = end + 1
        }
        return out
    }
}
