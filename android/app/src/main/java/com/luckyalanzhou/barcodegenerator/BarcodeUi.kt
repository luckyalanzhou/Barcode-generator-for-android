package com.luckyalanzhou.barcodegenerator

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.text.*
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import android.widget.PopupWindow
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun MainActivity.tabPageIndex(): Int = when (page) {
        "history" -> 1
        "favorites", "favoriteDetail" -> 2
        "settings" -> 3
        "results" -> when (resultsReturnPage) {
            "history" -> 1
            "favorites" -> 2
            "settings" -> 3
            else -> 0
        }
        else -> 0
    }

private val bottomTabIcons = intArrayOf(
    R.drawable.ic_tab_barcode, R.drawable.ic_tab_history, R.drawable.ic_tab_favorite, R.drawable.ic_tab_settings
)
private val bottomTabSelectedIcons = intArrayOf(
    R.drawable.ic_tab_barcode_selected, R.drawable.ic_tab_history_selected,
    R.drawable.ic_tab_favorite_selected, R.drawable.ic_tab_settings_selected
)

/** 使用真实弹簧驱动缩放，不使用 Bounce/OvershootInterpolator。 */
private fun springScale(view: View, start: Float, peak: Float, settle: Float = 1f) {
    val x = SpringAnimation(view, DynamicAnimation.SCALE_X)
    val y = SpringAnimation(view, DynamicAnimation.SCALE_Y)
    fun force(finalPosition: Float) = SpringForce(finalPosition).apply {
        // 低阻尼 + 中低刚度，产生轻微果冻回弹，但不会长时间晃动。
        dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
        // AndroidX 没有 MEDIUM_LOW 常量，600f 是 LOW(200) 与 MEDIUM(1500) 之间的中低刚度。
        stiffness = 600f
    }
    x.spring = force(peak)
    y.spring = force(peak)
    x.addEndListener { _, canceled, _, _ ->
        if (!canceled && settle != peak) {
            SpringAnimation(view, DynamicAnimation.SCALE_X).apply { spring = force(settle); start() }
            SpringAnimation(view, DynamicAnimation.SCALE_Y).apply { spring = force(settle); start() }
        }
    }
    x.cancel(); y.cancel()
    view.scaleX = start
    view.scaleY = start
    x.start(); y.start()
}


internal fun MainActivity.updateTopTabSelection() {
    val selected = tabPageIndex()
    // 浅色模式使用清晰的蓝色强调色，而不是深灰色；选中后不应显得更暗。
    val selectedColor = if (isDark()) 0xfff4f7ff.toInt() else 0xff246fc4.toInt()
    val unselectedColor = if (isDark()) 0xffc4cada.toInt() else 0xff64748b.toInt()
    topTabButtons.forEach { tab ->
        val isSelected = tab.tag == selected
        tab.findViewWithTag<TextView>("tabLabel")?.setTextColor(if (isSelected) selectedColor else unselectedColor)
        tab.findViewWithTag<ImageView>("tabIcon")?.apply {
            val iconResource = if (isSelected) bottomTabSelectedIcons[tab.tag as Int] else bottomTabIcons[tab.tag as Int]
            setImageResource(iconResource)
            setColorFilter(if (isSelected) selectedColor else unselectedColor)
            // 图标切换采用“收缩-注入-回弹”：线性图标切换为面性图标时不会闪现。
            if (isSelected) {
                alpha = 1f
                springScale(this, start = 0.86f, peak = 1.12f, settle = 1.06f)
            } else {
                alpha = 0.82f
                springScale(this, start = scaleX, peak = 1f)
            }
            translationY = if (isSelected) -dp(1).toFloat() else 0f
        }
        // 选中项自身抬升，玻璃表面在图文下方绘制，不会遮挡图标或文字。
        tab.setBackgroundResource(if (isSelected && !tabGlassDragActive) R.drawable.bg_tab_selected else R.drawable.bg_tab)
        // 保持很轻的悬浮距离，避免变成厚重的实体按钮。
        tab.elevation = if (isSelected && !tabGlassDragActive) dp(1).toFloat() else 0f
        tab.translationZ = 0f
        if (isSelected && !tabGlassDragActive) {
            // 激活背景轻微压缩后拉伸，模拟果冻吸附到当前 Tab 的回弹。
            springScale(tab, start = 0.97f, peak = 1.035f)
        }
    }
}

internal fun MainActivity.isDark() = style.colorScheme == "dark" || (style.colorScheme == "system" && (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)



// iOS 18 风格使用清晰的系统分组背景，避免全局过度透明。
internal fun MainActivity.appBackground() = if (isDark()) 0xff000000.toInt() else 0xfff2f2f7.toInt()
internal fun MainActivity.primaryText() = if (isDark()) 0xfff2f4f7.toInt() else 0xff172033.toInt()


internal fun MainActivity.secondaryText() = if (isDark()) 0xffc5cedb.toInt() else 0xff667085.toInt()


internal fun MainActivity.nextItemId(): Long = (items.maxOfOrNull { it.id } ?: 0L) + 1L


internal fun MainActivity.nextGroupId(): Long = (favoriteGroups.maxOfOrNull { it.id } ?: 0L) + 1L


internal fun MainActivity.inputField(hint: String, value: String = "") = EditText(this).apply {
        this.hint = hint; setText(value); setSingleLine(true); minHeight = dp(48)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = 16f; includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(primaryText()); setHintTextColor(secondaryText())
        setBackgroundResource(R.drawable.bg_input); setPadding(dp(14), 0, dp(14), 0)
    }


internal fun MainActivity.openSettings() {
        val activity = this
        // 已在设置页时只同步视觉状态；不能再次 render，否则某些导航实现会回调选中事件形成递归。
        if (page == "settings") {
            updateTopTabSelection()
            return
        }
        // 分享房间已在离页时关闭，不能把它作为返回页；否则返回设置会自动新建房间。
        settingsReturnPage = if (page == "lanShare") "generate" else page
        if (page == "lanShare") closeLanShare()
        page = "settings"
        render()
    }


internal fun MainActivity.switchTopTabBySwipe(deltaX: Float) {
        val activity = this
        if (kotlin.math.abs(deltaX) < dp(42).toFloat()) return
        val current = tabPageIndex()
        val next = (current + if (deltaX < 0) 1 else -1).coerceIn(0, 3)
        val target = listOf("generate", "history", "favorites", "settings")[next]
        if (next != current) pendingPageTransitionDirection = if (next > current) 1 else -1
        if (target == "settings") settingsReturnPage = if (page == "lanShare") "generate" else page
        if (page == "lanShare" && target != "lanShare") closeLanShare()
        page = target
        render()
    }



internal fun MainActivity.styleButton(button: Button, primary: Boolean = false) = button.apply {
        background = if (primary) glassPrimaryButtonBackground() else glassButtonBackground()
        // 以 Android 系统字实现接近 iOS 的清晰、略带强调的按钮文字，不嵌入受限字体。
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 15f
        letterSpacing = -0.01f
        includeFontPadding = false
        gravity = Gravity.CENTER
        isAllCaps = false
        // 约 2mm 的文字外边距（8dp）；避免按钮边框远大于文字。
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setTextColor(if (primary) Color.WHITE else if (isDark()) 0xffd7e3f5.toInt() else 0xff2453a6.toInt())
        stateListAnimator = null
        elevation = 0f
        // 所有通用按钮共享轻微压下与回弹，模拟玻璃受触时的柔软反馈。
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.975f).scaleY(0.975f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(0.7f)).start()
            }
            false
        }
    }

internal fun MainActivity.glassButtonBackground() = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(14).toFloat()
    setColor(if (isDark()) 0xff2c2c2e.toInt() else 0xffffffff.toInt())
    setStroke(dp(1), if (isDark()) 0xff3a3a3c.toInt() else 0xffd8d8dc.toInt())
}

internal fun MainActivity.glassPrimaryButtonBackground() = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(14).toFloat()
    setColor(if (isDark()) 0xff0a84ff.toInt() else 0xff007aff.toInt())
    setStroke(dp(1), if (isDark()) 0xff4da3ff.toInt() else 0xff007aff.toInt())
}

internal fun MainActivity.applyIos26DialogStyle(dialog: AlertDialog) {
    dialog.window?.setDimAmount(if (isDark()) 0.48f else 0.34f)
    dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
        setColor(if (isDark()) 0xff1c1c1e.toInt() else 0xffffffff.toInt())
        setStroke(dp(1), if (isDark()) 0xff3a3a3c.toInt() else 0xffd8d8dc.toInt())
    })
    dialog.window?.decorView?.elevation = dp(6).toFloat()
    val actionColor = if (isDark()) 0xffa9c4ff.toInt() else 0xff2166d1.toInt()
    val dialogTitleId = resources.getIdentifier("alertTitle", "id", "android")
    dialog.findViewById<TextView>(dialogTitleId)?.apply {
        textSize = 20f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = -0.015f
        includeFontPadding = false
    }
    dialog.findViewById<TextView>(android.R.id.message)?.apply {
        textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
        includeFontPadding = false
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(actionColor)
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(if (isDark()) 0xffc4cada.toInt() else 0xff667085.toInt())
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(actionColor)
    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
        dialog.getButton(which)?.apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = -0.01f
            isAllCaps = false
            // Android 默认按钮有较大的最小宽度；清除后让可见边框贴近文字，
            // 同时保留整块按钮的点击区域。
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
    }
}

internal fun MainActivity.showIos26Dialog(dialog: AlertDialog, compact: Boolean = false): AlertDialog {
    dialog.show()
    applyIos26DialogStyle(dialog)
    // 统一限制弹窗宽度：手机上保持适度留白，大屏上不铺满；同时保留输入和长文本所需的最小宽度。
    val screenWidth = resources.displayMetrics.widthPixels
    val preferredWidth = (screenWidth * if (compact) 0.82f else 0.88f).roundToInt()
    val availableWidth = (screenWidth - dp(24)).coerceAtLeast(1)
    val minWidth = dp(280).coerceAtMost(availableWidth)
    val maxWidth = dp(420).coerceAtMost(availableWidth)
    val dialogMaxWidth = if (compact) dp(360) else dp(400)
    val upperWidth = dialogMaxWidth.coerceAtMost(maxWidth)
    val width = preferredWidth.coerceIn(minWidth.coerceAtMost(upperWidth), upperWidth)
    dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    dialog.window?.setGravity(Gravity.CENTER)
    return dialog
}


internal fun MainActivity.sectionTitle(text: String, subtitle: String? = null): LinearLayout {
        val activity = this
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(4), activity.dp(10), activity.dp(4), activity.dp(12))
            addView(TextView(activity).apply {
                this.text = text; textSize = 22f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = -0.02f; includeFontPadding = false; setLineSpacing(activity.dp(2).toFloat(), 1f); setTextColor(activity.primaryText())
            })
            subtitle?.let { addView(TextView(activity).apply { this.text = it; textSize = 13f; includeFontPadding = false; setLineSpacing(activity.dp(2).toFloat(), 1f); setTextColor(activity.secondaryText()); setPadding(0, activity.dp(5), 0, 0) }) }
        }
    }


internal fun MainActivity.contentCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        // 所有页面卡片统一使用动态液态玻璃，避免设置页仍显示固定浅色卡片。
        background = liquidGlassCard()
        elevation = 0f
        clipToOutline = true
}


internal fun MainActivity.addSpaced(view: View, top: Int = 0, bottom: Int = 10) {
        content.addView(view, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(top), 0, dp(bottom)) })
    }


