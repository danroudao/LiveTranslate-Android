package com.example.livetranslate.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 共享 UI 组件 —— iOS 风格深色控件（程序化构建）。
 *
 * 色板参照 iOS 深色模式：
 *  - 页面底色 #101014 / 卡片 #1E1E23 / 卡片高亮 #26262E
 *  - 系统蓝 #0A84FF / 绿 #30D158 / 橙 #FF9F0A / 红 #FF453A
 *  - 文字 白 / 次级 #9A9AA5 / 三级 #5E5E6B
 */
object UIKit {

    const val BG = 0xFF101014.toInt()
    const val CARD = 0xFF1E1E23.toInt()
    const val CARD_HI = 0xFF26262E.toInt()
    const val CARD_LINE = 0xFF33333C.toInt()
    const val IOS_BLUE = 0xFF0A84FF.toInt()
    const val IOS_BLUE_DEEP = 0xFF0063C9.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val PURPLE = 0xFFBF5AF2.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_SECONDARY = 0xFF9A9AA5.toInt()
    const val TEXT_TERTIARY = 0xFF5E5E6B.toInt()
    const val TRACK = 0xFF3A3A44.toInt()

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    // ---------- 容器 ----------

    /** 液态玻璃卡片：半透明深色 + 顶部光泽 + 高光描边（iOS 26 Liquid Glass） */
    fun card(context: Context, padding: Int = 14, radiusDp: Int = 16): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = LiquidGlass.panel(context, radiusDp, LiquidGlass.GLASS_SOFT)
            setPadding(dp(context, padding), dp(context, 14), dp(context, padding), dp(context, 14))
        }

    /** 分组标题（iOS 风格：13sp 次级色，带上下留白） */
    fun sectionLabel(context: Context, text: String, marginTop: Int = 20): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(TEXT_SECONDARY)
            letterSpacing = 0.03f
            setPadding(dp(context, 4), dp(context, marginTop), dp(context, 4), dp(context, 8))
        }

    /** 状态圆点（配合 IOSMotion.breathe 呼吸） */
    fun statusDot(context: Context, color: Int = GREEN, sizeDp: Int = 8): View =
        View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    // ---------- 按钮 ----------

    enum class ButtonStyle { PRIMARY, SECONDARY, DANGER, CHIP }

    /** iOS 风格按钮：液态玻璃胶囊 + 按压缩放反馈（0.96 + 松手弹簧回弹） */
    fun iosButton(
        context: Context,
        text: String,
        style: ButtonStyle = ButtonStyle.PRIMARY,
        heightDp: Int = 50,
        small: Boolean = false,
        matchWidth: Boolean = true,
        onClick: (() -> Unit)? = null,
    ): TextView {
        val radius = if (small) dp(context, 10) else dp(context, 14)
        val bg: android.graphics.drawable.Drawable = when (style) {
            // 主按钮：品牌蓝 + 顶部光泽 + 高光描边（液态蓝玻璃）
            ButtonStyle.PRIMARY -> {
                val base = GradientDrawable().apply {
                    cornerRadius = radius.toFloat()
                    setColor(IOS_BLUE)
                }
                val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x45FFFFFF, 0x14FFFFFF, 0x00FFFFFF)).apply {
                    cornerRadius = radius.toFloat()
                }
                val ring = GradientDrawable().apply {
                    cornerRadius = radius.toFloat()
                    setColor(0x00000000)
                    setStroke(dp(context, 1), 0x66FFFFFF.toInt())
                }
                android.graphics.drawable.LayerDrawable(arrayOf(base, sheen, ring))
            }
            // 次级按钮：深色玻璃（半透明白 + 描边）
            ButtonStyle.SECONDARY -> LiquidGlass.panel(context, if (small) 10 else 14,
                base = 0xBD26262E.toInt(), sheenAlpha = 0x16, edgeColor = LiquidGlass.EDGE_HI)
            // 危险：透明红底
            ButtonStyle.DANGER -> GradientDrawable().apply {
                cornerRadius = radius.toFloat()
                setColor(0x26FF453A.toInt())
            }
            // 胶囊：玻璃 + 描边
            ButtonStyle.CHIP -> LiquidGlass.panel(context, if (small) 10 else 14,
                base = 0xBD26262E.toInt(), sheenAlpha = 0x12, edgeColor = LiquidGlass.EDGE_HI)
        }
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = if (small) 13f else 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                when (style) {
                    ButtonStyle.PRIMARY -> Color.WHITE
                    ButtonStyle.DANGER -> RED
                    else -> TEXT
                }
            )
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                if (matchWidth) LinearLayout.LayoutParams.MATCH_PARENT
                else LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(context, heightDp),
            )
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        IOSMotion.press(v)
                        v.alpha = 0.85f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        IOSMotion.release(v)
                        v.alpha = 1f
                    }
                }
                false // 不消费事件，让 OnClickListener 正常触发
            }
            if (onClick != null) setOnClickListener { onClick.invoke() }
        }
    }

    /** 小胶囊按钮（行内使用） */
    fun pillButton(
        context: Context,
        text: String,
        matchWidth: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): TextView =
        iosButton(context, text, ButtonStyle.SECONDARY, heightDp = 38, small = true,
            matchWidth = matchWidth, onClick = onClick)

    // ---------- 分段控件（UISegmentedControl） ----------

    /**
     * iOS 分段控件：胶囊容器 + 高亮条 spring 滑动动画。
     * 返回外层 LinearLayout（容器 40dp 高）。
     */
    fun segmentedControl(
        context: Context,
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): LinearLayout {
        val n = options.size
        val pad = dp(context, 3)
        val radius = dp(context, 11)
        // 高亮胶囊：白色液态玻璃（半透明白 + 光泽 + 高光描边）
        val highlight = View(context).apply {
            background = LiquidGlass.panel(context, radius - dp(context, 2),
                base = 0xE6FFFFFF.toInt(), sheenAlpha = 0x2E, edgeColor = 0x80FFFFFF.toInt())
        }
        val labels = mutableListOf<TextView>()
        // 容器：深色玻璃槽
        val container = FrameLayout(context).apply {
            background = LiquidGlass.panel(context, radius,
                base = 0x8026262E.toInt(), sheenAlpha = 0x0E, edgeColor = LiquidGlass.EDGE_SOFT)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setPadding(pad, pad, pad, pad)
        }
        options.forEachIndexed { i, label ->
            val tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(if (i == selected) IOS_BLUE else TEXT_SECONDARY)
                setTypeface(typeface, if (i == selected) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    if (i == selected) return@setOnClickListener
                    labels.forEachIndexed { j, t ->
                        t.setTextColor(if (j == i) IOS_BLUE else TEXT_SECONDARY)
                        t.setTypeface(t.typeface, if (j == i) Typeface.BOLD else Typeface.NORMAL)
                    }
                    container.post {
                        val step = (container.width - pad * 2) / n
                        highlight.animate()
                            .translationX((i * step).toFloat())
                            .setDuration(420)
                            .setInterpolator(IOSMotion.SPRING_STRONG)
                            .start()
                    }
                    onSelect(i)
                }
            }
            labels.add(tv)
            row.addView(tv)
        }
        container.addView(highlight, FrameLayout.LayoutParams(0, 0))
        container.addView(row)
        container.post {
            if (container.width > 0 && highlight.width == 0) {
                val step = (container.width - pad * 2) / n
                highlight.layoutParams = FrameLayout.LayoutParams(
                    step, row.height - pad * 2
                )
                highlight.translationX = (selected * step).toFloat()
            }
        }
        // 外层包装（统一 40dp 高）
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(container, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 40)))
        }
    }

    // ---------- iOS 滑杆（SeekBar） ----------

    /** 自定义进度 Drawable：轨道 + 蓝色填充（由 onProgressChanged 直接驱动，不依赖 setLevel） */
    private class IosProgressDrawable(
        private val trackColor: Int,
        private val fillColor: Int,
        private val radiusPx: Int,
    ) : android.graphics.drawable.Drawable() {
        private var fillWidth = 0f
        private val track = GradientDrawable().apply {
            cornerRadius = radiusPx.toFloat()
            setColor(trackColor)
        }
        private val fill = GradientDrawable().apply {
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
        }

        fun setProgress(progress: Int, max: Int) {
            fillWidth = if (max > 0) bounds.width() * progress.toFloat() / max else 0f
            invalidateSelf()
        }

        override fun draw(canvas: android.graphics.Canvas) {
            track.setBounds(bounds)
            track.draw(canvas)
            if (fillWidth > 0) {
                fill.setBounds(bounds.left, bounds.top,
                    bounds.left + fillWidth.toInt(), bounds.bottom)
                fill.draw(canvas)
            }
        }

        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** 自定义拇指 Drawable：白色圆 + 投影（带 intrinsic 尺寸） */
    private class IosThumbDrawable(private val sizePx: Int) : android.graphics.drawable.Drawable() {
        private val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000
        }
        private val core = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val c = bounds
            canvas.drawCircle(c.exactCenterX() + sizePx * 0.06f, c.exactCenterY() + sizePx * 0.06f,
                c.width() / 2f, shadow)
            canvas.drawCircle(c.exactCenterX(), c.exactCenterY(), c.width() / 2f - sizePx * 0.02f, core)
        }

        override fun setAlpha(a: Int) {
            core.alpha = a
            invalidateSelf()
        }

        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx
    }

    /**
     * iOS 风格滑杆：灰色圆角轨道 + 蓝色进度 + 白色圆形拇指（带投影）。
     */
    fun iosSeekBar(
        context: Context,
        max: Int,
        progress: Int,
        onProgress: (Int) -> Unit,
    ): SeekBar {
        val trackH = dp(context, 4)
        val thumbD = dp(context, 18)
        val progressDrawable = IosProgressDrawable(TRACK, IOS_BLUE, trackH / 2)
        return SeekBar(context).apply {
            this.max = max
            this.progress = progress
            // 清除默认背景/分割线，避免深色主题下出现突兀色块
            background = null
            splitTrack = false
            this.progressDrawable = progressDrawable
            thumb = IosThumbDrawable(thumbD)
            thumbOffset = dp(context, 1)
            minHeight = dp(context, 28)
            setPadding(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 2))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    progressDrawable.setProgress(p, max)
                    if (fromUser) onProgress(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            // 首次布局后同步填充宽度
            post { progressDrawable.setProgress(this.progress, max) }
        }
    }

    /** 圆角背景 */
    fun roundedBg(context: Context, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(color)
        }
}
