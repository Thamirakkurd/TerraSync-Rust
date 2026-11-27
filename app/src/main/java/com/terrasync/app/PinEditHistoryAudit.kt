package com.terrasync.app

import java.util.Calendar
import java.util.Locale

data class PinEditEntry(
    val pin: PinItem,
    val historyLineIndex: Int, // -1 if synthesized from editedAt/editedBy
    val dateGroup: String,     // e.g. "24 Nov"
    val clockTime: String,     // e.g. "18:42"
    val fullTimeLabel: String, // e.g. "24 Nov 18:42"
    val minuteOfDay: Int?,     // 0..1439 or null if unparseable
    val sortEpochMs: Long,
    val operatorRaw: String,
    val operatorShort: String,
    val actionSummary: String,
    val actionStatus: String,  // "done", "issue", "pending", "text"
    val isOutsideWorkHours: Boolean
)

data class RecomputedPinHistory(
    val status: String,
    val issueReason: String,
    val isEdited: Boolean,
    val editedBy: String,
    val editedAt: Long,
    val editHistory: String
)

/**
 * Parses per-pin edit history logs across a KML/cloud folder into a unified
 * chronological submission timeline with working-hours compliance checks.
 */
object PinEditHistoryAudit {

    private val TIME_PREFIX_REGEX = Regex("""^(\d{1,2}\s+[A-Za-z]{3})\s+(\d{2}):(\d{2})$""")
    private val WS_REGEX = Regex("\\s+")
    private val MONTH_MAP = mapOf(
        "jan" to Calendar.JANUARY,
        "feb" to Calendar.FEBRUARY,
        "mar" to Calendar.MARCH,
        "apr" to Calendar.APRIL,
        "may" to Calendar.MAY,
        "jun" to Calendar.JUNE,
        "jul" to Calendar.JULY,
        "aug" to Calendar.AUGUST,
        "sep" to Calendar.SEPTEMBER,
        "oct" to Calendar.OCTOBER,
        "nov" to Calendar.NOVEMBER,
        "dec" to Calendar.DECEMBER
    )

    fun formatMinutes(totalMin: Int): String {
        val clamped = totalMin.coerceIn(0, 23 * 60 + 59)
        return "%02d:%02d".format(Locale.US, clamped / 60, clamped % 60)
    }

    /**
     * Returns true if [minuteOfDay] is outside the configured [startMin]..[endMin] window.
     * Supports both standard daytime shifts (08:00–17:00) and overnight shifts (22:00–06:00).
     */
    fun isOutsideWorkingHours(minuteOfDay: Int?, startMin: Int, endMin: Int): Boolean {
        if (minuteOfDay == null) return false
        if (startMin == endMin) return false
        val inside = if (startMin < endMin) {
            minuteOfDay in startMin..endMin
        } else {
            minuteOfDay >= startMin || minuteOfDay <= endMin
        }
        return !inside
    }

    /** Checks a single raw history line for after-hours status (used on Pin Card badges). */
    fun isHistoryLineOutsideWorkHours(line: String, startMin: Int, endMin: Int): Boolean {
        val timePart = line.substringBefore("•").trim()
        val m = TIME_PREFIX_REGEX.matchEntire(timePart) ?: return false
        val hh = m.groupValues[2].toIntOrNull() ?: return false
        val mm = m.groupValues[3].toIntOrNull() ?: return false
        if (hh !in 0..23 || mm !in 0..59) return false
        return isOutsideWorkingHours(hh * 60 + mm, startMin, endMin)
    }

    /** Reconstructs an approximate epoch timestamp (ms) from a raw history line, or 0L if unparseable. */
    fun parseLineTimestampMs(rawLine: String): Long {
        val timePart = rawLine.substringBefore("•").trim()
        val m = TIME_PREFIX_REGEX.matchEntire(timePart) ?: return 0L
        val hh = m.groupValues[2].toIntOrNull() ?: return 0L
        val mm = m.groupValues[3].toIntOrNull() ?: return 0L
        if (hh !in 0..23 || mm !in 0..59) return 0L
        val nowCal = Calendar.getInstance()
        return reconstructEpoch(
            dateGroup = m.groupValues[1].trim(),
            hh = hh,
            mm = mm,
            currentYear = nowCal.get(Calendar.YEAR),
            nowMs = nowCal.timeInMillis,
            scratchCal = nowCal
        )
    }

