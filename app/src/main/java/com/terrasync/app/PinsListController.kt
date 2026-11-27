package com.terrasync.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import kotlin.math.floor

sealed interface PinListRowItem {
    val stableId: String
    data class MessageRow(val text: String) : PinListRowItem {
        override val stableId: String = "msg:$text"
    }
    data class PinRow(
        val pin: PinItem,
        val isFirst: Boolean,
        val isLast: Boolean,
        val selectionMode: Boolean,
        val isSelected: Boolean,
        val sameCount: Int,
        val distLabel: String?
    ) : PinListRowItem {
        override val stableId: String = "pin:${pin.id}"
    }
    data class ShareCodeRow(val shareCode: String) : PinListRowItem {
        override val stableId: String = "share:$shareCode"
    }
}

/**
 * Owns the per-folder / Starred Pins List subpage with off-UI duplicate & same-coordinate
 * cluster analysis, Nearest/A-Z sorting, RecyclerView DiffUtil updates, and bulk actions.
 */
class PinsListController(
    private val activity: AppCompatActivity,
    private val viewModel: MapViewModel,
    private val repository: TerraSyncRepository,
    private val dp: (Int) -> Int,
    private val currentPalette: () -> ThemePalette,
    private val themedDialogBuilder: (String) -> AlertDialog.Builder,
    private val showThemedDialog: (AlertDialog, Boolean) -> AlertDialog,
    private val getCachedSpeedUnit: () -> SpeedUnit,
    private val getCurrentLocation: () -> Location?,
    private val isActivityAlive: () -> Boolean,
    private val getPageBackStack: () -> MutableList<() -> Unit>,
    private val makePageFrame: (String, () -> Unit, View?, Boolean) -> Pair<LinearLayout, LinearLayout>,
    private val pushSubPage: (() -> Unit, () -> View) -> Unit,
    private val popSubPage: (() -> View) -> Unit,
    private val closeFullPage: () -> Unit,
    private val setLivePinListRefresh: ((() -> Unit)?) -> Unit,
    private val openEditHistoryPage: (LayerItem) -> View,
    private val ensurePinVisibleOnMap: (PinItem) -> Unit,
    private val selectPinAndOpenCard: (PinItem, Boolean) -> Unit,
    private val collectLayerLatLngs: (List<PinItem>, List<TrackItem>) -> List<LatLng>,
    private val zoomToKmlBounds: (List<LatLng>, String?) -> Unit,
    private val updateSentToggleUi: () -> Unit,
    private val renderMapData: () -> Unit,
    private val notifySyncError: (String) -> Unit
) {

    fun buildPinsListPage(title: String, sourcePins: List<PinItem>, layer: LayerItem?): View {
        val p = currentPalette()
        val night = p.isDark
        val txtPrimary = p.textPrimary
        val txtSub = p.textSecondary

        fun currentSourcePins(): List<PinItem> {
            val snap = viewModel.folderSnapshot
            return when {
                layer != null -> snap.pinsByLayerId[layer.id].orEmpty()
                title == "Starred" -> {
                    val bm = DevicePrefs.getBookmarks(activity)
                    snap.pins.filter { it.id in bm }
                }
                else -> sourcePins
            }
        }

        val topActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (layer != null) {
            topActions.addView(MaterialButton(activity).apply {
                text = "History"
                textSize = 12f
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                cornerRadius = dp(10)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                    marginEnd = dp(6)
                }
                DesignSystem.styleSegmentedButton(this, false, p)
                UiAnimations.attachSnappyPress(this)
                setOnClickListener {
                    pushSubPage({
                        popSubPage {
                            buildPinsListPage(layer.name, currentSourcePins(), layer)
                        }
                    }) {
                        openEditHistoryPage(layer)
                    }
                }
            })
        }
        topActions.addView(MaterialButton(activity).apply {
            text = AppStrings.zoom(activity)
            textSize = 12f
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(Color.WHITE)
            setBackgroundColor(p.accent)
            cornerRadius = dp(10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
            UiAnimations.attachSnappyPress(this)
            setOnClickListener {
                val targetPins = currentSourcePins()
                val targetTracks = if (layer != null) viewModel.folderSnapshot.tracks.filter { it.layerId == layer.id } else emptyList()
                val targetPts = collectLayerLatLngs(targetPins, targetTracks)
                closeFullPage()
                zoomToKmlBounds(targetPts, layer?.id)
            }
        })

        val (outer, body) = makePageFrame(title, {
            val stack = getPageBackStack()
            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex).invoke() else closeFullPage()
        }, topActions, false)

        var sortNearest = getCurrentLocation() != null
        var filterKey = "all"
        var selectionMode = false
        val selectedPinIds = linkedSetOf<String>()
        val canDeletePins = layer == null || repository.isLayerOwner(layer)

        fun pinPriorityRank(pin: PinItem): Int = when (PinStatus.fromRaw(pin.status)) {
            PinStatus.DONE -> 3
            PinStatus.ISSUE -> 2
            PinStatus.PENDING -> if (pin.isEdited) 1 else 0
        }

        fun sortKeepPrimaryFirst(group: List<PinItem>): List<PinItem> =
            group.sortedWith(
                compareByDescending<PinItem> { pinPriorityRank(it) }
                    .thenByDescending { it.editedAt }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.id }
            )

        fun findDuplicateExtraIds(list: List<PinItem>): Set<String> {
            val byKey = list.groupBy { pin -> DailyReportGenerator.pinDedupKey(pin) }
            val extras = linkedSetOf<String>()
            byKey.values.forEach { grp ->
                if (grp.size >= 2) {
                    val ordered = sortKeepPrimaryFirst(grp)
                    for (i in 1 until ordered.size) extras.add(ordered[i].id)
                }
            }
            return extras
        }

        fun analyzeSameCoordClusters(list: List<PinItem>): Pair<Set<String>, Map<String, Int>> {
            RustCoreBridge.analyzeSameCoordClusters(list)?.let { return it }
            val cellSize = STACKED_CELL_SIZE
            val buckets = HashMap<Long, MutableList<Int>>()
            list.forEachIndexed { idx, pin ->
                val cx = floor(pin.lat / cellSize).toInt()
                val cy = floor(pin.lng / cellSize).toInt()
                buckets.getOrPut(stackedBucketKey(cx, cy)) { mutableListOf() }.add(idx)
            }

            val visited = BooleanArray(list.size)
            val extras = linkedSetOf<String>()
            val clusterSizes = HashMap<String, Int>()

            for (i in list.indices) {
                if (visited[i]) continue
                val cluster = mutableListOf<PinItem>()
                val queue = ArrayDeque<Int>()
                visited[i] = true
                queue.add(i)

                while (queue.isNotEmpty()) {
                    val curIdx = queue.removeFirst()
                    val curPin = list[curIdx]
                    cluster.add(curPin)
                    val curCx = floor(curPin.lat / cellSize).toInt()
                    val curCy = floor(curPin.lng / cellSize).toInt()
                    for (dx in -1..1) {
                        for (dy in -1..1) {
                            val candIndices = buckets[stackedBucketKey(curCx + dx, curCy + dy)] ?: continue
                            for (candIdx in candIndices) {
                                if (visited[candIdx]) continue
                                val cand = list[candIdx]
                                if (RoutingEngine.haversineMeters(curPin.lat, curPin.lng, cand.lat, cand.lng) <= 2.0) {
                                    visited[candIdx] = true
                                    queue.add(candIdx)
                                }
                            }
                        }
                    }
                }

                if (cluster.size >= 2) {
                    val ordered = sortKeepPrimaryFirst(cluster)
                    ordered.forEach { clusterSizes[it.id] = ordered.size }
                    for (k in 1 until ordered.size) extras.add(ordered[k].id)
                }
            }
            return extras to clusterSizes
        }

        val controlsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val controlsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val btnSelectMode = MaterialButton(activity).apply {
            text = "Select"
            textSize = 12f
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(10)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(8) }
            UiAnimations.attachSnappyPress(this)
        }
        controlsRow.addView(btnSelectMode)

        val btnSort = MaterialButton(activity).apply {
            textSize = 12f
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(10)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(Color.WHITE)
            setBackgroundColor(p.green)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(8) }
        }
        controlsRow.addView(btnSort)

        val filterBtns = mutableMapOf<String, MaterialButton>()
        listOf("all" to "All", "pending" to AppStrings.pending(activity), "done" to AppStrings.done(activity), "issue" to AppStrings.issue(activity)).forEach { (k, lbl) ->
            val b = MaterialButton(activity).apply {
                text = lbl
                textSize = 12f
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                cornerRadius = dp(10)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(6) }
            }
            filterBtns[k] = b
            controlsRow.addView(b)
        }
        controlsScroll.addView(controlsRow)
        body.addView(controlsScroll)

        val autoSelectScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
        }
        val autoSelectRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val btnAutoDuplicates = MaterialButton(activity).apply {
            text = "Auto: Duplicates"
            textSize = 12f
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(11), 0, dp(11), 0)
            cornerRadius = dp(10)
            DesignSystem.applyTabularNumerals(this)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(6) }
            DesignSystem.styleSegmentedButton(this, false, p)
            UiAnimations.attachSnappyPress(this)
        }
        val btnAutoSameCoords = MaterialButton(activity).apply {
            text = "Auto: Same Coords"
            textSize = 12f
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(11), 0, dp(11), 0)
            cornerRadius = dp(10)
            DesignSystem.applyTabularNumerals(this)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(6) }
            DesignSystem.styleSegmentedButton(this, false, p)
            UiAnimations.attachSnappyPress(this)
        }
        val btnSelectAll = MaterialButton(activity).apply {
            text = "All"
            textSize = 12f
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(11), 0, dp(11), 0)
            cornerRadius = dp(10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(6) }
            DesignSystem.styleSegmentedButton(this, false, p)
            UiAnimations.attachSnappyPress(this)
        }
        val btnClearSel = MaterialButton(activity).apply {
            text = "Clear"
            textSize = 12f
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(11), 0, dp(11), 0)
            cornerRadius = dp(10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
            DesignSystem.styleSegmentedButton(this, false, p)
            UiAnimations.attachSnappyPress(this)
        }
        autoSelectRow.addView(btnAutoDuplicates)
        autoSelectRow.addView(btnAutoSameCoords)
        autoSelectRow.addView(btnSelectAll)
        autoSelectRow.addView(btnClearSel)
        autoSelectScroll.addView(autoSelectRow)
        body.addView(autoSelectScroll)

        val listCard = FrameLayout(activity).apply {
            DesignSystem.applyGroupedCardSurface(activity, this, p, radiusDp = 16f)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val recycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            itemAnimator = null
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        listCard.addView(recycler)
        body.addView(listCard)

        val selectionDivider = DesignSystem.createHairlineDivider(activity, p).apply {
            visibility = View.GONE
        }
        val selectionBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.pageBackdrop)
            setPadding(dp(14), dp(8), dp(14), dp(10))
            visibility = View.GONE
        }
        val tvSelSummary = TextView(activity).apply {
            text = "0 selected"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(txtPrimary)
            DesignSystem.applyTabularNumerals(this)
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        val selBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun makeSelActionBtn(label: String, bgColor: Int, txtColor: Int, weight: Float, marginEndDp: Int): MaterialButton {
            return MaterialButton(activity).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(6), 0, dp(6), 0)
                setTextColor(txtColor)
                setBackgroundColor(bgColor)
                cornerRadius = dp(10)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), weight).apply {
                    if (marginEndDp > 0) marginEnd = dp(6)
                }
                UiAnimations.attachSnappyPress(this)
            }
        }
        val btnSelZoom = makeSelActionBtn("Zoom", p.chipSurface, txtPrimary, 0.85f, 6)
        val btnSelExport = makeSelActionBtn("Export", p.accent, Color.WHITE, 0.95f, 6)
        val btnSelStatus = makeSelActionBtn("Status", p.green, Color.WHITE, 0.95f, if (canDeletePins) 6 else 0)
        val btnSelDelete = makeSelActionBtn("Delete", p.red, Color.WHITE, 0.95f, 0).apply {
            visibility = if (canDeletePins) View.VISIBLE else View.GONE
        }
        selBtnRow.addView(btnSelZoom)
        selBtnRow.addView(btnSelExport)
        selBtnRow.addView(btnSelStatus)
        if (canDeletePins) selBtnRow.addView(btnSelDelete)
        selectionBar.addView(tvSelSummary)
        selectionBar.addView(selBtnRow)
        outer.addView(selectionDivider)
        outer.addView(selectionBar)

        var latestFiltered: List<PinItem> = emptyList()
        var cachedAnalysisKey: Pair<String, Long>? = null
        var cachedDupExtras: Set<String> = emptySet()
        var cachedSameCoordExtras: Set<String> = emptySet()
        var cachedClusterSizes: Map<String, Int> = emptyMap()
        var renderSeq = 0
        var analysisSeq = 0
        lateinit var pinAdapter: ListAdapter<PinListRowItem, RecyclerView.ViewHolder>
        lateinit var rebuildSubmittedRows: () -> Unit

        fun syncAutoSelectButtons() {
            btnAutoDuplicates.text = "Duplicates (${cachedDupExtras.size})"
            btnAutoSameCoords.text = "Same Coords (${cachedSameCoordExtras.size})"
            DesignSystem.styleSegmentedButton(
                btnAutoDuplicates,
                selectionMode && cachedDupExtras.isNotEmpty() && selectedPinIds.containsAll(cachedDupExtras) && selectedPinIds.size == cachedDupExtras.size,
                p,
                p.amber
            )
            DesignSystem.styleSegmentedButton(
                btnAutoSameCoords,
                selectionMode && cachedSameCoordExtras.isNotEmpty() && selectedPinIds.containsAll(cachedSameCoordExtras) && selectedPinIds.size == cachedSameCoordExtras.size,
                p,
                p.accent
            )
        }

        fun updateSelectionBarState() {
            val liveIds = currentSourcePins().mapTo(HashSet()) { it.id }
            selectedPinIds.retainAll(liveIds)
            val cnt = selectedPinIds.size
            btnSelectMode.text = if (selectionMode) "Done" else "Select"
            DesignSystem.styleSegmentedButton(btnSelectMode, selectionMode, p, p.accent)
            if (selectionMode) {
                UiAnimations.fadeShow(autoSelectScroll, 160L)
                selectionDivider.visibility = View.VISIBLE
                UiAnimations.showSlideBottom(selectionBar, 180L)
            } else {
                UiAnimations.fadeHide(autoSelectScroll, 120L)
                selectionDivider.visibility = View.GONE
                UiAnimations.hideSlideBottom(selectionBar, 140L)
            }
            tvSelSummary.text = if (cnt == 0) "Tap bookmarks or use Auto-Select above" else "$cnt bookmark${if (cnt == 1) "" else "s"} selected"
            val hasAny = cnt > 0
            listOf(btnSelZoom, btnSelExport, btnSelStatus, btnSelDelete).forEach { b ->
                b.isEnabled = hasAny
                b.alpha = if (hasAny) 1f else 0.45f
            }
            syncAutoSelectButtons()
        }

        var latestSortedPins: List<PinItem> = emptyList()
        var latestDistLabelById: Map<String, String> = emptyMap()

        pinAdapter = object : ListAdapter<PinListRowItem, RecyclerView.ViewHolder>(
            object : DiffUtil.ItemCallback<PinListRowItem>() {
                override fun areItemsTheSame(o: PinListRowItem, n: PinListRowItem) = o.stableId == n.stableId
                override fun areContentsTheSame(o: PinListRowItem, n: PinListRowItem) = o == n
            }
        ) {
            inner class MsgVH(val tv: TextView) : RecyclerView.ViewHolder(tv)
            inner class PinVH(
                val wrap: LinearLayout,
                val divider: View,
                val clickRow: LinearLayout,
                val checkBadge: TextView,
                val dot: View,
                val tvTitle: TextView,
                val tvGpsBadge: TextView,
                val tvSub: TextView,
                val tvDist: TextView
            ) : RecyclerView.ViewHolder(wrap)

            override fun getItemViewType(position: Int): Int = when (getItem(position)) {
                is PinListRowItem.MessageRow -> 0
                is PinListRowItem.PinRow -> 1
                is PinListRowItem.ShareCodeRow -> 2
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                return when (viewType) {
                    0, 2 -> MsgVH(TextView(activity).apply {
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        textSize = if (viewType == 0) 13f else 12.5f
                        setTextColor(txtSub)
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                    })
                    else -> {
                        val wrap = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        }
                        val divider = DesignSystem.createHairlineDivider(activity, p, 16, 16)
                        val row = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            minimumHeight = dp(52)
                            setPadding(dp(14), dp(11), dp(14), dp(11))
                        }
                        val badge = TextView(activity).apply {
                            textSize = 13f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) }
                        }
                        val dot = View(activity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) }
                        }
                        val col = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginEnd = dp(8)
                            }
                        }
                        val nameRow = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        val tvTitle = TextView(activity).apply {
                            textSize = 15f
                            setTextColor(txtPrimary)
                            setTypeface(null, Typeface.BOLD)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val tvGps = TextView(activity).apply {
                            textSize = 10f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(p.amber)
                            DesignSystem.applyTabularNumerals(this)
                            setPadding(dp(6), dp(2), dp(6), dp(2))
                            background = GradientDrawable().apply {
                                setColor(p.amberSoft)
                                cornerRadius = dp(6).toFloat()
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginStart = dp(6) }
                        }
                        nameRow.addView(tvTitle)
                        nameRow.addView(tvGps)
                        val tvSub = TextView(activity).apply {
                            textSize = 12f
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }
                        col.addView(nameRow)
                        col.addView(tvSub)
                        val tvDist = TextView(activity).apply {
                            textSize = 12f
                            setTextColor(p.accent)
                            setTypeface(null, Typeface.BOLD)
                            DesignSystem.applyTabularNumerals(this)
                        }
                        row.addView(badge)
                        row.addView(dot)
                        row.addView(col)
                        row.addView(tvDist)
                        wrap.addView(divider)
                        wrap.addView(row)
                        PinVH(wrap, divider, row, badge, dot, tvTitle, tvGps, tvSub, tvDist)
                    }
                }
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                when (val item = getItem(position)) {
                    is PinListRowItem.MessageRow -> (holder as MsgVH).tv.text = item.text
                    is PinListRowItem.ShareCodeRow -> (holder as MsgVH).tv.text = "Share Code: ${item.shareCode}"
                    is PinListRowItem.PinRow -> {
                        val vh = holder as PinVH
                        val pin = item.pin
                        vh.divider.visibility = if (item.isFirst) View.GONE else View.VISIBLE
                        if (item.selectionMode && item.isSelected) {
                            vh.clickRow.setBackgroundColor(p.accentSoft)
                        } else {
                            UiAnimations.applyRowRipple(activity, vh.clickRow, night)
                        }
                        if (item.selectionMode) {
                            vh.dot.visibility = View.GONE
                            vh.checkBadge.visibility = View.VISIBLE
                            vh.checkBadge.text = if (item.isSelected) "✓" else ""
                            vh.checkBadge.background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                if (item.isSelected) {
                                    setColor(p.accent)
                                } else {
                                    setColor(Color.TRANSPARENT)
                                    setStroke(dp(2), p.iconMuted)
                                }
                            }
                        } else {
                            vh.checkBadge.visibility = View.GONE
                            vh.dot.visibility = View.VISIBLE
                            vh.dot.background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor(statusColorHex(pin.status)))
                            }
                        }
                        vh.tvTitle.text = DesignSystem.isolateBidi(pin.name)
                        if (item.sameCount >= 2) {
                            vh.tvGpsBadge.text = "${item.sameCount}× GPS"
                            vh.tvGpsBadge.visibility = View.VISIBLE
                        } else {
                            vh.tvGpsBadge.visibility = View.GONE
                        }
                        val st = PinStatus.fromRaw(pin.status)
                        val cleanNote = DailyReportGenerator.flattenSingleLine(pin.issueReason)
                        val subParts = mutableListOf(st.label)
                        if (st == PinStatus.ISSUE && cleanNote.isNotBlank()) subParts += "⚠ $cleanNote"
                        else if (st == PinStatus.DONE && cleanNote.isNotBlank()) subParts += "✓ $cleanNote"
                        if (pin.isEdited && pin.editedBy.isNotBlank()) subParts += "Edited by ${pin.editedBy}"
                        vh.tvSub.text = DesignSystem.joinBidi(*subParts.toTypedArray())
                        vh.tvSub.setTextColor(
                            when {
                                st == PinStatus.ISSUE -> p.red
                                st == PinStatus.DONE && cleanNote.isNotBlank() -> p.green
                                else -> txtSub
                            }
                        )
                        if (item.distLabel != null) {
                            vh.tvDist.text = item.distLabel
                            vh.tvDist.visibility = View.VISIBLE
                        } else {
                            vh.tvDist.text = ""
                            vh.tvDist.visibility = View.GONE
                        }
                        vh.clickRow.setOnClickListener {
                            if (selectionMode) {
                                if (!selectedPinIds.add(pin.id)) selectedPinIds.remove(pin.id)
                                updateSelectionBarState()
                                rebuildSubmittedRows()
                            } else {
                                ensurePinVisibleOnMap(pin)
                                closeFullPage()
                                selectPinAndOpenCard(pin, true)
                            }
                        }
                        vh.clickRow.setOnLongClickListener {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedPinIds.add(pin.id)
                                updateSelectionBarState()
                                rebuildSubmittedRows()
                                true
                            } else false
                        }
                    }
                }
            }
        }
        recycler.adapter = pinAdapter
        pinAdapter.submitList(listOf(PinListRowItem.MessageRow(AppStrings.loadingBookmarks(activity))))

        rebuildSubmittedRows = {
            if (latestSortedPins.isEmpty()) {
                pinAdapter.submitList(listOf(PinListRowItem.MessageRow(AppStrings.noBookmarksMatchFilter(activity))))
            } else {
                val rows = ArrayList<PinListRowItem>(latestSortedPins.size + 1)
                latestSortedPins.forEachIndexed { idx, pin ->
                    rows.add(
                        PinListRowItem.PinRow(
                            pin = pin,
                            isFirst = idx == 0,
                            isLast = idx == latestSortedPins.lastIndex,
                            selectionMode = selectionMode,
                            isSelected = pin.id in selectedPinIds,
                            sameCount = cachedClusterSizes[pin.id] ?: 0,
                            distLabel = latestDistLabelById[pin.id]
                        )
                    )
                }
                if (layer != null && layer.shareCode.isNotBlank()) {
                    rows.add(PinListRowItem.ShareCodeRow(layer.shareCode))
                }
                pinAdapter.submitList(rows)
            }
        }

        fun renderPinRows() {
            val mySeq = ++renderSeq
            btnSort.text = if (sortNearest) "Nearest ↓" else "A–Z ↓"
            filterBtns.forEach { (k, b) ->
                val active = k == filterKey
                DesignSystem.styleSegmentedButton(b, active, p)
            }

            val loc = getCurrentLocation()
            val unit = getCachedSpeedUnit()
            val curFilterKey = filterKey
            val curSortNearest = sortNearest
            val curSelectionMode = selectionMode
            val curSelectedSnap = if (curSelectionMode) selectedPinIds.toSet() else emptySet()
            val allInFolder = currentSourcePins().toList()
            val curKey = curFilterKey to viewModel.folderSnapshot.dataVersion
            val prevCachedOk = cachedAnalysisKey == curKey
            val prevDupExtras = cachedDupExtras
            val prevSameExtras = cachedSameCoordExtras
            val prevClusterSizes = cachedClusterSizes

            AppExecutors.uiQueryExecutor.execute {
                val filtered = if (curFilterKey == "all") {
                    allInFolder
                } else {
                    allInFolder.filter { PinStatus.fromRaw(it.status).key == curFilterKey }
                }
                val dupExtras: Set<String>
                val sameExtras: Set<String>
                val clusterSizes: Map<String, Int>
                if (prevCachedOk) {
                    dupExtras = prevDupExtras
                    sameExtras = prevSameExtras
                    clusterSizes = prevClusterSizes
                } else {
                    dupExtras = findDuplicateExtraIds(filtered)
                    val (sExtras, cSizes) = analyzeSameCoordClusters(filtered)
                    sameExtras = sExtras
                    clusterSizes = cSizes
                }

                val useNearest = curSortNearest && loc != null
                val sortedPins: List<PinItem>
                val distLabelById: Map<String, String>
                val nativeSorted = RustCoreBridge.sortAndScorePins(
                    pins = filtered,
                    selectionMode = curSelectionMode,
                    selectedIds = curSelectedSnap,
                    loc = loc,
                    useNearest = useNearest,
                    unit = unit
                )
                if (nativeSorted != null) {
                    sortedPins = nativeSorted.first
                    distLabelById = nativeSorted.second
                } else if (loc != null) {
                    val uLat = loc.latitude
                    val uLng = loc.longitude
                    val scored = ArrayList<Pair<PinItem, Double>>(filtered.size)
                    for (pin in filtered) {
                        scored.add(pin to RoutingEngine.haversineMeters(uLat, uLng, pin.lat, pin.lng))
                    }
                    if (useNearest) {
                        scored.sortWith(
                            compareBy<Pair<PinItem, Double>> {
                                if (curSelectionMode && curSelectedSnap.isNotEmpty() && it.first.id in curSelectedSnap) 0 else 1
                            }.thenBy { it.second }
                        )
                    } else {
                        scored.sortWith(
                            compareBy<Pair<PinItem, Double>> {
                                if (curSelectionMode && curSelectedSnap.isNotEmpty() && it.first.id in curSelectedSnap) 0 else 1
                            }.thenBy { it.first.name.lowercase(Locale.ROOT) }
                        )
                    }
                    sortedPins = scored.map { it.first }
                    val dMap = HashMap<String, String>(scored.size)
                    for ((pin, distM) in scored) {
                        dMap[pin.id] = formatDistanceShort(distM, unit)
                    }
                    distLabelById = dMap
                } else {
                    sortedPins = filtered.sortedWith(
                        compareBy<PinItem> {
                            if (curSelectionMode && curSelectedSnap.isNotEmpty() && it.id in curSelectedSnap) 0 else 1
                        }.thenBy { it.name.lowercase(Locale.ROOT) }
                    )
                    distLabelById = emptyMap()
                }

                AppExecutors.postMain {
                    if (!isActivityAlive() || mySeq != renderSeq) return@postMain
                    latestFiltered = filtered
                    latestSortedPins = sortedPins
                    latestDistLabelById = distLabelById
                    if (!prevCachedOk) {
                        cachedAnalysisKey = curKey
                        cachedDupExtras = dupExtras
                        cachedSameCoordExtras = sameExtras
                        cachedClusterSizes = clusterSizes
                    }
                    updateSelectionBarState()
                    rebuildSubmittedRows()
                }
            }
        }

        btnSelectMode.setOnClickListener {
            selectionMode = !selectionMode
            if (!selectionMode) selectedPinIds.clear()
            renderPinRows()
        }
        btnAutoDuplicates.setOnClickListener {
            val curKey = filterKey to viewModel.folderSnapshot.dataVersion
            fun applyDup(extras: Set<String>) {
                selectedPinIds.clear()
                selectedPinIds.addAll(extras)
                renderPinRows()
                val msg = if (extras.isEmpty()) {
                    "No duplicate bookmarks found in this folder"
                } else {
                    "Auto-selected ${extras.size} duplicate cop${if (extras.size == 1) "y" else "ies"} (kept 1 primary per group)"
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
            if (cachedAnalysisKey == curKey) {
                applyDup(cachedDupExtras)
            } else {
                val myASeq = ++analysisSeq
                val listSnap = if (latestFiltered.isNotEmpty()) {
                    latestFiltered
                } else {
                    val fk = filterKey
                    currentSourcePins().filter { fk == "all" || PinStatus.fromRaw(it.status).key == fk }
                }
                AppExecutors.uiQueryExecutor.execute {
                    val dup = findDuplicateExtraIds(listSnap)
                    val (same, sizes) = analyzeSameCoordClusters(listSnap)
                    AppExecutors.postMain {
                        if (!isActivityAlive() || myASeq != analysisSeq) return@postMain
                        cachedAnalysisKey = curKey
                        cachedDupExtras = dup
                        cachedSameCoordExtras = same
                        cachedClusterSizes = sizes
                        applyDup(dup)
                    }
                }
            }
        }
        btnAutoSameCoords.setOnClickListener {
            val curKey = filterKey to viewModel.folderSnapshot.dataVersion
            fun applySame(extras: Set<String>) {
                selectedPinIds.clear()
                selectedPinIds.addAll(extras)
                renderPinRows()
                val msg = if (extras.isEmpty()) {
                    "No bookmarks sharing the same coordinates found"
                } else {
                    "Auto-selected ${extras.size} extra bookmark${if (extras.size == 1) "" else "s"} at same coordinates (kept 1 primary per spot)"
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
            if (cachedAnalysisKey == curKey) {
                applySame(cachedSameCoordExtras)
            } else {
                val myASeq = ++analysisSeq
                val listSnap = if (latestFiltered.isNotEmpty()) {
                    latestFiltered
                } else {
                    val fk = filterKey
                    currentSourcePins().filter { fk == "all" || PinStatus.fromRaw(it.status).key == fk }
                }
                AppExecutors.uiQueryExecutor.execute {
                    val dup = findDuplicateExtraIds(listSnap)
                    val (same, sizes) = analyzeSameCoordClusters(listSnap)
                    AppExecutors.postMain {
                        if (!isActivityAlive() || myASeq != analysisSeq) return@postMain
                        cachedAnalysisKey = curKey
                        cachedDupExtras = dup
                        cachedSameCoordExtras = same
                        cachedClusterSizes = sizes
                        applySame(same)
                    }
                }
            }
        }
        btnSelectAll.setOnClickListener {
            selectedPinIds.clear()
            selectedPinIds.addAll(latestFiltered.map { it.id })
            renderPinRows()
        }
        btnClearSel.setOnClickListener {
            selectedPinIds.clear()
            renderPinRows()
        }

        btnSelZoom.setOnClickListener {
            val chosen = currentSourcePins().filter { it.id in selectedPinIds }
            if (chosen.isEmpty()) return@setOnClickListener
            closeFullPage()
            zoomToKmlBounds(collectLayerLatLngs(chosen, emptyList()), layer?.id)
        }
        btnSelExport.setOnClickListener {
            val chosen = currentSourcePins().filter { it.id in selectedPinIds }
            if (chosen.isEmpty()) return@setOnClickListener
            val exportLayer = layer ?: LayerItem(id = "selected", name = title)
            KmlCsvExporter.exportAndShareKml(activity, exportLayer, chosen, emptyList(), "Selected_${chosen.size}")
        }
        btnSelStatus.setOnClickListener {
            val chosen = currentSourcePins().filter { it.id in selectedPinIds }
            if (chosen.isEmpty()) return@setOnClickListener
            fun applyBulk(action: () -> Unit) {
                action()
                selectedPinIds.clear()
                selectionMode = false
                renderPinRows()
                Toast.makeText(activity, "Updated ${chosen.size} bookmark${if (chosen.size == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
            }
            val stamp = DevicePrefs.getDeviceStamp(activity)
            DesignSystem.showActionBottomSheet(
                ctx = activity,
                palette = p,
                title = "Update ${chosen.size} Selected",
                subtitle = "Apply status across selected bookmarks",
                actions = listOf(
                    SheetActionItem("Mark Pending", R.drawable.ic_location_pin_glyph, tintOverride = p.amber) {
                        applyBulk { repository.savePinsStatusBatch(chosen, "pending", stamp, "", notifySyncError) }
                    },
                    SheetActionItem("Mark Done", R.drawable.ic_check, tintOverride = p.green) {
                        applyBulk { repository.savePinsStatusBatch(chosen, "done", stamp, "", notifySyncError) }
                    },
                    SheetActionItem("Mark Issue", R.drawable.ic_info, tintOverride = p.red) {
                        applyBulk { repository.savePinsStatusBatch(chosen, "issue", stamp, "Flagged in bulk", notifySyncError) }
                    },
                    SheetActionItem("Mark Sent (This Phone)", R.drawable.ic_cloud_sync, tintOverride = p.accentPrimary) {
                        applyBulk {
                            DevicePrefs.addLocalSentPinIds(activity, chosen.map { it.id })
                            updateSentToggleUi()
                            renderMapData()
                        }
                    }
                )
            )
        }
        btnSelDelete.setOnClickListener {
            val layerMap = viewModel.folderSnapshot.layerById
            val chosen = currentSourcePins()
                .filter { it.id in selectedPinIds }
                .filter { pin -> repository.isLayerOwner(layerMap[pin.layerId]) }
            if (chosen.isEmpty()) {
                Toast.makeText(activity, "Only folder owners can delete cloud bookmarks", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val scopeMsg = if (layer != null && !LocalFolderStore.isLocalLayerId(layer.id)) {
                "Permanently delete ${chosen.size} selected bookmark${if (chosen.size == 1) "" else "s"} from this cloud folder for all team members?"
            } else {
                "Delete ${chosen.size} selected bookmark${if (chosen.size == 1) "" else "s"} from this phone?"
            }
            showThemedDialog(
                themedDialogBuilder("Delete ${chosen.size} Bookmark${if (chosen.size == 1) "" else "s"}?")
                    .setMessage(scopeMsg)
                    .setPositiveButton("Delete") { _, _ ->
                        repository.deletePins(chosen, notifySyncError)
                        selectedPinIds.clear()
                        selectionMode = false
                        renderPinRows()
                        Toast.makeText(activity, "Deleted ${chosen.size} bookmark${if (chosen.size == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .create(),
                true
            )
        }

        btnSort.setOnClickListener {
            sortNearest = !sortNearest
            renderPinRows()
        }
        filterBtns.forEach { (k, b) ->
            b.setOnClickListener { filterKey = k; renderPinRows() }
        }

        setLivePinListRefresh { renderPinRows() }
        renderPinRows()
        return outer
    }
}
