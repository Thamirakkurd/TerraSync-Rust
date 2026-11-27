package com.terrasync.app

import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt

data class DailyFieldReport(
    val whatsappText: String,
    val totalPins: Int = 0,
    val doneCount: Int = 0,
    val issueCount: Int = 0,
    val pendingCount: Int = 0
)

object DailyReportGenerator {

    const val UNMAPPED_DEVICE_LABEL = "Unnamed Device"
    private const val OTHER_CUSTOM_NOTE_BUCKET = "Other / Custom Note"

    private val UNICODE_WS_REGEX = Regex("[\\s\\u00A0\\u200B\\u200E\\u200F\\uFEFF]+")
    private val NEWLINE_REGEX = Regex("[\\r\\n]+")
    private val DEVICE_ID_REGEX = Regex("#([A-Fa-f0-9]{4,16})")
    private val PAREN_DEVICE_REGEX = Regex("""^(.+?)\s*\(([^)]*#[A-Fa-f0-9]{4,16}[^)]*)\)\s*$""")
    private val RAW_DEVICE_STAMP_REGEX = Regex("""^[^()]*•\s*#[A-Fa-f0-9]{4,16}$|^#[A-Fa-f0-9]{4,16}$""")
    private val RAW_MODEL_ONLY_REGEX = Regex(
        """^(?i)(honor|samsung|xiaomi|redmi|poco|oppo|vivo|realme|huawei|oneplus|google\s+pixel|pixel|iphone|ipad|motorola|nokia|tecno|infinix|sony|lg|asus|lenovo|nothing|sm-[a-z0-9]+|ptp-[a-z0-9]+)\b.*$"""
    )
    private val TEAM_NUMBER_REGEX = Regex("""^(?i)team[\s_-]*(\d+)$""")
    private val TEAM_SIGNATURE_LINE_REGEX = Regex(
        """^(?i)(?:by\s+)?(?:team[\s_-]*\d+|t[\s_-]*\d+|supervisor|admin|inspector|operator|engineer|manager|lead|driver)$"""
    )
    private val TRAILING_TEAM_SIG_REGEX = Regex(
        """(?i)(?:[\s,;•|/\-–—]+|\(\s*)(team[\s_-]*\d+|supervisor)\s*\)?$"""
    )
    private val TITLE_SEG_SPLIT_REGEX = Regex("""\s+[-–—|]\s+""")
    private val TEAM_GROUP_SEP_REGEX = Regex("""[\s_-]+""")

    private data class KnownCategoryRule(
        val canonical: String,
        val aliases: List<String>
    )

    private val KNOWN_CATEGORY_RULES = listOf(
        KnownCategoryRule("Meter Linked", listOf("meter linked", "meter link", "linked")),
        KnownCategoryRule("Inaccessible", listOf("inaccessible", "inaccess")),
        KnownCategoryRule("No Bill", listOf("no bill", "nobill", "no-bill")),
        KnownCategoryRule("Need Electricity", listOf("need electricity", "needs electricity", "no electricity", "need electric", "no electric")),
        KnownCategoryRule("Customer Refused / No Access", listOf("customer refused / no access", "customer refused", "refused", "no access")),
        KnownCategoryRule("Wrong Location / Coordinates", listOf("wrong location / coordinates", "wrong location", "wrong coordinates", "wrong coords", "wrong gps")),
        KnownCategoryRule("Closed / Nobody Home", listOf("closed / nobody home", "nobody home", "no one home", "closed")),
        KnownCategoryRule("Safety Hazard / Network Fault", listOf("safety hazard / network fault", "safety hazard", "network fault", "hazard")),
        KnownCategoryRule("Wrong Link", listOf("wrong link")),
        KnownCategoryRule("Disconnected", listOf("disconnected")),
        KnownCategoryRule("Replaced", listOf("replaced", "meter replaced")),
        KnownCategoryRule("Damaged", listOf("damaged", "broken"))
    )

    private data class ParsedStatusNote(
        val category: String,
        val customNote: String,
        val embeddedTeamSignature: String?
    )

    fun plural(count: Int, singular: String, pluralForm: String = "${singular}s"): String {
        return "$count ${if (count == 1) singular else pluralForm}"
    }

