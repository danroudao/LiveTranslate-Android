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

        /** CaptureService 翻译完成时调用 */
        fun updateSubtitle(text: String) {
            instance?.postText(text)
        }

        /** 显示/重新显示字幕条 */
        fun showSubtitleBar() {
            instance?.showBar()
        }

        /** 隐藏字幕条（服务保持连接） */
        fun hideSubtitleBar() {
            instance?.hideBar()
        }
    }

    private var textView: TextView? = null
    private var containerView: LinearLayout? = null
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
        // 液态玻璃字幕条：深蓝灰半透明（透明度随样式）+ 顶部光泽 + 高光描边
        val radius = dp(style.cornerRadius).toFloat()
        val base = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.argb(style.alpha, 16, 18, 28))
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x3DFFFFFF, 0x14FFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = radius
        }
        val ring = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0x00000000)
            setStroke(dp(1), 0x73FFFFFF.toInt())
        }
        c.background = android.graphics.drawable.LayerDrawable(arrayOf(base, sheen, ring))
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
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(80)  // 底部安全区上方（边缘留白）
        }
        // 液态玻璃：窗口级背景模糊（API 31+）
        com.example.livetranslate.ui.LiquidGlass.blurWindow(params, 24, this)
        try {
            wm.addView(container, params)
            containerView = container
            textView = tv
            this.params = params
            applyStyle()
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            // 液态玻璃菜单面板
            background = com.example.livetranslate.ui.LiquidGlass.panel(this@SubtitleAccessibilityService, 18,
                com.example.livetranslate.ui.ThemeManager.current.cardBase,
                if (com.example.livetranslate.ui.ThemeManager.current.name == "light") 0 else 0x33,
                com.example.livetranslate.ui.ThemeManager.current.cardEdge)
        }

        // ── 预设模板行 ──
        val presets = listOf(
            "高清" to SubtitleStyle(alpha = 255, fontSize = 0f, bold = true, cornerRadius = 14),
            "夜览" to SubtitleStyle(alpha = 180, fontSize = 0f, bold = false, cornerRadius = 20),
            "极简" to SubtitleStyle(alpha = 120, fontSize = 0f, bold = false, cornerRadius = 8),
        )
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val presetBtns = mutableListOf<TextView>()
        for ((i, p) in presets.withIndex()) {
            val btn = presetPill(p.first)
            btn.setOnClickListener {
                style = p.second
                store.subtitleStyle = style
                applyStyle()
                presetBtns.forEachIndexed { j, b -> b.alpha = if (j == i) 1f else 0.55f }
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

        // ── 背景透明度 ──
        content.addView(rowLabel("背景透明度"))
        content.addView(UIKit.iosSeekBar(this, 255, style.alpha) { p ->
            style = style.copy(alpha = p)
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
        val sizeVal = TextView(this).apply {
            text = "20sp"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val plusBtn = UIKit.pillButton(this, "+", matchWidth = true)
        fun adjust(delta: Float) {
            var size = (if (style.fontSize > 0) style.fontSize else 20f) + delta
            size = size.coerceIn(10f, 60f)
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

        val popup = PopupWindow(content, dp(290), WindowManager.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        try {
            // 锚定字幕条上方弹出（不占屏幕中心）
            popup.showAsDropDown(anchor, 0, -popup.contentView.height - dp(8))
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
            val c = containerView ?: return@post
            c.animate()
                .alpha(0f)
                .translationY(dp(16).toFloat())
                .setDuration(IOSMotion.FAST_MS)
                .setInterpolator(IOSMotion.ACCELERATE)
                .withEndAction {
                    try {
                        c.let { wm.removeView(it) }
                    } catch (e: Exception) {
                    }
                    containerView = null
                    textView = null
                }
                .start()
        }
    }

    private fun postText(text: String) {
        handler.post {
            textView?.let {
                if (it.text?.toString() != text) {
                    IOSMotion.crossfadeText(it, text)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 纯字幕显示，无需处理事件
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        try {
            containerView?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        containerView = null
        textView = null
        super.onDestroy()
    }

}