    /** Prepends a new history line and trims whole lines from the tail so total length never exceeds [maxChars]. */
    fun prependHistoryLine(newLine: String, existingHistory: String, maxChars: Int = 1500): String {
        val cleanNew = newLine.trim()
        val lines = ArrayList<String>()
        if (cleanNew.isNotBlank()) lines.add(cleanNew)
        existingHistory.lines().forEach { raw ->
            val t = raw.trim()
            if (t.isNotBlank()) lines.add(t)
        }
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        for (line in lines) {
            val needed = if (sb.isEmpty()) line.length else 1 + line.length
            if (sb.length + needed > maxChars) {
                if (sb.isEmpty()) {
                    sb.append(line.take(maxChars))
                }
                break
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return sb.toString()
    }

    fun countOutsideWorkingHours(
        layerPins: List<PinItem>,
        workStartMin: Int,
        workEndMin: Int
    ): Int {
        if (workStartMin == workEndMin) return 0
        RustCoreBridge.countOutsideWorkingHours(layerPins, workStartMin, workEndMin)?.let { return it }
        var count = 0
        val scratchCal = Calendar.getInstance()
        for (pin in layerPins) {
            val hist = pin.editHistory
            if (hist.isNotBlank()) {
                var idx = 0
                var start = 0
                val len = hist.length
                while (start <= len) {
                    val nl = hist.indexOf('\n', start).let { if (it < 0) len else it }
                    val rawLine = hist.substring(start, nl).trim()
                    start = nl + 1
                    if (rawLine.isEmpty()) {
                        if (nl == len) break
                        continue
                    }
                    val bulletIdx = rawLine.indexOf('•')
                    val timePart = if (bulletIdx > 0) rawLine.substring(0, bulletIdx).trim() else ""
                    val match = TIME_PREFIX_REGEX.matchEntire(timePart)
                    var minOfDay: Int? = null
                    if (match != null) {
                        val hh = match.groupValues[2].toIntOrNull()
                        val mm = match.groupValues[3].toIntOrNull()
                        if (hh != null && mm != null && hh in 0..23 && mm in 0..59) {
                            minOfDay = hh * 60 + mm
                        }
                    } else if (idx == 0 && pin.editedAt > 0L) {
                        scratchCal.timeInMillis = pin.editedAt
                        minOfDay = scratchCal.get(Calendar.HOUR_OF_DAY) * 60 + scratchCal.get(Calendar.MINUTE)
                    }
                    if (isOutsideWorkingHours(minOfDay, workStartMin, workEndMin)) {
                        count++
                    }
                    idx++
                    if (nl == len) break
                }
            } else if (pin.isEdited || PinStatus.fromRaw(pin.status) != PinStatus.PENDING || pin.issueReason.isNotBlank()) {
                val ts = when {
                    pin.editedAt > 0L -> pin.editedAt
                    pin.updatedAt > 0L -> pin.updatedAt
                    else -> 0L
                }
                if (ts > 0L) {
                    scratchCal.timeInMillis = ts
                    val minOfDay = scratchCal.get(Calendar.HOUR_OF_DAY) * 60 + scratchCal.get(Calendar.MINUTE)
                    if (isOutsideWorkingHours(minOfDay, workStartMin, workEndMin)) {
                        count++
                    }
                }
            }
        }
        return count
    }

    fun collectFolderHistory(
        layerPins: List<PinItem>,
        workStartMin: Int,
        workEndMin: Int,
        deviceToTeam: Map<String, String> = DailyReportGenerator.buildDeviceToTeamMap(layerPins)
    ): List<PinEditEntry> {
        val entries = ArrayList<PinEditEntry>(layerPins.size)
        val scratchCal = Calendar.getInstance()
        val currentYear = scratchCal.get(Calendar.YEAR)
        val nowMs = scratchCal.timeInMillis

        for (pin in layerPins) {
            val lines = pin.editHistory.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                lines.forEachIndexed { idx, rawLine ->
                    entries.add(
                        parseHistoryLine(
                            pin = pin,
                            lineIdx = idx,
                            rawLine = rawLine,
                            workStartMin = workStartMin,
                            workEndMin = workEndMin,
                            currentYear = currentYear,
                            nowMs = nowMs,
                            scratchCal = scratchCal,
                            deviceToTeam = deviceToTeam
                        )
                    )
                }
            } else if (pin.isEdited || PinStatus.fromRaw(pin.status) != PinStatus.PENDING || pin.issueReason.isNotBlank()) {
                val ts = when {
                    pin.editedAt > 0L -> pin.editedAt
                    pin.updatedAt > 0L -> pin.updatedAt
                    else -> 0L
                }
                val dateGroup: String
                val clockTime: String
                val fullTime: String
                val minOfDay: Int?
                if (ts > 0L) {
                    scratchCal.timeInMillis = ts
                    dateGroup = TimeFormatters.dayMonth(ts)
                    clockTime = TimeFormatters.hourMinute(ts)
                    fullTime = "$dateGroup $clockTime"
                    minOfDay = scratchCal.get(Calendar.HOUR_OF_DAY) * 60 + scratchCal.get(Calendar.MINUTE)
                } else {
                    dateGroup = "Unknown Date"
                    clockTime = "--:--"
                    fullTime = "Unknown time"
                    minOfDay = null
                }
                val opRaw = pin.editedBy.ifBlank { "Unknown operator" }
                val cleanReason = DailyReportGenerator.flattenSingleLine(pin.issueReason)
                val normStatus = PinStatus.fromRaw(pin.status)
                val action = buildString {
                    append(normStatus.label)
                    if (cleanReason.isNotBlank()) append(": $cleanReason")
                }
                entries.add(
                    PinEditEntry(
                        pin = pin,
                        historyLineIndex = -1,
                        dateGroup = dateGroup,
                        clockTime = clockTime,
                        fullTimeLabel = fullTime,
                        minuteOfDay = minOfDay,
                        sortEpochMs = ts,
                        operatorRaw = opRaw,
                        operatorShort = extractShortOperator(opRaw, deviceToTeam),
                        actionSummary = action,
                        actionStatus = normStatus.key,
                        isOutsideWorkHours = isOutsideWorkingHours(minOfDay, workStartMin, workEndMin)
                    )
                )
            }
        }

        return entries.sortedWith(
            compareByDescending<PinEditEntry> { it.sortEpochMs }
                .thenBy { it.historyLineIndex }
        )
    }

    private fun parseHistoryLine(
        pin: PinItem,
        lineIdx: Int,
        rawLine: String,
        workStartMin: Int,
        workEndMin: Int,
        currentYear: Int,
        nowMs: Long,
        scratchCal: Calendar,
        deviceToTeam: Map<String, String>
    ): PinEditEntry {
        val bulletIdx = rawLine.indexOf('•')
        val timePart = if (bulletIdx > 0) rawLine.substring(0, bulletIdx).trim() else ""
        val rest = if (bulletIdx > 0) rawLine.substring(bulletIdx + 1).trim() else rawLine

        val arrowIdx = rest.indexOf('→')
        val operatorRaw: String
        val actionSummary: String
        if (arrowIdx >= 0) {
            operatorRaw = rest.substring(0, arrowIdx).trim().ifBlank { pin.editedBy.ifBlank { "Unknown" } }
            actionSummary = rest.substring(arrowIdx + 1).trim().ifBlank { "Updated" }
        } else if (rest.endsWith("edited text", ignoreCase = true)) {
            operatorRaw = rest.dropLast("edited text".length).trim().ifBlank { pin.editedBy.ifBlank { "Unknown" } }
            actionSummary = "Edited text"
        } else {
            operatorRaw = pin.editedBy.ifBlank { "Unknown" }
            actionSummary = rest.ifBlank { "Updated" }
        }

        val actionStatus = when {
            actionSummary.startsWith("Done", ignoreCase = true) -> "done"
            actionSummary.startsWith("Issue", ignoreCase = true) -> "issue"
            actionSummary.startsWith("Pending", ignoreCase = true) -> "pending"
            actionSummary.contains("edited text", ignoreCase = true) -> "text"
            else -> PinStatus.fromRaw(pin.status).key
        }

        val match = TIME_PREFIX_REGEX.matchEntire(timePart)
        var dateGroup = "Unknown Date"
        var clockTime = "--:--"
        var minOfDay: Int? = null
        var sortMs = if (lineIdx == 0 && pin.editedAt > 0L) pin.editedAt else 0L

        if (match != null) {
            val hh = match.groupValues[2].toIntOrNull()
            val mm = match.groupValues[3].toIntOrNull()
            if (hh != null && mm != null && hh in 0..23 && mm in 0..59) {
                dateGroup = match.groupValues[1].trim()
                clockTime = "%02d:%02d".format(Locale.US, hh, mm)
                minOfDay = hh * 60 + mm

                val reconstructed = reconstructEpoch(dateGroup, hh, mm, currentYear, nowMs, scratchCal)
                // Keep exact editedAt for the newest history line if it matches the same minute
                if (lineIdx == 0 && pin.editedAt > 0L) {
                    scratchCal.timeInMillis = pin.editedAt
                    val sameClock = scratchCal.get(Calendar.HOUR_OF_DAY) == hh && scratchCal.get(Calendar.MINUTE) == mm
                    sortMs = if (sameClock) pin.editedAt else reconstructed
                } else {
                    // Subtract lineIdx ms so multiple edits within the same minute preserve exact order
                    sortMs = reconstructed - lineIdx
                }
            }
        } else if (lineIdx == 0 && pin.editedAt > 0L) {
            scratchCal.timeInMillis = pin.editedAt
            dateGroup = TimeFormatters.dayMonth(pin.editedAt)
            clockTime = TimeFormatters.hourMinute(pin.editedAt)
            minOfDay = scratchCal.get(Calendar.HOUR_OF_DAY) * 60 + scratchCal.get(Calendar.MINUTE)
            sortMs = pin.editedAt
        }

        val fullLabel = if (clockTime != "--:--") "$dateGroup $clockTime" else timePart.ifBlank { "Unknown time" }

        return PinEditEntry(
            pin = pin,
            historyLineIndex = lineIdx,
            dateGroup = dateGroup,
            clockTime = clockTime,
            fullTimeLabel = fullLabel,
            minuteOfDay = minOfDay,
            sortEpochMs = sortMs,
            operatorRaw = operatorRaw,
            operatorShort = extractShortOperator(operatorRaw, deviceToTeam),
            actionSummary = actionSummary,
            actionStatus = actionStatus,
            isOutsideWorkHours = isOutsideWorkingHours(minOfDay, workStartMin, workEndMin)
        )
    }

    private fun reconstructEpoch(
        dateGroup: String,
        hh: Int,
        mm: Int,
        currentYear: Int,
        nowMs: Long,
        scratchCal: Calendar
    ): Long {
        val parts = dateGroup.split(WS_REGEX)
        val day = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 31) ?: 1
        val mon = MONTH_MAP[parts.getOrNull(1)?.lowercase(Locale.US)] ?: Calendar.JANUARY
        scratchCal.clear()
        scratchCal.set(Calendar.YEAR, currentYear)
        scratchCal.set(Calendar.MONTH, mon)
        val maxDay = scratchCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        scratchCal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(maxDay))
        scratchCal.set(Calendar.HOUR_OF_DAY, hh)
        scratchCal.set(Calendar.MINUTE, mm)
        scratchCal.set(Calendar.SECOND, 0)
        scratchCal.set(Calendar.MILLISECOND, 0)
        // Handle year boundary (e.g., Dec logs viewed in Jan)
        if (scratchCal.timeInMillis > nowMs + 7L * 24L * 3600L * 1000L) {
            scratchCal.add(Calendar.YEAR, -1)
        }
        return scratchCal.timeInMillis
    }

    private fun extractShortOperator(raw: String, deviceToTeam: Map<String, String> = emptyMap()): String {
        val resolved = DailyReportGenerator.resolveOperator(raw, deviceToTeam)
        return resolved?.second ?: "Unknown"
    }

    fun buildAuditShareText(
        folderName: String,
        entries: List<PinEditEntry>,
        workStartMin: Int,
        workEndMin: Int
    ): String {
        val window = "${formatMinutes(workStartMin)}–${formatMinutes(workEndMin)}"
        val afterHours = entries.filter { it.isOutsideWorkHours }
        val inHours = entries.size - afterHours.size
        return buildString {
            appendLine("*$folderName — Edit History & Hours Audit*")
            appendLine("Working Hours: $window")
            appendLine("Total Submissions: ${entries.size} (In Hours: $inHours • After Hours: ${afterHours.size})")
            if (afterHours.isNotEmpty()) {
                appendLine()
                appendLine("*⏰ Outside Working Hours (${afterHours.size})*")
                afterHours.take(40).forEach { e ->
                    val shortPin = DailyReportGenerator.formatCompactPinLabel(e.pin.name)
                    val cleanAction = DailyReportGenerator.flattenSingleLine(e.actionSummary)
                    appendLine("• [${e.fullTimeLabel}] ${e.operatorShort} — $shortPin → $cleanAction")
                }
                if (afterHours.size > 40) {
                    val more = afterHours.size - 40
                    appendLine("• _+${DailyReportGenerator.plural(more, "more after-hours edit")}_")
                }
            }
            val byOperator = entries.groupBy { DailyReportGenerator.teamGroupKey(it.operatorShort) }
                .values
                .sortedByDescending { it.size }
            if (byOperator.isNotEmpty()) {
                appendLine()
                appendLine("*Per Operator*")
                byOperator.forEach { list ->
                    val op = list.first().operatorShort
                    val late = list.count { it.isOutsideWorkHours }
                    val lateNote = if (late > 0) " ($late after hours)" else ""
                    appendLine("• $op: ${DailyReportGenerator.plural(list.size, "edit")}$lateNote")
                }
            }
        }.trim()
    }

    /**
     * Recomputes authoritative pin status/note/metadata fields from remaining edit-history lines
     * after removing an audit entry (shared by LocalFolderStore SQLite WAL and SyncRepo Firestore).
     */
    fun recomputeFromHistoryLines(lines: List<String>, nowMs: Long): RecomputedPinHistory {
        val cleaned = lines.map { it.trim() }.filter { it.isNotBlank() }
        val newHistory = cleaned.joinToString("\n")
        val latestStatusLine = cleaned.firstOrNull { it.contains("→") }
        var newStatus = "pending"
        var newReason = ""
        var newEditedBy = ""
        val newIsEdited = cleaned.isNotEmpty()
        val newEditedAt = if (cleaned.isNotEmpty()) {
            parseLineTimestampMs(cleaned.first()).takeIf { it > 0L } ?: nowMs
        } else 0L

        if (latestStatusLine != null) {
            val left = latestStatusLine.substringBefore("→").trim()
            val right = latestStatusLine.substringAfter("→").trim()
            newEditedBy = left.substringAfter("•", left).trim()
            val statusToken = right.substringBefore(":").trim().lowercase(Locale.US)
            val reasonPart = if (right.contains(":")) right.substringAfter(":").trim() else ""
            newStatus = when (statusToken) {
                "done" -> "done"
                "issue" -> "issue"
                else -> "pending"
            }
            newReason = if (newStatus == "done" || newStatus == "issue") reasonPart else ""
        } else if (cleaned.isNotEmpty()) {
            val first = cleaned.first()
            val afterBullet = first.substringAfter("•", first).trim()
            newEditedBy = if (afterBullet.endsWith("edited text", ignoreCase = true)) {
                afterBullet.dropLast("edited text".length).trim()
            } else afterBullet
        }

        return RecomputedPinHistory(
            status = newStatus,
            issueReason = newReason,
            isEdited = newIsEdited,
            editedBy = newEditedBy,
            editedAt = newEditedAt,
            editHistory = newHistory
        )
    }
}
