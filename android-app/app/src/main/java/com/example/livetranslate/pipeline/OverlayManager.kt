package com.example.livetranslate.pipeline

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮字幕窗 —— 对应 subtitle_overlay.py 的核心形态。
 * TYPE_APPLICATION_OVERLAY：可拖动、半透明黑底、原文（小灰字）+ 译文（大白字带描边）。
 */
class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: LinearLayout? = null
    private var originalView: TextView? = null
    private var translationView: TextView? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = 240  // 顶部下拉一段，避开状态栏
    }

    private var touchStartY = 0f
    private var startY = 0

    @Volatile
    private var original = ""
    @Volatile
    private var translation = ""
    @Volatile
    private var visible = false

    fun show() {
        if (view != null) return
        handler.post {
            val v = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(8))
                setBackgroundColor(Color.argb(200, 0, 0, 0))
            }
            // 顶部工具行：✕ 关闭按钮
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
            }
            val closeBtn = TextView(context).apply {
                text = "✕"
                textSize = 14f
                setTextColor(Color.argb(220, 255, 255, 255))
                setPadding(dp(10), dp(2), dp(4), dp(2))
                setOnClickListener { hide() }
            }
            topRow.addView(closeBtn)
            v.addView(topRow)

            val original = TextView(context).apply {
                textSize = 13f
                setTextColor(Color.argb(220, 220, 220, 220))
                setShadowLayer(2f, 0f, 0f, Color.BLACK)
                visibility = View.GONE
            }
            val translation = TextView(context).apply {
                textSize = 22f
                setTextColor(Color.WHITE)
                setShadowLayer(3f, 0f, 0f, Color.BLACK)
            }
            v.addView(original)
            v.addView(translation)

            // 拖动
            v.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartY = event.rawY
                        startY = params.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.y = startY + (event.rawY - touchStartY).toInt()
                        wm.updateViewLayout(v, params)
                        true
                    }
                    else -> false
                }
            }

            try {
                wm.addView(v, params)
                view = v
                originalView = original
                translationView = translation
                visible = true
                refresh()
            } catch (e: Exception) {
                view = null
            }
        }
    }

    fun hide() {
        handler.post {
            view?.let { wm.removeView(it) }
            view = null
            originalView = null
            translationView = null
            visible = false
        }
    }

    fun update(originalText: String, translationText: String, showOriginal: Boolean = true) {
        original = originalText
        translation = translationText
        refresh()
    }

    private fun refresh() {
        if (view == null) return
        handler.post {
            val ov = originalView ?: return@post
            val tv = translationView ?: return@post
            if (original.isEmpty()) {
                ov.visibility = View.GONE
            } else {
                ov.visibility = View.VISIBLE
                ov.text = original
            }
            tv.text = translation.ifEmpty { "…" }
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
