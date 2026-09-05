package com.luckyalanzhou.barcodegenerator

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.text.*
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatDelegate
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


internal fun MainActivity.updateTopTabSelection() {
    val selected = tabPageIndex()
    // 浅色模式使用清晰的蓝色强调色，而不是深灰色；选中后不应显得更暗。
    val selectedColor = if (isDark()) 0xfff4f7ff.toInt() else 0xff246fc4.toInt()
    val unselectedColor = if (isDark()) 0xffc4cada.toInt() else 0xff64748b.toInt()
    topTabButtons.forEach { tab ->
        val isSelected = tab.tag == selected
        tab.findViewWithTag<TextView>("tabLabel")?.setTextColor(if (isSelected) selectedColor else unselectedColor)
        tab.findViewWithTag<ImageView>("tabIcon")?.setColorFilter(if (isSelected) selectedColor else unselectedColor)
        // 选中项自身抬升，玻璃表面在图文下方绘制，不会遮挡图标或文字。
        tab.setBackgroundResource(if (isSelected && !tabGlassDragActive) R.drawable.bg_tab_selected else R.drawable.bg_tab)
        // 保持很轻的悬浮距离，避免变成厚重的实体按钮。
        tab.elevation = if (isSelected && !tabGlassDragActive) dp(3).toFloat() else 0f
        tab.translationZ = if (isSelected && !tabGlassDragActive) dp(1).toFloat() else 0f
    }
}

internal fun MainActivity.isDark() = style.colorScheme == "dark" || (style.colorScheme == "system" && (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)



internal fun MainActivity.appBackground() = if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt()
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
        if (page != "settings") settingsReturnPage = page
        page = "settings"
        render()
    }


internal fun MainActivity.switchTopTabBySwipe(deltaX: Float) {
        val activity = this
        if (kotlin.math.abs(deltaX) < dp(42).toFloat()) return
        val next = (tabPageIndex() + if (deltaX < 0) 1 else -1).coerceIn(0, 3)
        val target = listOf("generate", "history", "favorites", "settings")[next]
        if (target == "settings") settingsReturnPage = page
        page = target
        render()
    }



internal fun MainActivity.styleButton(button: Button, primary: Boolean = false) = button.apply {
        setBackgroundResource(if (primary) R.drawable.bg_button_primary else R.drawable.bg_button)
        // 以 Android 系统字实现接近 iOS 的清晰、略带强调的按钮文字，不嵌入受限字体。
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 15f
        letterSpacing = -0.01f
        includeFontPadding = false
        gravity = Gravity.CENTER
        isAllCaps = false
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setTextColor(if (primary) Color.WHITE else if (isDark()) 0xffd7e3f5.toInt() else 0xff2453a6.toInt())
        stateListAnimator = null
        elevation = dp(1).toFloat()
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
    cornerRadius = dp(18).toFloat()
    orientation = GradientDrawable.Orientation.TOP_BOTTOM
    // 次级玻璃保持中性，蓝色只用于选中态与主操作。
    setColors(if (isDark()) intArrayOf(0x663D4249, 0x3D2E3238) else intArrayOf(0xB3FFFFFF.toInt(), 0x80F0F2F4.toInt()))
    setStroke(dp(1), if (isDark()) 0x665E6670.toInt() else 0x80FFFFFF.toInt())
}

internal fun MainActivity.applyIos26DialogStyle(dialog: AlertDialog) {
    dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(28).toFloat()
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setColors(if (isDark()) intArrayOf(0xF0363A41.toInt(), 0xE62A2E34.toInt()) else intArrayOf(0xFAFFFFFF.toInt(), 0xE6EEF0F2.toInt()))
        setStroke(dp(1), if (isDark()) 0x66737A84.toInt() else 0x99FFFFFF.toInt())
    })
    dialog.window?.decorView?.elevation = dp(14).toFloat()
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
        }
    }
}