internal fun MainActivity.buildShell() {
        val activity = this
        val d = resources.displayMetrics.density
        val p = (18 * d).toInt()
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else 0
        // 自绘界面没有自动处理系统栏；底部也要避开三键/手势导航区域，不能让 Tab 压在系统导航栏上。
        val navigationBarId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navigationBar = if (navigationBarId > 0) resources.getDimensionPixelSize(navigationBarId) else 0
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p + statusBar, p, dp(10) + navigationBar); gravity = Gravity.CENTER_HORIZONTAL }
        rootLayout = root
        root.setBackgroundColor(appBackground())
        val title = TextView(this).apply { text = "条码生成器"; textSize = 25f; gravity = Gravity.CENTER_VERTICAL; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = -0.025f; includeFontPadding = false; setTextColor(primaryText()) }
        appTitle = title
        val header = LinearLayout(this).apply {
            // 图标和标题作为一个整体居中，而不是让标题从容器左侧起排。
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(9))
            addView(ImageView(this@buildShell).apply {
                setImageResource(R.drawable.ic_tab_barcode)
                setColorFilter(if (isDark()) 0xffd9e6ff.toInt() else 0xff2166d1.toInt())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                // 顶部标识仅保留符号本身，不再使用圆形玻璃底座。
                setPadding(dp(1), dp(1), dp(1), dp(1))
                contentDescription = "条码生成器"
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(0, 0, dp(9), 0) })
            addView(title, LinearLayout.LayoutParams(-2, dp(46)))
        }
        appHeader = header
        root.addView(header, LinearLayout.LayoutParams(-1, dp(60)))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pageScrollView = object : ScrollView(activity) {
            private fun settingsNeedsScroll(): Boolean {
                val viewportHeight = height - paddingTop - paddingBottom
                return viewportHeight > 0 && content.measuredHeight > viewportHeight
            }
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                // 设置页内容完整时保持固定；只有超出可视区域才接管手势允许滚动。
                return if (page == "generate" || (page == "settings" && !settingsNeedsScroll())) false else super.onInterceptTouchEvent(event)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                return if (page == "settings" && !settingsNeedsScroll()) false else super.onTouchEvent(event)
            }
        }.also { pageScroll = it }.apply {
            addView(content); isFillViewport = true; clipToPadding = false
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(pageScrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        lanShareComposer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        root.addView(lanShareComposer, LinearLayout.LayoutParams(-1, dp(72)).apply { setMargins(0, dp(4), 0, dp(4)) })
        val nav = FrameLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            // 不再绘制底部 Tab 的整体大外框，视觉重点仅留给选中项的玻璃胶囊。
            background = null
            elevation = 0f
            clipChildren = false
        }
        topNav = nav
        val tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(1).toFloat()
            clipChildren = false
            clipToPadding = false
        }
        // 拖动时使用这一层连续跟随手指的玻璃，而非在各 Tab 之间跳换背景。
        val dragGlass = View(this).apply {
            setBackgroundResource(R.drawable.bg_tab_selected)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            elevation = 0f
        }
        tabGlassDragOverlay = dragGlass
        nav.addView(dragGlass, FrameLayout.LayoutParams(0, 0))
        val tabPages = listOf("generate", "history", "favorites", "settings")
        val tabData = listOf(
            Triple("生成", R.drawable.ic_tab_barcode, "生成条码"),
            Triple("历史", R.drawable.ic_tab_history, "历史记录"),
            Triple("收藏", R.drawable.ic_tab_favorite, "收藏夹"),
            Triple("设置", R.drawable.ic_tab_settings, "设置")
        )
        fun activateTab(index: Int) {
            if (index !in tabPages.indices) return
            val current = tabPageIndex()
            if (index != current) pendingPageTransitionDirection = if (index > current) 1 else -1
            if (index == 3) openSettings()
            else if (page != tabPages[index]) { if (page == "lanShare") closeLanShare(); page = tabPages[index]; render() }
            else updateTopTabSelection()
        }
        var touchDownX = 0f
        var isDraggingTab = false
        var lastDraggedTab = -1
        // 玻璃经过图标与文字时，内容本身也以很小的比例被“折射放大”。
        // 直接缩放实际 Tab 内容，比只移动底层背景更接近液态玻璃的局部透镜效果。
        fun updateTabGlassMagnification(rawX: Float) {
            val stripLocation = IntArray(2)
            tabStrip.getLocationOnScreen(stripLocation)
            val pointerX = rawX - stripLocation[0]
            topTabButtons.forEach { tab ->
                if (tab.width <= 0) return@forEach
                val tabCenter = tab.left + tab.width / 2f
                val proximity = (1f - kotlin.math.abs(pointerX - tabCenter) / tab.width)
                    .coerceIn(0f, 1f)
                val lensStrength = proximity * proximity
                tab.scaleX = 1f + 0.075f * lensStrength
                tab.scaleY = 1f + 0.075f * lensStrength
                tab.translationY = -dp(1).toFloat() * lensStrength
                tab.elevation = dp(2).toFloat() * lensStrength
            }
        }
        fun clearTabGlassMagnification(animated: Boolean) {
            topTabButtons.forEach { tab ->
                tab.animate().cancel()
                if (animated) {
                    tab.animate()
                        .scaleX(1f).scaleY(1f).translationY(0f)
                        .setDuration(160)
                        .setInterpolator(OvershootInterpolator(0.55f))
                        .start()
                } else {
                    tab.scaleX = 1f
                    tab.scaleY = 1f
                    tab.translationY = 0f
                }
            }
        }
        fun moveDragGlass(rawX: Float) {
            val overlay = tabGlassDragOverlay ?: return
            val tabWidth = topTabButtons.firstOrNull()?.width ?: return
            if (tabWidth <= 0 || tabStrip.height <= 0) return
            val params = overlay.layoutParams as FrameLayout.LayoutParams
            if (params.width != tabWidth || params.height != tabStrip.height) {
                params.width = tabWidth
                params.height = tabStrip.height
                overlay.layoutParams = params
            }
            val navLocation = IntArray(2)
            nav.getLocationOnScreen(navLocation)
            val desiredLeft = (rawX - navLocation[0] - tabWidth / 2f)
                .coerceIn(tabStrip.left.toFloat(), (tabStrip.right - tabWidth).toFloat())
            overlay.translationX = desiredLeft - overlay.left
            // overlay 高度与 tabStrip 相同；直接对齐顶部，避免首次 layout 前高度为 0 时发生纵向偏移。
            overlay.translationY = tabStrip.top.toFloat() - overlay.top
            // 高光在每个 Tab 的中心最亮、跨越边界时略微变柔，模拟玻璃随内容流动的反射变化。
            val normalizedCenter = (desiredLeft + tabWidth / 2f) / tabWidth
            val distanceToCenter = kotlin.math.abs(normalizedCenter - normalizedCenter.roundToInt())
            overlay.alpha = 0.84f + 0.16f * (1f - (distanceToCenter * 2f).coerceIn(0f, 1f))
            overlay.visibility = View.VISIBLE
            updateTabGlassMagnification(rawX)
        }
        fun finishDragGlass(withBounce: Boolean) {
            val overlay = tabGlassDragOverlay
            val selectedTab = topTabButtons.getOrNull(tabPageIndex())
            if (withBounce && overlay?.visibility == View.VISIBLE && selectedTab != null && overlay.width > 0) {
                // 松手时吸附到目标项，使用轻微过冲的果冻回弹；结束后再固化为静态选中态。
                val targetX = (tabStrip.left + selectedTab.left - overlay.left).toFloat()
                overlay.animate().cancel()
                overlay.animate()
                    .translationX(targetX)
                    .alpha(1f)
                    .setDuration(240)
                    .setInterpolator(OvershootInterpolator(1.15f))
                    .withEndAction {
                        tabGlassDragActive = false
                        overlay.visibility = View.GONE
                        clearTabGlassMagnification(animated = true)
                        updateTopTabSelection()
                    }
                    .start()
            } else {
                tabGlassDragActive = false
                overlay?.visibility = View.GONE
                clearTabGlassMagnification(animated = false)
                updateTopTabSelection()
            }
        }
        topTabButtons.clear()
        tabData.forEachIndexed { index, (label, icon, description) ->
            val button = LinearLayout(this).apply {
                tag = index; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, dp(3))
                isClickable = true; isFocusable = true; contentDescription = description
                // 原生 Ripple 只作为轻触反馈，范围裁剪在当前 Tab 内，不改变底部布局。
                foreground = RippleDrawable(
                    ColorStateList.valueOf(if (isDark()) 0x336f9fff else 0x244080c8),
                    null,
                    GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(23).toFloat(); setColor(Color.WHITE) }
                )
                setOnClickListener { activateTab(index) }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            touchDownX = event.rawX
                            isDraggingTab = false
                            lastDraggedTab = index
                            tabGlassDragActive = true
                            updateTopTabSelection()
                            moveDragGlass(event.rawX)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            moveDragGlass(event.rawX)
                            if (kotlin.math.abs(event.rawX - touchDownX) >= dp(8)) isDraggingTab = true
                            if (isDraggingTab) {
                                val stripLocation = IntArray(2)
                                tabStrip.getLocationOnScreen(stripLocation)
                                val localX = event.rawX - stripLocation[0]
                                val target = topTabButtons.indexOfFirst { localX >= it.left && localX < it.right }
                                if (target >= 0 && target != lastDraggedTab) {
                                    lastDraggedTab = target
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    activateTab(target)
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            if (isDraggingTab) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            finishDragGlass(isDraggingTab)
                            if (!isDraggingTab) view.performClick()
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> { finishDragGlass(false); true }
                        else -> true
                    }
                }
            }
            button.addView(ImageView(this).apply {
                tag = "tabIcon"; setImageResource(icon); scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(if (isDark()) 0xffc4cada.toInt() else 0xff64748b.toInt())
            }, LinearLayout.LayoutParams(-1, dp(23)))
            button.addView(TextView(this).apply {
                tag = "tabLabel"; text = label; textSize = 12f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = -0.01f
                includeFontPadding = false; setTextColor(if (isDark()) 0xffc4cada.toInt() else 0xff64748b.toInt())
            }, LinearLayout.LayoutParams(-1, dp(19)))
            topTabButtons.add(button)
            tabStrip.addView(button, LinearLayout.LayoutParams(0, -1, 1f))
        }
        nav.addView(tabStrip, FrameLayout.LayoutParams(-1, -1))
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(72)).apply { setMargins(dp(12), 0, dp(12), 0) })
        updateTopTabSelection()
        setContentView(root)
    }


internal fun MainActivity.render() {
        // setContentView / Tab 选中同步期间可能触发同一监听器；忽略嵌套刷新以断开递归链。
        if (isRenderingUi) return
        isRenderingUi = true
        try {
        val activity = this
        updateTopTabSelection()
        // 顶部标题随当前 Tab 同步更新，并参与下方统一的页面过渡动画。
        appTitle.text = when (page) {
            "history" -> "历史记录"
            "favorites", "favoriteDetail" -> "收藏"
            "settings" -> "设置"
            "lanShare" -> "局域网分享"
            else -> "条码生成器"
        }
        // 所有页面统一使用纯文字居中标题，不显示标题前的图标。
        appHeader.getChildAt(0)?.visibility = View.GONE
        showAppChrome(page !in listOf("results", "favoriteDetail", "lanShare"))
        when (page) { "history" -> content.post { showList(false) }; "favorites" -> showFavoriteGroups(); "favoriteDetail" -> showFavoriteDetail(); "results" -> showResults(); "settings" -> showSettings(); "lanShare" -> showLanShare(); else -> showGenerate() }
        content.clearAnimation()
        content.alpha = 1f
        // 设置项触发重绘时可能打断上一次上弹动画；先清除残留的属性动画状态，避免整页持续下移。
        content.translationX = 0f
        content.translationY = 0f
        content.scaleX = 1f
        content.scaleY = 1f
        runCatching {
            appHeader.animate().cancel()
            appHeader.alpha = 1f
            appHeader.translationX = 0f
            appHeader.translationY = 0f
            appHeader.scaleX = 1f
            appHeader.scaleY = 1f
        }
        val transitionDirection = pendingPageTransitionDirection
        pendingPageTransitionDirection = 0
        if (transitionDirection != 0 && page in listOf("generate", "history", "favorites", "settings")) {
            // 页面从底部 Tab 上方短距离浮起，像玻璃面板被轻轻托起，避免整屏横向飞入。
            content.animate().cancel()
            content.translationX = 0f
            content.translationY = dp(26).toFloat()
            content.scaleX = 0.985f
            content.scaleY = 0.985f
            content.alpha = 0.78f
            content.animate()
                .translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f)
                .alpha(1f)
                .setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.65f))
                .start()
            // 顶部标题与页面内容同步上弹，避免标题停在原位产生割裂感。
            appHeader.animate().cancel()
            appHeader.translationY = dp(14).toFloat()
            appHeader.scaleX = 0.99f
            appHeader.scaleY = 0.99f
            appHeader.alpha = 0.82f
            appHeader.animate()
                .translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.65f))
                .start()
        }
        } finally {
            isRenderingUi = false
        }
    }


internal fun MainActivity.showAppChrome(visible: Boolean) {
        val activity = this
        runCatching { appHeader.visibility = if (visible) View.VISIBLE else View.GONE }
        runCatching { topNav.visibility = if (visible) View.VISIBLE else View.GONE }
        runCatching { pageScroll?.isVerticalScrollBarEnabled = false; pageScroll?.overScrollMode = View.OVER_SCROLL_NEVER; pageScroll?.isEnabled = visible || page == "lanShare" }
        runCatching { lanShareComposer?.visibility = if (!visible && page == "lanShare") View.VISIBLE else View.GONE }
        if (!visible) runCatching { rootLayout.setBackgroundColor(appBackground()) }
        if (visible) runCatching {
            content.setPadding(0, 0, 0, 0)
            content.setBackgroundColor(Color.TRANSPARENT)
        }
    }


internal fun MainActivity.showMaterialDropdown(
    anchor: View,
    options: List<String>,
    popupWidth: Int? = null,
    selectedIndex: Int = -1,
    // 所有下拉菜单均从触发控件下方展开；空间不足时由菜单自身滚动，
    // 不将锚点上移，也不翻转到控件上方。
    forceBelowAnchor: Boolean = true,
    onSelected: (Int) -> Unit
) {
    val menu = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    var popup: PopupWindow? = null
    options.forEachIndexed { index, label ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener {
                onSelected(index)
                popup?.dismiss()
            }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            setTextColor(primaryText())
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        row.addView(TextView(this).apply {
            text = if (index == selectedIndex) "✓" else ""
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(if (isDark()) 0xffb8c9ff.toInt() else 0xff367be8.toInt())
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(28), dp(40)))
        menu.addView(row)
        if (index < options.lastIndex) menu.addView(View(this).apply { setBackgroundColor(if (isDark()) 0x33ffffff else 0x33475b7a) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(16), 0, dp(16), 0) })
    }
    val frame = Rect()
    anchor.getWindowVisibleDisplayFrame(frame)
    val metrics = resources.displayMetrics
    val maxWidth = (metrics.widthPixels - dp(32)).coerceAtLeast(dp(1))
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * resources.displayMetrics.scaledDensity }
    val measuredContentWidth = (options.maxOfOrNull { labelPaint.measureText(it) } ?: 0f).roundToInt() + dp(16) * 2 + dp(28) + dp(4)
    val width = maxOf(anchor.width, popupWidth ?: 0, measuredContentWidth, dp(150)).coerceAtMost(maxWidth)
    val contentHeight = options.size * dp(40) + dp(4)
    val location = IntArray(2)
    anchor.getLocationOnScreen(location)
    val below = frame.bottom - (location[1] + anchor.height) - dp(6)
    val above = location[1] - frame.top - dp(6)
    // 右边界与触发控件右边界对齐；宽度不足时才按屏幕边距收缩。
    val desiredRight = location[0] + anchor.width
    val desiredLeft = (desiredRight - width).coerceIn(frame.left + dp(16), frame.right - width - dp(16))
    val opensBelow = forceBelowAnchor || below >= dp(48) || below >= above
    // 文件夹菜单必须保持在控件下方：空间不足时限制窗口高度并让选项在窗口内滚动，
    // 绝不能为了完整显示选项而把锚点抬高或翻转到控件上方。
    val height = if (forceBelowAnchor) contentHeight.coerceAtMost(below.coerceAtLeast(dp(48))) else contentHeight
    val popupContent: View = if (height < contentHeight) {
        ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(menu, FrameLayout.LayoutParams(-1, -2))
        }
    } else menu
    popup = PopupWindow(popupContent, width, height, true).apply {
        setBackgroundDrawable(getDrawable(R.drawable.bg_popup))
        isOutsideTouchable = true
        isFocusable = true
        isClippingEnabled = true
        elevation = dp(6).toFloat()
    }
    if (forceBelowAnchor) {
        // 弹窗内的 View 使用屏幕坐标会产生偏差；由系统直接相对控件定位，确保紧贴“选择文件夹”项的下边缘。
        popup.showAsDropDown(anchor, desiredLeft - location[0], dp(6))
    } else if (opensBelow) {
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, desiredLeft, location[1] + anchor.height + dp(6))
    } else {
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, desiredLeft, location[1] - height - dp(6))
    }
    // 所有下拉菜单统一从锚点附近轻微放大展开，保持玻璃面板的连续感。
    popupContent.apply {
        alpha = 0f
        scaleX = 0.94f
        scaleY = 0.94f
        translationY = -dp(4).toFloat()
        post {
            pivotX = width / 2f
            pivotY = 0f
            animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.45f))
                .start()
        }
    }
}

internal fun MainActivity.showFormatPopup(anchor: View) = showMaterialDropdown(anchor, formats.map { it.first }, popupWidth = anchor.width, selectedIndex = formatSpinner.selectedItemPosition) { index ->
    formatSpinner.setSelection(index)
}

