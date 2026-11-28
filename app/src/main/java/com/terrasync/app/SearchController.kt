package com.terrasync.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.maplibre.android.geometry.LatLng
import java.util.Locale

data class SearchIndexEntry(
    val pin: PinItem,
    val searchBlob: String,
    val folderName: String,
    val statusTag: String,
    val dotColor: Int,
    val coordsText: String
)

sealed interface SearchRowItem {
    val stableId: String
    data class HintAndChips(val langKey: String) : SearchRowItem {
        override val stableId: String = "hint_chips"
    }
    data class WebStatus(val message: String, val showRetry: Boolean) : SearchRowItem {
        override val stableId: String = "web_status"
    }
    data class Header(val title: String) : SearchRowItem {
        override val stableId: String = "hdr:$title"
    }
    data class WebPlace(val place: PlaceResult, val isFirst: Boolean, val isLast: Boolean) : SearchRowItem {
        override val stableId: String = "web:${place.lat},${place.lng}:${place.title}"
    }
    data class CoordJump(val lat: Double, val lng: Double) : SearchRowItem {
        override val stableId: String = "coord:$lat,$lng"
    }
    data class BookmarkMatch(
        val entry: SearchIndexEntry,
        val distText: String?,
        val showFolderInSub: Boolean,
        val isFirst: Boolean,
        val isLast: Boolean
    ) : SearchRowItem {
        override val stableId: String = "pin:${entry.pin.id}"
    }
}