internal fun MainActivity.showIos26Dialog(dialog: AlertDialog): AlertDialog {
    dialog.show()
    applyIos26DialogStyle(dialog)
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
        setBackgroundResource(R.drawable.bg_card)
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
        root.setBackgroundColor(if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt())
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
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                // 设置页在竖屏下固定在一屏内，保留控件点击/滑块操作但不允许页面滚动。
                return if (page == "generate" || (page == "settings" && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)) false else super.onInterceptTouchEvent(event)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                return if (page == "settings" && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) false else super.onTouchEvent(event)
            }
        }.also { pageScroll = it }.apply {
            addView(content); isFillViewport = true; clipToPadding = false
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(pageScrollView, LinearLayout.LayoutParams(-1, 0, 1f))
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
            if (index == 3) openSettings()
            else if (page != tabPages[index]) { page = tabPages[index]; render() }
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
                tab.elevation = dp(5).toFloat() * lensStrength
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
        showAppChrome(page !in listOf("results", "favoriteDetail"))
        when (page) { "history" -> content.post { showList(false) }; "favorites" -> showFavoriteGroups(); "favoriteDetail" -> showFavoriteDetail(); "results" -> showResults(); "settings" -> showSettings(); else -> showGenerate() }
        content.clearAnimation()
        content.alpha = 1f
        } finally {
            isRenderingUi = false
        }
    }


internal fun MainActivity.showAppChrome(visible: Boolean) {
        val activity = this
        runCatching { appHeader.visibility = if (visible) View.VISIBLE else View.GONE }
        runCatching { topNav.visibility = if (visible) View.VISIBLE else View.GONE }
        runCatching { pageScroll?.isVerticalScrollBarEnabled = false; pageScroll?.overScrollMode = View.OVER_SCROLL_NEVER; pageScroll?.isEnabled = visible }
        if (!visible) runCatching { rootLayout.setBackgroundColor(if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt()) }
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
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(TextView(this).apply {
            text = if (index == selectedIndex) "✓" else ""
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(if (isDark()) 0xffb8c9ff.toInt() else 0xff367be8.toInt())
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(28), dp(48)))
        menu.addView(row)
    }
    val frame = Rect()
    anchor.getWindowVisibleDisplayFrame(frame)
    val metrics = resources.displayMetrics
    val maxWidth = (metrics.widthPixels - dp(32)).coerceAtLeast(dp(1))
    val width = if (popupWidth != null) {
        popupWidth.coerceAtMost(maxWidth).coerceAtLeast(dp(1))
    } else {
        dp(220).coerceAtMost(maxWidth)
    }
    val contentHeight = options.size * dp(48) + dp(4)
    val location = IntArray(2)
    anchor.getLocationOnScreen(location)
    val below = frame.bottom - (location[1] + anchor.height) - dp(6)
    val above = location[1] - frame.top - dp(6)
    val desiredLeft = location[0].coerceIn(frame.left + dp(16), frame.right - width - dp(16))
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
        elevation = dp(10).toFloat()
        setAnimationStyle(android.R.style.Animation_Dialog)
    }
    if (forceBelowAnchor) {
        // 弹窗内的 View 使用屏幕坐标会产生偏差；由系统直接相对控件定位，确保紧贴“选择文件夹”项的下边缘。
        popup.showAsDropDown(anchor, desiredLeft - location[0], dp(6))
    } else if (opensBelow) {
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, desiredLeft, location[1] + anchor.height + dp(6))
    } else {
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, desiredLeft, location[1] - height - dp(6))
    }
}

