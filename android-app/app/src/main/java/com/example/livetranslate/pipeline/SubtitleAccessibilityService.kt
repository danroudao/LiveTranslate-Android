package com.example.livetranslate.pipeline

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.example.livetranslate.model.SettingsStore
import com.example.livetranslate.model.SubtitleStyle
import com.example.livetranslate.ui.IOSMotion
import com.example.livetranslate.ui.UIKit

/**
 * 无障碍字幕条 —— 免悬浮窗权限路线（iOS 风格现代化版本）。
 *
 * TYPE_ACCESSIBILITY_OVERLAY（API 22+）：
 *  - 无需 SYSTEM_ALERT_WINDOW 权限，用户只需在系统设置中开启无障碍服务
 *  - 可在任意应用上方显示（包括全屏游戏）
 *  - API 33+ 限制：覆盖层高度 ≤ 屏幕 2/3（字幕条形态无影响）
 *
 * 交互约定不变：⚙ 弹菜单 / ✕ 关闭 / ⤡ 调尺寸；样式实时生效 + 立即持久化。
 */
class SubtitleAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: SubtitleAccessibilityService? = null

        /** 是否已连接 */
        val isActive: Boolean get() = instance != null

        /** 字幕条当前是否可见 */
        val isVisible: Boolean get() = instance?.textView != null

        /** CaptureService 翻译完成时调用（原文+译文分开传，字幕模式在服务内格式化） */
        fun updateSubtitle(original: String, translation: String) {
            instance?.postText(original, translation)
        }

        /** 显示/重新显示字幕条 */
        fun showSubtitleBar() {
            instance?.showBar()
        }

        /** 隐藏字幕条（服务保持连接） */
        fun hideSubtitleBar() {
            instance?.hideBar()
        }

        /** 全透明模式 chrome 自动隐藏：静止 4s 淡出；初始显示 5s（留操作窗口） */
        private const val CHROME_IDLE_MS = 4000L
        private const val CHROME_INITIAL_MS = 5000L
        private const val CHROME_FADE_MS = 180L
    }

    private var textView: TextView? = null
    private var containerView: LinearLayout? = null

    // 最近一次字幕：切换模式/样式后立即重渲染（无需等下一句）
    private var lastOriginal = ""
    private var lastTranslation = ""

    // 全透明模式：chrome（⚙/✕/⤡）自动隐藏状态
    private var menuBtnView: View? = null
    private var closeBtnView: View? = null
    private var resizeBtnView: View? = null
    private var chromeHidden = false
    private val chromeHideRunnable = Runnable { hideChrome() }
    private val handler = Handler(Looper.getMainLooper())
    private val wm: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }
    private val store by lazy { SettingsStore(this) }
    private var style: SubtitleStyle = store.subtitleStyle
    private var params: WindowManager.LayoutParams? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun applyStyle() {
        val tv = textView ?: return
        val c = containerView ?: return
        if (style.transparent) {
            // 全透明：背景层（底色/光泽/描边）整体移除，只留文字
            c.background = null
        } else {
            // 液态玻璃字幕条：深蓝灰半透明（透明度随样式）+ 顶部光泽 + 高光描边
            // 光泽/描边强度随 alpha 等比缩放：滑杆拉低时不会残留白边
            val radius = dp(style.cornerRadius).toFloat()
            fun scaled(a: Int): Int = a * style.alpha / 255
            val base = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.argb(style.alpha, 16, 18, 28))
            }
            val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(scaled(0x3D) shl 24, scaled(0x14) shl 24, 0x00000000)).apply {
                cornerRadius = radius
            }
            val ring = GradientDrawable().apply {
                cornerRadius = radius
                setColor(0x00000000)
                setStroke(dp(1), scaled(0x73) shl 24)
            }
            c.background = android.graphics.drawable.LayerDrawable(arrayOf(base, sheen, ring))
        }
        // 全透明时同步关闭窗口毛玻璃（否则透明背景仍透出模糊灰雾，同时省 GPU）
        params?.let {
            com.example.livetranslate.ui.LiquidGlass.updateWindowBlur(
                it, if (style.transparent) 0 else 24, wm, c)
        }
        val size = if (style.fontSize > 0) style.fontSize
                   else SubtitleStyle.autoFontSize(
                       resources.displayMetrics.widthPixels / resources.displayMetrics.density)
        val tf = when (style.fontFamily) {
            "serif" -> android.graphics.Typeface.SERIF
            "monospace" -> android.graphics.Typeface.MONOSPACE
            "cursive" -> android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL)
            "sans-serif-medium" -> android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            else -> android.graphics.Typeface.DEFAULT
        }
        tv.typeface = if (style.bold) android.graphics.Typeface.create(tf, android.graphics.Typeface.BOLD) else tf
        tv.textSize = size
        // 横屏：文本在可用槽位内居中（竖屏左对齐）；按钮占右侧，居中为槽位中心
        tv.gravity = if (resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels)
            Gravity.CENTER_HORIZONTAL else Gravity.START
        // 背景越透明文字越需要阴影：全透明加重、半透明常规、不透明关闭
        when {
            style.transparent -> tv.setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), 0xE6000000.toInt())
            style.needsTextShadow -> tv.setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xB3000000.toInt())
            else -> tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        // 模式/字号变化后立即重渲染当前字幕（无需等下一句）
        renderText()
        syncChromeForTransparency(initial = false)
    }

    /** 底部边距：竖屏 80dp 让开导航/手势区；横屏屏高小 → 48dp */
    private fun defaultBottomMargin(): Int =
        if (resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels) dp(48)
        else dp(80)

    // ---------- 全透明模式：chrome 自动隐藏 + 点击呼出 ----------
    // 关键约定：透明只作用于背景绘制，绝不改窗口 alpha / FLAG_NOT_TOUCHABLE，
    // 保证 ✕ 永远可点；chrome 隐藏时置 INVISIBLE（不响应触摸，防误触关闭），
    // 点击字幕条任意处呼出；主界面③按钮/通知栏开关可完底关闭。

    /** chrome = ⚙/✕/⤡（全透明模式下淡出，点击字幕条恢复） */
    private fun chromeViews(): List<View> = listOfNotNull(menuBtnView, closeBtnView, resizeBtnView)

    private fun syncChromeForTransparency(initial: Boolean) {
        if (!style.transparent) {
            handler.removeCallbacks(chromeHideRunnable)
            showChrome(animate = false)
            return
        }
        if (styleMenu != null) return  // 菜单打开期间保持按钮可见（滑杆还要继续拖）
        handler.removeCallbacks(chromeHideRunnable)
        if (initial) showChrome(animate = false)
        handler.postDelayed(chromeHideRunnable, if (initial) CHROME_INITIAL_MS else CHROME_IDLE_MS)
    }

    private fun showChrome(animate: Boolean = true) {
        chromeHidden = false
        for (cv in chromeViews()) {
            cv.animate().cancel()
            cv.visibility = View.VISIBLE
            if (animate) {
                if (cv.alpha < 1f) cv.alpha = 0f
                cv.animate().alpha(1f).setDuration(CHROME_FADE_MS).start()
            } else {
                cv.alpha = 1f
            }
        }
    }

    private fun hideChrome() {
        if (!style.transparent || styleMenu != null) return
        chromeHidden = true
        for (cv in chromeViews()) {
            cv.animate().alpha(0f).setDuration(CHROME_FADE_MS).withEndAction {
                if (chromeHidden && cv.alpha == 0f) cv.visibility = View.INVISIBLE
            }.start()
        }
    }

    /** 点击字幕条呼出 chrome —— 全透明模式下 ✕/⚙ 的唯一入口 */
    private fun revealChrome() {
        if (!style.transparent) return
        handler.removeCallbacks(chromeHideRunnable)
        showChrome()
        if (styleMenu == null) handler.postDelayed(chromeHideRunnable, CHROME_IDLE_MS)
    }

    private fun showTransparentHint() {
        android.widget.Toast.makeText(this,
            "纯字幕模式：点击字幕条可呼出 ⚙/✕ 按钮",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showBar()
    }

    /** 显示字幕条（iOS 进入动效：底部上滑 + 淡入） */
    fun showBar() {
        if (containerView != null) return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
            setPadding(dp(16), dp(10), dp(8), dp(10))
            text = "LiveTranslate 字幕条就绪"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val menuBtn = TextView(this).apply {
            text = "⚙"
            textSize = 16f
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(dp(10), dp(10), dp(4), dp(10))
            setOnClickListener { showStyleMenu(container) }
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(dp(14), dp(10), dp(12), dp(10))
            // 点击关闭字幕条（服务保持连接，可从主界面重新显示）
            setOnClickListener { hideBar() }
        }
        val resizeBtn = TextView(this).apply {
            text = "⤡"
            textSize = 16f
            setTextColor(Color.argb(180, 255, 255, 255))
            setPadding(dp(4), dp(10), dp(8), dp(10))
            // 抓取边缘调整高度
            setOnTouchListener { _, event ->
                handleResize(event)
                true
            }
        }
        container.addView(tv)
        container.addView(menuBtn)
        container.addView(closeBtn)
        container.addView(resizeBtn)
        // 点击字幕条呼出 chrome（全透明模式下按钮自动隐藏后的唯一入口）
        container.setOnClickListener { revealChrome() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = defaultBottomMargin()  // 底部安全区上方（竖屏 80dp / 横屏 48dp）
        }
        // 液态玻璃：窗口级背景模糊（API 31+）
        com.example.livetranslate.ui.LiquidGlass.blurWindow(params, 24, this)
        try {
            wm.addView(container, params)
            containerView = container
            textView = tv
            this.params = params
            applyStyle()
            menuBtnView = menuBtn
            closeBtnView = closeBtn
            resizeBtnView = resizeBtn
            // 全透明模式：初始显示 chrome，5s 后自动淡出（点击字幕条呼出）
            syncChromeForTransparency(initial = true)
            // iOS 进入动效：底部上滑 + 淡入
            container.alpha = 0f
            container.translationY = dp(24).toFloat()
            container.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(IOSMotion.BASE_MS)
                .setInterpolator(IOSMotion.DECELERATE)
                .start()
        } catch (e: Exception) {
            containerView = null
            textView = null
        }
    }

    /** 抓取 ⤡ 调整字幕条高度（限高 2/3 屏，无障碍覆盖层限制） */
    private var resizeStartH = 0
    private var resizeStartY = 0f

    private fun handleResize(event: MotionEvent) {
        val lp = params ?: return
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartH = if (lp.height > 0) lp.height else containerView?.height ?: 0
                resizeStartY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (event.rawY - resizeStartY).toInt()
                val maxH = (resources.displayMetrics.heightPixels * 0.66f).toInt()
                lp.height = (resizeStartH + dy).coerceIn(dp(56), maxH)
                try {
                    wm.updateViewLayout(containerView, lp)
                } catch (e: Exception) {
                }
            }
        }
    }

    // ---------- 二级菜单（iOS 风格，锚定字幕条上方） ----------

    private var styleMenu: PopupWindow? = null
    private var dismissing = false

    private fun showStyleMenu(anchor: View) {
        styleMenu?.dismiss()
        dismissing = false
        // 菜单打开期间 chrome 保持可见（滑杆实时预览时按钮不能被自动隐藏）
        handler.removeCallbacks(chromeHideRunnable)
        showChrome(animate = false)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            // 液态玻璃菜单面板
            background = com.example.livetranslate.ui.LiquidGlass.panel(this@SubtitleAccessibilityService, 18,
                com.example.livetranslate.ui.ThemeManager.current.menuBase,
                if (com.example.livetranslate.ui.ThemeManager.current.name == "light") 0
                else com.example.livetranslate.ui.ThemeManager.current.menuSheen,
                com.example.livetranslate.ui.ThemeManager.current.menuEdge)
        }

        // ── 预设模板行 ──
        val presets = listOf(
            "高清" to SubtitleStyle(alpha = 255, fontSize = 0f, bold = true, cornerRadius = 14),
            "夜览" to SubtitleStyle(alpha = 180, fontSize = 0f, bold = false, cornerRadius = 20),
            "极简" to SubtitleStyle(alpha = 120, fontSize = 0f, bold = false, cornerRadius = 8),
            "透明" to SubtitleStyle(alpha = 0, fontSize = 0f, bold = false, cornerRadius = 8),
        )
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val presetBtns = mutableListOf<TextView>()
        for ((i, p) in presets.withIndex()) {
            val btn = presetPill(p.first)
            btn.setOnClickListener {
                // 预设只改透明度/粗体/圆角，保留用户选择的字体族
                style = p.second.copy(fontFamily = style.fontFamily, showOriginal = style.showOriginal)
                store.subtitleStyle = style
                applyStyle()
                presetBtns.forEachIndexed { j, b -> b.alpha = if (j == i) 1f else 0.55f }
                if (style.transparent) showTransparentHint()
            }
            presetBtns.add(btn)
            presetRow.addView(btn, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        content.addView(presetRow)

        fun rowLabel(s: String) = TextView(this).apply {
            text = s; textSize = 12.5f; setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(2), dp(12), dp(2), dp(2))
        }

        // ── 背景透明度（拉到 0 = 全透明，只保留文字） ──
        val alphaLabel = rowLabel(SubtitleStyle.alphaLabel(style.alpha))
        content.addView(alphaLabel)
        content.addView(UIKit.iosSeekBar(this, 255, style.alpha) { p ->
            val becameTransparent = style.alpha != 0 && p == 0
            style = style.copy(alpha = p)
            store.subtitleStyle = style
            alphaLabel.text = SubtitleStyle.alphaLabel(p)
            applyStyle()
            if (becameTransparent) showTransparentHint()
        })

        // ── 字幕模式（仅译文 / 双语） ──
        content.addView(rowLabel("字幕模式"))
        content.addView(UIKit.segmentedControl(
            this, listOf("仅译文", "双语"), if (style.showOriginal) 1 else 0
        ) { pos ->
            style = style.copy(showOriginal = pos == 1)
            store.subtitleStyle = style
            applyStyle()
        })

        // ── 字号 ──
        content.addView(rowLabel("字号"))
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minusBtn = UIKit.pillButton(this, "−", matchWidth = true)
        val curFontSize = if (style.fontSize > 0) style.fontSize
            else SubtitleStyle.autoFontSize(resources.displayMetrics.widthPixels / resources.displayMetrics.density)
        val sizeVal = TextView(this).apply {
            text = "${curFontSize.toInt()}sp"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val plusBtn = UIKit.pillButton(this, "+", matchWidth = true)
        fun adjust(delta: Float) {
            val cur = if (style.fontSize > 0) style.fontSize
                else SubtitleStyle.autoFontSize(resources.displayMetrics.widthPixels / resources.displayMetrics.density)
            val size = (cur + delta).coerceIn(10f, 60f)
            style = style.copy(fontSize = size)
            store.subtitleStyle = style
            sizeVal.text = "${size.toInt()}sp"
            applyStyle()
        }
        minusBtn.setOnClickListener { adjust(-2f) }
        plusBtn.setOnClickListener { adjust(2f) }
        sizeRow.addView(minusBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        sizeRow.addView(sizeVal, LinearLayout.LayoutParams(0, dp(36), 2f))
        sizeRow.addView(plusBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        content.addView(sizeRow)

        // ── 粗体 ──
        val boldToggle = UIKit.pillButton(this, if (style.bold) "粗体: 开" else "粗体: 关", matchWidth = true)
        boldToggle.setOnClickListener {
            style = style.copy(bold = !style.bold)
            store.subtitleStyle = style
            boldToggle.text = if (style.bold) "粗体: 开" else "粗体: 关"
            applyStyle()
        }
        content.addView(boldToggle.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        })

        // ── 圆角 ──
        content.addView(rowLabel("圆角"))
        content.addView(UIKit.iosSeekBar(this, 48, style.cornerRadius) { p ->
            style = style.copy(cornerRadius = p)
            store.subtitleStyle = style
            applyStyle()
        })

        // ── 完成 ──
        val doneBtn = UIKit.iosButton(this, "完成", UIKit.ButtonStyle.PRIMARY, heightDp = 38, small = true)
        doneBtn.setOnClickListener { dismissMenu() }
        content.addView(doneBtn.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
        })

        // 菜单内容包 ScrollView：横屏屏高小/菜单超高时内部滚动（与悬浮窗菜单一致）
        val scrollContent = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        val popup = PopupWindow(scrollContent, dp(290), WindowManager.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        // 点击外部关闭菜单时恢复 chrome 计时（否则弹出状态残留、按钮不再自动隐藏）
        popup.setOnDismissListener {
            if (styleMenu === popup) {
                styleMenu = null
                dismissing = false
            }
            syncChromeForTransparency(initial = false)
        }
        try {
            // 先测量内容高度再定位：原实现用 content.height（恒为 0）算偏移，菜单会压住字幕条
            content.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(dp(290), android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            val contentH = content.measuredHeight
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            // 上方可用空间不足（横屏常见）→ 限高内部滚动，菜单顶部不越出屏幕
            val spaceAbove = (loc[1] - dp(12)).coerceAtLeast(dp(200))
            popup.height = (contentH + dp(24)).coerceAtMost(spaceAbove)
            popup.showAsDropDown(anchor, 0, -popup.height - dp(8))
            styleMenu = popup
            content.postDelayed({
                com.example.livetranslate.ui.LiquidGlass.blurPopup(popup, 24, this)
            }, 160)
            IOSMotion.popIn(content)
        } catch (e: Exception) {
            try {
                popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(12))
                styleMenu = popup
                content.postDelayed({
                    com.example.livetranslate.ui.LiquidGlass.blurPopup(popup, 24, this)
                }, 160)
                IOSMotion.popIn(content)
            } catch (e2: Exception) {
            }
        }
    }

    private fun presetPill(text: String): TextView {
        val t = com.example.livetranslate.ui.ThemeManager.current
        val bg = com.example.livetranslate.ui.LiquidGlass.panel(this, 10,
            base = com.example.livetranslate.ui.UIKit.withAlphaCompat(t.inputBg, 0xB8), sheenAlpha = if (t.name == "light") 0 else 0x10,
            edgeColor = t.cardEdge)
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTextColor(UIKit.TEXT)
            background = bg
        }
    }

    /** 收起菜单：pop 动画后 dismiss */
    private fun dismissMenu() {
        val popup = styleMenu ?: return
        if (dismissing) return
        dismissing = true
        val content = popup.contentView
        IOSMotion.popOut(content) {
            popup.dismiss()
            styleMenu = null
        }
    }

    /** 隐藏字幕条（iOS 退出动效） */
    fun hideBar() {
        handler.post {
            handler.removeCallbacks(chromeHideRunnable)
            val c = containerView ?: return@post
            // 立即解除引用：与主界面③按钮并发时避免重复 remove / 状态残留
            containerView = null
            textView = null
            menuBtnView = null
            closeBtnView = null
            resizeBtnView = null
            c.animate()
                .alpha(0f)
                .translationY(dp(16).toFloat())
                .setDuration(IOSMotion.FAST_MS)
                .setInterpolator(IOSMotion.ACCELERATE)
                .withEndAction {
                    runCatching { wm.removeView(c) }
                }
                .start()
        }
    }

    private fun postText(original: String, translation: String) {
        handler.post {
            lastOriginal = original
            lastTranslation = translation
            renderText()
        }
    }

    /** 按字幕模式渲染：仅译文 / 双语（译文未出时回退显示原文，避免空白） */
    private fun renderText() {
        val tv = textView ?: return
        if (lastOriginal.isEmpty() && lastTranslation.isEmpty()) return
        val text = when {
            !style.showOriginal -> lastTranslation.ifEmpty { lastOriginal }
            lastTranslation.isEmpty() -> lastOriginal
            else -> "$lastOriginal\n$lastTranslation"
        }
        if (tv.text?.toString() != text) {
            IOSMotion.crossfadeText(tv, text)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 纯字幕显示，无需处理事件
    }

    /** 横竖屏/尺寸变化：重排字幕条位置与高度上限，收起锚点失效的菜单 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post { adaptToScreen() }
    }

    private fun adaptToScreen() {
        val c = containerView ?: return
        val lp = params ?: return
        val dm = resources.displayMetrics
        val maxH = (dm.heightPixels * 0.66f).toInt().coerceAtLeast(dp(56))
        if (lp.height > 0) lp.height = lp.height.coerceIn(dp(56), maxH)
        lp.y = defaultBottomMargin()
        if (styleMenu != null) {
            styleMenu?.dismiss()
            styleMenu = null
        }
        runCatching { wm.updateViewLayout(c, lp) }
        applyStyle()
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(chromeHideRunnable)
        try { styleMenu?.dismiss() } catch (e: Exception) {}
        styleMenu = null
        try {
            containerView?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        containerView = null
        textView = null
        menuBtnView = null
        closeBtnView = null
        resizeBtnView = null
        super.onDestroy()
    }

}