class BookmarkSearchAdapter(
    private val ctx: Context,
    private val palette: ThemePalette,
    private val dp: (Int) -> Int,
    private val closeFullPage: () -> Unit,
    private val ensurePinVisibleOnMap: (PinItem) -> Unit,
    private val selectPinAndOpenCard: (PinItem, Boolean) -> Unit,
    private val selectCoordinateOnMap: (LatLng, String?, String, Double?) -> Unit,
    private val onQuickFilter: ((String) -> Unit)? = null,
    private val onRetryWebSearch: (() -> Unit)? = null
) : ListAdapter<SearchRowItem, RecyclerView.ViewHolder>(
    object : DiffUtil.ItemCallback<SearchRowItem>() {
        override fun areItemsTheSame(oldItem: SearchRowItem, newItem: SearchRowItem) =
            oldItem.stableId == newItem.stableId
        override fun areContentsTheSame(oldItem: SearchRowItem, newItem: SearchRowItem) =
            oldItem == newItem
    }
) {
    private inner class SimpleVH(val view: View) : RecyclerView.ViewHolder(view)

    private inner class BookmarkVH(
        val cardWrap: LinearLayout,
        val topDivider: View,
        val clickRow: LinearLayout,
        val dot: View,
        val tvTitle: TextView,
        val tvSub: TextView,
        val tvDist: TextView
    ) : RecyclerView.ViewHolder(cardWrap)

    private inner class WebPlaceVH(
        val cardWrap: LinearLayout,
        val topDivider: View,
        val clickRow: LinearLayout,
        val tvTitle: TextView,
        val tvSub: TextView
    ) : RecyclerView.ViewHolder(cardWrap)

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchRowItem.HintAndChips -> 0
        is SearchRowItem.WebStatus -> 1
        is SearchRowItem.Header -> 2
        is SearchRowItem.WebPlace -> 3
        is SearchRowItem.CoordJump -> 4
        is SearchRowItem.BookmarkMatch -> 5
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            0 -> {
                val card = DesignSystem.createGroupedCard(ctx, palette, radiusDp = 16f, paddingHDp = 14, paddingVDp = 14)
                card.addView(TextView(ctx).apply {
                    text = AppStrings.searchHintPrompt(ctx)
                    textSize = 13.5f
                    setLineSpacing(dp(2).toFloat(), 1.05f)
                    setTextColor(palette.textBody)
                })
                val chipRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                }
                fun makeChip(label: String, actionKey: String, accent: Int, marginEndDp: Int): MaterialButton = MaterialButton(ctx).apply {
                    text = label
                    textSize = 12f
                    isAllCaps = false
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(dp(10), 0, dp(10), 0)
                    cornerRadius = dp(12)
                    DesignSystem.styleSegmentedButton(this, false, palette, accent, onTonalCard = true)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                        if (marginEndDp > 0) marginEnd = dp(marginEndDp)
                    }
                    UiAnimations.attachSnappyPress(this)
                    setOnClickListener { onQuickFilter?.invoke(actionKey) }
                }
                chipRow.addView(makeChip(AppStrings.pending(ctx), "pending", palette.amber, 6))
                chipRow.addView(makeChip(AppStrings.issue(ctx), "issue", palette.red, 0))
                card.addView(chipRow)
                SimpleVH(card)
            }
            1 -> {
                val card = DesignSystem.createGroupedCard(ctx, palette, radiusDp = 16f, paddingHDp = 14, paddingVDp = 14, bottomMarginDp = 10)
                card.orientation = LinearLayout.HORIZONTAL
                card.gravity = Gravity.CENTER_VERTICAL
                val tvMsg = TextView(ctx).apply {
                    tag = "msg"
                    textSize = 13.5f
                    setTextColor(palette.textBody)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8)
                    }
                }
                val btnRetry = MaterialButton(ctx).apply {
                    tag = "retry"
                    text = AppStrings.retry(ctx)
                    textSize = 12f
                    isAllCaps = false
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(dp(12), 0, dp(12), 0)
                    cornerRadius = dp(12)
                    DesignSystem.applyPrimaryCtaButton(this, palette)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                    UiAnimations.attachSnappyPress(this)
                    setOnClickListener { onRetryWebSearch?.invoke() }
                }
                card.addView(tvMsg)
                card.addView(btnRetry)
                SimpleVH(card)
            }
            2 -> {
                val tv = DesignSystem.createSectionHeader(ctx, "", palette)
                tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                SimpleVH(tv)
            }
            3 -> {
                val cardWrap = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    clipToOutline = true
                }
                val topDivider = DesignSystem.createHairlineDivider(ctx, palette, 16, 16)
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    UiAnimations.applyRowRipple(ctx, this, palette.isDark)
                }
                val tvTitle = TextView(ctx).apply {
                    textSize = 14.5f
                    setTextColor(palette.textPrimary)
                    setTypeface(null, Typeface.BOLD)
                }
                val tvSub = TextView(ctx).apply {
                    textSize = 12f
                    setTextColor(palette.textSecondary)
                }
                row.addView(tvTitle)
                row.addView(tvSub)
                cardWrap.addView(topDivider)
                cardWrap.addView(row)
                WebPlaceVH(cardWrap, topDivider, row, tvTitle, tvSub)
            }
            4 -> {
                val card = DesignSystem.createGroupedCard(ctx, palette, radiusDp = 16f, paddingHDp = 14, paddingVDp = 12, bottomMarginDp = 8)
                val tv = TextView(ctx).apply {
                    tag = "coord"
                    textSize = 14.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(palette.accentPrimary)
                    DesignSystem.applyTabularNumerals(this)
                    UiAnimations.applyRowRipple(ctx, this, palette.isDark)
                }
                card.addView(tv)
                SimpleVH(card)
            }
            else -> {
                val cardWrap = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    clipToOutline = true
                }
                val topDivider = DesignSystem.createHairlineDivider(ctx, palette, 16, 16)
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(52)
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    UiAnimations.applyRowRipple(ctx, this, palette.isDark)
                }
                val dot = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) }
                }
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8)
                    }
                }
                val tvTitle = TextView(ctx).apply {
                    textSize = 14.5f
                    setTextColor(palette.textPrimary)
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                val tvSub = TextView(ctx).apply {
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                col.addView(tvTitle)
                col.addView(tvSub)
                val tvDist = TextView(ctx).apply {
                    textSize = 12f
                    setTextColor(palette.accentPrimary)
                    setTypeface(null, Typeface.BOLD)
                    setSingleLine(true)
                    DesignSystem.applyTabularNumerals(this)
                }
                row.addView(dot)
                row.addView(col)
                row.addView(tvDist)
                cardWrap.addView(topDivider)
                cardWrap.addView(row)
                BookmarkVH(cardWrap, topDivider, row, dot, tvTitle, tvSub, tvDist)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchRowItem.HintAndChips -> Unit
            is SearchRowItem.WebStatus -> {
                val v = (holder as SimpleVH).view
                v.findViewWithTag<TextView>("msg")?.text = item.message
                v.findViewWithTag<MaterialButton>("retry")?.visibility = if (item.showRetry) View.VISIBLE else View.GONE
            }
            is SearchRowItem.Header -> {
                ((holder as SimpleVH).view as TextView).text = item.title
            }
            is SearchRowItem.WebPlace -> {
                val vh = holder as WebPlaceVH
                vh.topDivider.visibility = if (item.isFirst) View.GONE else View.VISIBLE
                vh.cardWrap.background = DesignSystem.createGroupedRowBackground(ctx, palette, item.isFirst, item.isLast, 18f)
                vh.tvTitle.text = "🌐 ${DesignSystem.isolateBidi(item.place.title)}"
                if (item.place.subtitle.isNotBlank()) {
                    vh.tvSub.text = DesignSystem.isolateBidi(item.place.subtitle)
                    vh.tvSub.visibility = View.VISIBLE
                } else {
                    vh.tvSub.text = ""
                    vh.tvSub.visibility = View.GONE
                }
                vh.clickRow.setOnClickListener {
                    closeFullPage()
                    selectCoordinateOnMap(LatLng(item.place.lat, item.place.lng), item.place.title, item.place.subtitle, 16.0)
                }
            }
            is SearchRowItem.CoordJump -> {
                val tv = (holder as SimpleVH).view.findViewWithTag<TextView>("coord") ?: return
                tv.text = "📍 ${AppStrings.goToCoordinates(ctx)}: ${"%.6f".format(Locale.US, item.lat)}, ${"%.6f".format(Locale.US, item.lng)}"
                tv.setOnClickListener {
                    closeFullPage()
                    selectCoordinateOnMap(LatLng(item.lat, item.lng), "Coordinates", "", 16.5)
                }
            }
            is SearchRowItem.BookmarkMatch -> {
                val vh = holder as BookmarkVH
                val pin = item.entry.pin
                vh.topDivider.visibility = if (item.isFirst) View.GONE else View.VISIBLE
                vh.cardWrap.background = DesignSystem.createGroupedRowBackground(ctx, palette, item.isFirst, item.isLast, 16f)
                vh.dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(item.entry.dotColor)
                }
                vh.tvTitle.text = DesignSystem.isolateBidi(pin.name)
                val st = PinStatus.fromRaw(pin.status)
                vh.tvSub.text = if (item.showFolderInSub && item.entry.folderName.isNotBlank()) {
                    DesignSystem.joinBidi(item.entry.folderName, item.entry.statusTag)
                } else {
                    item.entry.statusTag
                }
                vh.tvSub.setTextColor(
                    when {
                        st == PinStatus.ISSUE -> palette.red
                        st == PinStatus.DONE && pin.issueReason.isNotBlank() -> palette.green
                        else -> palette.textSecondary
                    }
                )
                if (item.distText != null) {
                    vh.tvDist.text = item.distText
                    vh.tvDist.visibility = View.VISIBLE
                } else {
                    vh.tvDist.text = ""
                    vh.tvDist.visibility = View.GONE
                }
                vh.clickRow.setOnClickListener {
                    ensurePinVisibleOnMap(pin)
                    closeFullPage()
                    selectPinAndOpenCard(pin, true)
                }
            }
        }
    }
}