internal fun MainActivity.showFormatPopup(anchor: View) = showMaterialDropdown(anchor, formats.map { it.first }, popupWidth = anchor.width, selectedIndex = formatSpinner.selectedItemPosition) { index ->
    formatSpinner.setSelection(index)
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
        content.setBackgroundColor(if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt())
        rootLayout.setBackgroundColor(if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt())
        fun sectionLabel(text: String) = TextView(this).apply {
            this.text = text; textSize = 12f; letterSpacing = 0.055f; includeFontPadding = false
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); setTextColor(secondaryText())
            setPadding(dp(8), dp(8), dp(8), dp(6))
        }
        fun input(hint: String, value: String) = EditText(this).apply {
            // 颜色值属于短格式内容，置中后更容易快速核对 #RRGGBB 的完整性。
            this.hint = hint; setText(value); setSingleLine(true); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL); includeFontPadding = false; textSize = 16f; setTextColor(primaryText()); setHintTextColor(secondaryText())
            setBackgroundResource(R.drawable.bg_input); setPadding(dp(10), 0, dp(10), 0)
        }
        fun sliderRow(title: String, seekBar: SeekBar, valueText: (Int) -> String): LinearLayout {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
            val titleView = TextView(this).apply { text = title; textSize = 16f; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false; setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); setTextColor(primaryText()) }
            val value = TextView(this).apply { text = valueText(seekBar.progress); textSize = 14f; setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); letterSpacing = -0.01f; gravity = Gravity.CENTER_VERTICAL or Gravity.END; includeFontPadding = false; setSingleLine(true); setTextColor(if (isDark()) 0xffa9c4ff.toInt() else 0xff2864d7.toInt()) }
            row.addView(titleView, LinearLayout.LayoutParams(dp(88), dp(48)))
            row.addView(seekBar, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(8), 0) })
            row.addView(value, LinearLayout.LayoutParams(dp(60), dp(48)))
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { value.text = valueText(progress); if (fromUser) (bar.tag as? ((Int) -> Unit))?.invoke(progress) }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
            return row
        }

         val appearance = Spinner(this).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("跟随系统", "浅色", "深色")); setSelection(listOf("system", "light", "dark").indexOf(draft.colorScheme).coerceAtLeast(0)); gravity = Gravity.CENTER; setBackgroundResource(R.drawable.bg_input) }
         val appearanceValue = TextView(activity).apply { text = listOf("跟随系统", "浅色", "深色")[appearance.selectedItemPosition]; gravity = Gravity.CENTER; setTextColor(primaryText()); setBackgroundResource(R.drawable.bg_input); setOnClickListener { showMaterialDropdown(this, listOf("跟随系统", "浅色", "深色"), selectedIndex = appearance.selectedItemPosition) { appearance.setSelection(it) } } }
        val barColor = input("例如 #000000", String.format("#%06X", 0xFFFFFF and draft.barColor))
        val bgColor = input("例如 #FFFFFF", String.format("#%06X", 0xFFFFFF and draft.bgColor))
        val showText = Switch(this).apply { text = ""; isChecked = draft.showText; setTextColor(primaryText()); gravity = Gravity.CENTER_VERTICAL }
        val showFormat = Switch(this).apply { text = ""; isChecked = draft.showFormat; setTextColor(primaryText()); gravity = Gravity.CENTER_VERTICAL }
         val position = Spinner(this).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("上方", "下方")); setSelection(if (draft.textPosition == "top") 0 else 1) }
         val positionValue = TextView(activity).apply { text = if (draft.textPosition == "top") "上方" else "下方"; gravity = Gravity.CENTER; setTextColor(primaryText()); setBackgroundResource(R.drawable.bg_input); setOnClickListener { showMaterialDropdown(this, listOf("上方", "下方"), selectedIndex = position.selectedItemPosition) { position.setSelection(it) } } }
        val textSizeSeekBar = SeekBar(this).apply { max = 14; progress = (draft.textSize.roundToInt() - 10).coerceIn(0, 14) }
        val barHeight = SeekBar(this).apply { max = 120; progress = (draft.barHeight - 30).coerceIn(0, 120) }
        val barWidth = SeekBar(this).apply { max = 240; progress = (draft.barWidth.roundToInt() - 120).coerceIn(0, 240) }
        val margin = SeekBar(this).apply { max = 40; progress = draft.margin.coerceIn(0, 40) }

        fun persistSettings() {
            draft.barColor = parseColor(barColor.text.toString(), draft.barColor)
            draft.bgColor = parseColor(bgColor.text.toString(), draft.bgColor)
            draft.colorScheme = listOf("system", "light", "dark")[appearance.selectedItemPosition]
            draft.showText = showText.isChecked; draft.showFormat = showFormat.isChecked
            draft.textPosition = if (position.selectedItemPosition == 0) "top" else "bottom"
            draft.textSize = (10 + textSizeSeekBar.progress).toFloat(); draft.barHeight = 30 + barHeight.progress
            draft.barWidth = (120 + barWidth.progress).toFloat(); draft.margin = margin.progress
            style.barColor = draft.barColor; style.bgColor = draft.bgColor; style.colorScheme = draft.colorScheme
            style.showText = draft.showText; style.showFormat = draft.showFormat; style.textPosition = draft.textPosition
            style.textSize = draft.textSize; style.barHeight = draft.barHeight; style.barWidth = draft.barWidth
            style.margin = draft.margin
            saveStyle()
            applyAppearance()
        }
        val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = persistSettings()
        override fun afterTextChanged(s: Editable?) = Unit
        }
        barColor.addTextChangedListener(watcher); bgColor.addTextChangedListener(watcher)
        var suppressAppearanceCallback = true
        appearance.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selectedScheme = listOf("system", "light", "dark")[pos]
                if (!suppressAppearanceCallback) {
                    persistSettings()
                }
            }
        }
        // Spinner 绑定监听器后可能异步触发一次初始回调；必须先放行初始化回调，避免刚进入设置页就 recreate。
        appearance.post { suppressAppearanceCallback = false }
        position.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) = persistSettings()
        }
        showText.setOnCheckedChangeListener { _, _ -> persistSettings() }
        showFormat.setOnCheckedChangeListener { _, _ -> persistSettings() }
        listOf(textSizeSeekBar, barHeight, barWidth, margin).forEach { seekBar ->
            seekBar.tag = { _: Int -> persistSettings() }
        }
        fun groupCard(rows: List<View>): LinearLayout = contentCard().apply {
             orientation = LinearLayout.VERTICAL
             elevation = dp(2).toFloat()
             setPadding(dp(8), dp(4), dp(8), dp(4))
             rows.forEachIndexed { index, row ->
                 addView(row, LinearLayout.LayoutParams(-1, dp(48)))
                 if (index < rows.lastIndex) addView(View(activity).apply { setBackgroundColor(if (isDark()) 0x263b4658 else 0x1a667085) }, LinearLayout.LayoutParams(-1, dp(1)))
             }
         }
         fun textRow(title: String, trailing: View): LinearLayout = LinearLayout(this).apply {
             gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0)
             addView(TextView(activity).apply { text = title; this.textSize = 16f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; includeFontPadding = false; letterSpacing = -0.01f; setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); setTextColor(primaryText()) }, LinearLayout.LayoutParams(0, -1, 1f))
             addView(trailing, LinearLayout.LayoutParams(dp(132), dp(40)))
         }
        fun compactSliderRow(title: String, seekBar: SeekBar, valueText: (Int) -> String) = sliderRow(title, seekBar, valueText).apply { setPadding(0, 0, 0, 0) }
          addSpaced(sectionLabel("显示设置"), bottom = 2)
         addSpaced(groupCard(listOf(
            textRow("外观", appearanceValue), textRow("条码颜色", barColor), textRow("背景颜色", bgColor),
            textRow("显示文字", showText), textRow("显示条码格式", showFormat), textRow("文字位置", positionValue)
         )), bottom = 12)
          addSpaced(sectionLabel("条码尺寸"), bottom = 2)
         addSpaced(groupCard(listOf(
            compactSliderRow("文字大小", textSizeSeekBar) { "${10 + it} sp" }, compactSliderRow("条码高度", barHeight) { "${30 + it} dp" },
            compactSliderRow("条码宽度", barWidth) { "${120 + it} dp" }, compactSliderRow("条码间距", margin) { "$it dp" }
        )), bottom = 12)
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
             if (aboutTapCount == 4) toast("再点一次，打开彩蛋")
             if (aboutTapCount >= 5) {
                 aboutTapCount = 0
                showFireworksEasterEgg()
             }
         }
         addSpaced(about, bottom = 10)
    }