/** 锚定在设置项下方的多选下拉菜单；每项独立切换，点击外部关闭。 */
internal fun MainActivity.showMaterialMultiDropdown(
    anchor: View,
    options: List<String>,
    selected: Set<Int>,
    onChanged: (Set<Int>) -> Unit
) {
    val menu = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    var popup: PopupWindow? = null
    val selectedItems = selected.toMutableSet()
    options.forEachIndexed { index, label ->
        val check = TextView(this).apply {
            text = if (index in selectedItems) "✓" else ""
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(if (isDark()) 0xffb8c9ff.toInt() else 0xff367be8.toInt())
            setPadding(dp(8), 0, 0, 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener {
                if (!selectedItems.add(index)) selectedItems.remove(index)
                check.text = if (index in selectedItems) "✓" else ""
                onChanged(selectedItems.toSet())
            }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            setTextColor(primaryText())
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        row.addView(check, LinearLayout.LayoutParams(dp(28), dp(40)))
        menu.addView(row)
        if (index < options.lastIndex) menu.addView(View(this).apply { setBackgroundColor(if (isDark()) 0x33ffffff else 0x33475b7a) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(16), 0, dp(16), 0) })
    }
    val frame = Rect()
    anchor.getWindowVisibleDisplayFrame(frame)
    val metrics = resources.displayMetrics
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * resources.displayMetrics.scaledDensity }
    val longestLabel = options.maxOfOrNull { labelPaint.measureText(it) } ?: 0f
    val contentWidth = longestLabel.roundToInt() + dp(16) * 2 + dp(28) + dp(4)
    val maxWidth = (metrics.widthPixels - dp(32)).coerceAtLeast(dp(1))
    val width = maxOf(anchor.width, contentWidth, dp(150)).coerceAtMost(maxWidth)
    val height = options.size * dp(40) + dp(4)
    val location = IntArray(2)
    anchor.getLocationOnScreen(location)
    // 多选菜单同样让右边界贴齐设置按钮，避免菜单向右错开。
    val left = (location[0] + anchor.width - width).coerceIn(frame.left + dp(16), frame.right - width - dp(16))
    popup = PopupWindow(menu, width, height, true).apply {
        setBackgroundDrawable(getDrawable(R.drawable.bg_popup))
        isOutsideTouchable = true
        isFocusable = true
        isClippingEnabled = true
        elevation = dp(6).toFloat()
    }
    popup.showAsDropDown(anchor, left - location[0], dp(6))
    menu.alpha = 0f
    menu.scaleX = 0.94f
    menu.scaleY = 0.94f
    menu.translationY = -dp(4).toFloat()
    menu.post {
        menu.pivotX = width / 2f
        menu.pivotY = 0f
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(180)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.45f))
            .start()
    }
}

internal fun MainActivity.formatSpinnerAdapter(): ArrayAdapter<String> {
    val activity = this
    return object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, formats.map { it.first }) {
    fun style(view: View, dropdown: Boolean): View = (view as? TextView)?.apply {
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(activity.primaryText())
            setPadding(activity.dp(if (dropdown) 16 else 14), 0, activity.dp(if (dropdown) 16 else 14), 0)
            if (dropdown) {
                minimumHeight = activity.dp(48)
                setBackgroundColor(if (activity.isDark()) 0xff20242e.toInt() else Color.WHITE)
            }
        } ?: view

    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = style(super.getView(position, convertView, parent), false)
    override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = style(super.getDropDownView(position, convertView, parent), true)
    }
}

internal fun MainActivity.showManualAdd(prefill: String = "") {
        val valueInput = EditText(this).apply { hint = "输入一行条码内容"; setSingleLine(true); minLines = 1; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false; setBackgroundResource(R.drawable.bg_input); setPadding(dp(12), 0, dp(12), 0); setText(prefill); setSelection(text.length) }
        val selector = Spinner(this).apply {
            adapter = formatSpinnerAdapter()
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), 0, dp(12), 0)
            setSelection(formats.indexOfFirst { it.second == BarcodeFormat.CODE_128 }.coerceAtLeast(0))
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    showMaterialDropdown(view, formats.map { it.first }, popupWidth = view.width, selectedIndex = selectedItemPosition) { index ->
                        setSelection(index)
                    }
                }
                true
            }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(valueInput); addView(selector) }
         AlertDialog.Builder(this).setTitle("添加一行条码").setView(box).setNegativeButton("取消", null).setPositiveButton("添加") { _, _ ->
            val value = valueInput.text.toString().trim()
            if (value.isEmpty()) { toast("请输入条码内容"); return@setPositiveButton }
            val selected = formats[selector.selectedItemPosition]
            items.add(0, CodeItem(nextItemId(), value, selected.first))
            saveItems(); page = "history"; showList(false); toast("已添加条码")
        }.create().also { showIos26Dialog(it) }
    }


internal fun MainActivity.showSettings() {
        val activity = this
        val draft = style.copy().apply { barHeight = barHeight.coerceIn(30, 150); barWidth = barWidth.coerceIn(120f, 360f); textSize = textSize.coerceIn(10f, 24f); margin = margin.coerceIn(0, 40) }
        content.removeAllViews()
        content.setPadding(dp(8), dp(4), dp(8), dp(18))
        content.setBackgroundColor(appBackground())
        rootLayout.setBackgroundColor(appBackground())
        fun sectionLabel(text: String) = TextView(this).apply {
            this.text = text; textSize = 12f; letterSpacing = 0.055f; includeFontPadding = false
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); setTextColor(secondaryText())
            setPadding(dp(8), dp(8), dp(8), dp(6))
        }
        fun sliderRow(title: String, seekBar: SeekBar, valueText: (Int) -> String): LinearLayout {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
            val titleView = TextView(this).apply { text = title; textSize = 16f; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false; setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); setTextColor(primaryText()) }
            val value = TextView(this).apply { text = valueText(seekBar.progress); textSize = 15f; setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); letterSpacing = -0.01f; gravity = Gravity.CENTER_VERTICAL or Gravity.END; includeFontPadding = false; setSingleLine(true); setTextColor(if (isDark()) 0xffb8ccff.toInt() else 0xff2864d7.toInt()) }
            row.addView(titleView, LinearLayout.LayoutParams(dp(88), dp(48)))
            row.addView(seekBar, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(8), 0) })
            row.addView(value, LinearLayout.LayoutParams(dp(72), dp(48)))
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { value.text = valueText(progress); if (fromUser) (bar.tag as? ((Int) -> Unit))?.invoke(progress) }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
            return row
        }

        var persistSettingsAction: (() -> Unit)? = null
         val appearance = Spinner(this).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("跟随系统", "浅色", "深色")); setSelection(listOf("system", "light", "dark").indexOf(draft.colorScheme).coerceAtLeast(0)); gravity = Gravity.CENTER; setBackgroundResource(R.drawable.bg_input) }
         val appearanceValue = TextView(activity).apply { text = listOf("跟随系统", "浅色", "深色")[appearance.selectedItemPosition]; gravity = Gravity.CENTER; setTextColor(primaryText()); setBackgroundResource(R.drawable.bg_input); setOnClickListener { view -> showMaterialDropdown(view, listOf("跟随系统", "浅色", "深色"), selectedIndex = appearance.selectedItemPosition) { index -> (view as TextView).text = listOf("跟随系统", "浅色", "深色")[index]; appearance.setSelection(index); persistSettingsAction?.invoke() } } }
        val showFormat = SwitchCompat(this).apply {
            // 51x31dp 的胶囊比例接近 iOS 设置开关，SwitchCompat 自带平滑滑块动画。
            showText = false
            isChecked = draft.showFormat
            minWidth = dp(58)
            minimumWidth = dp(58)
            minHeight = dp(34)
            minimumHeight = dp(34)
            setPadding(0, 0, 0, 0)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, if (isDark()) 0xffd8dde6.toInt() else 0xfff4f5f7.toInt())
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xff34c759.toInt(), if (isDark()) 0xff4b5058.toInt() else 0xffd1d5db.toInt())
            )
        }
        val ocrReplacementLabels = arrayOf("O → 0", "I → 1", "S → 5", "B → 8")
        val ocrReplacementBits = intArrayOf(
            SettingsStore.OCR_REPLACE_O_ZERO,
            SettingsStore.OCR_REPLACE_I_ONE,
            SettingsStore.OCR_REPLACE_S_FIVE,
            SettingsStore.OCR_REPLACE_B_EIGHT
        )
        val ocrReplacementValue = TextView(activity).apply {
            gravity = Gravity.CENTER
            setTextColor(primaryText())
            setBackgroundResource(R.drawable.bg_input)
        }
        fun updateOcrReplacementValue(mask: Int) {
            val selected = ocrReplacementLabels.mapIndexedNotNull { index, label -> if (mask and ocrReplacementBits[index] != 0) label else null }
            ocrReplacementValue.text = when (selected.size) {
                0 -> "关闭"
                1 -> selected.first()
                ocrReplacementLabels.size -> "全部启用"
                else -> "已启用 ${selected.size} 项"
            }
            // 视觉上保持紧凑，辅助功能仍能读出完整的替换规则。
            ocrReplacementValue.contentDescription = if (selected.isEmpty()) {
                "OCR 字符纠错：关闭"
            } else {
                "OCR 字符纠错：${selected.joinToString("、")}"
            }
        }
        updateOcrReplacementValue(settingsStore.getOcrConfusionReplacementMask())
        ocrReplacementValue.setOnClickListener {
            val current = settingsStore.getOcrConfusionReplacementMask()
            showMaterialMultiDropdown(
                ocrReplacementValue,
                ocrReplacementLabels.toList(),
                ocrReplacementBits.indices.filter { current and ocrReplacementBits[it] != 0 }.toSet()
            ) { selected ->
                val mask = selected.sumOf { ocrReplacementBits[it] }
                settingsStore.setOcrConfusionReplacementMask(mask)
                updateOcrReplacementValue(mask)
            }
        }
         val textSizeSeekBar = SeekBar(this).apply { max = 14; progress = (draft.textSize.roundToInt() - 10).coerceIn(0, 14) }
        val barHeight = SeekBar(this).apply { max = 120; progress = (draft.barHeight - 30).coerceIn(0, 120) }
        val barWidth = SeekBar(this).apply { max = 240; progress = (draft.barWidth.roundToInt() - 120).coerceIn(0, 240) }
         val margin = SeekBar(this).apply { max = 40; progress = draft.margin.coerceIn(0, 40) }

        fun persistSettings() {
            draft.barColor = Color.BLACK
            draft.bgColor = Color.WHITE
            draft.colorScheme = listOf("system", "light", "dark")[appearance.selectedItemPosition]
            draft.showText = true; draft.textPosition = "bottom"; draft.showFormat = showFormat.isChecked
            draft.textSize = (10 + textSizeSeekBar.progress).toFloat(); draft.barHeight = 30 + barHeight.progress
            draft.barWidth = (120 + barWidth.progress).toFloat(); draft.margin = margin.progress
            style.barColor = draft.barColor; style.bgColor = draft.bgColor; style.colorScheme = draft.colorScheme
            style.showText = draft.showText; style.showFormat = draft.showFormat; style.textPosition = draft.textPosition
            style.textSize = draft.textSize; style.barHeight = draft.barHeight; style.barWidth = draft.barWidth
            style.margin = draft.margin
            saveStyle()
            applyAppearance()
        }
        persistSettingsAction = { persistSettings() }
        var suppressAppearanceCallback = true
        appearance.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selectedScheme = listOf("system", "light", "dark")[pos]
                appearanceValue.text = listOf("跟随系统", "浅色", "深色")[pos]
                if (!suppressAppearanceCallback) {
                    persistSettings()
                }
            }
        }
        // Spinner 绑定监听器后可能异步触发一次初始回调；必须先放行初始化回调，避免刚进入设置页就 recreate。
        appearance.post { suppressAppearanceCallback = false }
        showFormat.setOnCheckedChangeListener { _, _ -> persistSettings() }
        listOf(textSizeSeekBar, barHeight, barWidth, margin).forEach { seekBar ->
            seekBar.tag = { _: Int -> persistSettings() }
        }
        fun groupCard(rows: List<View>): LinearLayout = contentCard().apply {
             orientation = LinearLayout.VERTICAL
             elevation = 0f
             setPadding(dp(8), dp(4), dp(8), dp(4))
             rows.forEachIndexed { index, row ->
                 addView(row, LinearLayout.LayoutParams(-1, dp(48)))
                 if (index < rows.lastIndex) addView(View(activity).apply { setBackgroundColor(if (isDark()) 0x263b4658 else 0x1a667085) }, LinearLayout.LayoutParams(-1, dp(1)))
             }
         }
         fun textRow(title: String, trailing: View, trailingWidth: Int = dp(132)): LinearLayout = LinearLayout(this).apply {
             gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0)
             addView(TextView(activity).apply { text = title; this.textSize = 16f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; includeFontPadding = false; letterSpacing = -0.01f; setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); setTextColor(primaryText()) }, LinearLayout.LayoutParams(0, -1, 1f))
             addView(trailing, LinearLayout.LayoutParams(trailingWidth, dp(40)))
         }
        fun compactSliderRow(title: String, seekBar: SeekBar, valueText: (Int) -> String) = sliderRow(title, seekBar, valueText).apply { setPadding(0, 0, 0, 0) }
          addSpaced(sectionLabel("显示"), bottom = 2)
         addSpaced(groupCard(listOf(
            textRow("外观", appearanceValue)
         )), bottom = 12)
          addSpaced(sectionLabel("条码"), bottom = 2)
         addSpaced(groupCard(listOf(
            compactSliderRow("文字大小", textSizeSeekBar) { "${10 + it} sp" }, compactSliderRow("条码高度", barHeight) { "${30 + it} dp" },
            compactSliderRow("条码宽度", barWidth) { "${120 + it} dp" }, compactSliderRow("条码间距", margin) { "$it dp" },
            textRow("显示条码格式", showFormat),
            textRow("OCR 字符纠错", ocrReplacementValue)
        )), bottom = 12)
        addSpaced(sectionLabel("工具"), bottom = 2)
        val toolRows = mutableListOf<View>()
        fun toolActionButton(label: String, buttonMinHeight: Int = 48, horizontalPadding: Int = 14, action: () -> Unit) = styleButton(Button(activity).apply {
            text = label
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(buttonMinHeight)
            minimumHeight = dp(buttonMinHeight)
            setPadding(dp(horizontalPadding), dp(7), dp(horizontalPadding), dp(7))
            setOnClickListener { action() }
        }).apply {
            // 工具按钮保留玻璃质感，但减少胶囊感并稍微放大外框。
            background = glassButtonBackground().apply { cornerRadius = dp(14).toFloat() }
        }
        toolRows += textRow("局域网文件分享", toolActionButton("启动", buttonMinHeight = 40, horizontalPadding = 16) { enterLanShare() }, trailingWidth = dp(88))
        val versionLine = LinearLayout(this).apply {
             orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
             val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
             info.addView(TextView(activity).apply { text = "作者：Alan"; this.textSize = 13f; setTextColor(secondaryText()) })
             val versionRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
             // 红点使用版本文字右侧的独立槽位，而非覆盖式叠放在同一容器中。
             versionRow.addView(TextView(activity).apply { text = "版本：${BuildConfig.VERSION_NAME}"; this.textSize = 13f; includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL; setTextColor(secondaryText()) }, LinearLayout.LayoutParams(-2, dp(18)))
             val updateBadgeSlot = FrameLayout(activity)
             updateBadgeSlot.addView(View(activity).apply {
                 background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xffef4444.toInt()) }
                 visibility = if (availableUpdateUrl != null) View.VISIBLE else View.GONE
             }, FrameLayout.LayoutParams(dp(5), dp(5), Gravity.END or Gravity.TOP).apply { topMargin = dp(2); rightMargin = 0 })
             // 12dp 的紧凑槽位保证红点不压住版本文字，也不会显得游离。
             versionRow.addView(updateBadgeSlot, LinearLayout.LayoutParams(dp(12), dp(18)))
             info.addView(versionRow)
             addView(info, LinearLayout.LayoutParams(0, -2, 1f))
             addView(styleButton(Button(activity).apply { text = "检查更新"; setOnClickListener { checkForUpdates(silent = false) } }), LinearLayout.LayoutParams(-2, dp(38)))
         }
         val about = contentCard().apply {
             orientation = LinearLayout.VERTICAL
             setPadding(dp(12), dp(6), dp(8), dp(6))
             addView(TextView(activity).apply { text = "关于"; this.textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(primaryText()) })
             addView(versionLine, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, 0) })
         }
         toolRows += textRow("恢复默认设置", toolActionButton("恢复", buttonMinHeight = 40, horizontalPadding = 16) {
                 textSizeSeekBar.progress = 4
                 barHeight.progress = 25
                 barWidth.progress = 80
                 margin.progress = 4
                 persistSettings()
                 toast("已恢复条码默认设置")
              }, trailingWidth = dp(88))
         val backupActions = LinearLayout(activity).apply {
             orientation = LinearLayout.HORIZONTAL
             gravity = Gravity.CENTER_VERTICAL
             // 给按钮上下留出空间，避免圆角背景和阴影被 48dp 行高裁切；固定宽度让左右边框完整且一致。
             addView(toolActionButton("导入", buttonMinHeight = 40, horizontalPadding = 16) { restoreFavoritesImport() }, LinearLayout.LayoutParams(dp(88), dp(40)))
             addView(toolActionButton("导出", buttonMinHeight = 40, horizontalPadding = 16) { createFavoritesExport() }, LinearLayout.LayoutParams(dp(88), dp(40)).apply { leftMargin = dp(8) })
         }
         toolRows += textRow("收藏备份", backupActions, trailingWidth = -2)
          if (BuildConfig.DEBUG_LOG_EXPORT) {
              toolRows += textRow("调试日志", toolActionButton("导出", buttonMinHeight = 40, horizontalPadding = 16) { shareDebugLog() }, trailingWidth = dp(88))
          }
        addSpaced(groupCard(toolRows), bottom = 12)
          // 整张“关于”卡片是一个安静的入口：在短时间内连点五次才打开彩蛋，日常浏览不会误触。
         var aboutTapCount = 0
         var lastAboutTapAt = 0L
         about.isClickable = true
         about.setOnClickListener {
             val now = System.currentTimeMillis()
             aboutTapCount = if (now - lastAboutTapAt <= 1_500L) aboutTapCount + 1 else 1
             lastAboutTapAt = now
             it.animate().scaleX(0.985f).scaleY(0.985f).setDuration(65).withEndAction {
                 it.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
             }.start()
             if (aboutTapCount >= 5) {
                 aboutTapCount = 0
                showFireworksEasterEgg()
             }
         }
         addSpaced(about, bottom = 10)
    }

