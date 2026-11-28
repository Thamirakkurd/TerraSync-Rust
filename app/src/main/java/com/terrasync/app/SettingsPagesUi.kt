package com.terrasync.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

internal enum class SettingsSection {
    MAP_SHADERS,
    LANGUAGE_LABELS,
    NAVIGATION_ROUTING,
    FIELD_DATA_SYNC,
    OFFLINE_CACHE,
    ABOUT_BUILD
}

internal fun MainActivity.openSettingsSection(section: SettingsSection) {
    pushSubPage({ popSubPage(::buildSettingsPage) }) {
        buildSettingsSubPage(section)
    }
}

internal fun MainActivity.reopenSettingsSection(section: SettingsSection) {
    pageOverlayContainer.animate().cancel()
    activePageCleanup?.invoke()
    activePageCleanup = null
    liveBookmarksRefresh = null
    livePinListRefresh = null
    liveEditHistoryRefresh = null
    offlineMaps.setStateListener(null)
    hideSoftKeyboard()
    pageBackStack.clear()
    pageBackStack.add { popSubPage(::buildSettingsPage) }
    pageOverlayContainer.setBackgroundColor(currentPalette().pageBackdrop)
    UiAnimations.transitionCrossfade(pageOverlayContainer, buildSettingsSubPage(section))
    updateSystemBarContrast(forFullPage = true)
}

internal fun MainActivity.buildSettingsPage(): View {
    val p = currentPalette()
    val night = p.isDark
    val txtPrimary = p.textPrimary
    val txtSub = p.textSecondary
    val isRtl = AppStrings.isRtl(this)

    val (outer, body) = makePageFrame(AppStrings.settings(this), { closeFullPage() })

    fun addMenuRow(
        card: LinearLayout,
        iconRes: Int,
        iconColor: Int,
        title: String,
        summary: String,
        onClick: () -> Unit
    ) {
        if (card.childCount > 0) {
            card.addView(DesignSystem.createHairlineDivider(this, p, insetStartDp = 54, insetEndDp = 14))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(62)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            UiAnimations.applyRowRipple(this@buildSettingsPage, this, night)
            UiAnimations.attachSnappyPress(this, 0.985f)
            setOnClickListener { onClick() }
        }
        val iconWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(p.surfaceFloating)
                setStroke(dp(1), p.borderSubtle)
            }
        }
        iconWrap.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(iconColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        })
        row.addView(iconWrap)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(txtPrimary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(this).apply {
            text = summary
            textSize = 12.5f
            setTextColor(txtSub)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            DesignSystem.applyTabularNumerals(this)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textCol)

        row.addView(TextView(this).apply {
            text = if (isRtl) "‹" else "›"
            textSize = 22f
            setTextColor(p.iconMuted)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(2), 0)
        })
        card.addView(row)
    }

    val themeLabel = when (DevicePrefs.getThemeMode(this)) {
        "night" -> "Night"
        "auto" -> "Auto"
        else -> "Day"
    }
    val mapSummary = buildList {
        add(mapStyleMode.label)
        add(themeLabel)
        add(DevicePrefs.getMapPinStyle(this@buildSettingsPage).label)
        if (DevicePrefs.isHillshadeEnabled(this@buildSettingsPage)) add("Hillshade")
        if (DevicePrefs.isContoursEnabled(this@buildSettingsPage)) add("Contours")
    }.joinToString(" • ")

    val langSummary = listOf(
        DevicePrefs.getAppLanguage(this).label,
        DevicePrefs.getMapLabelLanguage(this).label,
        DevicePrefs.getPoiLabelBehavior(this).label
    ).joinToString(" • ")

    val navSummary = buildList {
        add(travelMode.label)
        add(DevicePrefs.getSpeedUnit(this@buildSettingsPage).speedSuffix)
        if (DevicePrefs.isSpeedIndicatorEnabled(this@buildSettingsPage)) add("Speed HUD")
        if (DevicePrefs.isVoiceNav(this@buildSettingsPage)) add("Voice")
    }.joinToString(" • ")

    val localCount = layers.count { LocalFolderStore.isLocalLayerId(it.id) }
    val cloudCount = layers.size - localCount
    val pendingUploads = repo.pendingUploadCount()
    val syncPrefix = when {
        pendingUploads > 0 -> AppStrings.pendingUploadCount(this, pendingUploads)
        isLiveConnected -> "Cloud live"
        else -> "Offline cache"
    }
    val syncSummary = "$syncPrefix • $localCount local, $cloudCount cloud • ${pins.size} pins"

    val downloadedPacks = OfflineMapManager.REGIONAL_PACKS.count { offlineMaps.getState(it.id).isDownloaded }
    val offlineSummary = if (downloadedPacks > 0) {
        "$downloadedPacks of ${OfflineMapManager.REGIONAL_PACKS.size} regions downloaded"
    } else {
        "Kurdistan & Southern Iraq • Embedded roads"
    }

    val aboutSummary = "v${getInstalledVersionName()} • Device #${DevicePrefs.getDeviceId(this)}"

    val mainMenuCard = DesignSystem.createGroupedCard(this, p, radiusDp = 16f, paddingHDp = 0, paddingVDp = 2, bottomMarginDp = 14)

    addMenuRow(
        mainMenuCard,
        R.drawable.ic_layers,
        p.accentPrimary,
        AppStrings.secMapShaders(this),
        mapSummary
    ) { openSettingsSection(SettingsSection.MAP_SHADERS) }

    addMenuRow(
        mainMenuCard,
        R.drawable.ic_map,
        p.accentPrimary,
        AppStrings.secLanguageLabels(this),
        langSummary
    ) { openSettingsSection(SettingsSection.LANGUAGE_LABELS) }

    addMenuRow(
        mainMenuCard,
        R.drawable.ic_my_location,
        p.accentPrimary,
        AppStrings.secNavigationRouting(this),
        navSummary
    ) { openSettingsSection(SettingsSection.NAVIGATION_ROUTING) }

    addMenuRow(
        mainMenuCard,
        R.drawable.ic_cloud_sync,
        when {
            pendingUploads > 0 -> p.accentWarm
            isLiveConnected -> p.accentPrimary
            else -> p.amber
        },
        AppStrings.secFieldDataSync(this),
        syncSummary
    ) { openSettingsSection(SettingsSection.FIELD_DATA_SYNC) }

    addMenuRow(
        mainMenuCard,
        R.drawable.ic_import_file,
        p.iconTint,
        AppStrings.secOfflineCache(this),
        offlineSummary
    ) { openSettingsSection(SettingsSection.OFFLINE_CACHE) }

    body.addView(mainMenuCard)

    val metaMenuCard = DesignSystem.createGroupedCard(this, p, radiusDp = 16f, paddingHDp = 0, paddingVDp = 2, bottomMarginDp = 12)
    addMenuRow(
        metaMenuCard,
        R.drawable.ic_info,
        p.iconTint,
        AppStrings.secAboutBuild(this),
        aboutSummary
    ) { openSettingsSection(SettingsSection.ABOUT_BUILD) }
    body.addView(metaMenuCard)

    return outer
}

