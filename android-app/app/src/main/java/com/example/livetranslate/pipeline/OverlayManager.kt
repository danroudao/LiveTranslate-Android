package com.example.livetranslate.pipeline

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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import com.example.livetranslate.model.SettingsStore
import com.example.livetranslate.model.SubtitleStyle

/**
 * 悬浮字幕窗 —— 支持：
 *  - 圆角 + 半透明背景（透明度可调）
 *  - 点击字幕条弹出二级菜单（锚定字幕条下方，不占屏幕中心）：透明度/字号/字体/圆角
 *  - 右下角抓取边缘调整大小（resize 手柄）
 *  - 边缘留白 + 按屏幕分辨率自适应初始面积
 */
class OverlayManager(private val context: Context, private val store: SettingsStore) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: android.widget.FrameLayout? = null
    private var originalView: TextView? = null
    private var translationView: TextView? = null
    private var resizeHandle: TextView? = null
    private var params: WindowManager.LayoutParams? = null

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
        android.util.Log.i("OverlayStyle", "$tag textSize=${tv.textSize} typeface=${tv.typeface}")
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
            // 顶部工具行：✕ 关闭
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            val closeBtn = TextView(context).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Color.argb(220, 255, 255, 255))
                // 大点击热区（右上角容易点中）
                setPadding(dp(20), dp(10), dp(12), dp(10))
                setOnClickListener { hide() }
            }
            topRow.addView(closeBtn)
            v.addView(topRow)

            // 头部区域拖动整个窗口
            topRow.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartY = event.rawY
                        dragStartLpY = params?.y ?: 0
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
                    else -> false
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
            // 点击内容区 → 二级菜单（topRow 拖动、handle resize 互不干扰）
            content.setOnClickListener { showStyleMenu(v) }
            v.addView(content, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))

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

            // 点击字幕条（非按钮区域）→ 二级菜单（锚定下方弹出，不占屏幕中心）
            v.setOnClickListener { showStyleMenu(v) }

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
                y = dp(240)
            }
            try {
                wm.addView(v, lp)
                view = v
                params = lp
                originalView = original
                translationView = translation
                resizeHandle = handle
                applyStyle()
                refresh()
            } catch (e: Exception) {
                view = null
            }
        }
    }

    private fun applyStyle() {
        val v = view ?: return
        // 圆角 + 透明度背景
        val bg = GradientDrawable().apply {
            cornerRadius = dp(style.cornerRadius).toFloat()
            setColor(Color.argb(style.alpha, 0, 0, 0))
        }
        v.background = bg
        originalView?.let { applyFont(it, "original") }
        translationView?.let { applyFont(it, "translation") }
    }

    // ---------- 二级菜单（锚定字幕条下方弹出） ----------

    private var styleMenu: PopupWindow? = null

    private fun showStyleMenu(anchor: View) {
        styleMenu?.dismiss()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.argb(245, 30, 30, 35))
        }

        fun rowLabel(s: String) = TextView(context).apply {
            text = s
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        fun rowLayout() = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 透明度
        content.addView(rowLabel("背景透明度"))
        val alphaBar = SeekBar(context).apply { max = 255; progress = style.alpha }
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
        val sizeRow = rowLayout()
        val minusBtn = Button(context).apply { text = "−" }
        val sizeVal = TextView(context).apply {
            text = "${effectiveFontSize().toInt()}sp"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val plusBtn = Button(context).apply { text = "+" }
        minusBtn.setOnClickListener { adjustFontSize(-2f, sizeVal) }
        plusBtn.setOnClickListener { adjustFontSize(2f, sizeVal) }
        sizeRow.addView(minusBtn)
        sizeRow.addView(sizeVal, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sizeRow.addView(plusBtn)
        content.addView(sizeRow)

        // 字体
        content.addView(rowLabel("字体"))
        val fontSpinner = Spinner(context)
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

        // 粗体
        val boldRow = rowLayout()
        val boldToggle = Button(context).apply {
            text = if (style.bold) "粗体: 开" else "粗体: 关"
            setOnClickListener {
                style = style.copy(bold = !style.bold)
                store.subtitleStyle = style
                text = if (style.bold) "粗体: 开" else "粗体: 关"
                applyStyle()
            }
        }
        boldRow.addView(boldToggle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(boldRow)

        // 圆角
        content.addView(rowLabel("圆角"))
        val cornerBar = SeekBar(context).apply { max = 48; progress = style.cornerRadius }
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

        // 重置 + 关闭
        val actionRow = rowLayout()
        val resetBtn = Button(context).apply {
            text = "重置样式"
            setOnClickListener {
                style = SubtitleStyle()
                store.subtitleStyle = style
                store.overlayWidthPx = 0
                store.overlayHeightPx = 0
                applyStyle()
                styleMenu?.dismiss()
                handler.post { refreshLayout() }
            }
        }
        val closeMenuBtn = Button(context).apply { text = "完成" }
        actionRow.addView(resetBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(closeMenuBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(actionRow)
        closeMenuBtn.setOnClickListener { styleMenu?.dismiss() }

        val popup = PopupWindow(content, dp(280), WindowManager.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        // 服务上下文无 Activity token：显式用 overlay 窗口类型
        popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        try {
            // 锚定字幕条下方（不占据屏幕中心）
            popup.showAsDropDown(anchor, 0, dp(6))
            styleMenu = popup
            android.util.Log.i("OverlayMenu", "popup shown asDropDown")
        } catch (e: Exception) {
            android.util.Log.w("OverlayMenu", "showAsDropDown failed: ${e.message}")
            // 无障碍等受限环境回退：屏幕底部弹出
            try {
                popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(12))
                styleMenu = popup
                android.util.Log.i("OverlayMenu", "popup shown atLocation")
            } catch (e2: Exception) {
                android.util.Log.e("OverlayMenu", "showAtLocation failed: ${e2.message}")
            }
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

    // ---------- 拖动整个窗口 ----------

    private var dragStartY = 0f
    private var dragStartLpY = 0

    fun hide() {
        handler.post {
            styleMenu?.dismiss()
            styleMenu = null
            view?.let { runCatching { wm.removeView(it) } }
            view = null
            originalView = null
            translationView = null
            resizeHandle = null
            params = null
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
}
