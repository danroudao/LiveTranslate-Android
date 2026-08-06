package com.example.livetranslate.pipeline

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView

/**
 * 无障碍字幕条 —— subtitle_overlay.py 的免悬浮窗权限路线。
 *
 * TYPE_ACCESSIBILITY_OVERLAY（API 22+）：
 *  - 无需 SYSTEM_ALERT_WINDOW 权限，用户只需在系统设置中开启无障碍服务
 *  - 可在任意应用上方显示（包括全屏游戏）
 *  - API 33+ 限制：覆盖层高度 ≤ 屏幕 2/3（字幕条形态无影响）
 *
 * 与 CaptureService 同进程，通过静态实例直接更新。
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
    private var containerView: android.widget.LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private val wm: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }
    private val store by lazy { com.example.livetranslate.model.SettingsStore(this) }
    private var style: com.example.livetranslate.model.SubtitleStyle = store.subtitleStyle
    private var params: WindowManager.LayoutParams? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun applyStyle() {
        val tv = textView ?: return
        val c = containerView ?: return
        val bg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(style.cornerRadius).toFloat()
            setColor(android.graphics.Color.argb(style.alpha, 0, 0, 0))
        }
        c.background = bg
        val size = if (style.fontSize > 0) style.fontSize
                   else com.example.livetranslate.model.SubtitleStyle.autoFontSize(
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

    /** 显示字幕条（含 ✕ 关闭按钮） */
    fun showBar() {
        if (containerView != null) return
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
            setPadding(dp(16), dp(10), dp(8), dp(10))
            text = "LiveTranslate 字幕条就绪"
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(dp(12), dp(10), dp(16), dp(10))
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
        container.addView(closeBtn)
        container.addView(resizeBtn)
        // 点击文本区 → 二级菜单（锚定字幕条上方弹出，不占屏幕中心）
        tv.setOnClickListener { showStyleMenu(container) }
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
        try {
            wm.addView(container, params)
            containerView = container
            textView = tv
            this.params = params
            applyStyle()
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

    /** 二级菜单（锚定字幕条上方） */
    private var styleMenu: PopupWindow? = null

    private fun showStyleMenu(anchor: View) {
        styleMenu?.dismiss()
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.argb(245, 30, 30, 35))
        }
        fun rowLabel(s: String) = TextView(this).apply {
            text = s; textSize = 13f; setTextColor(Color.WHITE)
        }
        // 透明度
        content.addView(rowLabel("背景透明度"))
        val alphaBar = SeekBar(this).apply { max = 255; progress = style.alpha }
        alphaBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                style = style.copy(alpha = p)
                store.subtitleStyle = style
                applyStyle()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        content.addView(alphaBar)
        // 字号
        content.addView(rowLabel("字号"))
        val sizeRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minusBtn = Button(this).apply { text = "−" }
        val sizeVal = TextView(this).apply {
            text = "20sp"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val plusBtn = Button(this).apply { text = "+" }
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
        sizeRow.addView(minusBtn)
        sizeRow.addView(sizeVal, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sizeRow.addView(plusBtn)
        content.addView(sizeRow)
        // 圆角
        content.addView(rowLabel("圆角"))
        val cornerBar = SeekBar(this).apply { max = 48; progress = style.cornerRadius }
        cornerBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                style = style.copy(cornerRadius = p)
                store.subtitleStyle = style
                applyStyle()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        content.addView(cornerBar)
        // 完成
        val doneBtn = Button(this).apply { text = "完成" }
        content.addView(doneBtn)
        doneBtn.setOnClickListener { styleMenu?.dismiss() }

        val popup = PopupWindow(content, dp(280), WindowManager.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        try {
            // 锚定字幕条上方弹出（不占屏幕中心）
            popup.showAsDropDown(anchor, 0, -popup.contentView.height - dp(8))
            styleMenu = popup
        } catch (e: Exception) {
            try {
                popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(12))
                styleMenu = popup
            } catch (e2: Exception) {
            }
        }
    }

    /** 隐藏字幕条 */
    fun hideBar() {
        handler.post {
            try {
                containerView?.let { wm.removeView(it) }
            } catch (e: Exception) {
            }
            containerView = null
            textView = null
        }
    }

    private fun postText(text: String) {
        handler.post {
            textView?.text = text
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
