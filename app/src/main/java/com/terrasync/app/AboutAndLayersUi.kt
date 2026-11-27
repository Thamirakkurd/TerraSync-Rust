package com.terrasync.app

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

private const val GITHUB_PROFILE_URL = "https://github.com/Thamirakkurd"
private const val GITHUB_REPO_URL = "https://github.com/Thamirakkurd/TerraSync-Rust"
private const val GITHUB_RELEASES_URL = "https://github.com/Thamirakkurd/TerraSync-Rust/releases"

internal fun MainActivity.showMapLayersSheet() {
    val p = currentPalette()
    val d = resources.displayMetrics.density
    val isRtl = AppStrings.isRtl(this)

    DesignSystem.showActionBottomSheet(
        ctx = this,
        palette = p,
        title = AppStrings.mapLayers(this),
        subtitle = AppStrings.mapLayersSubtitle(this),
        dimAmount = 0.18f,
        actions = emptyList(),
        headerContent = { listCol, dismiss ->
            listCol.layoutDirection = if (isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            val swatchRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(4)
                }
            }

            MapStyleMode.entries.forEachIndexed { idx, mode ->
                val active = mode == mapStyleMode
                val isSat = mode == MapStyleMode.SATELLITE
                val modeTitle = if (isSat) AppStrings.satelliteHybrid(this) else AppStrings.vectorStreet(this)
                val modeSub = if (isSat) AppStrings.satelliteHybridSub(this) else AppStrings.vectorStreetSub(this)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(6), dp(6), dp(8))
                    isClickable = true
                    isFocusable = true
                    background = DesignSystem.buildOpaqueDrawable(
                        ctx = this@showMapLayersSheet,
                        fillColor = if (active) p.accentPrimarySoft else p.surfaceTonal,
                        radiusDp = 18f,
                        strokeColor = if (active) p.accentPrimary else p.borderSubtle,
                        strokeDp = if (active) 2f else 1f
                    )
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx == 0) marginEnd = dp(10)
                    }
                    UiAnimations.attachSnappyPress(this, 0.97f)
                    setOnClickListener {
                        UiAnimations.hapticTick(this)
                        dismiss()
                        if (mapStyleMode != mode) {
                            mapStyleMode = mode
                            DevicePrefs.setMapStyleMode(this@showMapLayersSheet, mode)
                            applyMapChromeTheme()
                            loadMapStyle()
                            Toast.makeText(this@showMapLayersSheet, modeTitle, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val previewFrame = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                    }
                    clipToOutline = true
                }
                val swatchGraphic = object : View(this) {
                    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    private val riverPath = Path()
                    private val patchPath = Path()

                    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                        super.onSizeChanged(w, h, oldw, oldh)
                        val wf = w.toFloat()
                        val hf = h.toFloat()
                        riverPath.reset()
                        riverPath.moveTo(-4f * d, hf * 0.72f)
                        riverPath.cubicTo(wf * 0.30f, hf * 0.50f, wf * 0.62f, hf * 0.88f, wf + 4f * d, hf * 0.38f)

                        patchPath.reset()
                        patchPath.moveTo(wf * 0.58f, 0f)
                        patchPath.lineTo(wf, 0f)
                        patchPath.lineTo(wf, hf * 0.56f)
                        patchPath.cubicTo(wf * 0.76f, hf * 0.44f, wf * 0.64f, hf * 0.24f, wf * 0.58f, 0f)
                        patchPath.close()
                    }

                    override fun onDraw(canvas: Canvas) {
                        val wf = width.toFloat()
                        val hf = height.toFloat()
                        if (wf <= 0f || hf <= 0f) return
                        val nightPreview = p.isDark

                        if (isSat) {
                            canvas.drawColor(Color.parseColor("#243227"))
                            paint.style = Paint.Style.FILL
                            paint.color = Color.parseColor("#2E4130")
                            canvas.drawPath(patchPath, paint)
                            paint.color = Color.parseColor("#3B4732")
                            canvas.drawCircle(wf * 0.22f, hf * 0.28f, wf * 0.28f, paint)
                            paint.color = Color.parseColor("#1D332B")
                            canvas.drawCircle(wf * 0.82f, hf * 0.84f, wf * 0.26f, paint)

                            roadPaint.color = Color.parseColor("#1B3B45")
                            roadPaint.strokeWidth = 8.5f * d
                            canvas.drawPath(riverPath, roadPaint)

                            roadPaint.color = Color.parseColor("#73FFFFFF")
                            roadPaint.strokeWidth = 1.8f * d
                            canvas.drawLine(0f, hf * 0.32f, wf, hf * 0.24f, roadPaint)
                            canvas.drawLine(wf * 0.36f, 0f, wf * 0.50f, hf, roadPaint)

                            roadPaint.color = Color.parseColor("#CCEB9E38")
                            roadPaint.strokeWidth = 2.6f * d
                            canvas.drawLine(0f, hf * 0.54f, wf, hf * 0.48f, roadPaint)
                        } else {
                            canvas.drawColor(Color.parseColor(if (nightPreview) "#1F2622" else "#EDEFE9"))
                            paint.style = Paint.Style.FILL
                            paint.color = Color.parseColor(if (nightPreview) "#24382C" else "#D5E8D0")
                            canvas.drawPath(patchPath, paint)
                            paint.color = Color.parseColor(if (nightPreview) "#242A26" else "#E2E4DD")
                            canvas.drawRect(wf * 0.08f, hf * 0.14f, wf * 0.30f, hf * 0.38f, paint)
                            canvas.drawRect(wf * 0.42f, hf * 0.58f, wf * 0.64f, hf * 0.82f, paint)

                            roadPaint.color = Color.parseColor(if (nightPreview) "#20404D" else "#B8DDF0")
                            roadPaint.strokeWidth = 7.5f * d
                            canvas.drawPath(riverPath, roadPaint)

                            roadPaint.color = Color.parseColor(if (nightPreview) "#3B423D" else "#FFFFFF")
                            roadPaint.strokeWidth = 2.8f * d
                            canvas.drawLine(0f, hf * 0.30f, wf, hf * 0.22f, roadPaint)
                            canvas.drawLine(wf * 0.36f, 0f, wf * 0.48f, hf, roadPaint)
                            canvas.drawLine(wf * 0.72f, 0f, wf * 0.64f, hf, roadPaint)

                            roadPaint.color = Color.parseColor(if (nightPreview) "#A86C2B" else "#F2C478")
                            roadPaint.strokeWidth = 3.6f * d
                            canvas.drawLine(0f, hf * 0.54f, wf, hf * 0.48f, roadPaint)
                        }

                        fun drawPinDot(cx: Float, cy: Float, fillHex: String) {
                            paint.color = Color.WHITE
                            canvas.drawCircle(cx, cy, 4.3f * d, paint)
                            paint.color = Color.parseColor(fillHex)
                            canvas.drawCircle(cx, cy, 3.0f * d, paint)
                        }
                        drawPinDot(wf * 0.31f, hf * 0.45f, "#2E8B47")
                        drawPinDot(wf * 0.68f, hf * 0.33f, "#F0651F")
                    }
                }.apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                previewFrame.addView(swatchGraphic)

                if (active) {
                    val badge = FrameLayout(this).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
                            topMargin = dp(6)
                            marginEnd = dp(6)
                        }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(p.accentPrimary)
                        }
                    }
                    badge.addView(ImageView(this).apply {
                        setImageResource(R.drawable.ic_check)
                        setColorFilter(Color.WHITE)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER)
                    })
                    previewFrame.addView(badge)
                }
                card.addView(previewFrame)

                card.addView(TextView(this).apply {
                    text = modeTitle
                    textSize = 13.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (active) p.accentPrimary else p.textPrimary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(4), dp(8), dp(4), dp(1))
                })
                card.addView(TextView(this).apply {
                    text = modeSub
                    textSize = 11.5f
                    setTextColor(p.textMuted)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(4), 0, dp(4), 0)
                })

                swatchRow.addView(card)
            }
            listCol.addView(swatchRow)
        },
        extraContent = { listCol ->
            listCol.addView(DesignSystem.createSectionHeader(this, AppStrings.visualOverlays(this), p).apply {
                setPadding(dp(4), dp(14), dp(4), dp(8))
            })
            val overlayCard = DesignSystem.createGroupedCard(
                this,
                p,
                radiusDp = 18f,
                paddingHDp = 14,
                paddingVDp = 4,
                bottomMarginDp = 4
            )
            overlayCard.addView(
                DesignSystem.createSettingsSwitchRow(
                    ctx = this,
                    title = AppStrings.mountainHillshading(this),
                    subtitle = AppStrings.mountainHillshadingSub(this),
                    checked = DevicePrefs.isHillshadeEnabled(this),
                    palette = p
                ) { enabled ->
                    DevicePrefs.setHillshadeEnabled(this, enabled)
                    map?.style?.let { st -> if (st.isFullyLoaded) applyHillshadeSettings(st) }
                }
            )
            overlayCard.addView(
                DesignSystem.createSettingsSwitchRow(
                    ctx = this,
                    title = AppStrings.buildings3d(this),
                    subtitle = AppStrings.buildings3dSub(this),
                    checked = DevicePrefs.is3dBuildingsEnabled(this),
                    palette = p
                ) { enabled ->
                    DevicePrefs.set3dBuildingsEnabled(this, enabled)
                    map?.style?.let { st -> if (st.isFullyLoaded) apply3dBuildingsToggle(st) }
                }
            )
            overlayCard.addView(
                DesignSystem.createSettingsSwitchRow(
                    ctx = this,
                    title = AppStrings.elevationContours(this),
                    subtitle = AppStrings.elevationContoursSub(this),
                    checked = DevicePrefs.isContoursEnabled(this),
                    palette = p
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
            )
            listCol.addView(overlayCard)
        }
    )
}