/**
 * Owns version-cached pin search indexing, background token/coordinate filtering,
 * Nominatim map-place lookup, nearest-pending jump, and full-screen Search page construction.
 */
class SearchController(
    private val activity: AppCompatActivity,
    private val viewModel: MapViewModel,
    private val gpsController: GpsLocationController,
    private val dp: (Int) -> Int,
    private val currentPalette: () -> ThemePalette,
    private val getCachedSpeedUnit: () -> SpeedUnit,
    private val isActivityAlive: () -> Boolean,
    private val hideSoftKeyboard: () -> Unit,
    private val makePageFrame: (String, () -> Unit, View?, Boolean) -> Pair<LinearLayout, LinearLayout>,
    private val closeFullPage: () -> Unit,
    private val setActivePageCleanup: ((() -> Unit)?) -> Unit,
    private val launchLocationPermission: () -> Unit,
    private val ensurePinVisibleOnMap: (PinItem) -> Unit,
    private val selectPinAndOpenCard: (PinItem, Boolean) -> Unit,
    private val selectCoordinateOnMap: (LatLng, String?, String, Double?) -> Unit,
    private val getMapCenter: () -> LatLng?
) {

    private var webSearchSeq = 0
    private val doneDotColor by lazy { Color.parseColor(PinStatus.DONE.colorHex) }
    private val issueDotColor by lazy { Color.parseColor(PinStatus.ISSUE.colorHex) }
    private val pendingDotColor by lazy { Color.parseColor(PinStatus.PENDING.colorHex) }

    private fun buildSearchEntry(pin: PinItem, folderNames: Map<String, String>): SearchIndexEntry {
        val fName = folderNames[pin.layerId] ?: "Folder"
        val st = PinStatus.fromRaw(pin.status)
        val cleanNote = DailyReportGenerator.flattenSingleLine(pin.issueReason)
        val statusTag = if ((st == PinStatus.ISSUE || st == PinStatus.DONE) && cleanNote.isNotBlank()) {
            "${st.label}: $cleanNote"
        } else {
            st.label
        }
        val dotColor = when (st) {
            PinStatus.DONE -> doneDotColor
            PinStatus.ISSUE -> issueDotColor
            PinStatus.PENDING -> pendingDotColor
        }
        val coordsText = "${"%.6f".format(Locale.US, pin.lat)}, ${"%.6f".format(Locale.US, pin.lng)}"
        return SearchIndexEntry(
            pin = pin,
            searchBlob = "",
            folderName = fName,
            statusTag = statusTag,
            dotColor = dotColor,
            coordsText = coordsText
        )
    }

    fun searchPinsUnified(
        ver: Long,
        pinsSnap: List<PinItem>,
        folderNames: Map<String, String>,
        tokens: List<String>,
        hasDigits: Boolean,
        loc: Location?,
        unit: SpeedUnit
    ): List<Pair<SearchIndexEntry, String?>> {
        if (tokens.isEmpty() || pinsSnap.isEmpty()) return emptyList()
        val nativeRes = RustCoreBridge.queryUnifiedPinSearch(
            version = ver,
            pins = pinsSnap,
            folderNames = folderNames,
            tokens = tokens,
            hasDigits = hasDigits,
            hasLoc = loc != null,
            locLat = loc?.latitude ?: 0.0,
            locLng = loc?.longitude ?: 0.0
        )
        if (nativeRes != null) {
            val (indices, dists) = nativeRes
            val out = ArrayList<Pair<SearchIndexEntry, String?>>(indices.size)
            for (i in indices.indices) {
                val pin = pinsSnap.getOrNull(indices[i]) ?: continue
                val entry = buildSearchEntry(pin, folderNames)
                val distText = if (dists != null) formatDistanceShort(dists[i], unit) else null
                out.add(entry to distText)
            }
            return out
        }
        val out = ArrayList<Pair<SearchIndexEntry, String?>>()
        for (pin in pinsSnap) {
            val fName = folderNames[pin.layerId] ?: "Folder"
            val st = PinStatus.fromRaw(pin.status)
            val blob = buildString(
                pin.name.length + pin.description.length + pin.issueReason.length + st.label.length + fName.length + 8
            ) {
                append(pin.name).append(' ')
                if (pin.description.isNotBlank()) append(pin.description).append(' ')
                if (pin.issueReason.isNotBlank()) append(pin.issueReason).append(' ')
                append(st.label).append(' ').append(fName)
            }.lowercase(Locale.ROOT)
            val coordsText = if (hasDigits) {
                "${"%.6f".format(Locale.US, pin.lat)}, ${"%.6f".format(Locale.US, pin.lng)}"
            } else {
                ""
            }
            if (tokens.all { tok -> tok in blob || (hasDigits && tok in coordsText) }) {
                val entry = buildSearchEntry(pin, folderNames)
                val dist = if (loc != null) {
                    formatDistanceShort(
                        RoutingEngine.haversineMeters(loc.latitude, loc.longitude, pin.lat, pin.lng),
                        unit
                    )
                } else null
                out.add(entry to dist)
            }
        }
        return out
    }

    fun createSearchAdapter(
        palette: ThemePalette,
        onQuickFilter: ((String) -> Unit)? = null,
        onRetryWebSearch: (() -> Unit)? = null
    ): BookmarkSearchAdapter = BookmarkSearchAdapter(
        ctx = activity,
        palette = palette,
        dp = dp,
        closeFullPage = closeFullPage,
        ensurePinVisibleOnMap = ensurePinVisibleOnMap,
        selectPinAndOpenCard = selectPinAndOpenCard,
        selectCoordinateOnMap = selectCoordinateOnMap,
        onQuickFilter = onQuickFilter,
        onRetryWebSearch = onRetryWebSearch
    )

    fun jumpToNearestPendingPin() {
        if (!gpsController.hasLocationPermission()) {
            launchLocationPermission()
            return
        }
        if (!gpsController.ensureLocationServiceOn()) return
        val loc = gpsController.currentLocation
        if (loc == null) {
            Toast.makeText(activity, "Waiting for GPS fix first", Toast.LENGTH_SHORT).show()
            return
        }
        val snap = viewModel.folderSnapshot
        val pinsSnap = snap.pins
        val hidden = DevicePrefs.getHiddenLayerIds(activity)
        val sentIds = if (viewModel.hideSentPins) DevicePrefs.getLocalSentPinIds(activity) else emptySet()
        val nativeHit = RustCoreBridge.findNearestPin(
            version = snap.dataVersion,
            pins = pinsSnap,
            statusMask = 1 shl 2,
            hiddenLayerIds = hidden,
            sentIds = sentIds,
            lat = loc.latitude,
            lng = loc.longitude
        )
        val nearest = if (nativeHit != null) {
            val idx = nativeHit.first
            if (idx in pinsSnap.indices) pinsSnap[idx] else null
        } else {
            var best: PinItem? = null
            var bestDist = Double.POSITIVE_INFINITY
            for (pin in pinsSnap) {
                if (pin.layerId in hidden || pin.id in sentIds) continue
                if (PinStatus.fromRaw(pin.status) != PinStatus.PENDING) continue
                if (bestDist.isFinite() && kotlin.math.abs(pin.lat - loc.latitude) * 110_000.0 >= bestDist) continue
                val d = RoutingEngine.haversineMeters(loc.latitude, loc.longitude, pin.lat, pin.lng)
                if (d < bestDist) {
                    bestDist = d
                    best = pin
                }
            }
            best
        }
        if (nearest == null) {
            val msg = if (viewModel.hideSentPins) "No unsent pending pins in visible folders!" else "No pending pins in visible folders!"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            return
        }
        closeFullPage()
        selectPinAndOpenCard(nearest, true)
    }

    fun buildSearchPage(): View {
        val p = currentPalette()
        val night = p.isDark
        val txtPrimary = p.textPrimary
        val txtSub = p.textSecondary

        val (outer, body) = makePageFrame(AppStrings.search(activity), { closeFullPage() }, null, false)

        val searchBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            background = DesignSystem.buildOpaqueDrawable(
                ctx = activity,
                fillColor = p.surfaceTonal,
                radiusDp = 24f,
                strokeColor = p.borderSubtle,
                strokeDp = 1f
            )
            clipToOutline = true
        }
        searchBox.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(txtSub)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
        })
        val etQuery = EditText(activity).apply {
            hint = AppStrings.searchPlaceholder(activity)
            textSize = 14.5f
            setTextColor(txtPrimary)
            setHintTextColor(txtSub)
            setBackgroundColor(Color.TRANSPARENT)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val btnClearQuery = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(txtSub)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            UiAnimations.applyRowRipple(activity, this, night)
            setOnClickListener { etQuery.setText("") }
        }
        val btnPasteJump = TextView(activity).apply {
            text = "Paste"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(p.accentPrimary)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32)
            ).apply { marginStart = dp(4) }
            background = DesignSystem.buildOpaqueDrawable(
                ctx = activity,
                fillColor = p.surfaceFloating,
                radiusDp = 16f,
                strokeColor = p.borderSubtle,
                strokeDp = 1f
            )
            clipToOutline = true
            UiAnimations.attachSnappyPress(this, 0.94f)
            setOnClickListener {
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clip = cm?.primaryClip
                val raw = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(activity)?.toString()?.trim().orEmpty()
                } else ""
                if (raw.isBlank()) {
                    Toast.makeText(activity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val normalized = buildString(raw.length) {
                    for (ch in raw) {
                        append(
                            when (ch) {
                                in '\u0660'..'\u0669' -> ('0'.code + (ch.code - 0x0660)).toChar()
                                in '\u06F0'..'\u06F9' -> ('0'.code + (ch.code - 0x06F0)).toChar()
                                else -> ch
                            }
                        )
                    }
                }
                val coordMatch = Regex("""(-?\d{1,2}\.\d{3,})\s*[,\s]+\s*(-?\d{1,3}\.\d{3,})""").find(normalized)
                if (coordMatch != null) {
                    val lat = coordMatch.groupValues[1].toDoubleOrNull()
                    val lng = coordMatch.groupValues[2].toDoubleOrNull()
                    if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                        hideSoftKeyboard()
                        closeFullPage()
                        selectCoordinateOnMap(LatLng(lat, lng), "Copied Location", "", 16.5)
                        return@setOnClickListener
                    }
                }
                val clean = normalized.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(64).orEmpty()
                if (clean.isNotBlank()) {
                    etQuery.setText(clean)
                    etQuery.setSelection(clean.length)
                }
            }
        }
        searchBox.addView(etQuery)
        searchBox.addView(btnPasteJump)
        searchBox.addView(btnClearQuery)
        body.addView(searchBox)

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val btnWebSearch = MaterialButton(activity).apply {
            text = AppStrings.searchMapPlaces(activity)
            textSize = 13.5f
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(22)
            DesignSystem.applyPrimaryCtaButton(this, p)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1.05f).apply { marginEnd = dp(8) }
            UiAnimations.attachSnappyPress(this)
        }
        val btnNearest = MaterialButton(activity).apply {
            text = AppStrings.nearestPending(activity)
            textSize = 13.5f
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            insetTop = 0
            insetBottom = 0
            setTextColor(Color.WHITE)
            setBackgroundColor(p.accentPrimary)
            elevation = 0f
            stateListAnimator = null
            cornerRadius = dp(22)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            UiAnimations.attachSnappyPress(this)
            setOnClickListener { jumpToNearestPendingPin() }
        }
        btnRow.addView(btnWebSearch)
        btnRow.addView(btnNearest)
        body.addView(btnRow)

        var latestWebItems: List<SearchRowItem> = emptyList()
        var latestLocalItems: List<SearchRowItem> = emptyList()
        lateinit var searchAdapter: BookmarkSearchAdapter
        lateinit var triggerWebSearchRef: () -> Unit

        val searchRecycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            itemAnimator = null
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(16))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        searchAdapter = createSearchAdapter(
            palette = p,
            onQuickFilter = { key ->
                if (key == "nearest") {
                    jumpToNearestPendingPin()
                } else {
                    etQuery.setText(key)
                    etQuery.setSelection(key.length)
                }
            },
            onRetryWebSearch = { triggerWebSearchRef() }
        )
        searchRecycler.adapter = searchAdapter
        body.addView(searchRecycler)

        fun publishSearchList(scrollToTop: Boolean = false) {
            val combined = ArrayList<SearchRowItem>(latestWebItems.size + latestLocalItems.size)
            combined.addAll(latestWebItems)
            combined.addAll(latestLocalItems)
            searchAdapter.submitList(combined) {
                if (scrollToTop && combined.isNotEmpty()) searchRecycler.scrollToPosition(0)
            }
        }

        var localSearchSeq = 0

        fun renderLocalMatches(q: String) {
            val mySeq = ++localSearchSeq
            val trimmed = q.trim()
            if (trimmed.isEmpty()) {
                webSearchSeq++
                latestWebItems = emptyList()
                latestLocalItems = listOf(SearchRowItem.HintAndChips(DevicePrefs.getAppLanguage(activity).name))
                publishSearchList(scrollToTop = true)
                return
            }

            val snap = viewModel.folderSnapshot
            val ver = snap.dataVersion
            val pinsSnapshot = snap.pins
            val folderNameById = snap.layers.associate { it.id to it.name }
            val tokens = trimmed.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
            val hasDigits = trimmed.any { it.isDigit() }
            val loc = gpsController.currentLocation
            val unit = getCachedSpeedUnit()
            AppExecutors.uiQueryExecutor.execute {
                val coordParts = trimmed.split(",").map { it.trim().toDoubleOrNull() }
                val parsedCoord: Pair<Double, Double>? = if (coordParts.size == 2 && coordParts[0] != null && coordParts[1] != null) {
                    val lat = coordParts[0]!!
                    val lng = coordParts[1]!!
                    if (lat in -90.0..90.0 && lng in -180.0..180.0) lat to lng else null
                } else null

                val matches = searchPinsUnified(ver, pinsSnapshot, folderNameById, tokens, hasDigits, loc, unit)
                val nextLocal = ArrayList<SearchRowItem>(matches.size + 3)
                if (parsedCoord != null) {
                    nextLocal.add(SearchRowItem.CoordJump(parsedCoord.first, parsedCoord.second))
                }
                if (matches.isNotEmpty()) {
                    nextLocal.add(SearchRowItem.Header(AppStrings.bookmarksCount(activity, matches.size)))
                    matches.forEachIndexed { idx, (entry, dist) ->
                        nextLocal.add(
                            SearchRowItem.BookmarkMatch(
                                entry = entry,
                                distText = dist,
                                showFolderInSub = true,
                                isFirst = idx == 0,
                                isLast = idx == matches.lastIndex
                            )
                        )
                    }
                } else if (parsedCoord == null && latestWebItems.isEmpty()) {
                    nextLocal.add(SearchRowItem.WebStatus(AppStrings.noBookmarksMatchFilter(activity), showRetry = false))
                }

                AppExecutors.postMain {
                    if (!isActivityAlive() || mySeq != localSearchSeq) return@postMain
                    latestLocalItems = nextLocal
                    publishSearchList()
                }
            }
        }

        renderLocalMatches("")
        var searchPageRunnable: Runnable? = null
        etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                btnClearQuery.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                searchPageRunnable?.let { etQuery.removeCallbacks(it) }
                val r = Runnable { renderLocalMatches(q) }
                searchPageRunnable = r
                etQuery.postDelayed(r, if (q.isEmpty()) 0L else 120L)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        fun runWebSearch() {
            val q = etQuery.text.toString().trim()
            if (q.isBlank()) return
            hideSoftKeyboard()
            val seq = ++webSearchSeq
            btnWebSearch.isEnabled = false
            btnWebSearch.text = AppStrings.searching(activity)
            val center = getMapCenter()
            RoutingEngine.searchPlaces(q, center?.latitude, center?.longitude) { places, err ->
                AppExecutors.postMain {
                    if (!isActivityAlive() || seq != webSearchSeq) return@postMain
                    btnWebSearch.isEnabled = true
                    btnWebSearch.text = AppStrings.searchMapPlaces(activity)
                    if (!err.isNullOrBlank()) {
                        latestWebItems = listOf(SearchRowItem.WebStatus(err, showRetry = true))
                        publishSearchList(scrollToTop = true)
                        return@postMain
                    }
                    if (places.isEmpty()) {
                        latestWebItems = listOf(SearchRowItem.WebStatus(AppStrings.noPlacesFound(activity), showRetry = true))
                        publishSearchList(scrollToTop = true)
                        return@postMain
                    }
                    latestWebItems = ArrayList<SearchRowItem>(places.size + 1).apply {
                        add(SearchRowItem.Header(AppStrings.mapPlacesCount(activity, places.size)))
                        places.forEachIndexed { idx, place ->
                            add(SearchRowItem.WebPlace(place, isFirst = idx == 0, isLast = idx == places.lastIndex))
                        }
                    }
                    publishSearchList(scrollToTop = true)
                }
            }
        }
        triggerWebSearchRef = ::runWebSearch

        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runWebSearch()
                true
            } else false
        }
        btnWebSearch.setOnClickListener { runWebSearch() }
        setActivePageCleanup {
            localSearchSeq++
            webSearchSeq++
            RoutingEngine.cancelPendingSearch()
            searchPageRunnable?.let { etQuery.removeCallbacks(it) }
        }

        return outer
    }

    fun destroy() {
        webSearchSeq++
        RoutingEngine.cancelPendingSearch()
    }
}