internal fun MainActivity.parseColor(value: String, fallback: Int): Int = try {
        val normalized = value.trim().let { if (it.startsWith("#")) it else "#$it" }
        Color.parseColor(normalized)
    } catch (_: Exception) { fallback }


internal fun MainActivity.applyAppearance() {
        val activity = this
        val mode = when (style.colorScheme) { "dark" -> AppCompatDelegate.MODE_NIGHT_YES; "light" -> AppCompatDelegate.MODE_NIGHT_NO; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM }
        AppCompatDelegate.setDefaultNightMode(mode)
        val background = if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt()
        window.decorView.systemUiVisibility = if (isDark()) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.statusBarColor = background
        window.navigationBarColor = background
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
         runCatching { rootLayout.setBackgroundColor(background) }
    }


internal fun MainActivity.loadStyle(): StyleSettings = StyleSettings(
        barColor = settingsStore.get(SettingsStore.BAR_COLOR, Color.BLACK), bgColor = settingsStore.get(SettingsStore.BG_COLOR, Color.WHITE),
        showText = settingsStore.get(SettingsStore.SHOW_TEXT, true), textPosition = settingsStore.get(SettingsStore.TEXT_POSITION, "bottom"),
        textSize = settingsStore.get(SettingsStore.TEXT_SIZE, 14f).coerceIn(10f, 24f), barHeight = settingsStore.get(SettingsStore.BAR_HEIGHT, 60).coerceIn(30, 150), barWidth = settingsStore.get(SettingsStore.BAR_WIDTH, 200f).coerceIn(120f, 360f),
        margin = settingsStore.get(SettingsStore.MARGIN, 6).coerceIn(0, 40), showFormat = settingsStore.get(SettingsStore.SHOW_FORMAT, true), colorScheme = settingsStore.get(SettingsStore.COLOR_SCHEME, "system")
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
            setBackgroundResource(R.drawable.bg_card)
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
             setOnClickListener {
                 if (inputRows.size >= 100) { toast("最多保留 100 行输入框"); return@setOnClickListener }
                 addInputRow(focus = true)
                 inputScroll?.post { inputScroll?.fullScroll(View.FOCUS_DOWN) }
                 toast("已新增第 ${inputRows.size} 行输入框")
             }
         }).apply { setBackgroundDrawable(glassButtonBackground()) }
         actionRow.addView(addButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, 0, dp(6), 0) })
         val cameraAction = LinearLayout(this).apply {
             gravity = Gravity.CENTER
             setBackgroundResource(R.drawable.bg_button)
             setPadding(dp(10), 0, dp(10), 0)
             isClickable = true; isFocusable = true
             setOnClickListener { captureText() }
             addView(ImageView(activity).apply { setImageResource(R.drawable.ic_camera); contentDescription = "拍照取字" }, LinearLayout.LayoutParams(dp(22), dp(22)))
             addView(TextView(activity).apply { text = "拍照取字"; textSize = 14f; gravity = Gravity.CENTER_VERTICAL; setTextColor(if (isDark()) 0xffd7e3f5.toInt() else 0xff2453a6.toInt()) }, LinearLayout.LayoutParams(-2, dp(48)).apply { setMargins(dp(4), 0, 0, 0) })
         }
         actionRow.addView(cameraAction, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(6), 0, 0, 0) })
         addSpaced(actionRow, bottom = 10)
         formatSpinner = Spinner(this).apply { adapter = formatSpinnerAdapter(); setBackgroundResource(R.drawable.bg_input); setPadding(dp(8), 0, dp(8), 0) }
          formatSpinner.setSelection(formats.indexOfFirst { it.first == (pendingGenerateFormat ?: "Code 128-B") }.coerceAtLeast(0))
          pendingGenerateFormat = null
          val formatCard = LinearLayout(this).apply {
              orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
              setPadding(dp(14), dp(4), dp(14), dp(4)); setBackgroundResource(R.drawable.bg_card)
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

internal fun MainActivity.addInputRow(value: String = "", focus: Boolean = false) {
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
        inputRows.add(edit)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                inputDraft = inputRows.map { it.text.toString() }.toMutableList()
                updateBatchGenerateButton()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        inputDraft = inputRows.map { it.text.toString() }.toMutableList()
        container.addView(row, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, 0, 0, dp(6)) })
        container.requestLayout()
        updateInputScrollHeight()
        updateBatchGenerateButton()
        refreshInputActions()
        if (focus) edit.post { edit.requestFocus(); edit.setSelection(edit.text.length) }
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
    showIos26Dialog(dialog)
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
        val editingFavorite = selectedFavoriteGroup
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
        val resultBackground = if (isDark()) appBackground() else style.bgColor
        content.setBackgroundColor(resultBackground)
        rootLayout.setBackgroundColor(resultBackground)
        window.statusBarColor = if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt()
        window.navigationBarColor = if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt()
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
                    val textEnabled = activity.style.showText
                    val label = if (activity.style.showFormat) "${item.text} · ${item.format}" else item.text
                    // encode() 的 Bitmap 可能还包含文字。这里只取纯条码区域，文字交给独立 TextView，
                    // 从根上避免文字和条码共享同一个 Canvas 而发生重叠。
                    val sourceTop = if (activity.style.textPosition == "top" && textEnabled) {
                        (barcode.height - barHeightPx).coerceAtLeast(0)
                    } else 0
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
                    if (textEnabled && activity.style.textPosition == "top") {
                        itemBox.addView(labelView, LinearLayout.LayoutParams(-1, -2))
                    }
                    itemBox.addView(ImageView(activity).apply {
                        setImageBitmap(barOnly)
                        scaleType = ImageView.ScaleType.FIT_XY
                        setPadding(0, 0, 0, 0)
                        contentDescription = "${item.format} 条码"
                    }, LinearLayout.LayoutParams(desiredWidth, barHeightPx).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    })
                    if (textEnabled && activity.style.textPosition != "top") {
                        itemBox.addView(labelView, LinearLayout.LayoutParams(-1, -2))
                    }
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
        setPadding(dp(8), dp(4), dp(4), dp(4))
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

    row.addView(TextView(this).apply {
        text = "${orderedBatch.size}个条码"
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(primaryText())
    }, LinearLayout.LayoutParams(0, dp(44), 1f))

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
    row.addView(right, LinearLayout.LayoutParams(dp(100), dp(44)))
    addSpaced(row, bottom = 10)
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
        content.setBackgroundColor(if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt())
        rootLayout.setBackgroundColor(if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt())
        window.statusBarColor = if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt()
        window.navigationBarColor = if (isDark()) 0xff10131b.toInt() else 0xfff4f6fb.toInt()

        val transferPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            val exportSlot = FrameLayout(activity).apply {
                addView(styleButton(Button(activity).apply { text = "导出收藏"; textSize = 15f; minWidth = 0; minimumWidth = 0; setPadding(dp(10), 0, dp(10), 0); setOnClickListener { createFavoritesExport() } }).apply { setBackgroundDrawable(glassButtonBackground()) }, FrameLayout.LayoutParams(-2, dp(46), Gravity.CENTER))
            }
            val importSlot = FrameLayout(activity).apply {
                addView(styleButton(Button(activity).apply { text = "导入收藏"; textSize = 15f; minWidth = 0; minimumWidth = 0; setPadding(dp(10), 0, dp(10), 0); setOnClickListener { restoreFavoritesImport() } }).apply { setBackgroundDrawable(glassButtonBackground()) }, FrameLayout.LayoutParams(-2, dp(46), Gravity.CENTER))
            }
            addView(importSlot, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(exportSlot, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        addSpaced(transferPanel, bottom = 12)

        search = EditText(this).apply {
            hint = "⌕  搜索名称、文件夹或内容"; textSize = 17f; setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
            setBackgroundResource(R.drawable.bg_input); setPadding(dp(16), dp(2), dp(16), dp(2))
        }
        addSpaced(search, bottom = 16)
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
            addTreeHeader(tree, path.substringAfterLast('/'), path, groups.size + matchingDescendants.size, collapsed, if (level == 0) rootFolderColor else childFolderColor, level, query)
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
        // 只有一级文件夹属于功能玻璃层；二级文件夹退回轻量的树状内容行，避免每一层都像按钮。
        setPadding(dp(if (isRoot) 11 else 26), 0, dp(5), 0)
        if (isRoot) {
            setBackgroundDrawable(glassButtonBackground())
            elevation = dp(1).toFloat()
        } else {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
        }
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
    header.addView(TextView(this).apply {
        text = "⋯"; textSize = 21f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER; setTextColor(secondaryText()); setPadding(0, 0, 0, dp(4)); isClickable = true; isFocusable = true
        if (isRoot) setBackgroundDrawable(glassButtonBackground())
        setOnClickListener { showTreeFolderMenu(this, folder, level) }
    }, LinearLayout.LayoutParams(dp(if (isRoot) 38 else 34), dp(if (isRoot) 38 else 34)).apply { setMargins(dp(2), 0, 0, 0) })
    container.addView(header, LinearLayout.LayoutParams(-1, dp(if (isRoot) 50 else 43)).apply { setMargins(dp(if (isRoot) 0 else 10), 0, dp(if (isRoot) 0 else 4), dp(if (isRoot) 7 else 1)) })
}

private fun MainActivity.showTreeFolderMenu(anchor: View, folder: String, level: Int) {
    val actions = if (level == 0) listOf("新增文件夹", "重命名", "删除") else listOf("重命名", "删除")
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
    val row = contentCard().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(if (level == 1) 20 else 28), dp(2), dp(4), dp(2)); setOnClickListener { resultItems = groupItems; showingHistoryResult = false; resultsReturnPage = "favorites"; selectedFavoriteGroup = group; page = "results"; render() } }
    // 收藏文件名固定为绿色，与两级文件夹形成稳定的三级视觉关系。
    row.addView(TextView(this).apply { text = group.name; textSize = 17f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = -0.01f; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false; setTextColor(color) }, LinearLayout.LayoutParams(0, dp(44), 1f))
    row.addView(TextView(this).apply { text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(group.savedAt)); textSize = 11f; gravity = Gravity.CENTER_VERTICAL; setTextColor(secondaryText()) }, LinearLayout.LayoutParams(dp(78), dp(44)))
    row.addView(styleButton(Button(this).apply { text = "⋯"; textSize = 20f; setTextColor(secondaryText()); setPadding(0, 0, 0, 0); setOnClickListener { showFavoriteFileMenu(this, group, groupItems) } }).apply { setBackgroundDrawable(glassButtonBackground()) }, LinearLayout.LayoutParams(dp(42), dp(42)))
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
        }.create().also { showIos26Dialog(it) }
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
    val overlay = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        contentDescription = "烟花彩蛋"
    }
    val fireworks = InlineFireworksView(this)
    overlay.addView(fireworks, FrameLayout.LayoutParams(-1, -1))
    val close = TextView(this).apply {
        text = "×"; textSize = 30f; gravity = Gravity.CENTER; includeFontPadding = false
        setTextColor(0xffedf6ff.toInt()); background = glassButtonBackground()
        setOnClickListener { host.removeView(overlay) }
        contentDescription = "关闭烟花"
    }
    overlay.addView(close, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply { topMargin = dp(18); rightMargin = dp(18) })
    host.addView(overlay, ViewGroup.LayoutParams(-1, -1))
}

