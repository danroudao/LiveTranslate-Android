package com.example.livetranslate.pipeline

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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
            setBackgroundColor(Color.argb(210, 0, 0, 0))
        }
        val tv = TextView(this).apply {
            textSize = 20f
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
        container.addView(tv)
        container.addView(closeBtn)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(80)  // 底部安全区上方
        }
        try {
            wm.addView(container, params)
            containerView = container
            textView = tv
        } catch (e: Exception) {
            containerView = null
            textView = null
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
