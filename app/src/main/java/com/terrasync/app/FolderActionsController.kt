package com.terrasync.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.geometry.LatLng
import java.util.UUID

/**
 * Owns folder-level workflows: KML/CSV export dialogs, visible-folders WhatsApp Daily Reports,
 * rename/delete cloud & local folders, share-code join dialogs, Combine Folders, KML/KMZ import,
 * and Local -> Cloud upload conversion.
 */
class FolderActionsController(
    private val activity: AppCompatActivity,
    private val viewModel: MapViewModel,
    private val repository: TerraSyncRepository,
    private val dp: (Int) -> Int,
    private val currentPalette: () -> ThemePalette,
    private val themedDialogBuilder: (String) -> AlertDialog.Builder,
    private val showThemedDialog: (AlertDialog, Boolean) -> AlertDialog,
    private val isActivityAlive: () -> Boolean,
    private val safeStartActivity: (Intent, String) -> Unit,
    private val showDailyReport: (LayerItem, List<PinItem>, List<TrackItem>, String?) -> Unit,
    private val refreshJoinedSync: () -> Unit,
    private val collectLayerLatLngs: (List<PinItem>, List<TrackItem>) -> List<LatLng>,
    private val zoomToKmlBounds: (List<LatLng>, String?) -> Unit,
    private val notifySyncError: (String) -> Unit
) {

    private val uploadingLayerIds = mutableSetOf<String>()

    fun showExportKmlDialog(layer: LayerItem, layerPins: List<PinItem>, layerTracks: List<TrackItem>) {
        val sentIds = DevicePrefs.getLocalSentPinIds(activity)
        var sentInLayer = 0
        val unsentPins = ArrayList<PinItem>(layerPins.size)
        val pendingPins = ArrayList<PinItem>()
        val pendingUnsentPins = ArrayList<PinItem>()
        val issuePins = ArrayList<PinItem>()
        val donePins = ArrayList<PinItem>()
        for (p in layerPins) {
            val isSent = p.id in sentIds
            if (isSent) sentInLayer++ else unsentPins.add(p)
            when (PinStatus.fromRaw(p.status)) {
                PinStatus.PENDING -> {
                    pendingPins.add(p)
                    if (!isSent) pendingUnsentPins.add(p)
                }
                PinStatus.ISSUE -> issuePins.add(p)
                PinStatus.DONE -> donePins.add(p)
            }
        }

        val entries = mutableListOf(
            Triple("All Pins (${layerPins.size})", layerPins, ""),
            Triple("Done Only (${donePins.size})", donePins, "Done"),
            Triple("Pending Only (${pendingPins.size})", pendingPins, "Pending"),
            Triple("Issues Only (${issuePins.size})", issuePins, "Issues")
        )
        if (sentInLayer > 0) {
            entries.add(Triple("Unsent Only (${unsentPins.size})", unsentPins, "Unsent"))
            entries.add(Triple("Pending & Unsent (${pendingUnsentPins.size})", pendingUnsentPins, "Pending_Unsent"))
        }

        val p = currentPalette()
        DesignSystem.showActionBottomSheet(
            ctx = activity,
            palette = p,
            title = "Export KML",
            subtitle = layer.name,
            actions = entries.map { (label, subset, suffix) ->
                SheetActionItem(
                    title = label,
                    iconRes = R.drawable.ic_download
                ) {
                    val tracksToExport = if (suffix.isEmpty()) layerTracks else emptyList()
                    if (subset.isEmpty() && tracksToExport.isEmpty()) {
                        Toast.makeText(activity, "No matching pins to export", Toast.LENGTH_SHORT).show()
                    } else {
                        KmlCsvExporter.exportAndShareKml(activity, layer, subset, tracksToExport, suffix)
                    }
                }
            }
        )
    }

    fun showVisibleFoldersDailyReportDialog() {
        val snap = viewModel.folderSnapshot
        val hiddenIds = DevicePrefs.getHiddenLayerIds(activity)
        val visibleLayers = snap.layers.filter { it.id !in hiddenIds }
        if (visibleLayers.isEmpty()) {
            Toast.makeText(activity, "No visible folders on the map", Toast.LENGTH_SHORT).show()
            return
        }
        if (visibleLayers.size == 1) {
            val only = visibleLayers.first()
            showDailyReport(
                only,
                snap.pinsByLayerId[only.id].orEmpty(),
                snap.tracksByLayerId[only.id].orEmpty(),
                null
            )
            return
        }
        val visibleLayerIds = visibleLayers.mapTo(HashSet(visibleLayers.size)) { it.id }
        val scopedPins = ArrayList<PinItem>()
        val scopedTracks = ArrayList<TrackItem>()
        for (layer in visibleLayers) {
            snap.pinsByLayerId[layer.id]?.let { scopedPins.addAll(it) }
            snap.tracksByLayerId[layer.id]?.let { scopedTracks.addAll(it) }
        }
        val hasLocal = visibleLayers.any { LocalFolderStore.isLocalLayerId(it.id) }
        val cloudLayersVisible = visibleLayers.filterNot { LocalFolderStore.isLocalLayerId(it.id) }
        val allCloudLive = cloudLayersVisible.isNotEmpty() && cloudLayersVisible.all { repository.syncRepo.isLayerLive(it.id) }
        val syncBadge = when {
            cloudLayersVisible.isEmpty() -> "Local"
            !hasLocal && allCloudLive -> "Live Cloud"
            !hasLocal -> "Offline Cache"
            allCloudLive -> "Live Cloud + Local"
            else -> "Offline Cache + Local"
        }
        val syntheticLayer = LayerItem(
            id = "visible_scope",
            name = "Visible Folders (${visibleLayers.size})"
        )
        showDailyReport(syntheticLayer, scopedPins, scopedTracks, syncBadge)
    }

    fun showRenameFolderDialog(layer: LayerItem) {
        val p = currentPalette()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        val etName = DesignSystem.createThemedEditText(activity, p, "Folder name", initialText = layer.name)
        box.addView(etName)

        showThemedDialog(
            themedDialogBuilder("Rename Folder")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val next = etName.text.toString().trim()
                    if (next.isBlank()) {
                        Toast.makeText(activity, "Folder name cannot be empty", Toast.LENGTH_SHORT).show()
                    } else {
                        repository.renameLayer(layer, next, notifySyncError)
                        Toast.makeText(activity, "Renamed to \"$next\"", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .create(),
            false
        )
    }

    fun showDeleteCloudDialog(layer: LayerItem, layerPins: List<PinItem>, layerTracks: List<TrackItem>) {
        showThemedDialog(
            themedDialogBuilder("Delete \"${layer.name}\" from Cloud?")
                .setMessage("This permanently removes all ${layerPins.size} bookmarks for every team member.")
                .setPositiveButton("Delete Cloud") { _, _ ->
                    val deletedPinIds = layerPins.map { it.id }
                    DevicePrefs.removeJoinedLayerId(activity, layer.id)
                    DevicePrefs.removePinReferences(activity, deletedPinIds)
                    repository.syncRepo.deleteLayer(layer.id, deletedPinIds, layerTracks.map { it.id }, notifySyncError)
                    refreshJoinedSync()
                    Toast.makeText(activity, "Deleted \"${layer.name}\" from cloud", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .create(),
            true
        )
    }

    fun showDeleteLocalDialog(layer: LayerItem, pinCount: Int) {
        showThemedDialog(
            themedDialogBuilder("Delete Local Folder \"${layer.name}\"?")
                .setMessage("Removes all $pinCount pins stored locally on this phone.")
                .setPositiveButton("Delete") { _, _ ->
                    repository.deleteLocalLayer(layer.id)
                    Toast.makeText(activity, "Deleted local folder \"${layer.name}\"", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .create(),
            true
        )
    }

    fun shareLayerLink(layer: LayerItem) {
        val code = layer.shareCode
        val link = "https://thamirakkurd.github.io/TerraSync/join/?code=$code"
        val msg = "TerraSync Folder: ${layer.name}\nShare Code: $code\nOpen link: $link"
        safeStartActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, msg)
        }, "Share Folder"), "No app available to handle this action")
    }

    fun joinShareCode(rawCode: String) {
        repository.joinShareCode(rawCode) { ok, msg, layerId ->
            AppExecutors.postMain {
                if (!isActivityAlive()) return@postMain
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                if (ok && layerId != null) {
                    DevicePrefs.addJoinedLayerId(activity, layerId)
                    viewModel.hasAutoZoomedInitial = false
                    refreshJoinedSync()
                }
            }
        }
    }

    fun showJoinCodeDialog() {
        val p = currentPalette()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        val input = DesignSystem.createThemedEditText(activity, p, "Enter 6-character code (e.g. AKR742)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        box.addView(input)
        showThemedDialog(
            themedDialogBuilder("Join Shared Folder")
                .setView(box)
                .setPositiveButton("Join") { _, _ -> joinShareCode(input.text.toString()) }
                .setNegativeButton("Cancel", null)
                .create(),
            false
        )
    }

    fun showCombineFoldersDialog() {
        val snap = viewModel.folderSnapshot
        if (snap.layers.size < 2) {
            Toast.makeText(activity, "Need at least 2 folders to combine", Toast.LENGTH_SHORT).show()
            return
        }
        val p = currentPalette()
        val scroll = ScrollView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(8))
        }

        val etName = DesignSystem.createThemedEditText(activity, p, "New combined folder name")
        box.addView(etName)

        box.addView(TextView(activity).apply {
            text = "Select folders to combine:"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.textSecondary)
            setPadding(0, dp(14), 0, dp(6))
        })

        val selectedIds = mutableSetOf<String>()
        val cbTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
            intArrayOf(p.accent, p.iconMuted)
        )
        snap.layers.forEach { layer ->
            val count = snap.pinsByLayerId[layer.id]?.size ?: 0
            val cb = CheckBox(activity).apply {
                text = "${layer.name} ($count pins)"
                textSize = 14f
                setTextColor(p.textPrimary)
                buttonTintList = cbTint
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds.add(layer.id) else selectedIds.remove(layer.id)
                }
            }
            box.addView(cb)
        }

        box.addView(View(activity).apply {
            setBackgroundColor(p.divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        })

        val cbDedup = CheckBox(activity).apply {
            text = "Deduplicate matching pins (same name & GPS, keep latest)"
            textSize = 13f
            setTextColor(p.textSecondary)
            buttonTintList = cbTint
            isChecked = true
        }
        box.addView(cbDedup)

        val cbHideOriginals = CheckBox(activity).apply {
            text = "Hide original folders on map"
            textSize = 13f
            setTextColor(p.textSecondary)
            buttonTintList = cbTint
            isChecked = true
        }
        box.addView(cbHideOriginals)

        scroll.addView(box)

        val dlg = themedDialogBuilder("Combine Folders")
            .setView(scroll)
            .setPositiveButton("Combine", null)
            .setNegativeButton("Cancel", null)
            .create()

        dlg.setOnShowListener {
            DesignSystem.styleAlertDialog(activity, dlg, p)
            val btnPos = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btnPos.setOnClickListener {
                if (selectedIds.size < 2) {
                    Toast.makeText(activity, "Select at least 2 folders", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selIdsSnap = selectedIds.toSet()
                val combinedName = etName.text.toString().trim().ifBlank {
                    "Combined (${selIdsSnap.size} folders)"
                }
                val doDedup = cbDedup.isChecked
                val doHideOriginals = cbHideOriginals.isChecked
                val pinsSnap = snap.pins.toList()
                val tracksSnap = snap.tracks.toList()
                val newLayerId = LocalFolderStore.newLocalLayerId()
                val stamp = DevicePrefs.getDeviceStamp(activity)
                val devId = DevicePrefs.getDeviceId(activity)
                val uniqueName = repository.makeUniqueLayerName(combinedName)
                val appCtx = activity.applicationContext
                btnPos.isEnabled = false
                btnPos.text = "Combining..."

                AppExecutors.batchIoExecutor.execute {
                    val rawPins = pinsSnap.filter { it.layerId in selIdsSnap }
                    val rawTracks = tracksSnap.filter { it.layerId in selIdsSnap }
                    val finalPins = if (doDedup) {
                        DailyReportGenerator.deduplicatePins(rawPins)
                    } else {
                        rawPins
                    }

                    if (finalPins.isEmpty() && rawTracks.isEmpty()) {
                        AppExecutors.postMain {
                            if (!isActivityAlive()) return@postMain
                            btnPos.isEnabled = true
                            btnPos.text = "Combine"
                            Toast.makeText(activity, "Selected folders have no pins or tracks", Toast.LENGTH_SHORT).show()
                        }
                        return@execute
                    }

                    val skippedCount = rawPins.size - finalPins.size
                    val parsedCombined = ParsedKml(
                        pins = finalPins.map { it.copy(id = UUID.randomUUID().toString(), layerId = newLayerId) },
                        tracks = rawTracks.map { it.copy(id = UUID.randomUUID().toString(), layerId = newLayerId) },
                        documentName = uniqueName
                    )
                    LocalFolderStore.saveNewLayer(appCtx, newLayerId, uniqueName, stamp, devId, parsedCombined)

                    AppExecutors.postMain {
                        if (!isActivityAlive()) return@postMain
                        DevicePrefs.addCachedLayerName(activity, uniqueName)
                        DevicePrefs.setLayerVisible(activity, newLayerId, true)
                        if (doHideOriginals) {
                            selIdsSnap.forEach { oldId ->
                                DevicePrefs.setLayerVisible(activity, oldId, false)
                            }
                        }
                        repository.applyMergedFolders()
                        val skipNote = if (skippedCount > 0) " ($skippedCount duplicates skipped)" else ""
                        Toast.makeText(activity, "Combined ${finalPins.size} pins locally into \"$uniqueName\"$skipNote", Toast.LENGTH_LONG).show()
                        dlg.dismiss()
                    }
                }
            }
        }
        dlg.show()
    }

    fun handleImportUri(uri: Uri) {
        val fileName = getDisplayName(uri).substringBeforeLast('.')
        val localId = LocalFolderStore.newLocalLayerId()
        val stamp = DevicePrefs.getDeviceStamp(activity)
        val devId = DevicePrefs.getDeviceId(activity)
        val existingNameKeys = buildSet {
            viewModel.folderSnapshot.layers.forEach { add(repository.normalizeLayerNameKey(it.name)) }
            DevicePrefs.getCachedLayerNames(activity).forEach { add(repository.normalizeLayerNameKey(it)) }
        }
        val appCtx = activity.applicationContext

        AppExecutors.batchIoExecutor.execute {
            val parsed = runCatching {
                appCtx.contentResolver.openInputStream(uri)?.use { KmlParser.parse(it, localId) }
            }.getOrNull()

            if (parsed == null || (parsed.pins.isEmpty() && parsed.tracks.isEmpty())) {
                AppExecutors.postMain {
                    if (isActivityAlive()) {
                        Toast.makeText(activity, "No pins or tracks found in file", Toast.LENGTH_SHORT).show()
                    }
                }
                return@execute
            }

            val rawDocName = parsed.documentName.trim()
            val preferredBase = when {
                rawDocName.isNotBlank() && !rawDocName.equals("Document", true) && !rawDocName.equals("KML", true) -> rawDocName
                fileName.isNotBlank() -> fileName.replace('_', ' ').trim()
                else -> "Imported Folder"
            }.ifBlank { "Imported Folder" }

            val chosenName = if (repository.normalizeLayerNameKey(preferredBase) !in existingNameKeys) {
                preferredBase
            } else {
                val stem = preferredBase.replace(Regex("\\s*\\(\\d+\\)$"), "").trim().ifBlank { preferredBase }
                var n = 2
                while (repository.normalizeLayerNameKey("$stem ($n)") in existingNameKeys) {
                    n++
                }
                "$stem ($n)"
            }

            LocalFolderStore.saveNewLayer(appCtx, localId, chosenName, stamp, devId, parsed)

            AppExecutors.postMain {
                if (!isActivityAlive()) return@postMain
                DevicePrefs.addCachedLayerName(activity, chosenName)
                DevicePrefs.setLayerVisible(activity, localId, true)
                repository.applyMergedFolders()

                val allCoords = collectLayerLatLngs(parsed.pins, parsed.tracks)
                if (allCoords.isNotEmpty()) {
                    zoomToKmlBounds(allCoords, localId)
                }

                val summary = buildString {
                    append("Imported ")
                    if (parsed.pins.isNotEmpty()) append("${parsed.pins.size} pin${if (parsed.pins.size == 1) "" else "s"}")
                    if (parsed.tracks.isNotEmpty()) {
                        if (parsed.pins.isNotEmpty()) append(" & ")
                        append("${parsed.tracks.size} track${if (parsed.tracks.size == 1) "" else "s"}")
                    }
                    append(" into \"$chosenName\"")
                }
                Toast.makeText(activity, summary, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun confirmUploadLocalToCloud(layer: LayerItem, layerPins: List<PinItem>, layerTracks: List<TrackItem>) {
        if (layer.id in uploadingLayerIds) {
            Toast.makeText(activity, "Already uploading \"${layer.name}\"...", Toast.LENGTH_SHORT).show()
            return
        }
        showThemedDialog(
            themedDialogBuilder("Upload \"${layer.name}\" to Cloud?")
                .setMessage("Creates a live shared folder with a 6-character code for your teams and replaces the local-only copy.")
                .setPositiveButton("Upload") { _, _ ->
                    if (!uploadingLayerIds.add(layer.id)) return@setPositiveButton
                    Toast.makeText(activity, "Uploading \"${layer.name}\" to cloud...", Toast.LENGTH_SHORT).show()
                    val newCloudId = UUID.randomUUID().toString()
                    val stamp = DevicePrefs.getDeviceStamp(activity)
                    val devId = DevicePrefs.getDeviceId(activity)
                    val parsed = ParsedKml(
                        pins = layerPins.map { it.copy(id = it.id.ifBlank { UUID.randomUUID().toString() }, layerId = newCloudId) },
                        tracks = layerTracks.map { it.copy(id = it.id.ifBlank { UUID.randomUUID().toString() }, layerId = newCloudId) },
                        documentName = layer.name
                    )
                    repository.syncRepo.importLayer(newCloudId, layer.name, stamp, devId, parsed, requireServerCommit = true) { ok, msg, _ ->
                        AppExecutors.postMain {
                            uploadingLayerIds.remove(layer.id)
                            if (!isActivityAlive()) return@postMain
                            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                            if (ok) {
                                val wasPinned = DevicePrefs.isLayerPinned(activity, layer.id)
                                LocalFolderStore.deleteLayer(activity, layer.id)
                                DevicePrefs.removeJoinedLayerId(activity, layer.id)
                                DevicePrefs.addJoinedLayerId(activity, newCloudId)
                                if (wasPinned) DevicePrefs.toggleLayerPinned(activity, newCloudId)
                                refreshJoinedSync()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .create(),
            false
        )
    }

    private fun getDisplayName(uri: Uri): String {
        activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: "Folder"
        }
        return uri.lastPathSegment ?: "Folder"
    }
}