internal fun MainActivity.enterLanShare() {
    if (!lanShareManager.isOnLocalNetwork()) {
        showLanShareNetworkErrorDialog()
        return
    }
    settingsReturnPage = "settings"
    page = "lanShare"
    runCatching {
        lanShareSession = lanShareManager.start()
        lanShareIsHost = true
        lanShareQrVisible = true
        lanShareBrowserConnected = false
        lanShareOwnFileIds.clear()
        lanShareFiles = lanShareManager.localFiles()
        startLanShareAutoRefresh()
        render()
        content.post { if (page == "lanShare" && lanShareQrVisible) showLanShareQrDialog() }
    }.onFailure {
        stopLanShareAutoRefresh()
        lanShareSession = null
        lanShareIsHost = false
        page = "settings"
        render()
        if (it.message == "Error 当前不处于局域网") showLanShareNetworkErrorDialog() else toast(it.message ?: "无法创建房间")
    }
}

/** 局域网不可用时使用独立的紧凑玻璃提示，避免被普通 Toast 忽略。 */
internal fun MainActivity.showLanShareNetworkErrorDialog() {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(22), dp(24), dp(8))
        addView(TextView(this@showLanShareNetworkErrorDialog).apply {
            text = "Error"
            textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(if (isDark()) 0xffffb4ab.toInt() else 0xffb42318.toInt())
        }, LinearLayout.LayoutParams(-1, dp(30)))
        addView(TextView(this@showLanShareNetworkErrorDialog).apply {
            text = "当前不处于局域网"
            textSize = 15f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(secondaryText())
            setPadding(0, dp(8), 0, dp(6))
        }, LinearLayout.LayoutParams(-1, dp(38)))
        addView(View(this@showLanShareNetworkErrorDialog).apply {
            setBackgroundColor(if (isDark()) 0x33ffffff else 0x26475b7a)
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(10), 0, 0) })
    }
    showIos26Dialog(AlertDialog.Builder(this).setView(box).setPositiveButton("确定", null).create(), compact = true)
}

/** 用于短提示的紧凑居中 Liquid Glass 弹窗。 */
internal fun MainActivity.showIos26NoticeDialog(message: String) {
    val dialog = AlertDialog.Builder(this).create()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(18), dp(18), dp(18), dp(10))
        addView(TextView(this@showIos26NoticeDialog).apply {
            text = message
            textSize = 16f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(primaryText())
        }, LinearLayout.LayoutParams(-1, dp(30)))
         addView(styleButton(Button(this@showIos26NoticeDialog).apply {
             text = "确定"
             textSize = 15f
             minWidth = 0; minimumWidth = 0
             // 保持上下 44dp 不变，仅扩大左右可见外框。
             minWidth = dp(76); minimumWidth = dp(76)
             isAllCaps = false
             // 保持与其他弹窗一致的紧凑按钮边距。
             setPadding(dp(10), dp(7), dp(10), dp(7))
             setTextColor(primaryText())
             setBackgroundDrawable(glassButtonBackground().apply { cornerRadius = dp(14).toFloat() })
             setOnClickListener { dialog.dismiss() }
         }), LinearLayout.LayoutParams(dp(76), dp(44)).apply { gravity = Gravity.END; topMargin = dp(10) })
    }
    dialog.setView(box)
    showIos26Dialog(dialog, compact = true)
}

internal fun MainActivity.showLanShare() {
    val messageDraft = lanShareMessageInput?.text?.toString().orEmpty()
    val messageHadFocus = lanShareMessageInput?.hasFocus() == true
    content.removeAllViews()
    content.setPadding(0, 0, 0, dp(24))
    // 文件传输页跟随应用深浅色，保持原生传输面板的简洁层次。
    val shareBackground = if (isDark()) Color.BLACK else 0xfff4f6fb.toInt()
    val shareTitle = if (isDark()) Color.WHITE else primaryText()
    content.setBackgroundColor(shareBackground); rootLayout.setBackgroundColor(shareBackground)
    val toggleQr: () -> Unit = {
        if (!lanShareQrVisible && lanShareIsHost) {
            runCatching { lanShareSession = lanShareManager.restart(); lanShareBrowserConnected = false; lanShareQrVisible = true; lanShareFiles = lanShareManager.localFiles(); showLanShareQrDialog() }
                .onFailure { toast(it.message ?: "无法刷新分享端口") }
        } else if (lanShareQrVisible) {
            lanShareQrVisible = false
            render()
        }
    }
    content.addView(LinearLayout(this).apply {
        isClickable = true; isFocusable = true; setOnClickListener { toggleQr() }
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); background = liquidGlassCard(); elevation = dp(1).toFloat(); clipToOutline = true
        addView(Space(this@showLanShare), LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(TextView(this@showLanShare).apply { text = "文件传输"; textSize = 20f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setTextColor(shareTitle) }, LinearLayout.LayoutParams(0, dp(64), 1f))
        addView(ImageButton(this@showLanShare).apply {
            setImageResource(R.drawable.ic_qr_code)
            imageTintList = ColorStateList.valueOf(if (isDark()) 0xff8fc1ff.toInt() else 0xff0a84ff.toInt())
            // 保留 60dp 点击区域，收紧可见外框和图标比例，二维码图形更清晰。
            background = glassButtonBackground().apply { cornerRadius = dp(16).toFloat() }
            elevation = dp(1).toFloat(); clipToOutline = true
            isClickable = true; isFocusable = true; contentDescription = "显示二维码"
            // 60dp 的玻璃外框避免深色模式下被标题卡片边缘和阴影裁切；标题整块仍可点击。
            minimumWidth = dp(60); minimumHeight = dp(60)
            setPadding(dp(15), dp(15), dp(15), dp(15))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { toggleQr() }
        }, LinearLayout.LayoutParams(dp(60), dp(60)))
    }, LinearLayout.LayoutParams(-1, dp(80)))
    val sessionState = lanShareSession
    content.addView(TextView(this).apply { tag = "lanShareStatus"; textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(14)); updateLanShareConnectionStatus(this) })
    if (lanShareSession == null) {
        content.post { enterLanShare() }
        return
    }
    val session = sessionState ?: return
    // 二维码通过独立弹窗展示；消息列表可独立刷新，避免重建输入框与键盘。
    content.addView(LinearLayout(this).apply { tag = "lanShareFileList"; orientation = LinearLayout.VERTICAL; renderLanShareFileList(this) }, LinearLayout.LayoutParams(-1, -2))
    val actions = lanShareComposer ?: return
    actions.removeAllViews()
    actions.visibility = View.VISIBLE
    actions.apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = liquidGlassCard()
        elevation = dp(1).toFloat()
        clipToOutline = true
        addView(ImageButton(this@showLanShare).apply {
            setImageResource(R.drawable.ic_attachment)
            setColorFilter(if (isDark()) Color.WHITE else 0xff344054.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "选择附件"
            setOnClickListener { showLanShareAttachmentSheet(this) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        lanShareMessageInput = EditText(this@showLanShare).apply {
            hint = pendingLanUploadName?.let { "已选择：$it" } ?: "输入文字"
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(primaryText())
            setHintTextColor(secondaryText())
            setText(messageDraft)
            setSelection(text.length)
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(24).toFloat(); setColor(if (isDark()) 0xff2c2c2e.toInt() else 0xfff0f2f5.toInt()) }
        }.also { addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, 0, dp(8), 0) }) }
        addView(ImageButton(this@showLanShare).apply {
            setImageResource(R.drawable.ic_action_share)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xff0a84ff.toInt()) }
            contentDescription = "发送文字或上传附件"
            setOnClickListener {
                if (pendingLanUploadUri != null) uploadSelectedLanShareFile()
                else {
                    val text = lanShareMessageInput?.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) uploadLanShareMessage(text)
                }
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }
    if (messageHadFocus) lanShareMessageInput?.post {
        lanShareMessageInput?.requestFocus()
        lanShareMessageInput?.setSelection(lanShareMessageInput?.text?.length ?: 0)
    }
}

private fun MainActivity.renderLanShareFileList(list: LinearLayout) {
    val expectedIds = lanShareFiles.map { it.id }
    val currentIds = (0 until list.childCount).mapNotNull { list.getChildAt(it).tag as? String }
    if (currentIds == expectedIds) return
    list.removeAllViews()
    lanShareFiles.forEach { file ->
        val mine = file.id in lanShareOwnFileIds
        val imageFile = (lanShareManager.localFile(file.id) ?: lanSharePreviewFiles[file.id])?.takeIf { isLanShareImageName(file.name) }
        list.addView(LinearLayout(this).apply { tag = file.id
            gravity = if (mine) Gravity.END else Gravity.START; setPadding(0, dp(4), 0, dp(4))
            val bubble = LinearLayout(this@renderLanShareFileList).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(if (imageFile == null) 12 else 6), dp(if (imageFile == null) 8 else 6), dp(if (imageFile == null) 10 else 6), dp(if (imageFile == null) 8 else 6)); background = liquidGlassCard().apply { setColor(if (mine) (if (isDark()) 0x7a0a84ff else 0x660a84ff) else if (isDark()) 0x662c2c2e else 0xcfffffff.toInt()) }; elevation = 0f; clipToOutline = true
                if (imageFile == null) addView(ImageView(this@renderLanShareFileList).apply { setImageResource(R.drawable.ic_attachment); setColorFilter(if (mine) Color.WHITE else if (isDark()) 0xffd0d6e4.toInt() else 0xff52627a.toInt()); contentDescription = "文件附件" }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(10) })
                val details = LinearLayout(this@renderLanShareFileList).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 0, dp(6), 0) }
                imageFile?.let { source -> decodeLanSharePreview(source)?.let { bitmap ->
                    val scale = minOf(dp(220).toFloat() / bitmap.width.coerceAtLeast(1), dp(180).toFloat() / bitmap.height.coerceAtLeast(1), 1f)
                    details.addView(ImageView(this@renderLanShareFileList).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x22000000); contentDescription = file.name }, LinearLayout.LayoutParams((bitmap.width * scale).roundToInt().coerceAtLeast(dp(80)), (bitmap.height * scale).roundToInt().coerceAtLeast(dp(80))).apply { bottomMargin = dp(6) })
                } }
                details.addView(TextView(this@renderLanShareFileList).apply { text = file.name; textSize = 14f; maxLines = 4; maxWidth = dp(220); ellipsize = null; setHorizontallyScrolling(false); gravity = Gravity.CENTER_HORIZONTAL; setTextColor(if (mine) Color.WHITE else primaryText()) })
                details.addView(TextView(this@renderLanShareFileList).apply { text = formatLanShareSize(file.size); textSize = 12f; gravity = Gravity.CENTER_HORIZONTAL; setTextColor(if (mine) 0xffdbeafe.toInt() else secondaryText()) })
                addView(details, LinearLayout.LayoutParams(-2, -2))
            }
            bubble.setOnClickListener { saveLanShareFile(file) }
            addView(bubble, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
    }
}

