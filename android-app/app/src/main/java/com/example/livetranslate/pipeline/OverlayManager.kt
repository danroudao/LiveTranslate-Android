package com.example.livetranslate.pipeline

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import com.example.livetranslate.model.SettingsStore
import com.example.livetranslate.model.SubtitleStyle
import com.example.livetranslate.ui.IOSMotion
import com.example.livetranslate.ui.UIKit

/**
 * 悬浮字幕窗 —— iOS 风格现代化版本。
 *
 * 保留既有交互约定（重要）：
 *  - 菜单触发严格限定在 ⚙ 图标（内容区点击不弹菜单，避免误触 ✕）
 *  - ✕ 关闭 / 顶部拖动 / ⤡ 调尺寸
 *  - 样式调整实时生效 + 立即持久化
 *
 * 新增 iOS 动效：
 *  - 显示：淡入 + 顶部下滑 300ms（decelerate）
 *  - 隐藏：淡出 + 上滑 180ms（accelerate）
 *  - 译文更新：交叉淡化 + 上滑 6dp
 *  - 拖动：半透明跟随，松手边界回弹（spring）
 *  - 样式菜单：毛玻璃卡片 + 弹簧 pop 弹出 + 预设模板（高清/夜览/极简）
 */
class OverlayManager(private val context: Context, private val store: SettingsStore) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: android.widget.FrameLayout? = null
    private var originalView: TextView? = null
    private var translationView: TextView? = null
    private var resizeHandle: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    // 全透明模式：chrome（⚙/✕ 工具行 + ⤡ 手柄）自动隐藏状态
    private var topRowView: View? = null
    private var chromeHidden = false
    private val chromeHideRunnable = Runnable { hideChrome() }

    // 屏幕尺寸（自适应基准）
    private val screenW = context.resources.displayMetrics.widthPixels
    private val screenH = context.resources.displayMetrics.heightPixels
    private val screenWdp = screenW / context.resources.displayMetrics.density

    // 边缘留白（dp）
    private val edgeMargin = 12

    private var style = store.subtitleStyle

    @Volatile private var original = ""
    @Volatile private var translation = ""

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun effectiveFontSize(): Float =
        if (style.fontSize > 0) style.fontSize else SubtitleStyle.autoFontSize(screenWdp)

    private fun applyFont(tv: TextView, tag: String) {
        val tf = when (style.fontFamily) {
            "serif" -> Typeface.SERIF
            "monospace" -> Typeface.MONOSPACE
            "cursive" -> Typeface.create("cursive", Typeface.NORMAL)
            "sans-serif-medium" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        tv.typeface = if (style.bold) Typeface.create(tf, Typeface.BOLD) else tf
        tv.textSize = effectiveFontSize()
        // 背景越透明文字越需要阴影：全透明加重、半透明常规、不透明关闭
        when {
            style.transparent -> tv.setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), 0xE6000000.toInt())
            style.needsTextShadow -> tv.setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0xB3000000.toInt())
            else -> tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        android.util.Log.i("OverlayStyle", "$tag textSize=${tv.textSize} typeface=${tv.typeface} alpha=${style.alpha}")
    }

    fun show() {
        if (view != null) return
        handler.post {
            val v = android.widget.FrameLayout(context).apply {
                isClickable = true
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(4), dp(10), dp(24))  // 底部留白给 handle
            }
            // 顶部工具行：⚙ ✕
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            // ⚙ 菜单按钮（热区限定在图标区域，避免与 ✕ 冲突）
            val menuBtn = TextView(context).apply {
                text = "⚙"
                textSize = 16f
                setTextColor(Color.argb(220, 255, 255, 255))
                setPadding(dp(10), dp(10), dp(4), dp(10))
                setOnClickListener { showStyleMenu(v) }
            }
            val closeBtn = TextView(context).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Color.argb(220, 255, 255, 255))
                setPadding(dp(14), dp(10), dp(10), dp(10))
                setOnClickListener { hide() }
            }
            topRow.addView(menuBtn)
            topRow.addView(closeBtn)
            v.addView(topRow)

            // 头部区域拖动整个窗口（半透明跟随 + 松手回弹）
            topRow.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartY = event.rawY
                        dragStartLpY = params?.y ?: 0
                        v.animate().alpha(0.85f).setDuration(120).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val lp = params ?: return@setOnTouchListener true
                        lp.y = dragStartLpY + (event.rawY - dragStartY).toInt()
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (e: Exception) {
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        snapBack(v)
                        true
                    }
                    else -> true
                }
            }

            val original = TextView(context).apply {
                textSize = 13f
                setTextColor(Color.argb(220, 220, 220, 220))
                visibility = View.GONE
            }
            val translation = TextView(context).apply {
                textSize = 22f
                setTextColor(Color.WHITE)
            }
            content.addView(original)
            content.addView(translation)
            // 菜单触发限定在 ⚙ 图标（内容区点击不再弹菜单，避免误触）
            v.addView(content, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
            // 点击字幕条呼出 chrome（全透明模式下按钮自动隐藏后的唯一入口）
            v.setOnClickListener { revealChrome() }

            // resize 手柄：绝对定位右下角（窗口 resize 时始终跟随角落）
            val handle = TextView(context).apply {
                text = "⤡"
                textSize = 16f
                setTextColor(Color.argb(180, 255, 255, 255))
                gravity = Gravity.CENTER
            }
            val handleLp = android.widget.FrameLayout.LayoutParams(
                dp(32), dp(28),
                Gravity.BOTTOM or Gravity.END
            )
            v.addView(handle, handleLp)

            // resize 拖动手柄
            handle.setOnTouchListener { _, event ->
                handleResize(v, event)
                true
            }

            // 窗口参数：边缘留白 + 屏幕自适应初始尺寸
            val lp = WindowManager.LayoutParams(
                if (store.overlayWidthPx > 0) store.overlayWidthPx
                else screenW - dp(edgeMargin * 2),
                if (store.overlayHeightPx > 0) store.overlayHeightPx
                else WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(90)   // 屏幕顶部（状态栏下方），避免盖住主界面操作卡按钮
            }
            // 液态玻璃：窗口级背景模糊（API 31+，背后内容真实模糊）
            com.example.livetranslate.ui.LiquidGlass.blurWindow(lp, 26, context)
            try {
                wm.addView(v, lp)
                view = v
                params = lp
                originalView = original
                translationView = translation
                resizeHandle = handle
                topRowView = topRow
                applyStyle()
                refresh()
                // 全透明模式：初始显示 chrome，5s 后自动淡出（点击字幕条呼出）
                syncChromeForTransparency(initial = true)
                // iOS 进入动效：淡入 + 从顶部下滑
                v.alpha = 0f
                v.translationY = -dp(24).toFloat()
                v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(IOSMotion.BASE_MS)
                    .setInterpolator(IOSMotion.DECELERATE)
                    .start()
            } catch (e: Exception) {
                view = null
            }
        }
    }

    private fun applyStyle() {
        val v = view ?: return
        if (style.transparent) {
            // 全透明：背景层（底色/光泽/描边）整体移除，只留文字
            v.background = null
        } else {
            // 液态玻璃背景：深蓝灰半透明（透明度随样式）+ 顶部光泽 + 高光描边
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
            v.background = android.graphics.drawable.LayerDrawable(arrayOf(base, sheen, ring))
        }
        // 全透明时同步关闭窗口毛玻璃（否则透明背景仍透出模糊灰雾，同时省 GPU）
        params?.let {
            com.example.livetranslate.ui.LiquidGlass.updateWindowBlur(
                it, if (style.transparent) 0 else 26, wm, v)
        }
        originalView?.let { applyFont(it, "original") }
        translationView?.let { applyFont(it, "translation") }
        syncChromeForTransparency(initial = false)
    }

    // ---------- 全透明模式：chrome 自动隐藏 + 点击呼出 ----------
    // 关键约定：透明只作用于背景绘制，绝不改窗口 alpha / FLAG_NOT_TOUCHABLE，
    // 保证 ✕ 永远可点；chrome 隐藏时置 INVISIBLE（不响应触摸，防误触关闭），
    // 点击字幕条任意处呼出；通知栏另有"隐藏/显示字幕窗"兜底开关。

    /** chrome = ⚙/✕ 工具行 + ⤡ 缩放手柄（全透明模式下淡出，点击字幕条恢复） */
    private fun chromeViews(): List<View> = listOfNotNull(topRowView, resizeHandle)

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
        android.widget.Toast.makeText(context,
            "纯字幕模式：点击字幕条可呼出 ⚙/✕ 按钮",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------- 二级菜单（iOS 风格，锚定字幕条下方弹出） ----------

    private var styleMenu: PopupWindow? = null
    private var dismissing = false

    private fun showStyleMenu(anchor: View) {
        styleMenu?.dismiss()
        dismissing = false
        // 菜单打开期间 chrome 保持可见（滑杆实时预览时按钮不能被自动隐藏）
        handler.removeCallbacks(chromeHideRunnable)
        showChrome(animate = false)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            // 液态玻璃菜单面板：半透明深色 + 光泽 + 高光描边
            background = com.example.livetranslate.ui.LiquidGlass.panel(context, 18,
                com.example.livetranslate.ui.ThemeManager.current.menuBase,
                if (com.example.livetranslate.ui.ThemeManager.current.name == "light") 0
                else com.example.livetranslate.ui.ThemeManager.current.menuSheen,
                com.example.livetranslate.ui.ThemeManager.current.menuEdge)
        }

        // ── 预设模板行（高清 / 夜览 / 极简）──
        val presets = listOf(
            "高清" to SubtitleStyle(alpha = 255, fontSize = 0f, bold = true, cornerRadius = 14),
            "夜览" to SubtitleStyle(alpha = 180, fontSize = 0f, bold = false, cornerRadius = 20),
            "极简" to SubtitleStyle(alpha = 120, fontSize = 0f, bold = false, cornerRadius = 8),
            "透明" to SubtitleStyle(alpha = 0, fontSize = 0f, bold = false, cornerRadius = 8),
        )
        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val presetBtns = mutableListOf<TextView>()
        for ((i, p) in presets.withIndex()) {
            val btn = presetPill(p.first)
            btn.setOnClickListener {
                // 预设只改透明度/粗体/圆角，保留用户选择的字体族
                style = p.second.copy(fontFamily = style.fontFamily)
                store.subtitleStyle = style
                store.overlayWidthPx = 0
                store.overlayHeightPx = 0
                applyStyle()
                presetBtns.forEachIndexed { j, b -> b.alpha = if (j == i) 1f else 0.55f }
                handler.post { refreshLayout() }
                if (style.transparent) showTransparentHint()
            }
            presetBtns.add(btn)
            presetRow.addView(btn, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        content.addView(presetRow)

        fun rowLabel(s: String) = TextView(context).apply {
            text = s
            textSize = 12.5f
            setTextColor(UIKit.TEXT_SECONDARY)
            setPadding(dp(2), dp(12), dp(2), dp(2))
        }

        // ── 背景透明度（拉到 0 = 全透明，只保留文字） ──
        val alphaLabel = rowLabel(SubtitleStyle.alphaLabel(style.alpha))
        content.addView(alphaLabel)
        content.addView(UIKit.iosSeekBar(context, 255, style.alpha) { p ->
            val becameTransparent = style.alpha != 0 && p == 0
            style = style.copy(alpha = p)
            store.subtitleStyle = style
            alphaLabel.text = SubtitleStyle.alphaLabel(p)
            applyStyle()
            if (becameTransparent) showTransparentHint()
        })

        // ── 字号 ──
        content.addView(rowLabel("字号"))
        val sizeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minusBtn = UIKit.pillButton(context, "−", matchWidth = true)
        val sizeVal = TextView(context).apply {
            text = "${effectiveFontSize().toInt()}sp"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val plusBtn = UIKit.pillButton(context, "+", matchWidth = true)
        minusBtn.setOnClickListener { adjustFontSize(-2f, sizeVal) }
        plusBtn.setOnClickListener { adjustFontSize(2f, sizeVal) }
        sizeRow.addView(minusBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        sizeRow.addView(sizeVal, LinearLayout.LayoutParams(0, dp(36), 2f))
        sizeRow.addView(plusBtn, LinearLayout.LayoutParams(0, dp(36), 1f))
        content.addView(sizeRow)

        // ── 字体 ──
        content.addView(rowLabel("字体"))
        val fontSpinner = Spinner(context).apply {
            val t = com.example.livetranslate.ui.ThemeManager.current
            background = com.example.livetranslate.ui.LiquidGlass.panel(context, 9,
                base = com.example.livetranslate.ui.UIKit.withAlphaCompat(t.inputBg, 0xC0), sheenAlpha = if (t.name == "light") 0 else 0x0E,
                edgeColor = t.cardEdge)
        }
        fontSpinner.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_item,
            listOf("默认", "衬线 serif", "等宽 monospace", "楷体 cursive", "Medium")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        fontSpinner.setSelection(
            when (style.fontFamily) {
                "serif" -> 1; "monospace" -> 2; "cursive" -> 3; "sans-serif-medium" -> 4; else -> 0
            }
        )
        fontSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                style = style.copy(fontFamily = when (pos) {
                    1 -> "serif"; 2 -> "monospace"; 3 -> "cursive"; 4 -> "sans-serif-medium"; else -> "default"
                })
                store.subtitleStyle = style
                applyStyle()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
        content.addView(fontSpinner)

        // ── 粗体 + 圆角 ──
        val boldToggle = UIKit.pillButton(
            context, if (style.bold) "粗体: 开" else "粗体: 关", matchWidth = true
        )
        boldToggle.setOnClickListener {
            style = style.copy(bold = !style.bold)
            store.subtitleStyle = style
            boldToggle.text = if (style.bold) "粗体: 开" else "粗体: 关"
            applyStyle()
        }
        content.addView(boldToggle.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        })
        content.addView(rowLabel("圆角"))
        content.addView(UIKit.iosSeekBar(context, 48, style.cornerRadius) { p ->
            style = style.copy(cornerRadius = p)
            store.subtitleStyle = style
            applyStyle()
        })

        // ── 重置 + 完成 ──
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val resetBtn = UIKit.pillButton(context, "重置样式", matchWidth = true)
        resetBtn.setOnClickListener {
            style = SubtitleStyle()
            store.subtitleStyle = style
            store.overlayWidthPx = 0
            store.overlayHeightPx = 0
            applyStyle()
            presetBtns.forEach { it.alpha = 0.55f }
            dismissMenu()
            handler.post { refreshLayout() }
        }
        val closeMenuBtn = UIKit.iosButton(context, "完成", UIKit.ButtonStyle.PRIMARY, heightDp = 38, small = true)
        closeMenuBtn.setOnClickListener { dismissMenu() }
        actionRow.addView(resetBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
        actionRow.addView(closeMenuBtn, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            marginStart = dp(8)
        })
        content.addView(actionRow)

        // 锚定悬浮窗底部（而非 ⚙ 按钮下方，避免盖住悬浮窗原文/译文）
        val anchorTop = params?.y ?: 0
        val overlayBottom = anchorTop + (view?.height ?: anchor.height)
        val spaceBelow = screenH - overlayBottom - dp(16)
        val menuH = dp(460) // 菜单内容估算高度
        // 上方弹出时：菜单顶部对齐状态栏下方（不溢出屏幕）
        val targetTop = (anchorTop - menuH - dp(8)).coerceAtLeast(dp(44))
        val menuY = if (spaceBelow >= menuH) overlayBottom + dp(6) else targetTop
        val menuX = params?.x ?: 0
        // 菜单内容包 ScrollView：空间不足时内部滚动，窗口高度受限不遮挡悬浮窗
        val scrollContent = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        val popup = PopupWindow(scrollContent, dp(290), WindowManager.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        // 服务上下文无 Activity token：显式用 overlay 窗口类型
        popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        // 点击外部关闭菜单时恢复 chrome 计时（否则弹出状态残留、按钮不再自动隐藏）
        popup.setOnDismissListener {
            if (styleMenu === popup) {
                styleMenu = null
                dismissing = false
            }
            syncChromeForTransparency(initial = false)
        }
        // 高度上限 = 可用空间（菜单永不超出屏幕/不遮挡悬浮窗）
        val availH = if (spaceBelow >= menuH) spaceBelow else anchorTop - dp(44)
        val menuMaxH = (menuH + dp(24)).coerceAtMost(availH.coerceAtLeast(dp(240)))
        popup.height = menuMaxH
        android.util.Log.i("OverlayMenu", "anchorTop=$anchorTop overlayBottom=$overlayBottom " +
            "spaceBelow=$spaceBelow menuH=$menuH anchorH=${anchor.height} menuY=$menuY h=${popup.height}")
        try {
            // 手动定位：悬浮窗下方（空间不足则上方），绝不与悬浮窗重叠
            popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, menuX, menuY)
            styleMenu = popup
            // 液态玻璃：菜单窗口背景模糊（API 31+，延迟到布局稳定后应用）
            content.postDelayed({
                com.example.livetranslate.ui.LiquidGlass.blurPopup(popup, 24, context)
            }, 160)
            // iOS spring pop 弹出
            IOSMotion.popIn(content)
            android.util.Log.i("OverlayMenu", "popup shown asDropDown")
        } catch (e: Exception) {
            android.util.Log.w("OverlayMenu", "showAsDropDown failed: ${e.message}")
            // 无障碍等受限环境回退：屏幕底部弹出
            try {
                popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(12))
                styleMenu = popup
                content.postDelayed({
                    com.example.livetranslate.ui.LiquidGlass.blurPopup(popup, 24, context)
                }, 160)
                IOSMotion.popIn(content)
                android.util.Log.i("OverlayMenu", "popup shown atLocation")
            } catch (e2: Exception) {
                android.util.Log.e("OverlayMenu", "showAtLocation failed: ${e2.message}")
            }
        }
    }

    /** 预设胶囊按钮（主题玻璃胶囊） */
    private fun presetPill(text: String): TextView {
        val t = com.example.livetranslate.ui.ThemeManager.current
        val bg = com.example.livetranslate.ui.LiquidGlass.panel(context, 10,
            base = com.example.livetranslate.ui.UIKit.withAlphaCompat(t.inputBg, 0xB8), sheenAlpha = if (t.name == "light") 0 else 0x10,
            edgeColor = t.cardEdge)
        return TextView(context).apply {
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

    private fun adjustFontSize(delta: Float, label: TextView) {
        var size = effectiveFontSize() + delta
        size = size.coerceIn(10f, 60f)
        style = style.copy(fontSize = size)
        store.subtitleStyle = style
        label.text = "${size.toInt()}sp"
        applyStyle()
    }

    // ---------- resize（抓取右下角调整大小） ----------

    private var resizeStartW = 0
    private var resizeStartH = 0
    private var resizeStartX = 0f
    private var resizeStartY = 0f

    private fun handleResize(v: View, event: MotionEvent): Boolean {
        val lp = params ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartW = lp.width
                resizeStartH = if (lp.height > 0) lp.height else v.height
                resizeStartX = event.rawX
                resizeStartY = event.rawY
                android.util.Log.i("OverlayResize", "DOWN at raw=(${event.rawX},${event.rawY}) w=$resizeStartW h=$resizeStartH vh=${v.height}")
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - resizeStartX).toInt()
                val dy = (event.rawY - resizeStartY).toInt()
                val newW = (resizeStartW + dx).coerceIn(dp(140), screenW - dp(edgeMargin * 2))
                val newH = (resizeStartH + dy).coerceIn(dp(64), (screenH * 0.7f).toInt())
                lp.width = newW
                lp.height = newH
                store.overlayWidthPx = newW
                store.overlayHeightPx = newH
                try {
                    wm.updateViewLayout(v, lp)
                } catch (e: Exception) {
                }
            }
        }
        return true
    }

    /** 重置尺寸后重新布局（wrap 内容高度） */
    private fun refreshLayout() {
        val v = view ?: return
        val lp = params ?: return
        lp.width = screenW - dp(edgeMargin * 2)
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        try {
            wm.updateViewLayout(v, lp)
        } catch (e: Exception) {
        }
    }

    // ---------- 拖动整个窗口 + 边界回弹 ----------

    private var dragStartY = 0f
    private var dragStartLpY = 0

    /** 松手回弹：窗口回到可视边界内（spring 动画） */
    private fun snapBack(v: View) {
        val lp = params ?: return
        val maxY = screenH - dp(120)
        val targetY = lp.y.coerceIn(dp(40), maxY)
        v.animate().alpha(1f).setDuration(120).start()
        if (targetY == lp.y) return
        ValueAnimator.ofInt(lp.y, targetY).apply {
            duration = 320
            interpolator = IOSMotion.SPRING
            addUpdateListener {
                lp.y = it.animatedValue as Int
                try {
                    wm.updateViewLayout(v, lp)
                } catch (e: Exception) {
                }
            }
            start()
        }
    }

    /** 字幕窗当前是否可见（CaptureService 通知栏"隐藏/显示字幕窗"开关用） */
    val isVisible: Boolean get() = view != null

    fun hide() {
        handler.post {
            handler.removeCallbacks(chromeHideRunnable)
            styleMenu?.dismiss()
            styleMenu = null
            dismissing = false
            val v = view ?: return@post
            // 立即解除引用：与通知栏开关并发时避免重复 remove / 状态残留
            view = null
            originalView = null
            translationView = null
            resizeHandle = null
            topRowView = null
            params = null
            // iOS 退出动效：淡出 + 上滑
            v.animate()
                .alpha(0f)
                .translationY(-dp(16).toFloat())
                .setDuration(IOSMotion.FAST_MS)
                .setInterpolator(IOSMotion.ACCELERATE)
                .withEndAction { runCatching { wm.removeView(v) } }
                .start()
        }
    }

    fun update(originalText: String, translationText: String, showOriginal: Boolean = true) {
        original = originalText
        translation = translationText
        scheduleRefresh()
    }

    companion object {
        /** 流式字幕刷新节流：本地引擎逐 token 回调（~50ms/次），直接刷新会逐字闪烁，合并到 ~120ms */
        private const val THROTTLE_MS = 120L

        /** 全透明模式 chrome 自动隐藏：静止 4s 淡出；初始显示 5s（留操作窗口） */
        private const val CHROME_IDLE_MS = 4000L
        private const val CHROME_INITIAL_MS = 5000L
        private const val CHROME_FADE_MS = 180L
    }

    private var lastThrottleAt = 0L
    private var throttlePending = false

    /** 节流刷新：流式阶段合并高频更新，最终结果即时生效 */
    private fun scheduleRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastThrottleAt >= THROTTLE_MS) {
            lastThrottleAt = now
            refresh()
        } else if (!throttlePending) {
            throttlePending = true
            handler.postDelayed({
                throttlePending = false
                lastThrottleAt = System.currentTimeMillis()
                refresh()
            }, THROTTLE_MS)
        }
    }

    private fun refresh() {
        if (view == null) return
        handler.post {
            val ov = originalView ?: return@post
            val tv = translationView ?: return@post
            if (original.isEmpty()) {
                // 原文消失：淡出后 GONE
                if (ov.visibility == View.VISIBLE) {
                    ov.animate()
                        .alpha(0f)
                        .setDuration(IOSMotion.FAST_MS)
                        .withEndAction { ov.visibility = View.GONE }
                        .start()
                }
            } else {
                if (ov.visibility == View.GONE) {
                    ov.visibility = View.VISIBLE
                    ov.alpha = 0f
                    ov.text = original
                    ov.animate().alpha(1f).setDuration(IOSMotion.BASE_MS)
                        .setInterpolator(IOSMotion.DECELERATE).start()
                } else {
                    // 直接替换（无动画，避免频繁更新闪烁）
                    if (ov.text?.toString() != original) {
                        ov.text = original
                    }
                }
            }
            // 原文：直接替换（字幕原生行为，无动画避免闪烁）
            if (ov.text?.toString() != original) {
                ov.text = original
            }
            // 译文：直接替换（频繁更新时 fade 动画会闪烁）
            if (tv.text?.toString() != translation) {
                tv.text = translation.ifEmpty { "…" }
            }
            // 内容高度自适应：文本变多行时窗口高度自动扩展（避免 resize 固定高度裁剪译文底部）
            view?.post {
                val v = view ?: return@post
                val lp = params ?: return@post
                if (lp.height <= 0) return@post  // WRAP_CONTENT 已自适应
                v.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(lp.width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                )
                val wantH = v.measuredHeight
                if (wantH > lp.height) {
                    lp.height = wantH
                    try { wm.updateViewLayout(v, lp) } catch (e: Exception) {}
                }
            }
        }
    }
}
