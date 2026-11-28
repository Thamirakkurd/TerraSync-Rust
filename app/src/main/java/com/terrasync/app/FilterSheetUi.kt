package com.terrasync.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch

internal fun MainActivity.showMultiStatusFilterDialog() {
    sheetQuickSelectPinId = null
    val p = currentPalette()
    val night = p.isDark
    val isRtl = AppStrings.isRtl(this)

    clearSheetStickyHeader()
    sheetContent.removeAllViews()
    sheetContent.layoutDirection = if (isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

    val hiddenLayerIds = DevicePrefs.getHiddenLayerIds(this)
    var cntDone = 0
    var cntIssue = 0
    var cntPending = 0
    for (pin in pins) {
        if (pin.layerId in hiddenLayerIds) continue
        when (PinStatus.fromRaw(pin.status)) {
            PinStatus.DONE -> cntDone++
            PinStatus.ISSUE -> cntIssue++
            PinStatus.PENDING -> cntPending++
        }
    }
    val visibleFolderPinCount = cntDone + cntIssue + cntPending

    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(2)
            bottomMargin = dp(14)
        }
    }
    val titleCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        }
    }
    titleCol.addView(TextView(this).apply {
        text = AppStrings.filterPins(this@showMultiStatusFilterDialog)
        textSize = 19.5f
        letterSpacing = -0.01f
        setTypeface(null, Typeface.BOLD)
        setTextColor(p.textPrimary)
    })
    titleCol.addView(TextView(this).apply {
        text = AppStrings.pinsInVisibleFolders(this@showMultiStatusFilterDialog, visibleFolderPinCount)
        textSize = 12.5f
        setTextColor(p.textMuted)
        DesignSystem.applyTabularNumerals(this)
        setPadding(0, dp(2), 0, 0)
    })
    header.addView(titleCol)

    val btnResetFilter = TextView(this).apply {
        text = AppStrings.reset(this@showMultiStatusFilterDialog)
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(p.accentPrimary)
        setPadding(dp(11), dp(6), dp(11), dp(6))
        UiAnimations.applyRoundedRipple(this@showMultiStatusFilterDialog, this, p.accentPrimarySoft, 14f, night)
        UiAnimations.attachSnappyPress(this)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(6)
        }
    }
    header.addView(btnResetFilter)
    header.addView(DesignSystem.createSheetCloseButton(this, p) { hideInScreenSheet() })
    sheetContent.addView(header)

    val pillsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(12)
        }
    }

    data class FilterPillUi(
        val key: String,
        val card: LinearLayout,
        val tvCount: TextView,
        val tvLabel: TextView,
        val solidColor: Int,
        val softColor: Int
    )

    val pillItems = mutableListOf<FilterPillUi>()
    var updatingUi = false
    lateinit var swUnsent: MaterialSwitch

    fun refreshFilterVisuals() {
        updatingUi = true
        pillItems.forEach { item ->
            val active = visibleStatuses.contains(item.key)
            item.card.background = if (active) {
                DesignSystem.buildOpaqueDrawable(
                    ctx = this,
                    fillColor = item.softColor,
                    radiusDp = 16f,
                    strokeColor = item.solidColor,
                    strokeDp = 1.5f
                )
            } else {
                DesignSystem.buildOpaqueDrawable(
                    ctx = this,
                    fillColor = p.surfaceTonal,
                    radiusDp = 16f,
                    strokeColor = p.borderSubtle,
                    strokeDp = 1f
                )
            }
            item.tvCount.setTextColor(if (active) item.solidColor else p.textMuted)
            item.tvLabel.setTextColor(if (active) p.textPrimary else p.textMuted)
            item.tvLabel.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            item.card.alpha = if (active) 1f else 0.72f
        }
        swUnsent.isChecked = hideSentPins
        btnResetFilter.visibility =
            if (!visibleStatuses.containsAll(MainActivity.ALL_STATUSES) || hideSentPins) View.VISIBLE else View.GONE
        updatingUi = false
    }

    fun applyFilterChanges() {
        refreshFilterVisuals()
        DevicePrefs.setVisibleStatuses(this, visibleStatuses)
        DevicePrefs.setHideSentPins(this, hideSentPins)
        updateFilterIconTint()
        updateSentToggleUi()
        renderMapData()
    }

    val statusDefs = listOf(
        listOf("pending", AppStrings.pending(this), cntPending, p.amber, p.amberSoft),
        listOf("done", AppStrings.done(this), cntDone, p.green, p.greenSoft),
        listOf("issue", AppStrings.issue(this), cntIssue, p.red, p.redSoft)
    )
    statusDefs.forEachIndexed { idx, def ->
        val key = def[0] as String
        val labelText = def[1] as String
        val count = def[2] as Int
        val solidColor = def[3] as Int
        val softColor = def[4] as Int

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(68)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx < statusDefs.lastIndex) marginEnd = dp(8)
            }
            UiAnimations.attachSnappyPress(this, 0.95f)
        }
        val topLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        topLine.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(solidColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
        })
        val tvCount = TextView(this).apply {
            text = "$count"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            DesignSystem.applyTabularNumerals(this)
        }
        topLine.addView(tvCount)
        card.addView(topLine)

        val tvLabel = TextView(this).apply {
            text = labelText
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        card.addView(tvLabel)

        card.setOnClickListener {
            UiAnimations.hapticTick(card)
            if (visibleStatuses.contains(key)) visibleStatuses.remove(key) else visibleStatuses.add(key)
            UiAnimations.animateScalePop(card, 0.94f, 140L)
            applyFilterChanges()
        }

        pillItems += FilterPillUi(key, card, tvCount, tvLabel, solidColor, softColor)
        pillsRow.addView(card)
    }
    sheetContent.addView(pillsRow)

    val unsentCard = DesignSystem.createGroupedCard(
        this, p, radiusDp = 16f, paddingHDp = 14, paddingVDp = 4, bottomMarginDp = 4
    )
    val sentCount = activeLocalSentCount()
    val unsentRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(54)
        setPadding(dp(2), dp(6), dp(2), dp(6))
        UiAnimations.applyRowRipple(this@showMultiStatusFilterDialog, this, night)
    }
    val unsentTextCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(10)
        }
    }
    unsentTextCol.addView(TextView(this).apply {
        text = AppStrings.unsentOnly(this@showMultiStatusFilterDialog)
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        setTextColor(p.textPrimary)
    })
    unsentTextCol.addView(TextView(this).apply {
        text = AppStrings.hideSentPinsSub(this@showMultiStatusFilterDialog, sentCount)
        textSize = 12f
        setTextColor(p.textSecondary)
        DesignSystem.applyTabularNumerals(this)
    })
    swUnsent = MaterialSwitch(this).apply {
        isChecked = hideSentPins
        DesignSystem.styleMaterialSwitch(this, p)
        setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            hideSentPins = checked
            applyFilterChanges()
        }
    }
    unsentRow.setOnClickListener { swUnsent.toggle() }
    unsentRow.addView(unsentTextCol)
    unsentRow.addView(swUnsent)
    unsentCard.addView(unsentRow)
    sheetContent.addView(unsentCard)

    btnResetFilter.setOnClickListener {
        UiAnimations.hapticTick(btnResetFilter)
        visibleStatuses.clear()
        visibleStatuses.addAll(MainActivity.ALL_STATUSES)
        hideSentPins = false
        applyFilterChanges()
    }

    refreshFilterVisuals()
    UiAnimations.animateSheetContentReveal(sheetContent)
    showInScreenSheet(peekDp = 280, expandToFit = true)
}