private fun MainActivity.updateLanShareConnectionStatus(view: TextView? = content.findViewWithTag("lanShareStatus")) {
    val connected = lanShareSession != null && lanShareManager.browserConnected()
    lanShareBrowserConnected = connected
    view?.apply {
        text = if (connected) "●  浏览器已连接" else "○  等待浏览器连接..."
        setTextColor(if (connected) 0xff22c55e.toInt() else secondaryText())
    }
}

internal fun MainActivity.showLanShareAttachmentSheet(anchor: View) {
    showLanSharePopup(anchor, listOf(
        "拍摄图片" to { openLanShareCamera() },
        "照片图库" to { openLanShareGallery() },
        "选择文件" to { openLanShareFiles() }
    ))
}

internal fun MainActivity.openLanShareCamera() {
    pendingCameraRequest = MainActivity.REQUEST_LAN_SHARE_CAPTURE
    if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.CAMERA), MainActivity.REQUEST_CAMERA_PERMISSION)
        return
    }
    val photoFile = File.createTempFile("lan_share_photo_", ".jpg", cacheDir)
    val photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
    pendingCameraUri = photoUri
    pendingCameraFile = photoFile
    val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
        putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri("output", photoUri)
    }
    try { startActivityForResult(intent, MainActivity.REQUEST_LAN_SHARE_CAPTURE) } catch (_: Exception) { pendingCameraUri = null; pendingCameraFile = null; photoFile.delete(); toast("当前设备没有可用的系统相机") }
}

internal fun MainActivity.findRecentLanCameraMedia(): Uri? {
    val threshold = (pendingLanCameraStartedAt - 2_000L).coerceAtLeast(0L) / 1_000L
    val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    return contentResolver.query(collection, arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DATE_ADDED), null, null, "${android.provider.MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
        if (cursor.moveToFirst() && cursor.getLong(1) >= threshold) android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
    }
}

internal fun MainActivity.openLanShareGallery() {
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        openLanShareGalleryPicker()
        return
    }
    val permission = Manifest.permission.READ_EXTERNAL_STORAGE
    if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(permission), MainActivity.REQUEST_LAN_SHARE_GALLERY_PERMISSION)
    } else openLanShareGalleryPicker()
}

internal fun MainActivity.openLanShareGalleryPicker() {
    val intent = if (android.os.Build.VERSION.SDK_INT >= 33) {
        Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
    } else {
        Intent(Intent.ACTION_PICK).setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
    }
    startActivityForResult(intent.apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, MainActivity.REQUEST_LAN_SHARE_UPLOAD)
}

internal fun MainActivity.openLanShareFiles() {
    if (android.os.Build.VERSION.SDK_INT <= 32 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), MainActivity.REQUEST_LAN_SHARE_FILE_PERMISSION)
    } else openLanShareFilePicker()
}

internal fun MainActivity.openLanShareFilePicker() {
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, MainActivity.REQUEST_LAN_SHARE_UPLOAD)
}

internal fun MainActivity.selectLanShareAttachment(uri: Uri, temporaryFile: File? = null, autoUpload: Boolean = false) {
    pendingLanUploadTempFile?.takeIf { it != temporaryFile }?.delete()
    pendingLanUploadUri = uri
    pendingLanUploadTempFile = temporaryFile
    pendingLanUploadName = runCatching { contentResolver.query(uri, null, null, null, null)?.use { cursor -> cursor.moveToFirst(); cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) } }.getOrNull() ?: "附件"
    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    if (autoUpload) uploadSelectedLanShareFile() else { render(); toast("已选择附件，点击上传按钮发送") }
}

internal fun MainActivity.uploadSelectedLanShareFile() {
    val uri = pendingLanUploadUri ?: return
    val temporaryFile = pendingLanUploadTempFile
    pendingLanUploadUri = null
    pendingLanUploadTempFile = null
    pendingLanUploadName = null
    uploadLanShareFile(uri, temporaryFile)
}

private fun MainActivity.showLanSharePopup(anchor: View, options: List<Pair<String, () -> Unit>>) {
    lateinit var popup: PopupWindow
    val popupWidth = dp(128)
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(7), dp(7), dp(7), dp(7))
        background = liquidGlassCard()
         elevation = dp(6).toFloat()
        options.forEach { (label, action) ->
            addView(TextView(this@showLanSharePopup).apply { text = label; textSize = 15f; gravity = Gravity.CENTER; setTextColor(primaryText()); setBackgroundColor(Color.TRANSPARENT); isClickable = true; setOnClickListener { action(); popup.dismiss() } }, LinearLayout.LayoutParams(dp(114), dp(36)).apply { setMargins(0, dp(1), 0, dp(1)) })
            if (label != options.last().first) addView(View(this@showLanSharePopup).apply { setBackgroundColor(if (isDark()) 0x33ffffff else 0x33475b7a) }, LinearLayout.LayoutParams(dp(102), dp(1)).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
    }
    popup = PopupWindow(panel, popupWidth, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
         elevation = dp(6).toFloat()
    }
    panel.measure(View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val location = IntArray(2); anchor.getLocationOnScreen(location)
    popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, (location[0] - dp(8)).coerceAtLeast(dp(4)), (location[1] - panel.measuredHeight - dp(16)).coerceAtLeast(dp(8)))
}

private fun MainActivity.liquidGlassCard() = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(18).toFloat()
    setColor(if (isDark()) 0xff1c1c1e.toInt() else 0xffffffff.toInt())
    setStroke(dp(1), if (isDark()) 0xff3a3a3c.toInt() else 0xffd8d8dc.toInt())
}

private fun formatLanShareSize(bytes: Long): String = if (bytes >= 1024L * 1024L) {
    String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
} else {
    "${bytes / 1024} KB"
}

internal fun MainActivity.joinLanShareSession(value: String) {
    if (!value.startsWith("http://")) { toast("这不是局域网分享地址"); return }
    val uri = Uri.parse(value)
    if (uri.host.isNullOrBlank() || uri.query != null || !lanShareManager.isRouterLanHost(uri.host)) { toast("这不是局域网分享地址"); return }
    stopLanShareAutoRefresh(); lanShareManager.stop(); lanShareIsHost = false
    lanShareSession = LanShareSession("${uri.scheme}://${uri.host}:${if (uri.port > 0) uri.port else 80}")
    startLanShareAutoRefresh()
    refreshLanShareFiles()
}

internal fun MainActivity.showLanShareQrDialog() {
    val session = lanShareSession
    if (!lanShareIsHost || session == null) { toast("请先创建分享房间"); return }
    // 缩小二维码内部默认静区，保留可可靠识别所需的最小留白，避免白色方块过大。
    val matrix = MultiFormatWriter().encode(session.baseUrl, BarcodeFormat.QR_CODE, dp(240), dp(240), mapOf(EncodeHintType.MARGIN to 1))
    val qrForeground = if (isDark()) 0xff111318.toInt() else Color.BLACK
    val qrBackground = if (isDark()) 0xfff1f3f6.toInt() else Color.WHITE
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { image -> for (x in 0 until matrix.width) for (y in 0 until matrix.height) image.setPixel(x, y, if (matrix[x, y]) qrForeground else qrBackground) }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // 二维码弹窗只保留必要的安全留白，减少左右白边；弹窗本身仍保留圆角和系统最小宽度。
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(14), 0, dp(10))
        addView(ImageView(this@showLanShareQrDialog).apply {
            setImageBitmap(bitmap)
            contentDescription = "局域网分享二维码"
            setBackgroundColor(qrBackground)
            scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(dp(240), dp(240)))
        addView(LinearLayout(this@showLanShareQrDialog).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@showLanShareQrDialog).apply {
                text = session.baseUrl
                gravity = Gravity.CENTER
                setTextColor(secondaryText())
                setTextIsSelectable(true)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(ImageButton(this@showLanShareQrDialog).apply {
                setImageResource(R.drawable.ic_copy)
                setColorFilter(if (isDark()) Color.WHITE else 0xff334155.toInt())
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "复制局域网传输地址"
                setOnClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("局域网传输地址", session.baseUrl))
                    toast("已复制局域网传输地址")
                }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
        }, LinearLayout.LayoutParams(dp(240), dp(42)))
    }
    val dialog = AlertDialog.Builder(this).setView(box).create()
    dialog.setCanceledOnTouchOutside(true)
    dialog.setOnCancelListener { lanShareQrVisible = false }
    dialog.setOnDismissListener { lanShareQrVisible = false }
    showIos26Dialog(dialog)
}

internal fun MainActivity.startLanShareAutoRefresh() {
    stopLanShareAutoRefresh()
    val task = object : Runnable {
        override fun run() {
            if (page != "lanShare" || lanShareSession == null) return
            refreshLanShareFiles(showError = false)
            lanShareRefreshHandler.postDelayed(this, 1_500L)
        }
    }
    lanShareRefreshRunnable = task
    lanShareRefreshHandler.postDelayed(task, 1_500L)
}

internal fun MainActivity.stopLanShareAutoRefresh() {
    lanShareRefreshRunnable?.let(lanShareRefreshHandler::removeCallbacks)
    lanShareRefreshRunnable = null
}

internal fun MainActivity.refreshLanShareFiles(showError: Boolean = true) {
    val session = lanShareSession ?: return
    if (lanShareRefreshInFlight) return
    lanShareRefreshInFlight = true
    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val result = runCatching {
            val files = lanShareManager.list(session)
            files to fetchLanSharePreviews(session, files)
        }
        runOnUiThread {
            lanShareRefreshInFlight = false
            updateLanShareConnectionStatus()
            result.onSuccess {
                (files, previews) ->
                lanShareFiles = files
                lanSharePreviewFiles.putAll(previews)
                if (page == "lanShare") {
                    val messageList = content.findViewWithTag<LinearLayout>("lanShareFileList")
                    if (messageList != null) renderLanShareFileList(messageList) else render()
                }
            }
            result.onFailure { if (showError) toast("无法连接到分享房间") }
        }
    }
}

internal fun MainActivity.closeLanShare() {
    stopLanShareAutoRefresh()
    lanShareManager.stop(clearSharedFiles = true)
    lanShareSession = null
    lanShareFiles = emptyList()
    lanShareOwnFileIds.clear()
    lanSharePreviewFiles.clear()
    File(cacheDir, "lan-share-preview").listFiles().orEmpty().forEach { it.delete() }
}

private fun MainActivity.fetchLanSharePreviews(session: LanShareSession, files: List<LanShareFile>): Map<String, File> {
    val previewFolder = File(cacheDir, "lan-share-preview").apply { mkdirs() }
    val imageIds = files.filter { isLanShareImageName(it.name) }.map { it.id }.toSet()
    previewFolder.listFiles().orEmpty().filter { it.name !in imageIds }.forEach { it.delete() }
    var cachedBytes = previewFolder.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
    return buildMap {
        files.filter { isLanShareImageName(it.name) && lanShareManager.localFile(it.id) == null }.forEach { file ->
            val preview = File(previewFolder, file.id)
            if (!preview.isFile && file.size <= 16L * 1024L * 1024L && cachedBytes + file.size <= 64L * 1024L * 1024L) {
                runCatching { lanShareManager.downloadPreview(session, file.id, preview) }
                cachedBytes += preview.length()
            }
            if (preview.isFile) put(file.id, preview)
        }
    }
}
internal fun MainActivity.uploadLanShareFile(uri: Uri, temporaryFile: File? = null) { val session = lanShareSession ?: return; lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { val id = lanShareManager.upload(session, uri); id to lanShareManager.list(session) }.onSuccess { (id, files) -> temporaryFile?.delete(); runOnUiThread { lanShareOwnFileIds.add(id); lanShareFiles = files; render() } }.onFailure { temporaryFile?.delete(); runOnUiThread { toast("上传失败") } } } }
internal fun MainActivity.uploadLanShareMessage(text: String) { val session = lanShareSession ?: return; lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { val id = lanShareManager.uploadText(session, text); id to lanShareManager.list(session) }.onSuccess { (id, files) -> runOnUiThread { lanShareMessageInput?.setText(""); lanShareOwnFileIds.add(id); lanShareFiles = files; render(); toast("发送成功") } }.onFailure { runOnUiThread { toast("发送失败") } } } }
internal fun MainActivity.downloadLanShareFile(id: String, uri: Uri) { val session = lanShareSession ?: return; lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { lanShareManager.download(session, id, uri) }.onSuccess { runOnUiThread { toast("下载完成") } }.onFailure { runOnUiThread { toast("下载失败") } } } }

internal fun MainActivity.saveLanShareFile(file: LanShareFile) {
    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    pendingLanDownloadId = file.id
    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        type = mime
        putExtra(Intent.EXTRA_TITLE, file.name)
        addCategory(Intent.CATEGORY_OPENABLE)
    }, MainActivity.REQUEST_LAN_SHARE_DOWNLOAD)
}

private fun isLanShareImageName(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif")

/** 相机照片常将方向保存在 EXIF；BitmapFactory 不会自动应用，故在气泡预览前校正。 */
private fun decodeLanSharePreview(file: File): Bitmap? {
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val rotation = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270f
        else -> 0f
    }
    if (rotation == 0f) return bitmap
    return runCatching { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true) }.getOrDefault(bitmap)
}


internal fun MainActivity.parseColor(value: String, fallback: Int): Int = try {
        val normalized = value.trim().let { if (it.startsWith("#")) it else "#$it" }
        Color.parseColor(normalized)
    } catch (_: Exception) { fallback }


