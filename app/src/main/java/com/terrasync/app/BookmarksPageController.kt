package com.terrasync.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import org.maplibre.android.geometry.LatLng
import java.util.Locale

/**
 * Owns the full-screen Bookmarks & Folders page (master visibility switches,
 * cross-folder RecyclerView search, pinned protection badges, progress bars, and action sheets).
 */
class BookmarksPageController(
    private val activity: AppCompatActivity,
    private val viewModel: MapViewModel,
    private val repository: TerraSyncRepository,
    private val folderActions: FolderActionsController,
    private val searchController: SearchController,
    private val gpsController: GpsLocationController,
    private val dp: (Int) -> Int,
    private val currentPalette: () -> ThemePalette,
    private val getCachedSpeedUnit: () -> SpeedUnit,
    private val getInsetsLTRB: () -> IntArray,
    private val isActivityAlive: () -> Boolean,
    private val hideSoftKeyboard: () -> Unit,
    private val pushSubPage: (() -> Unit, () -> View) -> Unit,
    private val popSubPage: (() -> View) -> Unit,
    private val closeFullPage: () -> Unit,
    private val setLiveBookmarksRefresh: ((() -> Unit)?) -> Unit,
    private val setActivePageCleanup: ((() -> Unit)?) -> Unit,
    private val launchKmlPicker: () -> Unit,
    private val openPinsListPage: (String, List<PinItem>, LayerItem?) -> View,
    private val openEditHistoryPage: (LayerItem) -> View,
    private val showDailyReport: (LayerItem, List<PinItem>, List<TrackItem>) -> Unit,
    private val renderMapData: () -> Unit,
    private val collectLayerLatLngs: (List<PinItem>, List<TrackItem>) -> List<LatLng>,
    private val zoomToKmlBounds: (List<LatLng>, String?) -> Unit,
    private val refreshJoinedSync: () -> Unit
) {

    fun buildBookmarksAndTracksPage(): View {
        val p = currentPalette()
        val night = p.isDark
        val bg = p.pageBackdrop
        val txtPrimary = p.textPrimary
        val txtSub = p.textSecondary
        val dividerColor = p.divider
        val insets = getInsetsLTRB()

        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (AppStrings.isRtl(activity)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(bg)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(insets[0], insets[1], insets[2], insets[3])
        }

        val appBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(10), dp(6))
        }
        appBar.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(txtPrimary)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            UiAnimations.applyRowRipple(activity, this, night)
            UiAnimations.attachSnappyPress(this)
            setOnClickListener { closeFullPage() }
        })
        appBar.addView(TextView(activity).apply {
            text = AppStrings.bookmarks(activity)
            textSize = 20f
            setTextColor(txtPrimary)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        })
        appBar.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_plus)
            setColorFilter(p.accent)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            UiAnimations.applyRowRipple(activity, this, night)
            UiAnimations.attachSnappyPress(this)
            setOnClickListener {
                val snapNow = viewModel.folderSnapshot
                DesignSystem.showActionBottomSheet(
                    ctx = activity,
                    palette = p,
                    title = "Bookmarks Actions",
                    subtitle = "${snapNow.layers.size} folders • ${snapNow.pins.size} bookmarks",
                    actions = listOf(
                        SheetActionItem(
                            title = "Import KML / KMZ Folder",
                            subtitle = "Load field map file from this phone",
                            iconRes = R.drawable.ic_import_file
                        ) { closeFullPage(); launchKmlPicker() },
                        SheetActionItem(
                            title = "Join Folder by Share Code",
                            subtitle = "Sync a live shared team folder",
                            iconRes = R.drawable.ic_cloud_sync
                        ) { folderActions.showJoinCodeDialog() },
                        SheetActionItem(
                            title = "Combine Folders into One",
                            subtitle = "Merge multiple folders with smart dedup",
                            iconRes = R.drawable.ic_layers
                        ) { folderActions.showCombineFoldersDialog() },
                        SheetActionItem(
                            title = "Daily Report (Visible Folders)",
                            subtitle = "Generate WhatsApp / Telegram field summary",
                            iconRes = R.drawable.ic_report
                        ) { folderActions.showVisibleFoldersDailyReportDialog() },
                        SheetActionItem(
                            title = "Nearest Pending Pin",
                            subtitle = "Jump to closest unvisited location",
                            iconRes = R.drawable.ic_my_location
                        ) { searchController.jumpToNearestPendingPin() }
                    )
                )
            }
        })
        outer.addView(appBar)

        val searchWrap = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(4)
                bottomMargin = dp(10)
            }
            background = DesignSystem.buildOpaqueDrawable(
                ctx = activity,
                fillColor = p.surfaceTonal,
                radiusDp = 24f,
                strokeColor = p.borderSubtle,
                strokeDp = 1f
            )
            clipToOutline = true
        }
        searchWrap.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(txtSub)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
        })
        val etFolderSearch = EditText(activity).apply {
            hint = "Search across all folders (KMLs)..."
            textSize = 14f
            setTextColor(txtPrimary)
            setHintTextColor(txtSub)
            setBackgroundColor(Color.TRANSPARENT)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideSoftKeyboard()
                    true
                } else false
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val btnClearSearch = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(txtSub)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setPadding(dp(7), dp(7), dp(7), dp(7))
            UiAnimations.applyRowRipple(activity, this, night)
            setOnClickListener { etFolderSearch.setText("") }
        }
        searchWrap.addView(etFolderSearch)
        searchWrap.addView(btnClearSearch)
        outer.addView(searchWrap)

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        scroll.addView(body)
        outer.addView(scroll)

        val bmSearchAdapter = searchController.createSearchAdapter(p)
        val searchRecycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = bmSearchAdapter
            itemAnimator = null
            clipToPadding = false
            setPadding(dp(16), dp(4), dp(16), dp(16))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        outer.addView(searchRecycler)

        outer.addView(View(activity).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(10), dp(18), dp(16))
        }

        var suppressAllSwitch = false
        val swShowAll = MaterialSwitch(activity).apply {
            DesignSystem.styleMaterialSwitch(this, p)
        }

        val rowShowAll = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50)
            setPadding(dp(2), dp(4), dp(2), dp(4))
            UiAnimations.applyRowRipple(activity, this, night)
            setOnClickListener { swShowAll.toggle() }
        }
        val showAllCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        showAllCol.addView(TextView(activity).apply {
            text = AppStrings.showAllBookmarks(activity)
            textSize = 14.5f
            setTextColor(txtPrimary)
            setTypeface(null, Typeface.BOLD)
        })
        val tvTotalBookmarksCount = TextView(activity).apply {
            text = "${viewModel.folderSnapshot.pins.size} bookmarks"
            textSize = 12.5f
            setTextColor(txtSub)
            DesignSystem.applyTabularNumerals(this)
        }
        showAllCol.addView(tvTotalBookmarksCount)
        rowShowAll.addView(showAllCol)
        rowShowAll.addView(swShowAll)
        footer.addView(rowShowAll)

        val swShowTitles = MaterialSwitch(activity).apply {
            isChecked = DevicePrefs.isShowBookmarkTitles(activity)
            DesignSystem.styleMaterialSwitch(this, p)
            setOnCheckedChangeListener { _, checked ->
                DevicePrefs.setShowBookmarkTitles(activity, checked)
                renderMapData()
            }
        }
        val rowShowTitles = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            UiAnimations.applyRowRipple(activity, this, night)
            setOnClickListener { swShowTitles.toggle() }
        }
        rowShowTitles.addView(TextView(activity).apply {
            text = AppStrings.showBookmarkTitles(activity)
            textSize = 14.5f
            setTextColor(txtPrimary)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        rowShowTitles.addView(swShowTitles)
        footer.addView(rowShowTitles)
        outer.addView(footer)

        fun syncMasterVisibilitySwitch() {
            val layers = viewModel.folderSnapshot.layers
            val hiddenIds = DevicePrefs.getHiddenLayerIds(activity)
            val allVisible = layers.isNotEmpty() && layers.all { it.id !in hiddenIds }
            suppressAllSwitch = true
            swShowAll.isChecked = allVisible
            suppressAllSwitch = false
        }

        var bookmarkSearchSeq = 0

        fun renderFoldersOrSearch(query: String) {
            val mySeq = ++bookmarkSearchSeq
            val snap = viewModel.folderSnapshot
            val ver = snap.dataVersion
            val layersSnap = snap.layers
            val pinsSnap = snap.pins
            val tracksSnap = snap.tracks
            val pinsByLayerSnap = snap.pinsByLayerId
            val tracksByLayerSnap = snap.tracksByLayerId
            val folderNameById = layersSnap.associate { it.id to it.name }
            tvTotalBookmarksCount.text = "${pinsSnap.size} bookmarks"
            val trimmed = query.trim()

            if (trimmed.isNotEmpty()) {
                val tokens = trimmed.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
                val hasDigits = trimmed.any { it.isDigit() }
                val loc = gpsController.currentLocation
                val unit = getCachedSpeedUnit()
                AppExecutors.uiQueryExecutor.execute {
                    val matches = searchController.searchPinsUnified(ver, pinsSnap, folderNameById, tokens, hasDigits, loc, unit)
                    val rows = ArrayList<SearchRowItem>(matches.size + 2).apply {
                        add(SearchRowItem.Header("${matches.size} match${if (matches.size == 1) "" else "es"} across all folders"))
                        if (matches.isEmpty()) {
                            add(SearchRowItem.WebStatus("No bookmarks matched \"$trimmed\".", showRetry = false))
                        } else {
                            matches.forEachIndexed { idx, (entry, dist) ->
                                add(
                                    SearchRowItem.BookmarkMatch(
                                        entry = entry,
                                        distText = dist,
                                        showFolderInSub = true,
                                        isFirst = idx == 0,
                                        isLast = idx == matches.lastIndex
                                    )
                                )
                            }
                        }
                    }
                    AppExecutors.postMain {
                        if (!isActivityAlive() || mySeq != bookmarkSearchSeq) return@postMain
                        scroll.visibility = View.GONE
                        searchRecycler.visibility = View.VISIBLE
                        bmSearchAdapter.submitList(rows)
                    }
                }
                return
            }

            searchRecycler.visibility = View.GONE
            scroll.visibility = View.VISIBLE
            body.removeAllViews()

            val bookmarkedIds = DevicePrefs.getBookmarks(activity)
            val bookmarkedPins = pinsSnap.filter { it.id in bookmarkedIds }

            body.addView(TextView(activity).apply {
                text = "${layersSnap.size} folder${if (layersSnap.size == 1) "" else "s"}"
                textSize = 12.5f
                letterSpacing = 0.04f
                setTextColor(txtSub)
                setTypeface(null, Typeface.BOLD)
                DesignSystem.applyTabularNumerals(this)
                setPadding(dp(4), dp(4), dp(4), dp(8))
            })

            if (layersSnap.isEmpty() && bookmarkedPins.isEmpty()) {
                val emptyCard = DesignSystem.createGroupedCard(activity, p, radiusDp = 16f, paddingHDp = 16, paddingVDp = 18)
                emptyCard.addView(TextView(activity).apply {
                    text = "No bookmark folders yet"
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(txtPrimary)
                })
                emptyCard.addView(TextView(activity).apply {
                    text = "Import a KML / KMZ field map from your phone or join a live team folder using a 6-character share code."
                    textSize = 13.5f
                    setLineSpacing(dp(2).toFloat(), 1.08f)
                    setTextColor(txtSub)
                    setPadding(0, dp(6), 0, dp(14))
                })
                val emptyBtnRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                    gravity = Gravity.CENTER_VERTICAL
                }
                emptyBtnRow.addView(MaterialButton(activity).apply {
                    text = "Import KML/KMZ"
                    textSize = 13f
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
                    setPadding(dp(10), 0, dp(10), 0)
                    setTextColor(Color.WHITE)
                    setBackgroundColor(p.green)
                    cornerRadius = dp(12)
                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
                    UiAnimations.attachSnappyPress(this)
                    setOnClickListener {
                        closeFullPage()
                        launchKmlPicker()
                    }
                })
                emptyBtnRow.addView(MaterialButton(activity).apply {
                    text = "Join by Code"
                    textSize = 13f
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
                    setPadding(dp(10), 0, dp(10), 0)
                    setTextColor(Color.WHITE)
                    setBackgroundColor(p.accent)
                    cornerRadius = dp(12)
                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
                    UiAnimations.attachSnappyPress(this)
                    setOnClickListener { folderActions.showJoinCodeDialog() }
                })
                emptyCard.addView(emptyBtnRow)
                body.addView(emptyCard)
                return
            }

            val listCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                DesignSystem.applyGroupedCardSurface(activity, this, p, radiusDp = 16f)
            }

            var rowIndex = 0
            fun addDividerIfNeeded() {
                if (rowIndex > 0) {
                    listCard.addView(View(activity).apply {
                        setBackgroundColor(dividerColor)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                            marginStart = dp(18)
                            marginEnd = dp(18)
                        }
                    })
                }
                rowIndex++
            }

            if (bookmarkedPins.isNotEmpty()) {
                addDividerIfNeeded()
                val starRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(14), dp(10), dp(14))
                    UiAnimations.applyRowRipple(activity, this, night)
                    setOnClickListener {
                        pushSubPage({ popSubPage(::buildBookmarksAndTracksPage) }) {
                            openPinsListPage("Starred", bookmarkedPins, null)
                        }
                    }
                }
                starRow.addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_star_filled)
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) }
                })
                val col = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(TextView(activity).apply {
                    text = "Starred"
                    textSize = 15.5f
                    setTextColor(txtPrimary)
                    setTypeface(null, Typeface.BOLD)
                })
                col.addView(TextView(activity).apply {
                    text = "${bookmarkedPins.size} bookmarks"
                    textSize = 12.5f
                    setTextColor(txtSub)
                })
                starRow.addView(col)
                listCard.addView(starRow)
            }

            val hiddenLayerIds = DevicePrefs.getHiddenLayerIds(activity)
            val pinnedLayerIds = DevicePrefs.getPinnedLayerIds(activity)
            val orderedLayers = layersSnap.sortedByDescending { it.id in pinnedLayerIds }
            orderedLayers.forEach { layer ->
                addDividerIfNeeded()
                val layerPins = pinsByLayerSnap[layer.id].orEmpty()
                val layerTracks = tracksByLayerSnap[layer.id].orEmpty()
                val visible = layer.id !in hiddenLayerIds
                val isPinned = layer.id in pinnedLayerIds
                val isLocal = LocalFolderStore.isLocalLayerId(layer.id)
                val isCreator = repository.isLayerOwner(layer)

                var doneCnt = 0
                var issueCnt = 0
                var pendingCnt = 0
                for (item in layerPins) {
                    when (PinStatus.fromRaw(item.status)) {
                        PinStatus.DONE -> doneCnt++
                        PinStatus.ISSUE -> issueCnt++
                        else -> pendingCnt++
                    }
                }

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(12), dp(6), dp(12))
                    UiAnimations.applyRowRipple(activity, this, night)
                }

                row.addView(ImageButton(activity).apply {
                    setImageResource(if (visible) R.drawable.ic_eye_on else R.drawable.ic_eye_off)
                    setColorFilter(if (visible) p.accent else p.iconMuted)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    UiAnimations.applyRowRipple(activity, this, night)
                    UiAnimations.attachSnappyPress(this)
                    setOnClickListener {
                        val next = !DevicePrefs.isLayerVisible(activity, layer.id)
                        DevicePrefs.setLayerVisible(activity, layer.id, next)
                        UiAnimations.animateIconSwap(
                            this,
                            if (next) R.drawable.ic_eye_on else R.drawable.ic_eye_off,
                            newTint = if (next) p.accent else p.iconMuted
                        )
                        syncMasterVisibilitySwitch()
                        renderMapData()
                    }
                })

                val infoCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(6)
                        marginEnd = dp(4)
                    }
                    setOnClickListener {
                        pushSubPage({ popSubPage(::buildBookmarksAndTracksPage) }) {
                            openPinsListPage(layer.name, layerPins, layer)
                        }
                    }
                }
                val titleRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                titleRow.addView(TextView(activity).apply {
                    text = layer.name
                    textSize = 15.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(txtPrimary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (isLocal) {
                    titleRow.addView(TextView(activity).apply {
                        text = "LOCAL"
                        textSize = 10f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(p.accentPrimary)
                        setPadding(dp(7), dp(2), dp(7), dp(2))
                        background = GradientDrawable().apply {
                            setColor(p.accentPrimarySoft)
                            cornerRadius = dp(8).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(6) }
                    })
                }
                if (isPinned) {
                    titleRow.addView(TextView(activity).apply {
                        text = "📌 PINNED"
                        textSize = 10f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(p.amber)
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        background = GradientDrawable().apply {
                            setColor(p.amberSoft)
                            cornerRadius = dp(6).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(6) }
                    })
                }
                infoCol.addView(titleRow)
                infoCol.addView(TextView(activity).apply {
                    val trackStr = if (layerTracks.isNotEmpty()) " • ${layerTracks.size} tracks" else ""
                    val sb = SpannableStringBuilder("${layerPins.size} bookmarks$trackStr   ")
                    fun appendColoredStat(token: String, color: Int) {
                        val start = sb.length
                        sb.append(token)
                        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    appendColoredStat("✓$doneCnt", p.green)
                    sb.append("  ")
                    appendColoredStat("•$pendingCnt", p.amber)
                    sb.append("  ")
                    appendColoredStat("!$issueCnt", p.red)
                    text = sb
                    textSize = 12.5f
                    setTextColor(txtSub)
                    DesignSystem.applyTabularNumerals(this)
                    setPadding(0, dp(2), 0, dp(5))
                })

                if (layerPins.isNotEmpty()) {
                    val bar = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
                        background = GradientDrawable().apply {
                            setColor(dividerColor)
                            cornerRadius = dp(2).toFloat()
                        }
                        clipToOutline = true
                    }
                    if (doneCnt > 0) bar.addView(View(activity).apply {
                        setBackgroundColor(p.green)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, doneCnt.toFloat())
                    })
                    if (pendingCnt > 0) bar.addView(View(activity).apply {
                        setBackgroundColor(p.amber)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pendingCnt.toFloat())
                    })
                    if (issueCnt > 0) bar.addView(View(activity).apply {
                        setBackgroundColor(p.red)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, issueCnt.toFloat())
                    })
                    infoCol.addView(bar)
                }
                row.addView(infoCol)

                row.addView(ImageButton(activity).apply {
                    setImageResource(R.drawable.ic_more_vert)
                    setColorFilter(p.iconMuted)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(48))
                    setPadding(dp(10), dp(12), dp(10), dp(12))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    UiAnimations.applyRowRipple(activity, this, night)
                    setOnClickListener {
                        val folderSubtitle = buildString {
                            append("${layerPins.size} bookmark${if (layerPins.size == 1) "" else "s"}")
                            append(" • $doneCnt Done • $pendingCnt Pending • $issueCnt Issue")
                        }
                        val folderActionItems = buildList {
                            add(
                                SheetActionItem(
                                    title = if (isPinned) "Unpin Folder (Unlock)" else "Pin & Protect Folder",
                                    subtitle = if (isPinned) "Currently protected against accidental deletion" else null,
                                    iconRes = R.drawable.ic_pin_lock,
                                    checked = isPinned
                                ) {
                                    val nowPinned = DevicePrefs.toggleLayerPinned(activity, layer.id)
                                    Toast.makeText(
                                        activity,
                                        if (nowPinned) "Pinned & protected \"${layer.name}\"" else "Unpinned \"${layer.name}\"",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    renderFoldersOrSearch(etFolderSearch.text?.toString() ?: "")
                                }
                            )
                            if (isCreator) {
                                add(
                                    SheetActionItem(
                                        title = "Rename Folder",
                                        iconRes = R.drawable.ic_edit
                                    ) { folderActions.showRenameFolderDialog(layer) }
                                )
                            }
                            if (isLocal) {
                                add(
                                    SheetActionItem(
                                        title = "Delete Local Folder",
                                        subtitle = if (isPinned) "Unpin folder first to delete" else "Removes folder from this phone",
                                        iconRes = R.drawable.ic_trash,
                                        destructive = true
                                    ) {
                                        if (isPinned) {
                                            Toast.makeText(activity, "Folder is pinned. Unpin it first to delete.", Toast.LENGTH_LONG).show()
                                        } else {
                                            val freshCount = viewModel.folderSnapshot.pinsByLayerId[layer.id]?.size ?: 0
                                            folderActions.showDeleteLocalDialog(layer, freshCount)
                                        }
                                    }
                                )
                            } else {
                                add(
                                    SheetActionItem(
                                        title = "Leave Folder (This Phone)",
                                        subtitle = if (isPinned) "Unpin folder first to leave" else null,
                                        iconRes = R.drawable.ic_close,
                                        destructive = false
                                    ) {
                                        if (isPinned) {
                                            Toast.makeText(activity, "Folder is pinned. Unpin it first to leave.", Toast.LENGTH_LONG).show()
                                        } else {
                                            val leftPinIds = viewModel.folderSnapshot.pinsByLayerId[layer.id]?.map { it.id }.orEmpty()
                                            DevicePrefs.removeJoinedLayerId(activity, layer.id)
                                            DevicePrefs.removePinReferences(activity, leftPinIds)
                                            refreshJoinedSync()
                                            Toast.makeText(activity, "Left \"${layer.name}\"", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                if (isCreator) {
                                    add(
                                        SheetActionItem(
                                            title = "Delete from Cloud (All Users)",
                                            subtitle = if (isPinned) "Unpin folder first to delete" else "Permanently deletes for everyone",
                                            iconRes = R.drawable.ic_trash,
                                            destructive = true
                                        ) {
                                            if (isPinned) {
                                                Toast.makeText(activity, "Folder is pinned. Unpin it first to delete.", Toast.LENGTH_LONG).show()
                                            } else {
                                                val freshSnap = viewModel.folderSnapshot
                                                val freshPins = freshSnap.pinsByLayerId[layer.id].orEmpty()
                                                val freshTracks = freshSnap.tracksByLayerId[layer.id].orEmpty()
                                                folderActions.showDeleteCloudDialog(layer, freshPins, freshTracks)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        DesignSystem.showActionBottomSheet(
                            ctx = activity,
                            palette = p,
                            title = layer.name,
                            subtitle = folderSubtitle,
                            actions = folderActionItems,
                            headerContent = { listCol, dismissSheet ->
                                fun tileRippleBg(fill: Int, stroke: Int, radiusDp: Float): RippleDrawable {
                                    val content = DesignSystem.buildOpaqueDrawable(
                                        ctx = activity,
                                        fillColor = fill,
                                        radiusDp = radiusDp,
                                        strokeColor = stroke,
                                        strokeDp = if (stroke == Color.TRANSPARENT) 0f else 1f
                                    )
                                    val ripple = Color.parseColor(if (night) "#2AFFFFFF" else "#1A1C1E1B")
                                    val mask = GradientDrawable().apply {
                                        setColor(Color.WHITE)
                                        cornerRadius = dp(radiusDp.toInt()).toFloat()
                                    }
                                    return RippleDrawable(
                                        ColorStateList.valueOf(ripple),
                                        content,
                                        mask
                                    )
                                }
                                fun buildGridTile(
                                    label: String,
                                    iconRes: Int,
                                    highlight: Boolean = false,
                                    addEndGap: Boolean = true,
                                    onTap: () -> Unit
                                ): LinearLayout = LinearLayout(activity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    gravity = Gravity.CENTER
                                    isClickable = true
                                    isFocusable = true
                                    setPadding(dp(8), dp(6), dp(8), dp(6))
                                    background = tileRippleBg(
                                        fill = if (highlight) p.accentPrimarySoft else p.surfaceTonal,
                                        stroke = if (highlight) Color.TRANSPARENT else p.borderSubtle,
                                        radiusDp = 16f
                                    )
                                    layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                                        if (addEndGap) marginEnd = dp(8)
                                    }
                                    val fg = if (highlight) p.accentPrimary else txtPrimary
                                    addView(ImageView(activity).apply {
                                        setImageResource(iconRes)
                                        setColorFilter(fg)
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(4) }
                                    })
                                    addView(TextView(activity).apply {
                                        text = label
                                        textSize = 12.5f
                                        setTypeface(null, Typeface.BOLD)
                                        setTextColor(fg)
                                        maxLines = 1
                                        ellipsize = TextUtils.TruncateAt.END
                                        gravity = Gravity.CENTER
                                    })
                                    UiAnimations.attachSnappyPress(this, 0.97f)
                                    setOnClickListener {
                                        dismissSheet()
                                        onTap()
                                    }
                                }
                                fun buildExportPill(
                                    label: String,
                                    iconRes: Int,
                                    addEndGap: Boolean = true,
                                    onTap: () -> Unit
                                ): LinearLayout = LinearLayout(activity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER
                                    isClickable = true
                                    isFocusable = true
                                    setPadding(dp(10), 0, dp(10), 0)
                                    background = tileRippleBg(p.surfaceTonal, p.borderSubtle, 14f)
                                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                                        if (addEndGap) marginEnd = dp(8)
                                    }
                                    addView(ImageView(activity).apply {
                                        setImageResource(iconRes)
                                        setColorFilter(txtPrimary)
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(7) }
                                    })
                                    addView(TextView(activity).apply {
                                        text = label
                                        textSize = 13f
                                        setTypeface(null, Typeface.BOLD)
                                        setTextColor(txtPrimary)
                                        maxLines = 1
                                        ellipsize = TextUtils.TruncateAt.END
                                    })
                                    UiAnimations.attachSnappyPress(this, 0.97f)
                                    setOnClickListener {
                                        dismissSheet()
                                        onTap()
                                    }
                                }

                                val row1 = LinearLayout(activity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply { bottomMargin = dp(8) }
                                }
                                if (isLocal) {
                                    row1.addView(buildGridTile("Upload Cloud", R.drawable.ic_upload_cloud, highlight = true) {
                                        val freshSnap = viewModel.folderSnapshot
                                        folderActions.confirmUploadLocalToCloud(
                                            layer,
                                            freshSnap.pinsByLayerId[layer.id].orEmpty(),
                                            freshSnap.tracksByLayerId[layer.id].orEmpty()
                                        )
                                    })
                                } else {
                                    row1.addView(buildGridTile("Share (${layer.shareCode})", R.drawable.ic_share, highlight = true) {
                                        folderActions.shareLayerLink(layer)
                                    })
                                }
                                row1.addView(buildGridTile("Zoom Map", R.drawable.ic_my_location, addEndGap = false) {
                                    val freshSnap = viewModel.folderSnapshot
                                    val freshPins = freshSnap.pins.filter { it.layerId == layer.id }
                                    val freshTracks = freshSnap.tracks.filter { it.layerId == layer.id }
                                    closeFullPage()
                                    zoomToKmlBounds(collectLayerLatLngs(freshPins, freshTracks), layer.id)
                                })
                                listCol.addView(row1)

                                val row2 = LinearLayout(activity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                }
                                row2.addView(buildGridTile("Team Report", R.drawable.ic_report) {
                                    val freshSnap = viewModel.folderSnapshot
                                    showDailyReport(
                                        layer,
                                        freshSnap.pinsByLayerId[layer.id].orEmpty(),
                                        freshSnap.tracksByLayerId[layer.id].orEmpty()
                                    )
                                })
                                row2.addView(buildGridTile("Edit History", R.drawable.ic_history, addEndGap = false) {
                                    pushSubPage({ popSubPage(::buildBookmarksAndTracksPage) }) {
                                        openEditHistoryPage(layer)
                                    }
                                })
                                listCol.addView(row2)

                                val exportRow = LinearLayout(activity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        topMargin = dp(8)
                                        bottomMargin = dp(10)
                                    }
                                }
                                exportRow.addView(buildExportPill("Export KML", R.drawable.ic_download) {
                                    val freshSnap = viewModel.folderSnapshot
                                    folderActions.showExportKmlDialog(
                                        layer,
                                        freshSnap.pinsByLayerId[layer.id].orEmpty(),
                                        freshSnap.tracksByLayerId[layer.id].orEmpty()
                                    )
                                })
                                exportRow.addView(buildExportPill("Export CSV", R.drawable.ic_import_file, addEndGap = false) {
                                    val freshPins = viewModel.folderSnapshot.pinsByLayerId[layer.id].orEmpty()
                                    KmlCsvExporter.exportAndShareCsv(activity, layer, freshPins)
                                })
                                listCol.addView(exportRow)
                            }
                        )
                    }
                })

                listCard.addView(row)
            }

            body.addView(listCard)
        }

        syncMasterVisibilitySwitch()
        swShowAll.setOnCheckedChangeListener { _, checked ->
            if (suppressAllSwitch) return@setOnCheckedChangeListener
            DevicePrefs.setAllLayersVisible(activity, viewModel.folderSnapshot.layers.map { it.id }, checked)
            renderMapData()
            renderFoldersOrSearch(etFolderSearch.text?.toString() ?: "")
        }

        var searchRunnable: Runnable? = null
        etFolderSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                btnClearSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                searchRunnable?.let { etFolderSearch.removeCallbacks(it) }
                val r = Runnable { renderFoldersOrSearch(q) }
                searchRunnable = r
                etFolderSearch.postDelayed(r, if (q.isEmpty()) 0L else 120L)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        setLiveBookmarksRefresh {
            syncMasterVisibilitySwitch()
            renderFoldersOrSearch(etFolderSearch.text?.toString() ?: "")
        }
        setActivePageCleanup {
            bookmarkSearchSeq++
            searchRunnable?.let { etFolderSearch.removeCallbacks(it) }
        }
        renderFoldersOrSearch("")
        return outer
    }
}