internal fun MainActivity.buildAboutPage(): View {
    val p = currentPalette()
    val txtPrimary = p.textPrimary
    val txtSub = p.textSecondary
    val isRtl = AppStrings.isRtl(this)

    val (outer, body) = makePageFrame(AppStrings.about(this), { closeFullPage() })

    val heroCard = DesignSystem.createGroupedCard(this, p, radiusDp = 16f, paddingHDp = 16, paddingVDp = 16, bottomMarginDp = 12)
    val hero = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(12))
    }
    hero.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_app_icon)
        layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(14) }
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
        clipToOutline = true
    })
    val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    heroText.addView(TextView(this).apply {
        text = "TerraSync Rust"
        textSize = 22f
        letterSpacing = -0.01f
        setTextColor(txtPrimary)
        setTypeface(null, Typeface.BOLD)
    })
    heroText.addView(TextView(this).apply {
        text = "Version ${getInstalledVersionName()} • " + if (RustCoreBridge.isAvailable()) "Rust Core + MapLibre GL" else "Kotlin + MapLibre GL"
        textSize = 12.5f
        setTextColor(p.green)
        setTypeface(null, Typeface.BOLD)
        DesignSystem.applyTabularNumerals(this)
    })
    hero.addView(heroText)
    heroCard.addView(hero)
    heroCard.addView(TextView(this).apply {
        text = AppStrings.aboutTagline(this@buildAboutPage)
        textSize = 13.5f
        setLineSpacing(dp(2).toFloat(), 1.05f)
        setTextColor(txtSub)
    })
    body.addView(heroCard)

    val devCard = DesignSystem.createGroupedCard(this, p, radiusDp = 16f, paddingHDp = 16, paddingVDp = 14, bottomMarginDp = 14)
    devCard.addView(TextView(this).apply {
        text = AppStrings.aboutDeveloperHeader(this@buildAboutPage)
        textSize = 11.5f
        letterSpacing = 0.06f
        setTypeface(null, Typeface.BOLD)
        setTextColor(txtSub)
    })
    devCard.addView(TextView(this).apply {
        text = "Thamir (@Thamirakkurd)"
        textSize = 16.5f
        setTextColor(txtPrimary)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(6))
    })
    devCard.addView(TextView(this).apply {
        text = "Device Stamp: ${DevicePrefs.getDeviceStamp(this@buildAboutPage)}"
        textSize = 12f
        setTextColor(txtSub)
    })
    devCard.addView(TextView(this).apply {
        val rustLive = RustCoreBridge.isAvailable()
        text = if (rustLive) "Native Engine: Rust Active (${RustCoreBridge.activeModulesSummary()})" else "Native Engine: Kotlin Fallback"
        textSize = 12f
        setTextColor(if (rustLive) p.green else txtSub)
        setPadding(0, dp(4), 0, 0)
    })
    devCard.addView(TextView(this).apply {
        val fLabel = if (layers.size == 1) "folder" else "folders"
        val pLabel = if (pins.size == 1) "bookmark" else "bookmarks"
        val tLabel = if (tracks.size == 1) "track" else "tracks"
        val pendingCount = repo.pendingUploadCount()
        val syncState = when {
            pendingCount > 0 -> AppStrings.pendingUploadCount(this@buildAboutPage, pendingCount)
            isLiveConnected -> AppStrings.aboutCloudLive(this@buildAboutPage)
            else -> AppStrings.aboutOfflineCache(this@buildAboutPage)
        }
        text = "Data: ${layers.size} $fLabel • ${pins.size} $pLabel • ${tracks.size} $tLabel • $syncState"
        textSize = 12f
        setTextColor(txtSub)
        DesignSystem.applyTabularNumerals(this)
        setPadding(0, dp(4), 0, 0)
    })
    body.addView(devCard)

    val linksCard = DesignSystem.createGroupedCard(
        this, p, radiusDp = 16f, paddingHDp = 0, paddingVDp = 2, bottomMarginDp = 14
    )
    val linkSpecs = listOf(
        listOf(AppStrings.aboutGithubProfile(this), "@Thamirakkurd", GITHUB_PROFILE_URL, R.drawable.ic_info),
        listOf(AppStrings.aboutRepoTitle(this), AppStrings.aboutRepoSub(this), GITHUB_REPO_URL, R.drawable.ic_layers),
        listOf(AppStrings.aboutReleasesTitle(this), AppStrings.aboutReleasesSub(this), GITHUB_RELEASES_URL, R.drawable.ic_download)
    )
    linkSpecs.forEachIndexed { idx, spec ->
        if (idx > 0) {
            linksCard.addView(DesignSystem.createHairlineDivider(this, p, 62, 14))
        }
        val titleText = spec[0] as String
        val subText = spec[1] as String
        val url = spec[2] as String
        val iconRes = spec[3] as Int

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            UiAnimations.applyRowRipple(this@buildAboutPage, this, p.isDark)
            UiAnimations.attachSnappyPress(this, 0.985f)
            setOnClickListener {
                safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "No browser app installed")
            }
        }
        val badge = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(p.accentPrimarySoft)
            }
        }
        badge.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(p.accentPrimary)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        })
        row.addView(badge)

        val txtCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        txtCol.addView(TextView(this).apply {
            text = titleText
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(txtPrimary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        txtCol.addView(TextView(this).apply {
            text = subText
            textSize = 12f
            setTextColor(txtSub)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(txtCol)

        row.addView(TextView(this).apply {
            text = if (isRtl) "‹" else "›"
            textSize = 20f
            setTextColor(p.textMuted)
        })
        linksCard.addView(row)
    }
    body.addView(linksCard)

    return outer
}