internal fun MainActivity.applyAppearance() {
        val mode = when (style.colorScheme) { "dark" -> AppCompatDelegate.MODE_NIGHT_YES; "light" -> AppCompatDelegate.MODE_NIGHT_NO; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
        AppCompatDelegate.setDefaultNightMode(mode)
        syncSystemBars()
        runCatching { rootLayout.setBackgroundColor(appBackground()) }
    }

/** 在主题重建完成后再次同步系统栏，避免切换模式时短暂沿用旧颜色或旧图标明暗。 */
internal fun MainActivity.syncSystemBars() {
        val background = appBackground()
        window.decorView.systemUiVisibility = if (isDark()) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.statusBarColor = background
        window.navigationBarColor = background
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
}


internal fun MainActivity.loadStyle(): StyleSettings = StyleSettings(
        barColor = Color.BLACK, bgColor = Color.WHITE,
        showText = true, textPosition = "bottom",
        textSize = settingsStore.get(SettingsStore.TEXT_SIZE, 14f).coerceIn(10f, 24f), barHeight = settingsStore.get(SettingsStore.BAR_HEIGHT, 55).coerceIn(30, 150), barWidth = settingsStore.get(SettingsStore.BAR_WIDTH, 200f).coerceIn(120f, 360f),
        margin = settingsStore.get(SettingsStore.MARGIN, 4).coerceIn(0, 40), showFormat = settingsStore.get(SettingsStore.SHOW_FORMAT, true), colorScheme = settingsStore.get(SettingsStore.COLOR_SCHEME, "system")
    )


internal fun MainActivity.saveStyle() = settingsStore.saveStyle(style)

internal fun MainActivity.saveInputDraft() {
        val activity = this
        if (inputRows.isNotEmpty()) inputDraft = inputRows.map { it.text.toString() }.toMutableList()
    }


internal fun MainActivity.showGenerate() {
         val background = appBackground()
         content.setBackgroundColor(background)
         rootLayout.setBackgroundColor(background)
         window.statusBarColor = background
         window.navigationBarColor = background
         window.decorView.systemUiVisibility = if (isDark()) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val activity = this
        saveInputDraft()
        content.removeAllViews()
        content.setPadding(0, 0, 0, 0)
        inputRows.clear()
        inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val visibleInput = ScrollView(this).apply {
             isVerticalScrollBarEnabled = true
             overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            isNestedScrollingEnabled = false
            setBackgroundDrawable(liquidGlassCard())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(inputContainer)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        inputScroll = visibleInput
        content.addView(visibleInput, LinearLayout.LayoutParams(-1, dp(56 + 16)).apply { setMargins(0, 0, 0, dp(12)) })
        if (inputDraft.isEmpty()) addInputRow() else inputDraft.toList().forEach { addInputRow(it) }
         val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
         val addButton = styleButton(Button(this).apply {
             text = "+ 添加一行"
             textSize = 15f
             setOnClickListener {
                 if (inputRows.size >= 100) { toast("最多保留 100 行输入框"); return@setOnClickListener }
                 val currentInput = inputRows.firstOrNull { it.hasFocus() }
                 addInputRow(focus = true, after = currentInput)
             }
         }).apply {
             setBackgroundDrawable(glassButtonBackground().apply { cornerRadius = dp(18).toFloat() })
             setPadding(dp(12), 0, dp(12), 0)
         }
         actionRow.addView(addButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(0, 0, dp(4), 0) })
         val cameraAction = LinearLayout(this).apply {
             gravity = Gravity.CENTER
             setBackgroundDrawable(glassButtonBackground().apply { cornerRadius = dp(18).toFloat() })
             setPadding(dp(12), 0, dp(12), 0)
             isClickable = true; isFocusable = true
             setOnClickListener { captureText() }
             addView(ImageView(activity).apply {
                 setImageResource(R.drawable.ic_camera)
                 imageTintList = ColorStateList.valueOf(if (isDark()) 0xffa9caff.toInt() else 0xff2453a6.toInt())
                 contentDescription = "拍照取字"
             }, LinearLayout.LayoutParams(dp(24), dp(24)))
             addView(TextView(activity).apply { text = "拍照取字"; textSize = 15f; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false; setTextColor(if (isDark()) 0xffd7e3f5.toInt() else 0xff2453a6.toInt()) }, LinearLayout.LayoutParams(-2, dp(52)).apply { setMargins(dp(6), 0, 0, 0) })
         }
         actionRow.addView(cameraAction, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), 0, 0, 0) })
         addSpaced(actionRow, bottom = 10)
         formatSpinner = Spinner(this).apply { adapter = formatSpinnerAdapter(); setBackgroundResource(R.drawable.bg_input); setPadding(dp(8), 0, dp(8), 0) }
          formatSpinner.setSelection(formats.indexOfFirst { it.first == (pendingGenerateFormat ?: "Code 128-B") }.coerceAtLeast(0))
          pendingGenerateFormat = null
          val formatCard = LinearLayout(this).apply {
              orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
              setPadding(dp(14), dp(4), dp(14), dp(4)); setBackgroundDrawable(liquidGlassCard())
          }
          formatCard.addView(TextView(activity).apply { text = "条码类型"; textSize = 16f; gravity = Gravity.CENTER_VERTICAL; setTypeface(null, Typeface.BOLD); setTextColor(primaryText()); setPadding(dp(18), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(52), 1f))
           formatCard.addView(formatSpinner, LinearLayout.LayoutParams(dp(150), dp(44)))
           formatSpinner.setOnTouchListener { _, event ->
               if (event.actionMasked == MotionEvent.ACTION_UP) showFormatPopup(formatSpinner)
               true
          }
          addSpaced(formatCard, bottom = 12)
          batchGenerateButton = styleButton(Button(this).apply { isEnabled = false; setOnClickListener { if (isEnabled) generateAll() } }, primary = true)
         updateBatchGenerateButton()
         addSpaced(batchGenerateButton!!, bottom = 14)
    }


internal fun MainActivity.updateBatchGenerateButton() {
         val count = inputRows.count { it.text.toString().trim().isNotEmpty() }
                   batchGenerateButton?.apply {
              text = "生成 ${count} 个条码"
              isEnabled = count > 0
              setTextColor(if (isEnabled) Color.WHITE else 0xff98a2b3.toInt())
              setBackgroundResource(if (isEnabled) R.drawable.bg_button_primary else R.drawable.bg_button_disabled)
          }
}


internal fun MainActivity.updateInputScrollHeight() {
         val scroll = inputScroll ?: return
         val height = dp((inputRows.size.coerceIn(1, 5)) * 56 + 16)
         scroll.layoutParams = (scroll.layoutParams ?: LinearLayout.LayoutParams(-1, height)).apply { this.height = height }
         scroll.requestLayout()
     }

internal fun MainActivity.addInputRow(value: String = "", focus: Boolean = false, after: EditText? = null) {
        val container = runCatching { inputContainer }.getOrNull() ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val edit = EditText(this).apply {
            hint = "输入一行条码内容"
            textSize = 16f
            setSingleLine(true)
            setTextColor(primaryText())
            setHintTextColor(secondaryText())
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), 0, dp(12), 0)
            setText(value)
            setSelection(text.length)
        }
        row.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(inputActionButton("↑") { moveInputRow(edit, -1) })
        row.addView(inputActionButton("↓") { moveInputRow(edit, 1) })
        row.addView(deleteInputButton(onClick = { removeInputRow(edit) }, onLongClick = { confirmClearAllInputRows() }))
        val insertAt = after?.let { inputRows.indexOf(it) + 1 }?.takeIf { it > 0 } ?: inputRows.size
        inputRows.add(insertAt, edit)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                inputDraft = inputRows.map { it.text.toString() }.toMutableList()
                updateBatchGenerateButton()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        inputDraft = inputRows.map { it.text.toString() }.toMutableList()
        container.addView(row, insertAt, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(6)) })
        container.requestLayout()
        updateInputScrollHeight()
        updateBatchGenerateButton()
        refreshInputActions()
        if (focus) edit.post {
            edit.requestFocus()
            edit.setSelection(edit.text.length)
            inputScroll?.smoothScrollTo(0, row.top)
        }
    }


internal fun MainActivity.deleteInputButton(onClick: () -> Unit, onLongClick: () -> Unit): ImageButton {
        val activity = this
        val size = (36 * resources.displayMetrics.density).toInt()
        return ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete_light)
            contentDescription = "删除此行"
            background = null
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
            setOnLongClickListener {
                onLongClick()
                true
            }
        }
    }


internal fun MainActivity.inputActionButton(label: String, color: Int = secondaryText(), onClick: () -> Unit): Button {
        val size = (36 * resources.displayMetrics.density).toInt()
        return Button(this).apply {
            text = label
            setBackgroundResource(R.drawable.bg_sort_button)
            textSize = 14f
            setTextColor(color)
            minWidth = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
        }
    }


internal fun MainActivity.moveInputRow(edit: EditText, direction: Int) {
        val activity = this
        val from = inputRows.indexOf(edit)
        val to = from + direction
        if (from < 0 || to !in inputRows.indices) return
        val item = inputRows.removeAt(from)
        inputRows.add(to, item)
        inputContainer.removeAllViews()
        inputRows.forEach { current ->
            val row = (current.parent as? LinearLayout)
            if (row != null) row.removeAllViews()
            val rebuilt = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            rebuilt.addView(current, LinearLayout.LayoutParams(0, -2, 1f))
            rebuilt.addView(inputActionButton("↑") { moveInputRow(current, -1) })
            rebuilt.addView(inputActionButton("↓") { moveInputRow(current, 1) })
            rebuilt.addView(deleteInputButton(onClick = { removeInputRow(current) }, onLongClick = { confirmClearAllInputRows() }))
            inputContainer.addView(rebuilt, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(6)) })
        }
        inputDraft = inputRows.map { it.text.toString() }.toMutableList()
        updateBatchGenerateButton()
        refreshInputActions()
    }


internal fun MainActivity.removeInputRow(edit: EditText) {
        val activity = this
        if (inputRows.size <= 1) {
            edit.setText("")
            inputDraft = mutableListOf("")
            updateBatchGenerateButton()
            return
        }
        inputRows.remove(edit)
        inputDraft = inputRows.map { it.text.toString() }.toMutableList()
        (edit.parent as? View)?.let { (it.parent as? LinearLayout)?.removeView(it) }
        updateInputScrollHeight()
        updateBatchGenerateButton()
        refreshInputActions()
    }


internal fun MainActivity.clearAllInputRows() {
        inputContainer.removeAllViews()
        inputRows.clear()
        inputDraft = mutableListOf("")
        addInputRow()
        inputScroll?.post { inputScroll?.fullScroll(View.FOCUS_UP) }
        toast("已清空输入框，仅保留一个")
    }

/** 长按垃圾桶是批量操作，先确认以避免误触清空所有输入。 */
internal fun MainActivity.confirmClearAllInputRows() {
    if (inputRows.size <= 1 && inputRows.firstOrNull()?.text.isNullOrBlank()) return
    val dialog = AlertDialog.Builder(this)
        .setTitle("清空所有输入？")
        .setMessage("将删除当前所有输入内容，并保留一个空白输入框。")
        .setNegativeButton("取消", null)
        .setPositiveButton("清空") { _, _ -> clearAllInputRows() }
        .create()
    showIos26Dialog(dialog, compact = true)
}


internal fun MainActivity.refreshInputActions() {
        val activity = this
        for (i in 0 until inputContainer.childCount) {
            val row = inputContainer.getChildAt(i) as? LinearLayout ?: continue
            if (row.childCount >= 4) {
                val showActions = inputRows.size > 1
                row.getChildAt(1).visibility = if (showActions) View.VISIBLE else View.GONE
                row.getChildAt(2).visibility = if (showActions) View.VISIBLE else View.GONE
                row.getChildAt(3).visibility = if (showActions) View.VISIBLE else View.GONE
                row.getChildAt(1).isEnabled = showActions && i > 0
                row.getChildAt(2).isEnabled = showActions && i < inputContainer.childCount - 1
                row.getChildAt(3).isEnabled = showActions
            }
        }
    }


internal fun MainActivity.generateAll() {
        val activity = this
        saveInputDraft()
        val values = inputDraft.map { it.trim() }.filter { it.isNotEmpty() }
        if (values.isEmpty()) { toast("请输入内容"); return }
        val selected = formats[formatSpinner.selectedItemPosition]
        val invalid = values.indexOfFirst { !BarcodeValidator.validate(it, selected.first).valid }
        if (invalid >= 0) { toast("第 ${invalid + 1} 行：${BarcodeValidator.validate(values[invalid], selected.first).message}"); return }
        // 仅从收藏文件的“编辑”路径重新生成时才更新原收藏。
        // 其他入口可能保留了旧的选中状态，不能让它影响新建收藏。
        val editingFavorite = selectedFavoriteGroup?.takeIf { resultsReturnPage == "favorites" }
        if (editingFavorite == null) selectedFavoriteGroup = null
         val generated = mutableListOf<CodeItem>()
        val batchTime = System.currentTimeMillis()
        values.forEach { value ->
            CodeItem(nextItemId(), value, selected.first, batchTime).also {
                items.add(0, it)
                generated.add(it)
            }
        }
        saveItems()
        resultItems = generated
        showingHistoryResult = false
        resultsReturnPage = if (editingFavorite != null) "favorites" else "generate"
        page = "results"
        showResults()
    }