internal fun MainActivity.buildSettingsSubPage(section: SettingsSection): View {
    val p = currentPalette()
    val txtPrimary = p.textPrimary
    val txtSub = p.textSecondary

    val pageTitle = when (section) {
        SettingsSection.MAP_SHADERS -> AppStrings.secMapShaders(this)
        SettingsSection.LANGUAGE_LABELS -> AppStrings.secLanguageLabels(this)
        SettingsSection.NAVIGATION_ROUTING -> AppStrings.secNavigationRouting(this)
        SettingsSection.FIELD_DATA_SYNC -> AppStrings.secFieldDataSync(this)
        SettingsSection.OFFLINE_CACHE -> AppStrings.secOfflineCache(this)
        SettingsSection.ABOUT_BUILD -> AppStrings.secAboutBuild(this)
    }

    val (outer, body) = makePageFrame(pageTitle, {
        if (pageBackStack.isNotEmpty()) {
            pageBackStack.removeAt(pageBackStack.lastIndex).invoke()
        } else {
            openFullPage(::buildSettingsPage)
        }
    })

    fun addSegmentedBlock(
        card: LinearLayout,
        title: String,
        subtitle: String?,
        options: List<Pair<String, String>>,
        selectedId: String,
        activeColor: ((String) -> Int)? = null,
        onSelect: (String) -> Unit
    ) {
        card.addView(TextView(this).apply {
            text = title
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(txtPrimary)
            setPadding(0, dp(8), 0, if (subtitle != null) dp(2) else dp(8))
        })
        if (!subtitle.isNullOrBlank()) {
            card.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(txtSub)
                setPadding(0, 0, 0, dp(8))
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        val btns = mutableMapOf<String, MaterialButton>()
        fun sync(activeId: String) {
            btns.forEach { (id, b) ->
                DesignSystem.styleSegmentedButton(
                    b,
                    id == activeId,
                    p,
                    activeColor?.invoke(id) ?: p.accentPrimary,
                    onTonalCard = true
                )
            }
        }
        options.forEachIndexed { idx, (id, label) ->
            val btn = MaterialButton(this).apply {
                text = label
                textSize = 12.5f
                isAllCaps = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(6), 0, dp(6), 0)
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (idx < options.lastIndex) marginEnd = dp(6)
                }
                UiAnimations.attachSnappyPress(this)
                setOnClickListener {
                    sync(id)
                    onSelect(id)
                }
            }
            btns[id] = btn
            row.addView(btn)
        }
        sync(selectedId)
        card.addView(row)
    }

    fun addCardSwitch(card: LinearLayout, title: String, subtitle: String?, checked: Boolean, addDivider: Boolean = true, onChange: (Boolean) -> Unit) {
        if (addDivider && card.childCount > 0) {
            card.addView(DesignSystem.createHairlineDivider(this, p))
        }
        card.addView(DesignSystem.createSettingsSwitchRow(this, title, subtitle, checked, p, onChange))
    }

    when (section) {
        SettingsSection.MAP_SHADERS -> {
            val mapCard = DesignSystem.createGroupedCard(this, p, radiusDp = 16f, paddingHDp = 14, paddingVDp = 6)

            addSegmentedBlock(
                mapCard,
                title = AppStrings.mapStyleTitle(this),
                subtitle = AppStrings.mapStyleSub(this),
                options = MapStyleMode.entries.map { it.id.toString() to it.label },
                selectedId = mapStyleMode.id.toString(),
                activeColor = { p.accentPrimary }
            ) { chosenId ->
                val mode = MapStyleMode.fromId(chosenId.toIntOrNull() ?: MapStyleMode.VECTOR.id)
                if (mapStyleMode != mode) {
                    mapStyleMode = mode
                    DevicePrefs.setMapStyleMode(this, mode)
                    applyMapChromeTheme()
                    loadMapStyle()
                }
            }

            mapCard.addView(DesignSystem.createHairlineDivider(this, p))
            addSegmentedBlock(
                mapCard,
                title = AppStrings.appearanceThemeTitle(this),
                subtitle = AppStrings.appearanceThemeSub(this),
                options = listOf(
                    "day" to AppStrings.themeDay(this),
                    "night" to AppStrings.themeNight(this),
                    "auto" to AppStrings.themeAuto(this)
                ),
                selectedId = DevicePrefs.getThemeMode(this)
            ) { key ->
                if (DevicePrefs.getThemeMode(this) != key) {
                    DevicePrefs.setThemeMode(this, key)
                    refreshLocalizedChrome()
                    if (mapStyleMode == MapStyleMode.VECTOR) loadMapStyle()
                    reopenSettingsSection(SettingsSection.MAP_SHADERS)
                }
            }

            addCardSwitch(
                mapCard,
                title = AppStrings.mountainHillshading(this),
                subtitle = AppStrings.mountainHillshadingSub(this),
                checked = DevicePrefs.isHillshadeEnabled(this)
            ) { enabled ->
                DevicePrefs.setHillshadeEnabled(this, enabled)
                map?.style?.let { st -> if (st.isFullyLoaded) applyHillshadeSettings(st) }
            }

            mapCard.addView(DesignSystem.createHairlineDivider(this, p))
            addSegmentedBlock(
                mapCard,
                title = AppStrings.hillshadeIntensityTitle(this),
                subtitle = AppStrings.hillshadeIntensitySub(this),
                options = HillshadeIntensity.entries.map { it.id to it.label },
                selectedId = DevicePrefs.getHillshadeIntensity(this).id,
                activeColor = { p.accentPrimary }
            ) { id ->
                val next = HillshadeIntensity.fromId(id)
                DevicePrefs.setHillshadeIntensity(this, next)
                map?.style?.let { st -> if (st.isFullyLoaded) applyHillshadeSettings(st) }
            }

            addCardSwitch(
                mapCard,
                title = AppStrings.elevationContours(this),
                subtitle = AppStrings.elevationContoursSub(this),
                checked = DevicePrefs.isContoursEnabled(this)
            ) { enabled ->
                DevicePrefs.setContoursEnabled(this, enabled)
                val m = map
                val st = m?.style
                if (st != null && st.isFullyLoaded) {
                    contourManager.attachLayers(st, isDarkMapUi(), enabled)
                    contourManager.setVisible(st, enabled)
                    if (enabled) contourManager.scheduleUpdate(m, true, mapStyleMode == MapStyleMode.VECTOR)
                }
            }

            addCardSwitch(
                mapCard,
                title = AppStrings.buildings3d(this),
                subtitle = AppStrings.buildings3dSub(this),
                checked = DevicePrefs.is3dBuildingsEnabled(this)
            ) { enabled ->
                DevicePrefs.set3dBuildingsEnabled(this, enabled)
                map?.style?.let { st -> if (st.isFullyLoaded) apply3dBuildingsToggle(st) }
            }

            mapCard.addView(DesignSystem.createHairlineDivider(this, p))
            mapCard.addView(TextView(this).apply {
                text = AppStrings.mapPinStyleTitle(this@buildSettingsSubPage)
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(txtPrimary)
                setPadding(0, dp(8), 0, dp(2))
            })
            mapCard.addView(TextView(this).apply {
                text = AppStrings.mapPinStyleSub(this@buildSettingsSubPage)
                textSize = 12f
                setTextColor(txtSub)
                setPadding(0, 0, 0, dp(8))
            })
            val pinRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
            }
            data class PinStyleCardUi(
                val style: MapPinStyle,
                val card: LinearLayout,
                val previewRow: LinearLayout,
                val label: TextView
            )
            val pinCards = mutableListOf<PinStyleCardUi>()
            var selectedPinStyle = DevicePrefs.getMapPinStyle(this)

            fun syncPinStyleCards(popSelected: Boolean) {
                pinCards.forEach { item ->
                    val active = item.style == selectedPinStyle
                    item.card.background = if (active) {
                        DesignSystem.buildOpaqueDrawable(
                            ctx = this,
                            fillColor = p.accentPrimarySoft,
                            radiusDp = 14f,
                            strokeColor = p.accentPrimary,
                            strokeDp = 1.75f
                        )
                    } else {
                        DesignSystem.buildOpaqueDrawable(
                            ctx = this,
                            fillColor = p.surfaceFloating,
                            radiusDp = 14f,
                            strokeColor = p.borderSubtle,
                            strokeDp = 1f
                        )
                    }
                    item.label.setTextColor(if (active) p.accentPrimary else p.textPrimary)
                    item.label.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                    if (active && popSelected) {
                        UiAnimations.animateScalePop(item.previewRow, 0.85f, 160L)
                    }
                }
            }

            MapPinStyle.entries.forEachIndexed { idx, styleOption ->
                val styleCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx < MapPinStyle.entries.lastIndex) marginEnd = dp(8)
                    }
                    UiAnimations.attachSnappyPress(this, 0.96f)
                }
                val previewCluster = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                }
                listOf("#E69500", "#2E8B47").forEachIndexed { pinIdx, hex ->
                    val bmp = if (styleOption == MapPinStyle.CONCEPT) {
                        MapBitmaps.createConceptDropletPin(this, hex, scale = 0.85f)
                    } else {
                        MapBitmaps.createBookmarkPin(this, hex, scale = 0.85f)
                    }
                    previewCluster.addView(ImageView(this).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                            if (pinIdx == 0) marginEnd = dp(4)
                        }
                    })
                }
                val lblView = TextView(this).apply {
                    text = styleOption.label
                    textSize = 12.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                }
                styleCard.addView(previewCluster)
                styleCard.addView(lblView)
                styleCard.setOnClickListener {
                    if (selectedPinStyle != styleOption) {
                        UiAnimations.hapticTick(styleCard)
                        selectedPinStyle = styleOption
                        syncPinStyleCards(popSelected = true)
                        DevicePrefs.setMapPinStyle(this, styleOption)
                        map?.style?.let { st ->
                            if (st.isFullyLoaded) {
                                MapCartography.registerPinBitmaps(this, st)
                                renderMapData()
                            }
                        }
                    }
                }
                pinCards += PinStyleCardUi(styleOption, styleCard, previewCluster, lblView)
                pinRow.addView(styleCard)
            }
            syncPinStyleCards(popSelected = false)
            mapCard.addView(pinRow)

            addCardSwitch(
                mapCard,
                title = AppStrings.showZoomButtonsTitle(this),
                subtitle = AppStrings.showZoomButtonsSub(this),
                checked = DevicePrefs.isShowZoomButtons(this)
            ) { enabled ->
                DevicePrefs.setShowZoomButtons(this, enabled)
                applyZoomButtonsVisibility()
            }

            addCardSwitch(
                mapCard,
                title = AppStrings.mapScaleBarTitle(this),
                subtitle = AppStrings.mapScaleBarSub(this),
                checked = DevicePrefs.isScaleBarEnabled(this)
            ) { enabled ->
                DevicePrefs.setScaleBarEnabled(this, enabled)
                cachedScaleBarEnabled = enabled
                lastScaleLabel = ""
                lastScaleWidthPx = -1
                updateScaleBar()
            }

            addCardSwitch(
                mapCard,
                title = AppStrings.clusterDensePinsTitle(this),
                subtitle = AppStrings.clusterDensePinsSub(this),
                checked = DevicePrefs.isClusterDensePins(this)
            ) { enabled ->
                DevicePrefs.setClusterDensePins(this, enabled)
                lastPinsRenderKey = null
                renderMapData()
            }
            body.addView(mapCard)
        }

        SettingsSection.LANGUAGE_LABELS -> {
            val langCard = DesignSystem.createGroupedCard(this, p, radiusDp = 16f, paddingHDp = 14, paddingVDp = 6)

            langCard.addView(TextView(this).apply {
                text = AppStrings.appLanguageTitle(this@buildSettingsSubPage)
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(txtPrimary)
                setPadding(0, dp(8), 0, dp(2))
            })
            langCard.addView(TextView(this).apply {
                text = AppStrings.appLanguageSub(this@buildSettingsSubPage)
                textSize = 12f
                setTextColor(txtSub)
                setPadding(0, 0, 0, dp(8))
            })

            val currentAppLang = DevicePrefs.getAppLanguage(this)
            val appLangRows = AppLanguage.entries.chunked(2)
            appLangRows.forEachIndexed { rowIdx, pair ->
                val gridRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, if (rowIdx < appLangRows.lastIndex) dp(8) else dp(10))
                }
                pair.forEachIndexed { colIdx, langOption ->
                    val btn = MaterialButton(this).apply {
                        text = langOption.label
                        textSize = 12.5f
                        isAllCaps = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        insetTop = 0
                        insetBottom = 0
                        minWidth = 0
                        minimumWidth = 0
                        setPadding(dp(8), 0, dp(8), 0)
                        cornerRadius = dp(12)
                        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                            if (colIdx == 0) marginEnd = dp(8)
                        }
                        DesignSystem.styleSegmentedButton(
                            this,
                            langOption == currentAppLang,
                            p,
                            p.accentPrimary,
                            onTonalCard = true
                        )
                        UiAnimations.attachSnappyPress(this)
                        setOnClickListener {
                            if (DevicePrefs.getAppLanguage(this@buildSettingsSubPage) != langOption) {
                                UiAnimations.hapticTick(this)
                                DevicePrefs.setAppLanguage(this@buildSettingsSubPage, langOption)
                                refreshLocalizedChrome()
                                reopenSettingsSection(SettingsSection.LANGUAGE_LABELS)
                            }
                        }
                    }
                    gridRow.addView(btn)
                }
                langCard.addView(gridRow)
            }

            langCard.addView(DesignSystem.createHairlineDivider(this, p))
            langCard.addView(TextView(this).apply {
                text = "Map Label Language & Script"
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(txtPrimary)
                setPadding(0, dp(8), 0, dp(2))
            })
            val curMapLang = DevicePrefs.getMapLabelLanguage(this)
            val tvMapLangSub = TextView(this).apply {
                text = curMapLang.subtitle
                textSize = 12f
                setTextColor(txtSub)
                setPadding(0, 0, 0, dp(8))
            }
            langCard.addView(tvMapLangSub)

            val mapLangScroll = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val mapLangRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
            }
            val mapLangBtns = mutableMapOf<MapLabelLanguage, MaterialButton>()
            fun syncMapLangBtns(activeLang: MapLabelLanguage) {
                tvMapLangSub.text = activeLang.subtitle
                mapLangBtns.forEach { (l, b) -> DesignSystem.styleSegmentedButton(b, l == activeLang, p, onTonalCard = true) }
            }
            MapLabelLanguage.entries.forEachIndexed { idx, lang ->
                val btn = MaterialButton(this).apply {
                    text = lang.label
                    textSize = 12.5f
                    isAllCaps = false
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(12), 0, dp(12), 0)
                    cornerRadius = dp(12)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                        if (idx < MapLabelLanguage.entries.lastIndex) marginEnd = dp(6)
                    }
                    UiAnimations.attachSnappyPress(this)
                    setOnClickListener {
                        if (DevicePrefs.getMapLabelLanguage(this@buildSettingsSubPage) == lang) return@setOnClickListener
                        DevicePrefs.setMapLabelLanguage(this@buildSettingsSubPage, lang)
                        syncMapLangBtns(lang)
                        map?.style?.let { st ->
                            if (st.isFullyLoaded && mapStyleMode == MapStyleMode.VECTOR) {
                                MapCartography.applyOrganicCartography(this@buildSettingsSubPage, st, isDarkMapUi())
                            }
                        }
                    }
                }
                mapLangBtns[lang] = btn
                mapLangRow.addView(btn)
            }
            syncMapLangBtns(curMapLang)
            mapLangScroll.addView(mapLangRow)
            langCard.addView(mapLangScroll)

            langCard.addView(DesignSystem.createHairlineDivider(this, p))
            langCard.addView(TextView(this).apply {
                text = "POI & Icon Labels"
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(txtPrimary)
                setPadding(0, dp(8), 0, dp(2))
            })
            langCard.addView(TextView(this).apply {
                text = "Control text labels shown beneath map POI badges"
                textSize = 12f
                setTextColor(txtSub)
                setPadding(0, 0, 0, dp(8))
            })

            val curPoiBehavior = DevicePrefs.getPoiLabelBehavior(this)
            val poiScroll = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val poiRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
            }
            val poiBtns = mutableMapOf<PoiLabelBehavior, MaterialButton>()
            fun syncPoiBtns(activeBehavior: PoiLabelBehavior) {
                poiBtns.forEach { (bBehavior, b) ->
                    DesignSystem.styleSegmentedButton(b, bBehavior == activeBehavior, p, onTonalCard = true)
                }
            }
            PoiLabelBehavior.entries.forEachIndexed { idx, behavior ->
                val btn = MaterialButton(this).apply {
                    text = behavior.label
                    textSize = 12.5f
                    isAllCaps = false
                    maxLines = 1
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(11), 0, dp(11), 0)
                    cornerRadius = dp(12)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                        if (idx < PoiLabelBehavior.entries.lastIndex) marginEnd = dp(6)
                    }
                    UiAnimations.attachSnappyPress(this)
                    setOnClickListener {
                        if (DevicePrefs.getPoiLabelBehavior(this@buildSettingsSubPage) == behavior) return@setOnClickListener
                        DevicePrefs.setPoiLabelBehavior(this@buildSettingsSubPage, behavior)
                        syncPoiBtns(behavior)
                        map?.style?.let { st ->
                            if (st.isFullyLoaded && mapStyleMode == MapStyleMode.VECTOR) {
                                MapCartography.applyOrganicCartography(this@buildSettingsSubPage, st, isDarkMapUi())
                            }
                        }
                    }
                }
                poiBtns[behavior] = btn
                poiRow.addView(btn)
            }
            syncPoiBtns(curPoiBehavior)
            poiScroll.addView(poiRow)
            langCard.addView(poiScroll)
            body.addView(langCard)
        }

        SettingsSection.NAVIGATION_ROUTING -> {
            val navCard = DesignSystem.createGroupedCard(this, p, radiusDp = 16f, paddingHDp = 14, paddingVDp = 6)

            addSegmentedBlock(
                navCard,
                title = "Default Routing Mode",
                subtitle = "Used for Route To previews and turn-by-turn navigation",
                options = TravelMode.entries.map { it.id to it.label },
                selectedId = travelMode.id,
                activeColor = { p.accentPrimary }
            ) { id ->
                val mode = TravelMode.fromId(id)
                travelMode = mode
                DevicePrefs.setTravelMode(this, mode)
            }

            navCard.addView(DesignSystem.createHairlineDivider(this, p))
            addSegmentedBlock(
                navCard,
                title = "Speed & Distance Units",
                subtitle = "Applies to Speed HUD, scale bar, ruler, and distances",
                options = SpeedUnit.entries.map { it.id to it.label },
                selectedId = DevicePrefs.getSpeedUnit(this).id
            ) { id ->
                val nextUnit = SpeedUnit.fromId(id)
                DevicePrefs.setSpeedUnit(this, nextUnit)
                cachedSpeedUnit = nextUnit
                lastScaleLabel = ""
                lastScaleWidthPx = -1
                updateSpeedBadge(currentLocation)
                updateScaleBar()
                if (isRulerMode) updateRulerUi()
            }

            addCardSwitch(
                navCard,
                title = "Live Speed HUD",
                subtitle = "Tabular speedometer readout while driving or navigating",
                checked = DevicePrefs.isSpeedIndicatorEnabled(this)
            ) { enabled ->
                DevicePrefs.setSpeedIndicatorEnabled(this, enabled)
                updateSpeedBadge(currentLocation)
            }
            addCardSwitch(
                navCard,
                title = "Auto-Follow Live GPS",
                subtitle = "Keep map centered on GPS arrow while moving",
                checked = DevicePrefs.isAutoFollow(this)
            ) { DevicePrefs.setAutoFollow(this, it) }
            addCardSwitch(
                navCard,
                title = "Turn-by-Turn Voice Prompts",
                subtitle = "Spoken maneuver guidance during active navigation",
                checked = DevicePrefs.isVoiceNav(this)
            ) {
                DevicePrefs.setVoiceNav(this, it)
                btnNavVoice.alpha = if (it) 1.0f else 0.4f
            }
            addCardSwitch(
                navCard,
                title = "Keep Screen Awake",
                subtitle = "Prevent display sleep during field surveys (auto-on while navigating)",
                checked = DevicePrefs.isKeepAwake(this)
            ) {
                DevicePrefs.setKeepAwake(this, it)
                applyKeepAwakeFlag()
            }
            body.addView(navCard)
        }

        SettingsSection.FIELD_DATA_SYNC -> {
            val fieldCard = DesignSystem.createGroupedCard(this, p, radiusDp = 16f, paddingHDp = 14, paddingVDp = 6)

            addCardSwitch(
                fieldCard,
                title = "Show Bookmark Titles on Map",
                subtitle = "Display non-overlapping pin names beneath teardrop pins",
                checked = DevicePrefs.isShowBookmarkTitles(this),
                addDivider = false
            ) {
                DevicePrefs.setShowBookmarkTitles(this, it)
                renderMapData()
            }
            addCardSwitch(
                fieldCard,
                title = "Quick Map Tools",
                subtitle = "Show Lasso circle-select tool on the map",
                checked = DevicePrefs.isShowExtraMapTools(this)
            ) {
                DevicePrefs.setShowExtraMapTools(this, it)
                applyExtraMapToolsVisibility()
            }

            fieldCard.addView(DesignSystem.createHairlineDivider(this, p))

            val localLayersList = layers.filter { LocalFolderStore.isLocalLayerId(it.id) }
            val localCount = localLayersList.size
            val cloudCount = layers.size - localCount
            val pendingUploads = repo.pendingUploadCount()
            fieldCard.addView(TextView(this).apply {
                text = "Firebase Sync & KoboToolbox / KML"
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(txtPrimary)
                setPadding(0, dp(10), 0, dp(2))
            })
            fieldCard.addView(TextView(this).apply {
                val statusStr = when {
                    pendingUploads > 0 -> AppStrings.pendingUploadCount(this@buildSettingsSubPage, pendingUploads)
                    isLiveConnected -> "Cloud live connected"
                    else -> "Offline cache active"
                }
                text = "$statusStr • $localCount local & $cloudCount cloud folders (${pins.size} pins)"
                textSize = 12f
                setTextColor(
                    when {
                        pendingUploads > 0 -> p.accentWarm
                        isLiveConnected -> p.green
                        else -> p.amber
                    }
                )
                DesignSystem.applyTabularNumerals(this)
                setPadding(0, 0, 0, dp(10))
            })

            val syncActionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            syncActionRow.addView(MaterialButton(this).apply {
                text = "Import KML"
                textSize = 12.5f
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
                setPadding(dp(8), 0, dp(8), 0)
                setTextColor(Color.WHITE)
                setBackgroundColor(p.accentPrimary)
                elevation = 0f
                stateListAnimator = null
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1.05f).apply { marginEnd = dp(6) }
                UiAnimations.attachSnappyPress(this)
                setOnClickListener {
                    closeFullPage()
                    kmlPicker.launch(arrayOf("*/*"))
                }
            })
            syncActionRow.addView(MaterialButton(this).apply {
                text = AppStrings.bookmarks(this@buildSettingsSubPage)
                textSize = 12.5f
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
                setPadding(dp(8), 0, dp(8), 0)
                setBackgroundColor(p.actionDark)
                setTextColor(p.onActionDark)
                elevation = 0f
                stateListAnimator = null
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1.05f).apply { marginEnd = dp(6) }
                UiAnimations.attachSnappyPress(this)
                setOnClickListener { openFullPage(::buildBookmarksAndTracksPage) }
            })
            syncActionRow.addView(MaterialButton(this).apply {
                text = "Sync"
                textSize = 12.5f
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
                setPadding(dp(12), 0, dp(12), 0)
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 0.72f)
                DesignSystem.styleSegmentedButton(this, false, p, onTonalCard = true)
                UiAnimations.attachSnappyPress(this)
                setOnClickListener {
                    refreshJoinedSync()
                    Toast.makeText(this@buildSettingsSubPage, "Syncing folders...", Toast.LENGTH_SHORT).show()
                }
            })
            fieldCard.addView(syncActionRow)

            fieldCard.addView(MaterialButton(this).apply {
                text = AppStrings.backupLocalFolders(this@buildSettingsSubPage, localCount)
                textSize = 12.5f
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(12)
                alpha = if (localCount > 0) 1f else 0.58f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                    bottomMargin = dp(10)
                }
                DesignSystem.styleSegmentedButton(this, false, p, onTonalCard = true)
                UiAnimations.attachSnappyPress(this)
                setOnClickListener {
                    UiAnimations.hapticConfirm(this)
                    KmlCsvExporter.exportBackupAllLocalFoldersZip(
                        this@buildSettingsSubPage,
                        localLayersList,
                        pins,
                        tracks
                    )
                }
            })

            fieldCard.addView(DesignSystem.createHairlineDivider(this, p))

            val hoursRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(0, dp(8), 0, dp(8))
            }
            val hoursCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(10)
                }
            }
            hoursCol.addView(TextView(this).apply {
                text = AppStrings.workingHours(this@buildSettingsSubPage)
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(txtPrimary)
            })
            val tvWorkWindow = TextView(this).apply {
                val s = PinEditHistoryAudit.formatMinutes(DevicePrefs.getWorkStartMinutes(this@buildSettingsSubPage))
                val e = PinEditHistoryAudit.formatMinutes(DevicePrefs.getWorkEndMinutes(this@buildSettingsSubPage))
                text = "$s – $e • Flags after-hours pin edits"
                textSize = 12f
                setTextColor(txtSub)
                DesignSystem.applyTabularNumerals(this)
                setPadding(0, dp(2), 0, 0)
            }
            hoursCol.addView(tvWorkWindow)
            hoursRow.addView(hoursCol)
            hoursRow.addView(MaterialButton(this).apply {
                text = AppStrings.change(this@buildSettingsSubPage)
                textSize = 12.5f
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(14), 0, dp(14), 0)
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
                DesignSystem.styleSegmentedButton(this, false, p, onTonalCard = true)
                UiAnimations.attachSnappyPress(this)
                setOnClickListener {
                    showWorkingHoursDialog {
                        val s = PinEditHistoryAudit.formatMinutes(DevicePrefs.getWorkStartMinutes(this@buildSettingsSubPage))
                        val e = PinEditHistoryAudit.formatMinutes(DevicePrefs.getWorkEndMinutes(this@buildSettingsSubPage))
                        tvWorkWindow.text = "$s – $e • Flags after-hours pin edits"
                    }
                }
            })
            fieldCard.addView(hoursRow)

            fieldCard.addView(DesignSystem.createHairlineDivider(this, p))
            fieldCard.addView(TextView(this).apply {
                text = "Field Operator Stamp"
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(txtPrimary)
                setPadding(0, dp(10), 0, dp(2))
            })
            val tvCurrentStamp = TextView(this).apply {
                text = DevicePrefs.getDeviceStamp(this@buildSettingsSubPage)
                textSize = 12f
                setTextColor(txtSub)
                setPadding(0, 0, 0, dp(8))
            }
            fieldCard.addView(tvCurrentStamp)

            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            val etDeviceName = DesignSystem.createThemedEditText(
                ctx = this,
                palette = p,
                hintText = "Display name (e.g. Team 4 / Supervisor)",
                initialText = DevicePrefs.getCustomName(this@buildSettingsSubPage),
                singleLine = true,
                onTonalCard = true
            ).apply {
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            nameRow.addView(etDeviceName)
            nameRow.addView(MaterialButton(this).apply {
                text = "Save"
                textSize = 12.5f
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                setTextColor(Color.WHITE)
                setBackgroundColor(p.accentPrimary)
                elevation = 0f
                stateListAnimator = null
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(42)
                ).apply { marginStart = dp(8) }
                UiAnimations.attachSnappyPress(this)
                setOnClickListener {
                    DevicePrefs.setCustomName(this@buildSettingsSubPage, etDeviceName.text.toString())
                    val updated = DevicePrefs.getDeviceStamp(this@buildSettingsSubPage)
                    tvCurrentStamp.text = updated
                    Toast.makeText(this@buildSettingsSubPage, "Updated: $updated", Toast.LENGTH_SHORT).show()
                }
            })
            fieldCard.addView(nameRow)
            body.addView(fieldCard)
        }

        SettingsSection.OFFLINE_CACHE -> {
            body.addView(TextView(this).apply {
                text = "Each offline map pack bundles vector tiles and embedded turn-by-turn road routing."
                textSize = 12.5f
                setTextColor(txtSub)
                setPadding(dp(4), 0, dp(4), dp(10))
            })

            val packUpdaters = mutableListOf<() -> Unit>()
            val pixelRatio = resources.displayMetrics.density

            fun addOfflineCard(
                packId: String,
                title: String,
                subtitle: String,
                onDownload: () -> Unit,
                onDownloadRoadsOnly: () -> Unit
            ) {
                val card = DesignSystem.createGroupedCard(this, p, radiusDp = 16f, paddingHDp = 14, paddingVDp = 12)
                val topRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val iconBadge = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                        marginEnd = dp(10)
                    }
                }
                val iconView = ImageView(this).apply {
                    setImageResource(R.drawable.ic_map)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                }
                iconBadge.addView(iconView)
                topRow.addView(iconBadge)

                topRow.addView(TextView(this).apply {
                    text = title
                    textSize = 15.5f
                    setTextColor(txtPrimary)
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8)
                    }
                })
                val tvStatusPill = TextView(this).apply {
                    textSize = 10.5f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    DesignSystem.applyTabularNumerals(this)
                }
                topRow.addView(tvStatusPill)
                card.addView(topRow)

                card.addView(TextView(this).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(txtSub)
                    setPadding(0, dp(6), 0, dp(8))
                })

                val chipRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(4))
                }
                fun makePackChip(initialText: String, addEndGap: Boolean): TextView {
                    return TextView(this).apply {
                        text = initialText
                        textSize = 11f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                        DesignSystem.applyTabularNumerals(this)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            if (addEndGap) marginEnd = dp(6)
                        }
                    }
                }
                val tvTileChip = makePackChip(AppStrings.vectorTiles(this), addEndGap = true)
                val tvRoadChip = makePackChip(AppStrings.roadGraph(this), addEndGap = false)
                chipRow.addView(tvTileChip)
                chipRow.addView(tvRoadChip)
                card.addView(chipRow)

                val tvStatus = TextView(this).apply {
                    textSize = 12.5f
                    setTextColor(p.textSecondary)
                    DesignSystem.applyTabularNumerals(this)
                    visibility = View.GONE
                    setPadding(0, dp(4), 0, 0)
                }
                card.addView(tvStatus)

                val tvRoadStatus = TextView(this).apply {
                    textSize = 12f
                    setTextColor(p.textSecondary)
                    DesignSystem.applyTabularNumerals(this)
                    visibility = View.GONE
                    setPadding(0, dp(3), 0, 0)
                }
                card.addView(tvRoadStatus)

                val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    visibility = View.GONE
                    progressTintList = ColorStateList.valueOf(p.accentPrimary)
                    indeterminateTintList = ColorStateList.valueOf(p.accentPrimary)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                card.addView(progressBar)

                val btnRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                }
                val btnDl = MaterialButton(this).apply {
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    isAllCaps = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(12), 0, dp(12), 0)
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(p.accentPrimary)
                    cornerRadius = dp(12)
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
                    UiAnimations.attachSnappyPress(this)
                }
                val btnDel = MaterialButton(this).apply {
                    text = AppStrings.delete(this@buildSettingsSubPage)
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    isAllCaps = false
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(12), 0, dp(12), 0)
                    setTextColor(p.red)
                    backgroundTintList = ColorStateList.valueOf(p.redSoft)
                    strokeWidth = dp(1)
                    strokeColor = ColorStateList.valueOf(
                        Color.argb(if (p.isDark) 76 else 56, Color.red(p.red), Color.green(p.red), Color.blue(p.red))
                    )
                    cornerRadius = dp(12)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42))
                }
                val btnRetryRoads = MaterialButton(this).apply {
                    text = "Retry Roads"
                    textSize = 12.5f
                    isAllCaps = false
                    maxLines = 1
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(12), 0, dp(12), 0)
                    cornerRadius = dp(12)
                    DesignSystem.styleSegmentedButton(this, false, p, onTonalCard = true)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
                        topMargin = dp(8)
                    }
                    visibility = View.GONE
                    UiAnimations.attachSnappyPress(this)
                }

                fun refreshCard() {
                    val st = offlineMaps.getState(packId)
                    val busy = st.isDownloading || st.isRoadDownloading
                    val fullyReady = st.isDownloaded && st.hasRoadGraph
                    val hasPartialTiles = !st.isDownloaded && (st.completedResources > 0L || st.hasPartialMapData)
                    val hasAnyContent = st.hasAnyData
                    val sizeStr = offlineMaps.formatSize(st.sizeBytes)

                    val (pillLabel, pillFg, pillBg) = when {
                        st.mapError != null && !st.isDownloaded -> Triple("Tile Error", p.red, p.redSoft)
                        st.roadError != null && !st.hasRoadGraph -> Triple("Roads Error", p.red, p.redSoft)
                        busy -> Triple(AppStrings.downloading(this@buildSettingsSubPage), p.accentPrimary, p.accentPrimarySoft)
                        fullyReady -> Triple(AppStrings.offlineReady(this@buildSettingsSubPage), p.green, p.greenSoft)
                        st.isDownloaded || st.hasRoadGraph || hasPartialTiles -> Triple(AppStrings.partial(this@buildSettingsSubPage), p.amber, p.amberSoft)
                        else -> Triple(AppStrings.onlineOnly(this@buildSettingsSubPage), p.textMuted, p.surfaceFloating)
                    }
                    tvStatusPill.text = pillLabel
                    tvStatusPill.setTextColor(pillFg)
                    tvStatusPill.background = DesignSystem.buildOpaqueDrawable(
                        ctx = this@buildSettingsSubPage,
                        fillColor = pillBg,
                        radiusDp = 10f,
                        strokeColor = if (!hasAnyContent && !busy && st.mapError == null && st.roadError == null) p.borderSubtle else Color.TRANSPARENT,
                        strokeDp = 1f
                    )
                    iconBadge.background = DesignSystem.buildOpaqueDrawable(
                        ctx = this@buildSettingsSubPage,
                        fillColor = if (fullyReady || busy) p.accentPrimarySoft else p.surfaceFloating,
                        radiusDp = 10f,
                        strokeColor = p.borderSubtle,
                        strokeDp = if (fullyReady || busy) 0f else 1f
                    )
                    iconView.setColorFilter(if (fullyReady || busy) p.accentPrimary else p.textMuted)

                    fun styleChip(chip: TextView, label: String, ready: Boolean, failed: Boolean) {
                        chip.text = label
                        chip.setTextColor(
                            when {
                                failed -> p.red
                                ready -> p.green
                                else -> p.textMuted
                            }
                        )
                        chip.background = DesignSystem.buildOpaqueDrawable(
                            ctx = this@buildSettingsSubPage,
                            fillColor = when {
                                failed -> p.redSoft
                                ready -> p.greenSoft
                                else -> p.surfaceFloating
                            },
                            radiusDp = 8f,
                            strokeColor = if (!ready && !failed) p.borderSubtle else Color.TRANSPARENT,
                            strokeDp = 1f
                        )
                    }
                    styleChip(
                        tvTileChip,
                        when {
                            st.isDownloaded -> "Tiles • $sizeStr"
                            hasPartialTiles -> "Tiles • ${st.progressPercent}%"
                            else -> AppStrings.vectorTiles(this@buildSettingsSubPage)
                        },
                        ready = st.isDownloaded,
                        failed = st.mapError != null && !st.isDownloaded
                    )
                    styleChip(
                        tvRoadChip,
                        when {
                            st.hasRoadGraph -> "Roads • ${offlineMaps.formatSize(st.roadSizeBytes)}"
                            st.isRoadQueued -> "Roads • Queued"
                            else -> AppStrings.roadGraph(this@buildSettingsSubPage)
                        },
                        ready = st.hasRoadGraph,
                        failed = st.roadError != null && !st.hasRoadGraph
                    )

                    val (mapText, mapColor, mapBold) = when {
                        st.mapError != null && !st.isDownloaded ->
                            Triple("Map Tiles: ${st.mapError}", p.red, true)
                        st.isDownloading -> {
                            if (st.requiredResources > 0) {
                                val countPart = " (${st.completedResources}/${st.requiredResources})"
                                val sizePart = if (st.sizeBytes > 0L) " • $sizeStr" else ""
                                Triple("Map Tiles: ${AppStrings.downloading(this@buildSettingsSubPage)} ${st.progressPercent}%$countPart$sizePart", p.accentPrimary, true)
                            } else {
                                val detail = st.statusText.ifBlank { "${AppStrings.downloading(this@buildSettingsSubPage)} ${st.progressPercent}%" }
                                Triple("Map Tiles: $detail", p.accentPrimary, true)
                            }
                        }
                        hasPartialTiles -> {
                            val pausedDetail = if (st.requiredResources > 0) {
                                "Paused ${st.progressPercent}% (${st.completedResources}/${st.requiredResources}) • $sizeStr"
                            } else {
                                st.statusText.ifBlank { "Paused ${st.progressPercent}% • $sizeStr" }
                            }
                            Triple("Map Tiles: $pausedDetail", if (p.isDark) p.amber else Color.parseColor("#B36B00"), false)
                        }
                        else -> Triple("", p.textSecondary, false)
                    }
                    if (mapText.isNotEmpty()) {
                        tvStatus.text = mapText
                        tvStatus.setTextColor(mapColor)
                        tvStatus.setTypeface(null, if (mapBold) Typeface.BOLD else Typeface.NORMAL)
                        tvStatus.visibility = View.VISIBLE
                    } else {
                        tvStatus.visibility = View.GONE
                    }

                    if (st.roadError != null || st.isRoadDownloading || st.isRoadQueued) {
                        val (roadText, roadColor, roadBold) = when {
                            st.roadError != null ->
                                Triple("Roads failed: ${st.roadError}", p.red, true)
                            st.isRoadDownloading ->
                                Triple("Road Routing: ${st.roadStatusText.ifBlank { "${AppStrings.downloading(this@buildSettingsSubPage)}..." }}", p.accentPrimary, true)
                            else ->
                                Triple("Road Routing: Queued after map tiles finish", p.textSecondary, false)
                        }
                        tvRoadStatus.text = roadText
                        tvRoadStatus.setTextColor(roadColor)
                        tvRoadStatus.setTypeface(null, if (roadBold) Typeface.BOLD else Typeface.NORMAL)
                        tvRoadStatus.visibility = View.VISIBLE
                    } else {
                        tvRoadStatus.visibility = View.GONE
                    }

                    val showProgress = busy || hasPartialTiles
                    progressBar.visibility = if (showProgress) View.VISIBLE else View.GONE
                    val activeTint = if (busy) p.accentPrimary else p.textMuted
                    progressBar.progressTintList = ColorStateList.valueOf(activeTint)
                    progressBar.indeterminateTintList = ColorStateList.valueOf(activeTint)
                    if (st.isDownloading) {
                        progressBar.isIndeterminate = st.requiredResources <= 0L && st.progressPercent <= 0
                        if (st.progressPercent > 0 || st.requiredResources > 0L) {
                            progressBar.setProgress(st.progressPercent, !UiAnimations.isReducedMotion(this@buildSettingsSubPage))
                        }
                    } else if (st.isRoadDownloading) {
                        progressBar.isIndeterminate = st.roadProgressPercent <= 0
                        if (st.roadProgressPercent > 0) {
                            progressBar.setProgress(st.roadProgressPercent, !UiAnimations.isReducedMotion(this@buildSettingsSubPage))
                        }
                    } else {
                        progressBar.isIndeterminate = false
                        progressBar.progress = st.progressPercent
                    }

                    btnDl.text = when {
                        busy -> AppStrings.pause(this@buildSettingsSubPage)
                        fullyReady -> AppStrings.offlineReady(this@buildSettingsSubPage)
                        st.isDownloaded && !st.hasRoadGraph && st.roadError != null -> "Retry Roads"
                        st.isDownloaded && !st.hasRoadGraph -> "Download Offline Roads"
                        hasPartialTiles -> AppStrings.resumeDownload(this@buildSettingsSubPage)
                        else -> AppStrings.downloadMapAndRoads(this@buildSettingsSubPage)
                    }
                    btnDl.isEnabled = !fullyReady || busy
                    val dlBg = if (btnDl.isEnabled) p.accentPrimary else p.accentPrimarySoft
                    val dlFg = if (btnDl.isEnabled) Color.WHITE else p.accentPrimary
                    btnDl.backgroundTintList = ColorStateList.valueOf(dlBg)
                    btnDl.setTextColor(dlFg)
                    btnDl.alpha = 1f

                    val showDelete = hasAnyContent || st.roadError != null || st.mapError != null
                    btnDel.visibility = if (showDelete) View.VISIBLE else View.GONE
                    (btnDl.layoutParams as LinearLayout.LayoutParams).marginEnd = if (showDelete) dp(8) else 0

                    val showSeparateRoadRetry = !st.isDownloaded && !st.hasRoadGraph && !busy &&
                        (st.roadError != null || hasPartialTiles)
                    btnRetryRoads.visibility = if (showSeparateRoadRetry) View.VISIBLE else View.GONE
                    btnRetryRoads.text = if (st.roadError != null) "Retry Roads Only" else "Download Roads Only"
                }

                btnDl.setOnClickListener {
                    val st = offlineMaps.getState(packId)
                    when {
                        st.isDownloading || st.isRoadDownloading -> offlineMaps.pauseDownload(packId)
                        st.isDownloaded && !st.hasRoadGraph -> onDownloadRoadsOnly()
                        else -> onDownload()
                    }
                }
                btnRetryRoads.setOnClickListener {
                    onDownloadRoadsOnly()
                }
                btnDel.setOnClickListener {
                    val st = offlineMaps.getState(packId)
                    if (!st.hasAnyData && st.roadError == null && st.mapError == null) {
                        return@setOnClickListener
                    }
                    showThemedDialog(
                        themedDialogBuilder("Delete \"$title\" Offline Pack?")
                            .setMessage("This removes downloaded offline map tiles, partial road files, and offline routing data for $title from this phone.")
                            .setPositiveButton(AppStrings.delete(this@buildSettingsSubPage)) { _, _ ->
                                offlineMaps.deletePack(packId) { msg ->
                                    runOnUiThread { Toast.makeText(this@buildSettingsSubPage, msg, Toast.LENGTH_SHORT).show() }
                                }
                            }
                            .setNegativeButton(AppStrings.cancel(this@buildSettingsSubPage), null)
                            .create(),
                        destructivePositive = true
                    )
                }

                btnRow.addView(btnDl)
                btnRow.addView(btnDel)
                card.addView(btnRow)
                card.addView(btnRetryRoads)
                body.addView(card)

                refreshCard()
                packUpdaters += ::refreshCard
            }

            OfflineMapManager.REGIONAL_PACKS.forEach { pack ->
                addOfflineCard(
                    pack.id,
                    pack.title,
                    pack.subtitle,
                    onDownload = {
                        ensureNotificationPermissionForDownloads()
                        offlineMaps.downloadPack(pack, pixelRatio)
                    },
                    onDownloadRoadsOnly = {
                        ensureNotificationPermissionForDownloads()
                        offlineMaps.downloadRoadGraphOnly(pack)
                    }
                )
            }

            val tvCacheSize = TextView(this).apply {
                text = "…"
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(p.textSecondary)
                DesignSystem.applyTabularNumerals(this)
                setPadding(dp(9), dp(4), dp(9), dp(4))
                background = DesignSystem.buildOpaqueDrawable(
                    ctx = this@buildSettingsSubPage,
                    fillColor = p.surfaceFloating,
                    radiusDp = 10f,
                    strokeColor = p.borderSubtle,
                    strokeDp = 1f
                )
            }

            fun refreshCacheSize() {
                RoutingEngine.ioExecutor.execute {
                    val bytes = offlineMaps.estimateAmbientCacheBytes()
                    val label = offlineMaps.formatSize(bytes)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            tvCacheSize.text = label
                        }
                    }
                }
            }

            val cacheCard = DesignSystem.createGroupedCard(
                this, p, radiusDp = 16f, paddingHDp = 14, paddingVDp = 12, bottomMarginDp = 4
            ).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                UiAnimations.attachSnappyPress(this, 0.985f)
                setOnClickListener {
                    offlineMaps.clearAmbientCache { msg ->
                        runOnUiThread {
                            Toast.makeText(this@buildSettingsSubPage, msg, Toast.LENGTH_SHORT).show()
                            refreshCacheSize()
                            if (mapStyleMode == MapStyleMode.VECTOR) loadMapStyle()
                        }
                    }
                }
            }
            val trashBadge = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
                background = DesignSystem.buildOpaqueDrawable(
                    ctx = this@buildSettingsSubPage,
                    fillColor = p.surfaceFloating,
                    radiusDp = 18f,
                    strokeColor = p.borderSubtle,
                    strokeDp = 1f
                )
            }
            trashBadge.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_trash)
                setColorFilter(p.textMuted)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            })
            cacheCard.addView(trashBadge)

            val cacheTextCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
            }
            cacheTextCol.addView(TextView(this).apply {
                text = AppStrings.clearTempCache(this@buildSettingsSubPage)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(txtPrimary)
            })
            cacheTextCol.addView(TextView(this).apply {
                text = AppStrings.clearTempCacheSub(this@buildSettingsSubPage)
                textSize = 12f
                setTextColor(txtSub)
            })
            cacheCard.addView(cacheTextCol)
            cacheCard.addView(tvCacheSize)
            body.addView(cacheCard)
            refreshCacheSize()

            offlineMaps.setStateListener {
                runOnUiThread { packUpdaters.forEach { it() } }
            }
            offlineMaps.refreshAll()
            activePageCleanup = { offlineMaps.setStateListener(null) }
        }

        SettingsSection.ABOUT_BUILD -> {
            val aboutCard = DesignSystem.createGroupedCard(this, p, radiusDp = 16f, paddingHDp = 14, paddingVDp = 6)

            addCardSwitch(
                aboutCard,
                title = "Reduce Motion",
                subtitle = "Disable slide transitions for instant screen swaps",
                checked = DevicePrefs.isReducedMotion(this),
                addDivider = false
            ) { DevicePrefs.setReducedMotion(this, it) }

            aboutCard.addView(DesignSystem.createHairlineDivider(this, p))
            aboutCard.addView(TextView(this).apply {
                text = "TerraSync v${getInstalledVersionName()} • MapLibre GL"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(txtPrimary)
                DesignSystem.applyTabularNumerals(this)
                setPadding(0, dp(10), 0, dp(2))
            })
            aboutCard.addView(TextView(this).apply {
                val diag = "Device #${DevicePrefs.getDeviceId(this@buildSettingsSubPage)} • ${layers.size} folders • ${pins.size} pins • ${tracks.size} tracks"
                text = diag
                textSize = 12f
                setTextColor(txtSub)
                DesignSystem.applyTabularNumerals(this)
                setPadding(0, 0, 0, dp(10))
            })
            aboutCard.addView(MaterialButton(this).apply {
                text = "Open About & Diagnostics"
                textSize = 13f
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                    bottomMargin = dp(8)
                }
                DesignSystem.styleSegmentedButton(this, false, p, onTonalCard = true)
                UiAnimations.attachSnappyPress(this)
                setOnClickListener { openFullPage(::buildAboutPage) }
            })
            body.addView(aboutCard)

            val backupCard = DesignSystem.createGroupedCard(this, p, radiusDp = 16f, paddingHDp = 14, paddingVDp = 12).apply {
                val lp = (layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(12)
                layoutParams = lp
            }
            backupCard.addView(TextView(this).apply {
                text = "Backup & Restore (.tsbackup)"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(txtPrimary)
            })
            backupCard.addView(TextView(this).apply {
                text = "Save or restore all local folders, pins, tracks, sent flags, and app settings in one file."
                textSize = 12f
                setTextColor(txtSub)
                setPadding(0, dp(3), 0, dp(10))
            })
            val backupBtnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            backupBtnRow.addView(MaterialButton(this).apply {
                text = "Export Backup"
                textSize = 13f
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
                DesignSystem.applyPrimaryCtaButton(this, p)
                UiAnimations.attachSnappyPress(this)
                setOnClickListener { launchExportBackup() }
            })
            backupBtnRow.addView(MaterialButton(this).apply {
                text = "Restore Backup"
                textSize = 13f
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
                DesignSystem.styleSegmentedButton(this, false, p, onTonalCard = true)
                UiAnimations.attachSnappyPress(this)
                setOnClickListener { launchRestoreBackup() }
            })
            backupCard.addView(backupBtnRow)
            body.addView(backupCard)
        }
    }

    return outer
}