    fun collapseWhitespace(raw: String): String =
        raw.replace(UNICODE_WS_REGEX, " ").trim()

    fun flattenSingleLine(raw: String): String =
        raw.replace(NEWLINE_REGEX, " ").replace(UNICODE_WS_REGEX, " ").trim()

    fun normalizeTeamDisplayName(raw: String): String {
        val clean = collapseWhitespace(raw)
        if (clean.isEmpty()) return ""
        val teamMatch = TEAM_NUMBER_REGEX.matchEntire(clean)
        if (teamMatch != null) {
            return "Team ${teamMatch.groupValues[1]}"
        }
        return clean.split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) return@joinToString ""
            val hasAscii = word.any { it in 'A'..'Z' || it in 'a'..'z' }
            if (!hasAscii) {
                word
            } else {
                val allUpper = word.all { !it.isLetter() || it.isUpperCase() }
                val allLower = word.all { !it.isLetter() || it.isLowerCase() }
                if (allUpper || allLower) {
                    word.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
                } else {
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                }
            }
        }
    }

    fun teamGroupKey(normalizedDisplay: String): String {
        return collapseWhitespace(normalizedDisplay)
            .lowercase(Locale.ROOT)
            .replace(TEAM_GROUP_SEP_REGEX, " ")
            .trim()
    }

    fun isRawDeviceString(raw: String): Boolean {
        val clean = collapseWhitespace(raw)
        if (clean.isEmpty()) return false
        if (PAREN_DEVICE_REGEX.matches(clean)) return false
        return RAW_DEVICE_STAMP_REGEX.matches(clean) ||
            clean.contains("• #") ||
            RAW_MODEL_ONLY_REGEX.matches(clean)
    }

    /**
     * Builds a lookup from uppercase device ID (e.g. "D3A1" or full 12-char ID) to normalized team/operator name
     * using configured aliases plus all named stamps observed across pins and edit history.
     */
    fun buildDeviceToTeamMap(
        pins: List<PinItem>,
        configuredDeviceAliases: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        configuredDeviceAliases.forEach { (rawId, rawName) ->
            val id = rawId.trim().removePrefix("#").uppercase(Locale.US)
            val name = normalizeTeamDisplayName(rawName)
            if (id.isNotBlank() && name.isNotBlank() && !isRawDeviceString(name)) {
                map[id] = name
                if (id.length > 4) {
                    val shortId = id.takeLast(4)
                    if (!map.containsKey(shortId)) map[shortId] = name
                }
            }
        }

        fun inspectStamp(stamp: String) {
            val clean = collapseWhitespace(stamp)
            val m = PAREN_DEVICE_REGEX.matchEntire(clean) ?: return
            val rawName = m.groupValues[1].trim()
            val inside = m.groupValues[2]
            if (rawName.isBlank() || isRawDeviceString(rawName)) return
            val devId = DEVICE_ID_REGEX.find(inside)?.groupValues?.get(1)?.uppercase(Locale.US) ?: return
            val normName = normalizeTeamDisplayName(rawName)
            if (normName.isNotBlank()) {
                map[devId] = normName
                if (devId.length > 4) {
                    val shortId = devId.takeLast(4)
                    if (!map.containsKey(shortId)) map[shortId] = normName
                }
            }
        }

        for (pin in pins) {
            if (pin.editedBy.isNotBlank()) inspectStamp(pin.editedBy)
            if (pin.editHistory.isNotBlank()) {
                pin.editHistory.lines().forEach { line ->
                    val afterBullet = line.substringAfter("•", "").trim()
                    if (afterBullet.isNotEmpty()) {
                        val op = when {
                            afterBullet.contains("→") -> afterBullet.substringBefore("→").trim()
                            afterBullet.endsWith("edited text", ignoreCase = true) ->
                                afterBullet.dropLast("edited text".length).trim()
                            else -> ""
                        }
                        if (op.isNotBlank()) inspectStamp(op)
                    }
                }
            }
        }
        return map
    }

    fun resolveOperator(
        rawOperator: String,
        deviceToTeam: Map<String, String> = emptyMap(),
        fallbackTeamHint: String? = null
    ): Pair<String, String>? {
        val clean = collapseWhitespace(rawOperator)
        if (clean.isEmpty()) {
            val hint = fallbackTeamHint?.let { normalizeTeamDisplayName(it) }.orEmpty()
            return if (hint.isNotEmpty()) teamGroupKey(hint) to hint else null
        }

        val parenMatch = PAREN_DEVICE_REGEX.matchEntire(clean)
        if (parenMatch != null) {
            val rawName = parenMatch.groupValues[1].trim()
            if (rawName.isNotBlank() && !isRawDeviceString(rawName)) {
                val norm = normalizeTeamDisplayName(rawName)
                return teamGroupKey(norm) to norm
            }
        }

        val devId = DEVICE_ID_REGEX.find(clean)?.groupValues?.get(1)?.uppercase(Locale.US)
        if (devId != null) {
            val mapped = deviceToTeam[devId] ?: if (devId.length > 4) deviceToTeam[devId.takeLast(4)] else null
            if (!mapped.isNullOrBlank()) {
                return teamGroupKey(mapped) to mapped
            }
        }

        if (isRawDeviceString(clean)) {
            val hint = fallbackTeamHint?.let { normalizeTeamDisplayName(it) }.orEmpty()
            if (hint.isNotEmpty()) return teamGroupKey(hint) to hint
            return teamGroupKey(UNMAPPED_DEVICE_LABEL) to UNMAPPED_DEVICE_LABEL
        }

        val stripped = clean.substringBefore(" (").trim().ifBlank { clean }
        if (isRawDeviceString(stripped)) {
            val hint = fallbackTeamHint?.let { normalizeTeamDisplayName(it) }.orEmpty()
            if (hint.isNotEmpty()) return teamGroupKey(hint) to hint
            return teamGroupKey(UNMAPPED_DEVICE_LABEL) to UNMAPPED_DEVICE_LABEL
        }

        val norm = normalizeTeamDisplayName(stripped)
        if (norm.isEmpty()) return null
        return teamGroupKey(norm) to norm
    }

    fun pinDedupKey(pin: PinItem): String {
        val normName = collapseWhitespace(pin.name).lowercase(Locale.ROOT)
        val qLat = round(pin.lat * 100000.0).toLong()
        val qLng = round(pin.lng * 100000.0).toLong()
        return "$normName|$qLat|$qLng"
    }

    fun deduplicatePins(pins: List<PinItem>): List<PinItem> {
        if (pins.size <= 1) return pins
        RustCoreBridge.deduplicatePins(pins)?.let { return it }
        return pins
            .sortedWith(
                compareByDescending<PinItem> {
                    val st = PinStatus.fromRaw(it.status)
                    val worked = st != PinStatus.PENDING || it.isEdited || it.editedAt > 0L || it.editHistory.isNotBlank()
                    if (worked) 1 else 0
                }
                    .thenByDescending { it.editedAt }
                    .thenByDescending {
                        when (PinStatus.fromRaw(it.status)) {
                            PinStatus.DONE -> 2
                            PinStatus.ISSUE -> 1
                            PinStatus.PENDING -> 0
                        }
                    }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.id }
            )
            .distinctBy { pinDedupKey(it) }
    }

    fun buildReport(
        folderName: String,
        pins: List<PinItem>,
        isCloudLive: Boolean = true,
        workStartMin: Int? = null,
        workEndMin: Int? = null,
        syncBadgeOverride: String? = null,
        configuredDeviceAliases: Map<String, String> = emptyMap(),
        allKnownPinsForDeviceMap: List<PinItem> = pins
    ): DailyFieldReport {
        val uniquePins = deduplicatePins(pins)
        val dupSkipped = (pins.size - uniquePins.size).coerceAtLeast(0)
        var doneCnt = 0
        var issueCnt = 0
        for (pin in uniquePins) {
            when (PinStatus.fromRaw(pin.status)) {
                PinStatus.DONE -> doneCnt++
                PinStatus.ISSUE -> issueCnt++
                PinStatus.PENDING -> Unit
            }
        }
        val pendingCnt = uniquePins.size - doneCnt - issueCnt
        val text = generateFromUnique(
            folderName = folderName,
            uniquePins = uniquePins,
            dupSkipped = dupSkipped,
            isCloudLive = isCloudLive,
            workStartMin = workStartMin,
            workEndMin = workEndMin,
            syncBadgeOverride = syncBadgeOverride,
            configuredDeviceAliases = configuredDeviceAliases,
            allKnownPinsForDeviceMap = allKnownPinsForDeviceMap
        )
        return DailyFieldReport(
            whatsappText = text,
            totalPins = uniquePins.size,
            doneCount = doneCnt,
            issueCount = issueCnt,
            pendingCount = pendingCnt
        )
    }

    fun generate(
        folderName: String,
        pins: List<PinItem>,
        isCloudLive: Boolean = false,
        workStartMin: Int? = null,
        workEndMin: Int? = null,
        syncBadgeOverride: String? = null,
        configuredDeviceAliases: Map<String, String> = emptyMap(),
        allKnownPinsForDeviceMap: List<PinItem> = pins
    ): String {
        val uniquePins = deduplicatePins(pins)
        val dupSkipped = (pins.size - uniquePins.size).coerceAtLeast(0)
        return generateFromUnique(
            folderName = folderName,
            uniquePins = uniquePins,
            dupSkipped = dupSkipped,
            isCloudLive = isCloudLive,
            workStartMin = workStartMin,
            workEndMin = workEndMin,
            syncBadgeOverride = syncBadgeOverride,
            configuredDeviceAliases = configuredDeviceAliases,
            allKnownPinsForDeviceMap = allKnownPinsForDeviceMap
        )
    }

    private fun generateFromUnique(
        folderName: String,
        uniquePins: List<PinItem>,
        dupSkipped: Int,
        isCloudLive: Boolean = false,
        workStartMin: Int? = null,
        workEndMin: Int? = null,
        syncBadgeOverride: String? = null,
        configuredDeviceAliases: Map<String, String> = emptyMap(),
        allKnownPinsForDeviceMap: List<PinItem> = uniquePins
    ): String {
        val now = TimeFormatters.reportTimestamp(System.currentTimeMillis())
        val syncBadge = syncBadgeOverride ?: if (isCloudLive) "Live Cloud" else "Local"
        if (uniquePins.isNotEmpty() && RustCoreBridge.isAvailable()) {
            val outsideLine = if (workStartMin != null && workEndMin != null) {
                val lateCount = PinEditHistoryAudit.countOutsideWorkingHours(uniquePins, workStartMin, workEndMin)
                val window = "${PinEditHistoryAudit.formatMinutes(workStartMin)}–${PinEditHistoryAudit.formatMinutes(workEndMin)}"
                val editWord = if (lateCount == 1) "edit" else "edits"
                "• ⏰ Outside Working Hours ($window): *$lateCount* $editWord"
            } else ""
            val knownForMap = if (allKnownPinsForDeviceMap.size >= uniquePins.size) allKnownPinsForDeviceMap else uniquePins
            RustCoreBridge.buildDailyReportFromUnique(
                folderName = folderName,
                nowStr = now,
                syncBadge = syncBadge,
                dupSkipped = dupSkipped,
                outsideWorkLine = outsideLine,
                configuredDeviceAliases = configuredDeviceAliases,
                knownPinsForDeviceMap = knownForMap,
                uniquePins = uniquePins
            )?.let { return it }
        }
        if (uniquePins.isEmpty()) {
            return buildString {
                appendLine("*${flattenSingleLine(folderName)} — Daily Field Report*")
                appendLine("_${now} • ${syncBadge}_")
                appendLine()
                appendLine("No pins in this scope.")
            }.trim()
        }

        val total = uniquePins.size

        val deviceToTeam = buildDeviceToTeamMap(
            pins = if (allKnownPinsForDeviceMap.size >= uniquePins.size) allKnownPinsForDeviceMap else uniquePins,
            configuredDeviceAliases = configuredDeviceAliases
        )

        var doneCount = 0
        var issueCount = 0
        var pendingTotal = 0
        var pendingTouched = 0

        val doneReasons = linkedMapOf<String, Int>()
        val issueReasons = linkedMapOf<String, Int>()
        val fieldNotes = mutableListOf<String>()

        data class TeamStats(
            val displayName: String,
            var done: Int = 0,
            var issue: Int = 0,
            var pendingTouched: Int = 0
        ) {
            val worked: Int get() = done + issue + pendingTouched
        }

        val teamMap = linkedMapOf<String, TeamStats>()

        for (pin in uniquePins) {
            val status = PinStatus.fromRaw(pin.status)
            val rawReason = pin.issueReason.trim()
            val parsedNote = parseStatusNote(rawReason, status)
            val rawOperator = pin.editedBy.trim().ifBlank { latestOperatorFromHistory(pin.editHistory) }
            val teamKeyAndLabel = resolveOperator(rawOperator, deviceToTeam, parsedNote.embeddedTeamSignature)

            when (status) {
                PinStatus.DONE -> {
                    doneCount++
                    val bucket = flattenSingleLine(parsedNote.category).ifBlank { "Completed" }
                    doneReasons[bucket] = (doneReasons[bucket] ?: 0) + 1
                    teamKeyAndLabel?.let { (key, label) ->
                        teamMap.getOrPut(key) { TeamStats(label) }.done++
                    }
                }
                PinStatus.ISSUE -> {
                    issueCount++
                    val bucket = flattenSingleLine(parsedNote.category).ifBlank { "Other Issue" }
                    issueReasons[bucket] = (issueReasons[bucket] ?: 0) + 1
                    teamKeyAndLabel?.let { (key, label) ->
                        teamMap.getOrPut(key) { TeamStats(label) }.issue++
                    }
                }
                PinStatus.PENDING -> {
                    pendingTotal++
                    val isTouched = pin.isEdited || teamKeyAndLabel != null || rawReason.isNotBlank() || pin.editHistory.isNotBlank()
                    if (isTouched) {
                        pendingTouched++
                        teamKeyAndLabel?.let { (key, label) ->
                            teamMap.getOrPut(key) { TeamStats(label) }.pendingTouched++
                        }
                    }
                }
            }

            // Field Notes / Rework: only include genuine custom remarks, extra issue details, or actual rework flips
            val isRework = detectGenuineRework(pin, status)
            val customRemark = parsedNote.customNote
            if (customRemark.isNotBlank() || isRework) {
                val teamPrefix = teamKeyAndLabel?.second?.let { "[$it] " } ?: ""
                val statusPart = when {
                    isRework && status == PinStatus.PENDING -> "Rework (Pending)"
                    isRework -> "Rework (${status.label})"
                    parsedNote.category != OTHER_CUSTOM_NOTE_BUCKET &&
                        parsedNote.category != "Completed" &&
                        parsedNote.category != "Other Issue" &&
                        parsedNote.category.isNotBlank() -> "${status.label} (${parsedNote.category})"
                    else -> status.label
                }
                val shortPin = formatCompactPinLabel(pin.name)
                val noteBody = when {
                    customRemark.isNotBlank() -> truncateClean(customRemark, 100)
                    else -> "Reverted after Done"
                }
                fieldNotes.add(flattenSingleLine("$teamPrefix$statusPart • $shortPin: $noteBody"))
            }
        }

        // Strict mathematical invariants
        check(doneCount + issueCount + pendingTotal == total)
        val pendingUntouched = (pendingTotal - pendingTouched).coerceAtLeast(0)
        val visitedCount = doneCount + issueCount
        val pctVisited = if (total > 0) ((visitedCount * 100.0) / total).roundToInt() else 0
        val pctDone = if (total > 0) ((doneCount * 100.0) / total).roundToInt() else 0

        return buildString {
            appendLine("*${flattenSingleLine(folderName)} — Daily Field Report*")
            appendLine("_${now} • ${syncBadge}_")
            appendLine()
            appendLine("*Summary*")
            val dupNote = if (dupSkipped > 0) " (${plural(dupSkipped, "duplicate")} deduplicated)" else ""
            appendLine("• Total Pins: *$total*$dupNote")
            appendLine("• Visited Progress: *$pctVisited%* ($visitedCount / $total)")
            appendLine("• Done / Resolved: *$pctDone%* ($doneCount / $total)")
            appendLine("• ✓ Done: *$doneCount*")
            appendLine("• ⚠ Issue: *$issueCount*")
            appendLine("• ⏳ Pending: *$pendingTotal* ($pendingUntouched untouched, $pendingTouched touched)")
            if (workStartMin != null && workEndMin != null) {
                val lateCount = PinEditHistoryAudit.countOutsideWorkingHours(uniquePins, workStartMin, workEndMin)
                val window = "${PinEditHistoryAudit.formatMinutes(workStartMin)}–${PinEditHistoryAudit.formatMinutes(workEndMin)}"
                val editWord = if (lateCount == 1) "edit" else "edits"
                appendLine("• ⏰ Outside Working Hours ($window): *$lateCount* $editWord")
            }

            if (doneCount > 0) {
                appendLine()
                appendLine("*✓ Done Breakdown ($doneCount)*")
                doneReasons.entries
                    .sortedWith(
                        compareBy<Map.Entry<String, Int>> { it.key == OTHER_CUSTOM_NOTE_BUCKET }
                            .thenByDescending { it.value }
                            .thenBy { it.key }
                    )
                    .forEach { (reason, count) ->
                        appendLine("• ${flattenSingleLine(reason)}: *$count*")
                    }
            }

            if (issueCount > 0) {
                appendLine()
                appendLine("*⚠ Issue Breakdown ($issueCount)*")
                issueReasons.entries
                    .sortedWith(
                        compareBy<Map.Entry<String, Int>> { it.key == OTHER_CUSTOM_NOTE_BUCKET }
                            .thenByDescending { it.value }
                            .thenBy { it.key }
                    )
                    .forEach { (reason, count) ->
                        appendLine("• ${flattenSingleLine(reason)}: *$count*")
                    }
            }

            if (teamMap.isNotEmpty()) {
                appendLine()
                appendLine("*Per-Team Activity*")
                teamMap.values
                    .sortedWith(
                        compareBy<TeamStats> { it.displayName == UNMAPPED_DEVICE_LABEL }
                            .thenByDescending { it.worked }
                            .thenBy { it.displayName }
                    )
                    .forEach { t ->
                        val parts = mutableListOf<String>()
                        if (t.done > 0) parts.add("${t.done} Done")
                        if (t.issue > 0) parts.add("${t.issue} Issue")
                        if (t.pendingTouched > 0) parts.add("${t.pendingTouched} Pending")
                        val exactTotal = t.done + t.issue + t.pendingTouched
                        if (exactTotal > 0) {
                            appendLine("• *${t.displayName}*: $exactTotal worked (${parts.joinToString(" • ")})")
                        }
                    }
            }

            if (fieldNotes.isNotEmpty()) {
                appendLine()
                appendLine("*Field Notes / Rework (${fieldNotes.size})*")
                fieldNotes.take(20).forEach { note -> appendLine("• $note") }
                if (fieldNotes.size > 20) {
                    val more = fieldNotes.size - 20
                    appendLine("• _+${plural(more, "more note")}_")
                }
            }
        }.trim()
    }

    private fun parseStatusNote(rawReason: String, status: PinStatus): ParsedStatusNote {
        val defaultBucket = when (status) {
            PinStatus.DONE -> "Completed"
            PinStatus.ISSUE -> "Other Issue"
            PinStatus.PENDING -> ""
        }
        if (rawReason.isBlank()) {
            return ParsedStatusNote(defaultBucket, "", null)
        }

        val rawLines = rawReason
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { collapseWhitespace(it) }
            .filter { it.isNotEmpty() }

        var extractedTeam: String? = null
        val meaningfulLines = mutableListOf<String>()

        for (line in rawLines) {
            if (TEAM_SIGNATURE_LINE_REGEX.matches(line)) {
                if (extractedTeam == null) extractedTeam = normalizeTeamDisplayName(line.removePrefix("by ").trim())
                continue
            }
            var cleanedLine = line
            val trailMatch = TRAILING_TEAM_SIG_REGEX.find(cleanedLine)
            if (trailMatch != null) {
                val sig = trailMatch.groupValues[1].trim()
                val withoutSig = cleanedLine.substring(0, trailMatch.range.first).trim()
                if (withoutSig.isNotEmpty()) {
                    cleanedLine = withoutSig
                    if (extractedTeam == null) extractedTeam = normalizeTeamDisplayName(sig)
                }
            }
            if (cleanedLine.isNotEmpty() && !TEAM_SIGNATURE_LINE_REGEX.matches(cleanedLine)) {
                meaningfulLines.add(cleanedLine)
            }
        }

        if (meaningfulLines.isEmpty()) {
            return ParsedStatusNote(defaultBucket, "", extractedTeam)
        }

        val firstLine = meaningfulLines.first()
        val restLines = meaningfulLines.drop(1)

        val matched = matchKnownCategory(firstLine)
        if (matched != null) {
            val (canonical, remainderOnFirst) = matched
            val extraNote = (listOf(remainderOnFirst) + restLines)
                .map { flattenSingleLine(it) }
                .filter { it.isNotEmpty() && !TEAM_SIGNATURE_LINE_REGEX.matches(it) }
                .joinToString(" • ")
            return ParsedStatusNote(canonical, extraNote, extractedTeam)
        }

        // Unrecognized free-text note -> roll into "Other / Custom Note" and preserve exact cleaned text in Field Notes
        val fullCustom = meaningfulLines
            .map { flattenSingleLine(it) }
            .filter { it.isNotEmpty() }
            .joinToString(" • ")
        return ParsedStatusNote(OTHER_CUSTOM_NOTE_BUCKET, fullCustom, extractedTeam)
    }

    private fun matchKnownCategory(line: String): Pair<String, String>? {
        val clean = collapseWhitespace(line)
        val low = clean.lowercase(Locale.US)
        for (rule in KNOWN_CATEGORY_RULES) {
            for (alias in rule.aliases) {
                if (low == alias) {
                    return rule.canonical to ""
                }
                if (low.startsWith(alias) && clean.length > alias.length) {
                    val nextChar = clean[alias.length]
                    if (nextChar in charArrayOf(':', '-', '–', '—', '/', ',', '|', '•', '(')) {
                        val rem = clean.substring(alias.length + 1).trim().removeSuffix(")").trim()
                        return rule.canonical to rem
                    }
                }
            }
        }
        return null
    }

    private fun latestOperatorFromHistory(editHistory: String): String {
        val firstLine = editHistory.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        val afterBullet = firstLine.substringAfter("•", "").trim()
        if (afterBullet.isEmpty()) return ""
        return when {
            afterBullet.contains("→") -> afterBullet.substringBefore("→").trim()
            afterBullet.endsWith("edited text", ignoreCase = true) ->
                afterBullet.dropLast("edited text".length).trim()
            else -> ""
        }
    }

    private fun detectGenuineRework(pin: PinItem, currentStatus: PinStatus): Boolean {
        if (pin.editHistory.isBlank()) return false
        val statusLines = pin.editHistory.lines()
            .map { it.trim() }
            .filter { it.contains("→") }
        if (statusLines.size < 2) return false
        // History is newest-first: check if an older entry was Done and current status is Issue or Pending
        val hadEarlierDone = statusLines.drop(1).any { line ->
            val rhs = line.substringAfter("→").trim()
            PinStatus.fromRaw(rhs.substringBefore(":")) == PinStatus.DONE
        }
        return hadEarlierDone && currentStatus != PinStatus.DONE
    }

    fun formatCompactPinLabel(rawName: String): String {
        val clean = flattenSingleLine(rawName)
        if (clean.isEmpty()) return "Pin"
        val segments = clean.split(TITLE_SEG_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.size >= 2) {
            val picked = mutableListOf<String>()
            var accLen = 0
            for (seg in segments) {
                if (picked.size >= 3) break
                if (picked.any { it.equals(seg, ignoreCase = true) }) continue
                val addLen = if (picked.isEmpty()) seg.length else 3 + seg.length
                if (picked.size >= 2 && accLen + addLen > 42) break
                picked.add(seg)
                accLen += addLen
            }
            return truncateClean(picked.joinToString(" / "), 44)
        }
        return truncateClean(clean, 40)
    }

    private fun truncateClean(text: String, maxLen: Int): String {
        val clean = flattenSingleLine(text)
        if (clean.length <= maxLen) return clean
        return clean.take((maxLen - 1).coerceAtLeast(1)).trimEnd() + "…"
    }
}