internal fun MainActivity.showResults() {
        val activity = this
        content.removeAllViews()
        content.setPadding(0, dp(if (showingHistoryResult) 16 else 0), 0, dp(if (showingHistoryResult) 24 else 0))
        // 浅色结果页与固定白色条码画布使用同一底色，避免每个条码周围出现矩形白边。
        val resultBackground = if (isDark()) appBackground() else Color.WHITE
        content.setBackgroundColor(resultBackground)
        rootLayout.setBackgroundColor(resultBackground)
        window.statusBarColor = appBackground()
        window.navigationBarColor = appBackground()
        if (resultItems.isEmpty()) {
            addSpaced(sectionTitle("生成结果"), bottom = 6)
            addSpaced(TextView(this).apply { text = "暂无生成结果"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(secondaryText()); setPadding(0, dp(40), 0, dp(40)) }, bottom = 0)
            return
        }

        if (!showingHistoryResult) {
            val toolbar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(resultBackground)
            }
            fun toolButton(iconRes: Int, description: String, action: () -> Unit) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), 0, dp(6), 0)
                isClickable = true
                isFocusable = true
                contentDescription = description
                addView(ImageView(activity).apply {
                    setImageResource(iconRes)
                    setColorFilter(if (isDark()) 0xffb8ccff.toInt() else 0xff2166d1.toInt())
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(25), dp(27)))
                addView(TextView(activity).apply { text = description; textSize = 12f; gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = -0.01f; includeFontPadding = false; setTextColor(if (isDark()) 0xffd7e3f5.toInt() else 0xff2453a6.toInt()); setPadding(0, dp(3), 0, 0) })
                setOnClickListener { action() }
            }
            toolbar.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
            toolbar.addView(toolButton(R.drawable.ic_action_edit, "编辑") {
                if (selectedFavoriteGroup != null && resultsReturnPage == "favorites") {
                    // 从收藏文件的结果页编辑时，以当前结果页数据回填生成页，并保留收藏组以便保存时更新原文件。
                    inputDraft = resultItems.map { it.text }.toMutableList()
                    pendingGenerateFormat = resultItems.firstOrNull()?.format
                    // showGenerate() 会先保存现有输入框草稿；清除旧页面引用，避免其覆盖刚回填的结果数据。
                    inputRows.clear()
                    page = "generate"
                    render()
                }
                else {
                    inputDraft = resultItems.map { it.text }.toMutableList()
                    inputRows.clear()
                    page = "generate"
                    render()
                }
            }, LinearLayout.LayoutParams(dp(64), dp(64)))
            toolbar.addView(toolButton(R.drawable.ic_action_favorite, "收藏") { saveResultAsFavorite() }, LinearLayout.LayoutParams(dp(64), dp(64)))
            toolbar.addView(toolButton(R.drawable.ic_action_share, "分享") { shareResultPage() }, LinearLayout.LayoutParams(dp(64), dp(64)))
            addSpaced(toolbar, bottom = 0)
        }

        resultItems.forEachIndexed { index, item ->
            val itemBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(12), 0, dp(12), 0)
            }
            val barcode = encode(item.text, formats.firstOrNull { it.first == item.format }?.second ?: BarcodeFormat.CODE_128)
            if (barcode != null) {
                val isCode128 = item.format == "Code 128-B"
                if (isCode128) {
                    val barHeightPx = dp(activity.style.barHeight.coerceIn(30, 150).coerceAtLeast(1)).coerceAtMost(barcode.height)
                    val desiredWidth = dp(activity.style.barWidth.roundToInt().coerceIn(120, 360)).coerceAtLeast(1)
                    val textEnabled = true
                    val label = if (activity.style.showFormat) "${item.text} · ${item.format}" else item.text
                    // encode() 的 Bitmap 可能还包含文字。这里只取纯条码区域，文字交给独立 TextView，
                    // 从根上避免文字和条码共享同一个 Canvas 而发生重叠。
                    val sourceTop = 0
                    val barOnly = Bitmap.createBitmap(barcode, 0, sourceTop, barcode.width, barHeightPx)
                    val labelView = TextView(activity).apply {
                        text = label
                        textSize = activity.style.textSize.coerceIn(10f, 24f)
                        gravity = Gravity.CENTER
                        includeFontPadding = true
                        setTextColor(if (activity.isDark()) Color.WHITE else activity.style.barColor)
                        setPadding(0, dp(8), 0, dp(4))
                        contentDescription = "条码文字"
                    }
                    itemBox.addView(ImageView(activity).apply {
                        setImageBitmap(barOnly)
                        scaleType = ImageView.ScaleType.FIT_XY
                        setPadding(0, 0, 0, 0)
                        contentDescription = "${item.format} 条码"
                    }, LinearLayout.LayoutParams(desiredWidth, barHeightPx).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    })
                    itemBox.addView(labelView, LinearLayout.LayoutParams(-1, -2))
                } else {
                    itemBox.addView(ImageView(activity).apply {
                        setImageBitmap(barcode)
                        adjustViewBounds = true
                        setPadding(0, 0, 0, 0)
                        contentDescription = "${item.format} 条码"
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            }
            content.addView(itemBox, LinearLayout.LayoutParams(-1, -2))
            if (index < resultItems.lastIndex && item.format == "Code 128-B" && resultItems[index + 1].format == "Code 128-B" && style.margin > 0) {
                content.addView(Space(this), LinearLayout.LayoutParams(1, dp(style.margin)))
            }
        }
    }


internal fun MainActivity.shareResultPage() {
        val activity = this
        val images = resultItems.mapNotNull { item ->
            encode(item.text, formats.firstOrNull { it.first == item.format }?.second ?: BarcodeFormat.CODE_128)
        }
        if (images.isEmpty()) { toast("没有可分享的条码"); return }
        val width = images.maxOf { it.width }
        val spacing = if (resultItems.all { it.format == "Code 128-B" }) dp(style.margin).coerceAtLeast(0) else 0
        val height = images.sumOf { it.height } + spacing * (images.size - 1)
        val pageImage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(pageImage)
        canvas.drawColor(style.bgColor)
        var top = 0
        images.forEach { image ->
            canvas.drawBitmap(image, (width - image.width) / 2f, top.toFloat(), null)
            top += image.height + spacing
        }
        shareBitmap(pageImage, "本页生成的 ${images.size} 个条码")
    }


internal fun MainActivity.showList(favoritesOnly: Boolean) {
        content.removeAllViews()
        val background = appBackground()
        content.setBackgroundColor(background)
        rootLayout.setBackgroundColor(background)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = if (isDark()) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val tools = LinearLayout(this).apply { gravity = Gravity.END }
        tools.addView(Button(this).apply {
             text = "清空"; minHeight = dp(36); minimumHeight = dp(36); minWidth = 0; minimumWidth = 0
             setPadding(dp(10), 0, dp(10), 0); setBackgroundColor(Color.TRANSPARENT); stateListAnimator = null; elevation = 0f
             setTextColor(if (isDark()) 0xffff9b9b.toInt() else 0xffc85c5c.toInt())
             setOnClickListener { confirmClear(favoritesOnly) }
         })
        addSpaced(tools, bottom = 12)
        val list = if (favoritesOnly) items.filter { it.favorite } else items.filter { it.inHistory }
        if (list.isEmpty()) { addSpaced(TextView(this).apply { text = if (favoritesOnly) "还没有收藏" else "暂无历史记录"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(secondaryText()); setPadding(0, dp(40), 0, dp(40)) }, bottom = 0); return }
        if (favoritesOnly) list.forEach { addCard(it) }
        else list.groupBy { it.createdAt }.toList().sortedByDescending { it.first }.forEach { (time, batch) -> addHistoryRow(batch, time) }
    }


internal fun MainActivity.addHistoryRow(batch: List<CodeItem>, time: Long) {
    val activity = this
    val orderedBatch = batch.sortedBy { it.id }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(2), dp(6), dp(2))
        setBackgroundResource(R.drawable.bg_history_card)
        setOnClickListener {
            resultItems = orderedBatch.toMutableList()
            showingHistoryResult = true
            resultsReturnPage = "history"
            page = "results"
            showResults()
        }
        setOnLongClickListener {
            if (orderedBatch.size == 1) showItemEditor(orderedBatch.first())
            else AlertDialog.Builder(activity)
                .setTitle("本次生成的 ${orderedBatch.size} 个条码")
                .setItems(orderedBatch.map { it.text }.toTypedArray()) { _, which -> showItemEditor(orderedBatch[which]) }
                .create().also { showIos26Dialog(it) }
            true
        }
    }

    val firstCodePreview = orderedBatch.firstOrNull()?.text?.let { value ->
        if (value.length > 8) value.take(8) + "..." else value
    }.orEmpty()
    row.addView(TextView(this).apply {
        text = "${orderedBatch.size}条：$firstCodePreview"
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
        setTextColor(primaryText())
    }, LinearLayout.LayoutParams(0, dp(40), 1f))

    val right = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        addView(TextView(activity).apply {
            text = formatHistoryTime(time)
            textSize = 12f
            setTextColor(secondaryText())
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, dp(18), 1f))
        addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_delete_light)
            contentDescription = "删除这条历史记录"
            background = null
            setColorFilter(0xffd98787.toInt())
            setPadding(dp(4), dp(2), 0, dp(2))
            elevation = 0f
            setOnClickListener {
                orderedBatch.forEach { it.inHistory = false }
                saveItems()
                showList(false)
            }
        }, LinearLayout.LayoutParams(dp(32), dp(28)))
    }
    row.addView(right, LinearLayout.LayoutParams(dp(100), dp(40)))
    content.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(dp(6), 0, dp(6), dp(7))
    })
}

internal fun MainActivity.formatHistoryTime(time: Long): String {
    val date = Date(time)
    val now = Date()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date) == SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)
    return if (sameDay) SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    else SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(date)
}

internal fun MainActivity.openFavoriteForEditing(group: FavoriteGroup) {
    selectedFavoriteGroup = group
    resultItems = group.itemIds.mapNotNull { id -> items.firstOrNull { it.id == id } }
    inputDraft = resultItems.map { it.text }.toMutableList()
    pendingGenerateFormat = resultItems.firstOrNull()?.format
    page = "generate"
    resultsReturnPage = "favorites"
    render()
}

internal fun MainActivity.addCard(item: CodeItem, editable: Boolean = true) {
        val box = contentCard()
        val barcode = encode(item.text, formats.firstOrNull { it.first == item.format }?.second ?: BarcodeFormat.CODE_128)
        if (barcode != null) box.addView(ImageView(this).apply { setImageBitmap(barcode); adjustViewBounds = true; setPadding(0, dp(4), 0, dp(8)); contentDescription = "${item.format} 条码" }, LinearLayout.LayoutParams(-1, -2))
        val detail = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        detail.addView(TextView(this).apply { text = "${item.text}\n${item.format}"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(primaryText()); setPadding(0, 0, 0, dp(4)) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (editable) detail.addView(styleButton(Button(this).apply { text = "编辑"; setOnClickListener { selectedFavoriteGroup?.let { openFavoriteForEditing(it) } } }), LinearLayout.LayoutParams(-2, dp(38)))
        box.addView(detail)
        content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) })
    }


internal fun MainActivity.showFavoriteGroups() {
        val activity = this
        content.removeAllViews()
        content.setPadding(0, 0, 0, dp(20))
        content.setBackgroundColor(appBackground())
        rootLayout.setBackgroundColor(appBackground())
        window.statusBarColor = appBackground()
        window.navigationBarColor = appBackground()

        search = EditText(this).apply {
            hint = "搜索名称、文件夹或内容"; textSize = 17f; setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START; includeFontPadding = true
            minHeight = dp(56); minimumHeight = dp(56)
            setBackgroundResource(R.drawable.bg_input)
            // 图标单独放在容器中，避免复合 Drawable 与字体共用基线导致占位文字上下偏移。
            setPadding(dp(48), 0, dp(16), 0)
        }
        val searchBox = FrameLayout(this).apply {
            addView(search, FrameLayout.LayoutParams(-1, -1))
            addView(ImageView(this@showFavoriteGroups).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(secondaryText())
                contentDescription = "搜索"
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(16)
            })
        }
        addSpaced(searchBox, bottom = 16)
        favoriteTreeContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(favoriteTreeContainer, LinearLayout.LayoutParams(-1, -2))
        renderFavoriteTree("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderFavoriteTree(s?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }



internal fun MainActivity.renderFavoriteTree(query: String) {
    val tree = favoriteTreeContainer ?: return
    tree.removeAllViews()
    val folders = (favoriteFolders + favoriteGroups.map { it.folder }).filter { it.isNotBlank() }.distinct()
    val roots = folders.map { it.substringBefore('/') }.distinct().sorted()
    // 固定的三级语义色：蓝色一级目录、琥珀色二级目录、绿色收藏文件。
    val rootFolderColor = 0xff527ca8.toInt()
    val childFolderColor = 0xff9b7a57.toInt()
    val favoriteFileColor = 0xff5c8c7b.toInt()
    fun matches(g: FavoriteGroup) = query.isEmpty() || g.folder.lowercase(Locale.getDefault()).contains(query) || g.name.lowercase(Locale.getDefault()).contains(query) || g.itemIds.any { id -> items.firstOrNull { it.id == id }?.text?.lowercase(Locale.getDefault())?.contains(query) == true }
    if (!favoriteTreeInitialized) {
        // 仅首次进入收藏页时默认全折叠；后续重绘必须保留用户的展开状态。
        collapsedFavoriteFolders.addAll(folders)
        favoriteTreeInitialized = true
    } else {
        // 已删除的文件夹不再保留折叠状态，避免状态集合无限增长。
        collapsedFavoriteFolders.retainAll(folders)
    }
    if (query.isNotEmpty()) {
        if (favoriteCollapsedBeforeSearch == null) favoriteCollapsedBeforeSearch = collapsedFavoriteFolders.toSet()
        // 搜索命中的文件及其所有父级路径自动展开；用户清除搜索后会恢复原状态。
        favoriteGroups.filter(::matches).flatMap { group ->
            group.folder.split('/').indices.map { index -> group.folder.split('/').take(index + 1).joinToString("/") }
        }.forEach { collapsedFavoriteFolders.remove(it) }
    } else {
        favoriteCollapsedBeforeSearch?.let { previous ->
            collapsedFavoriteFolders.clear()
            collapsedFavoriteFolders.addAll(previous.filter { it in folders })
            favoriteCollapsedBeforeSearch = null
        }
    }
    roots.forEach { root ->
        fun renderFolder(path: String, level: Int) {
            val prefix = "$path/"
            val children = folders.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains("/") }.map { it.removePrefix(prefix) }.distinct().sorted()
            val groups = favoriteGroups.filter { it.folder == path && matches(it) }
            val matchingDescendants = favoriteGroups.filter { it.folder.startsWith(prefix) && matches(it) }
            if (query.isNotEmpty() && groups.isEmpty() && matchingDescendants.isEmpty()) return
            val collapsed = path in collapsedFavoriteFolders
            val count = if (level == 0) children.size else groups.size
            addTreeHeader(tree, path.substringAfterLast('/'), path, count, collapsed, if (level == 0) rootFolderColor else childFolderColor, level, query)
            if (!collapsed) {
                groups.forEach { addTreeFile(tree, it, level + 1, favoriteFileColor) }
                children.forEach { child -> renderFolder("$path/$child", level + 1) }
            }
        }
        renderFolder(root, 0)
    }
}

private fun MainActivity.addTreeHeader(container: LinearLayout, label: String, folder: String, count: Int, collapsed: Boolean, color: Int, level: Int, query: String) {
    val isRoot = level == 0
    val header = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        // 文件夹标题保持轻量的树状内容行，避免每一层都像按钮。
        setPadding(dp(if (isRoot) 11 else 26), 0, dp(5), 0)
        // 文件夹本身保持无底色，仅用缩进、颜色和分隔线表达层级；玻璃材质只留给操作按钮。
        setBackgroundColor(Color.TRANSPARENT)
        elevation = 0f
        setOnClickListener {
            val folders = (favoriteFolders + favoriteGroups.map { it.folder }).filter { it.isNotBlank() }.distinct()
            if (collapsed) collapsedFavoriteFolders.remove(folder)
            else collapsedFavoriteFolders.addAll(folders.filter { it == folder || it.startsWith("$folder/") })
            findViewWithTag<TextView>("folderArrow")?.animate()?.rotation(if (collapsed) 90f else 0f)?.setDuration(170)?.start()
            postDelayed({ renderFavoriteTree(query) }, 150)
        }
    }
    val rowHeight = dp(if (isRoot) 48 else 43)
    header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_folder); setColorFilter(if (isRoot) color else secondaryText()); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(if (isRoot) 27 else 21), rowHeight).apply { setMargins(0, 0, dp(if (isRoot) 8 else 7), 0) })
    // 名称使用固定的层级语义色，数量、箭头和操作入口继续保持弱化。
    header.addView(TextView(this).apply { text = label; textSize = if (isRoot) 18f else 17f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = -0.01f; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false; setTextColor(color) }, LinearLayout.LayoutParams(0, rowHeight, 1f))
    header.addView(TextView(this).apply { text = "$count"; textSize = 13f; gravity = Gravity.CENTER; includeFontPadding = false; setTextColor(secondaryText()) }, LinearLayout.LayoutParams(dp(28), rowHeight))
    header.addView(TextView(this).apply { tag = "folderArrow"; text = "›"; textSize = 22f; gravity = Gravity.CENTER; includeFontPadding = false; rotation = if (collapsed) 0f else 90f; setTextColor(secondaryText()) }, LinearLayout.LayoutParams(dp(25), rowHeight))
    header.addView(ImageButton(this).apply {
        setImageResource(R.drawable.ic_action_edit)
        imageTintList = ColorStateList.valueOf(if (isDark()) 0xffb8ccff.toInt() else 0xff2166d1.toInt())
        contentDescription = "编辑文件夹"
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = glassButtonBackground().apply { cornerRadius = dp(14).toFloat() }
        isClickable = true; isFocusable = true
        setOnClickListener { showTreeFolderMenu(this, folder, level) }
    }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(2), 0, 0, 0) })
    container.addView(header, LinearLayout.LayoutParams(-1, dp(if (isRoot) 50 else 43)).apply { setMargins(dp(if (isRoot) 0 else 10), 0, dp(if (isRoot) 0 else 4), dp(if (isRoot) 7 else 1)) })
}

private fun MainActivity.showTreeFolderMenu(anchor: View, folder: String, level: Int) {
    val actions = if (level == 0) listOf("新建文件夹", "重命名", "删除") else listOf("重命名", "删除")
    showMaterialDropdown(anchor, actions) { which ->
        when {
            level == 0 && which == 0 -> showSubfolderEditor(folder)
            which == if (level == 0) 1 else 0 -> showFolderEditor(folder) { renamed ->
                favoriteGroups.filter { it.folder == folder || it.folder.startsWith("$folder/") }.forEach { it.folder = if (it.folder == folder) renamed else renamed + it.folder.removePrefix(folder) }
                favoriteFolders.filter { it == folder || it.startsWith("$folder/") }.toList().forEach { old -> favoriteFolders.remove(old); favoriteFolders.add(if (old == folder) renamed else renamed + old.removePrefix(folder)) }
                saveAllFavorites(); render()
            }
            which == if (level == 0) 2 else 1 -> AlertDialog.Builder(this).setTitle("删除文件夹").setMessage("将删除文件夹内的所有收藏，确定继续吗？").setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                favoriteGroups.removeAll { it.folder == folder || it.folder.startsWith("$folder/") }
                favoriteFolders.removeAll { it == folder || it.startsWith("$folder/") }
                saveAllFavorites(); render()
            }.create().also { showIos26Dialog(it) }
        }
    }
}

private fun MainActivity.addTreeFile(container: LinearLayout, group: FavoriteGroup, level: Int, color: Int) {
    val groupItems = group.itemIds.mapNotNull { id -> items.firstOrNull { it.id == id } }
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.TRANSPARENT); setPadding(dp(if (level == 1) 20 else 28), dp(2), dp(4), dp(2)); setOnClickListener { resultItems = groupItems; showingHistoryResult = false; resultsReturnPage = "favorites"; selectedFavoriteGroup = group; page = "results"; render() } }
    // 收藏文件名固定为绿色，与两级文件夹形成稳定的三级视觉关系。
    row.addView(TextView(this).apply { text = group.name; textSize = 17f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = -0.01f; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false; setTextColor(color) }, LinearLayout.LayoutParams(0, dp(44), 1f))
    row.addView(TextView(this).apply { text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(group.savedAt)); textSize = 11f; gravity = Gravity.CENTER_VERTICAL; setTextColor(secondaryText()) }, LinearLayout.LayoutParams(dp(78), dp(44)))
    row.addView(ImageButton(this).apply {
        setImageResource(R.drawable.ic_action_edit)
        imageTintList = ColorStateList.valueOf(if (isDark()) 0xffb8ccff.toInt() else 0xff2166d1.toInt())
        contentDescription = "编辑收藏文件"
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = glassButtonBackground().apply { cornerRadius = dp(14).toFloat() }
        isClickable = true; isFocusable = true
        setOnClickListener { showFavoriteFileMenu(this, group, groupItems) }
    }, LinearLayout.LayoutParams(dp(40), dp(40)))
    // 文件为内容层，沿父文件夹缩进并保留平整材质，不再与文件夹头部争夺玻璃层级。
    container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(if (level <= 1) 16 else 38), 0, dp(4), dp(6)) })
}

private fun MainActivity.showFavoriteFileMenu(anchor: View, group: FavoriteGroup, groupItems: List<CodeItem>) {
    showMaterialDropdown(anchor, listOf("移动", "重命名", "删除")) { which ->
        when (which) {
            0 -> showFavoriteMoveDialog(group)
            1 -> showFavoriteRenameDialog(group)
            2 -> AlertDialog.Builder(this).setTitle("删除收藏").setMessage("确定删除“${group.name}”吗？").setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> favoriteGroups.removeAll { it.id == group.id }; groupItems.forEach { item -> if (favoriteGroups.none { it.itemIds.contains(item.id) }) item.favorite = false }; if (group.folder !in favoriteFolders) favoriteFolders.add(group.folder); saveAllFavorites(); showFavoriteGroups() }.create().also { showIos26Dialog(it) }
        }
    }
}

internal fun MainActivity.showSubfolderEditor(parent: String, onCreated: ((String) -> Unit)? = null) {
    val input = inputField("文件夹名称")
    val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, dp(50))) }
    val dialog = AlertDialog.Builder(this).setTitle("新建文件夹").setView(box).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val child = input.text.toString().trim()
            val path = "$parent/$child"
            if (child.isBlank()) toast("请输入文件夹名称")
            else if (child.contains('/')) toast("名称不能包含斜杠")
            else if (path in favoriteFolders) toast("已存在同名文件夹")
            else { favoriteFolders.add(path); saveFavoriteFolders(); dialog.dismiss(); onCreated?.invoke(child) ?: render() }
        }
    }
    showIos26Dialog(dialog)
}

internal fun MainActivity.showFavoriteRenameDialog(group: FavoriteGroup) {
    val input = inputField("收藏文件名", group.name)
    val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, dp(50))) }
    val dialog = AlertDialog.Builder(this).setTitle("重命名收藏").setView(box).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
    dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = input.text.toString().trim()
        if (name.isBlank()) toast("请输入收藏文件名") else { group.name = name; saveFavoriteGroups(); dialog.dismiss(); showFavoriteGroups() }
    } }
    showIos26Dialog(dialog)
}

internal fun MainActivity.showFavoriteMoveDialog(group: FavoriteGroup) {
    val folders = favoriteFolders.filter { it.isNotBlank() }
    if (folders.isEmpty()) { AlertDialog.Builder(this).setTitle("移动收藏").setMessage("请先创建文件夹").setPositiveButton("确定", null).create().also { showIos26Dialog(it) }; return }
    val spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@showFavoriteMoveDialog, android.R.layout.simple_spinner_dropdown_item, folders)
        setSelection(folders.indexOf(group.folder).coerceAtLeast(0))
        setBackgroundResource(R.drawable.bg_input)
        setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                showMaterialDropdown(view, folders, popupWidth = view.width, selectedIndex = selectedItemPosition) { index ->
                    setSelection(index)
                }
            }
            true
        }
    }
    val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(spinner, LinearLayout.LayoutParams(-1, dp(50))) }
    AlertDialog.Builder(this).setTitle("移动收藏").setView(box).setNegativeButton("取消", null).setPositiveButton("移动") { _, _ ->
        group.folder = spinner.selectedItem?.toString() ?: ""
        if (group.folder.isNotBlank() && group.folder !in favoriteFolders) favoriteFolders.add(group.folder)
        saveAllFavorites(); showFavoriteGroups()
    }.create().also { showIos26Dialog(it) }
}

internal fun MainActivity.showFavoriteDetail() {
        val activity = this
        val group = selectedFavoriteGroup ?: run { page = "favorites"; showFavoriteGroups(); return }
        content.removeAllViews()
        addSpaced(sectionTitle(group.name, "${group.folder} · 保存于 ${formatSavedTime(group.savedAt)}"), bottom = 6)
        addSpaced(styleButton(Button(this).apply { text = "返回收藏"; setOnClickListener { page = "favorites"; showFavoriteGroups() } }), bottom = 10)
        val groupItems = group.itemIds.mapNotNull { id -> items.firstOrNull { it.id == id } }
        groupItems.forEach { addCard(it, editable = true) }
        if (groupItems.isEmpty()) { addSpaced(TextView(this).apply { text = "此收藏暂无条码"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(secondaryText()); setPadding(0, dp(32), 0, dp(32)) }, bottom = 0) }
    }


internal fun MainActivity.formatSavedTime(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))


internal fun MainActivity.moveToFolder(item: CodeItem) {
        val activity = this
        val input = EditText(this).apply { hint = "例如：工作、商品、旅行"; setSingleLine(true); setText(item.folder) }
        AlertDialog.Builder(this).setTitle("移动到文件夹").setView(input).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ -> item.folder = input.text.toString().trim().ifEmpty { "默认" }; saveItems(); render() }.create().also { showIos26Dialog(it) }
    }


internal fun MainActivity.confirmClear(favoritesOnly: Boolean) {
        val activity = this
        AlertDialog.Builder(this).setTitle(if (favoritesOnly) "清空收藏" else "清空历史").setMessage(if (favoritesOnly) "确定删除全部收藏吗？" else "仅清空历史记录，收藏内容不会删除。") .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
            if (favoritesOnly) {
                favoriteGroups.clear()
                items.forEach { it.favorite = false; it.folder = "默认" }
                saveAllFavorites()
            } else {
                items.forEach { it.inHistory = false }
                saveItems()
            }
            render()
        }.create().also { showIos26Dialog(it, compact = !favoritesOnly) }
    }


internal fun MainActivity.preview(item: CodeItem) {
        val activity = this
        val bmp = encode(item.text, formats.first { it.first == item.format }.second) ?: run { toast("内容不符合该格式"); return }
        val image = ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true }
        AlertDialog.Builder(this).setTitle(item.format).setMessage(item.text).setView(image).setPositiveButton("关闭", null).setNeutralButton("分享图片") { _, _ -> shareBitmap(bmp, item.text) }.setNegativeButton("保存图片") { _, _ -> saveBitmap(bmp, item.text) }.create().also { showIos26Dialog(it) }
    }


internal fun MainActivity.shareText(text: String) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "分享条码内容")) }

internal fun MainActivity.saveBitmap(bitmap: Bitmap, label: String) {
        val activity = this
        val values = android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, label.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80).ifBlank { "barcode" } + ".png"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png"); if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BarcodeGenerator") }
        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { toast("保存失败"); return }
        contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        toast("已保存到相册")
    }


internal fun MainActivity.shareBitmap(bitmap: Bitmap, label: String) {
        val activity = this
        val values = android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, label.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80).ifBlank { "barcode" } + ".png"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png"); if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.IS_PENDING, 1) }
        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { toast("分享失败"); return }
        contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, label); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "分享条码图片"))
    }


internal fun MainActivity.encode(text: String, format: BarcodeFormat): Bitmap? = try {
        val activity = this
        val code128 = format == BarcodeFormat.CODE_128
        val width = if (code128) dp(activity.style.barWidth.roundToInt().coerceIn(120, 360)).coerceAtLeast(1) else 500
        val barcodeHeight = if (code128) dp(activity.style.barHeight.coerceIn(30, 150).coerceAtLeast(1)) else if (format == BarcodeFormat.QR_CODE) 500 else 200
        val matrix = MultiFormatWriter().encode(text, format, width, barcodeHeight, mapOf(EncodeHintType.MARGIN to 0))
        // 编码函数只返回纯条码 Bitmap；人类可读文字由结果页的独立 TextView 绘制。
        // 这里不能把文字画进 Bitmap，否则历史、预览、分享等路径会再次出现条码内嵌文字。
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            setColor(if (activity.isDark()) Color.BLACK else activity.style.barColor)
        }
        val bitmap = Bitmap.createBitmap(width, barcodeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (activity.isDark()) canvas.drawColor(Color.WHITE)
         else canvas.drawColor(activity.style.bgColor)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) canvas.drawRect(x.toFloat(), y.toFloat(), (x + 1).toFloat(), (y + 1).toFloat(), paint)
            }
        }
        bitmap
    } catch (_: Exception) { null }

internal fun MainActivity.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()


internal fun MainActivity.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

/** 与业务完全分离的全屏烟花；不写入任何设置或条码数据。 */
internal fun MainActivity.showFireworksEasterEgg() {
    val host = findViewById<ViewGroup>(android.R.id.content) ?: return
    // 在当前 Activity 叠加特效，而非启动新页面，避免改变设置页导航栈。
    // Android 15 默认边到边显示：让黑色夜空延伸到状态栏后方，但保持状态栏图标可读。
    val overlay = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        contentDescription = "烟花彩蛋"
    }
    val fireworks = InlineFireworksView(this)
    overlay.addView(fireworks, FrameLayout.LayoutParams(-1, -1))
    dismissFireworksEasterEgg()
    fireworksPreviousStatusBarColor = window.statusBarColor
    fireworksPreviousNavigationBarColor = window.navigationBarColor
    fireworksPreviousSystemUiVisibility = window.decorView.systemUiVisibility
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK
    val lightSystemBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and lightSystemBars.inv()
    fireworksOverlay = overlay
    // Android 15 会透明化导航栏颜色；必须让夜空内容本身延伸到导航栏后方，才能避免露出原页面浅色背景。
    host.addView(overlay, FrameLayout.LayoutParams(-1, -1))
}

/** 关闭彩蛋时恢复进入前的系统状态栏，避免影响当前页面的深浅色显示。 */
internal fun MainActivity.dismissFireworksEasterEgg() {
    fireworksOverlay?.let { overlay -> (overlay.parent as? ViewGroup)?.removeView(overlay) }
    fireworksOverlay = null
    fireworksPreviousStatusBarColor?.let { window.statusBarColor = it }
    fireworksPreviousNavigationBarColor?.let { window.navigationBarColor = it }
    fireworksPreviousSystemUiVisibility?.let { window.decorView.systemUiVisibility = it }
    fireworksPreviousStatusBarColor = null
    fireworksPreviousNavigationBarColor = null
    fireworksPreviousSystemUiVisibility = null
}

