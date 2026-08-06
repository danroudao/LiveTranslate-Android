package com.example.livetranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 共享 UI 组件 —— Apple 官网 / Block Studio 风格（浅色 Aurora & Glass）。
 *
 * 色板（参照 apple.com 与 iOS 浅色模式）：
 *  - 画布 #F5F5F7 / 毛玻璃卡 rgba(255,255,255,0.78) / 次级按钮 #E5E5EA
 *  - 品牌蓝 #0071E3 / 文字 #1D1D1F / 注释 #86868B / 分割 #0D000000(6%)
 *  - 输入框 #F2F2F7（focus 白底 + 品牌蓝光晕）
 *  - 极光光球：淡蓝 rgba(162,210,255,0.45) · 淡紫 rgba(200,180,255,0.35) · 淡青 rgba(160,230,255,0.3)
 */
object UIKit {

    const val BG = 0xFFF5F5F7.toInt()            // 画布
    const val GLASS = 0xB8FFFFFF.toInt()          // 毛玻璃卡 rgba(255,255,255,0.72)（让极光透出）
    const val GLASS_STRONG = 0xEFFFFFFF.toInt()   // 弹层 rgba(255,255,255,0.94)
    const val BRAND = 0xFF0071E3.toInt()          // 品牌蓝
    const val BRAND_DEEP = 0xFF0060C7.toInt()     // 按压态
    const val BRAND_RING = 0x330071E3.toInt()     // focus 光晕
    const val BTN_SECONDARY = 0xFFE5E5EA.toInt()  // 次级按钮实心浅灰
    const val INPUT_BG = 0xFFF2F2F7.toInt()       // 输入框
    const val TRACK = 0xFFE5E5EA.toInt()          // 滑杆轨道
    const val TEXT = 0xFF1D1D1F.toInt()           // 主文字
    const val TEXT_SECONDARY = 0xFF86868B.toInt() // 注释
    const val TEXT_TERTIARY = 0xFFB0B0B8.toInt()  // 弱注释
    const val HAIRLINE = 0x0D000000.toInt()       // 1px 分割线 rgba(0,0,0,6%)
    const val HAIRLINE_LIGHT = 0x59FFFFFF.toInt() // 白色 hairline rgba(255,255,255,35%)
    const val GREEN = 0xFF34C759.toInt()          // iOS 绿
    const val ORANGE = 0xFFFF9500.toInt()
    const val RED = 0xFFFF3B30.toInt()
    const val PURPLE = 0xFFAF52DE.toInt()

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    // ---------- 极光背景（Aurora View） ----------

    /**
     * 动态弥散背景：2-3 个超大径向渐变光球 + 缓慢浮动动画（呼吸感）。
     * 用法：作为根 FrameLayout 的第一个子 View，MATCH_PARENT。
     */
    class AuroraView(context: Context) : View(context) {
        private val orbs = mutableListOf<Orb>()
        private val animators = mutableListOf<ValueAnimator>()
        private val density = resources.displayMetrics.density

        private class Orb(
            val cx: Float, val cy: Float,        // 圆心（相对 View 比例）
            val radius: Float,                    // 半径（相对 View 宽）
            val colors: IntArray,                 // 径向渐变
            val driftX: Float, val driftY: Float, // 浮动幅度
            val duration: Long,
        ) {
            var phase: Float = 0f
        }

        init {
            val w = resources.displayMetrics.widthPixels
            // 光球中心落在屏幕内/边缘，保证屏幕大部分区域处于渐变高浓度区
            orbs.add(Orb(0.18f, -0.02f, 0.95f,
                intArrayOf(0x99A2D2FF.toInt(), 0x00A2D2FF.toInt()), 45f * density, 35f * density, 15000))
            orbs.add(Orb(1.05f, 0.42f, 0.80f,
                intArrayOf(0x85C8B4FF.toInt(), 0x00C8B4FF.toInt()), -55f * density, 25f * density, 18000))
            orbs.add(Orb(0.30f, 1.10f, 0.95f,
                intArrayOf(0x7AA0E6FF.toInt(), 0x00A0E6FF.toInt()), 35f * density, -40f * density, 21000))
            // 慢速无限浮动（呼吸）
            orbs.forEachIndexed { i, o ->
                val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = o.duration
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = DecelerateInterpolator(1.2f)
                    startDelay = i * 2000L
                    addUpdateListener {
                        o.phase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
                animators.add(anim)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 画布底色（含轻微纵向渐变，模拟 Apple 官网的柔和底）
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(),
                Paint().apply { shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0xFFF7F7F9.toInt(), 0xFFF5F5F7.toInt(), 0xFFF4F4F6.toInt()),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP) })
            val w = width.toFloat()
            for (o in orbs) {
                // 相位 → 位置微移（呼吸浮动）
                val px = o.cx * w + o.driftX * o.phase
                val py = o.cy * height + o.driftY * o.phase
                val r = o.radius * w * (1f + 0.04f * o.phase)
                canvas.drawCircle(px, py, r,
                    Paint().apply {
                        shader = RadialGradient(px, py, r, o.colors, null, Shader.TileMode.CLAMP)
                    })
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animators.forEach { it.cancel() }
        }
    }

    // ---------- 容器 ----------

    /** 毛玻璃卡片：squircle 24dp 大圆角 + 半透明白 + hairline + 阴影 */
    fun card(context: Context, radiusDp: Int = 24, alpha: Int = 0xFF, padding: Int = 18): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(context, radiusDp).toFloat()
                setColor(GLASS)
                setStroke(dp(context, 1), HAIRLINE)
            }
            elevation = dp(context, 8).toFloat()
            setPadding(dp(context, padding), dp(context, 18), dp(context, padding), dp(context, 18))
        }

    /** 分组标题（Apple 风格：13sp 注释色，微字距） */
    fun sectionLabel(context: Context, text: String, marginTop: Int = 22): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(TEXT_SECONDARY)
            letterSpacing = 0.02f
            setTypeface(typeface, Typeface.NORMAL)
            setPadding(dp(context, 6), dp(context, marginTop), dp(context, 6), dp(context, 8))
        }

    /** 大标题（Big Type：700 字重 + 字距微缩） */
    fun bigTitle(context: Context, text: String, sizeSp: Float = 30f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT)
            letterSpacing = -0.02f
            includeFontPadding = false
        }

    /** 副标题（注释色） */
    fun subtitle(context: Context, text: String, sizeSp: Float = 14f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(0xFF6E6E73.toInt())
        }

    /** 状态圆点（配合 IOSMotion.breathe 呼吸） */
    fun statusDot(context: Context, color: Int = GREEN, sizeDp: Int = 9): View =
        View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    // ---------- 按钮 ----------

    enum class ButtonStyle { PRIMARY, SECONDARY, DANGER, CHIP }

    /**
     * Apple 风格按钮：胶囊圆角。
     *  - PRIMARY：品牌蓝实心 #0071E3 + 白字
     *  - SECONDARY：浅灰实心 #E5E5EA + 深色字（禁止透明底）
     *  - 按压：scale 0.97 + 亮度变化，松手弹簧回弹
     */
    fun iosButton(
        context: Context,
        text: String,
        style: ButtonStyle = ButtonStyle.PRIMARY,
        heightDp: Int = 52,
        small: Boolean = false,
        matchWidth: Boolean = true,
        onClick: (() -> Unit)? = null,
    ): TextView {
        val radius = if (small) dp(context, 16) else dp(context, 26)
        val bg = GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            when (style) {
                ButtonStyle.PRIMARY -> setColor(BRAND)
                ButtonStyle.SECONDARY -> setColor(BTN_SECONDARY)
                ButtonStyle.DANGER -> setColor(0x1AFF3B30.toInt())
                ButtonStyle.CHIP -> {
                    setColor(0xE6FFFFFF.toInt())
                    setStroke(dp(context, 1), HAIRLINE)
                }
            }
        }
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = if (small) 13.5f else 15.5f
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
                        v.alpha = 0.9f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        IOSMotion.release(v)
                        v.alpha = 1f
                    }
                }
                false
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
        iosButton(context, text, ButtonStyle.SECONDARY, heightDp = 40, small = true,
            matchWidth = matchWidth, onClick = onClick)

    // ---------- 输入框（Apple 风格：浅灰底无边框，focus 白底+蓝光晕） ----------

    fun appleInput(context: Context, hint: String = "", value: String = "", singleLine: Boolean = true): EditText {
        val et = EditText(context).apply {
            setText(value)
            isSingleLine = singleLine
            textSize = 15f
            setTextColor(TEXT)
            setHintTextColor(TEXT_TERTIARY)
            background = inputBg(context, focused = false)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        }
        et.setOnFocusChangeListener { v, has ->
            v.background = inputBg(context, has)
            v.elevation = if (has) dp(context, 4).toFloat() else 0f
        }
        return et
    }

    private fun inputBg(context: Context, focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, 14).toFloat()
            setColor(if (focused) Color.WHITE else INPUT_BG)
            if (focused) {
                setStroke(dp(context, 2), BRAND)
            } else {
                setStroke(dp(context, 1), 0x1F000000.toInt()) // 12% 黑 hairline
            }
        }

    // ---------- 分段控件（UISegmentedControl） ----------

    /**
     * Apple 分段控件：浅灰容器 + 白色高亮胶囊（带阴影）+ spring 滑动。
     * 选中文字品牌蓝加粗。
     */
    fun segmentedControl(
        context: Context,
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): LinearLayout {
        val n = options.size
        val pad = dp(context, 3)
        val radius = dp(context, 12)
        val highlight = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = (radius - dp(context, 2)).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(context, 3).toFloat()
        }
        val labels = mutableListOf<TextView>()
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius.toFloat()
                setColor(0x0D000000.toInt()) // rgba(0,0,0,5%)
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setPadding(pad, pad, pad, pad)
            // Z 序必须高于高亮胶囊（否则白胶囊 elevation 会盖住文字）
            elevation = dp(context, 6).toFloat()
        }
        options.forEachIndexed { i, label ->
            val tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13.5f
                setTextColor(if (i == selected) BRAND else TEXT_SECONDARY)
                setTypeface(typeface, if (i == selected) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    if (i == selected) return@setOnClickListener
                    labels.forEachIndexed { j, t ->
                        t.setTextColor(if (j == i) BRAND else TEXT_SECONDARY)
                        t.setTypeface(t.typeface, if (j == i) Typeface.BOLD else Typeface.NORMAL)
                    }
                    container.post {
                        val step = (container.width - pad * 2) / n
                        highlight.animate()
                            .translationX((i * step).toFloat())
                            .setDuration(460)
                            .setInterpolator(IOSMotion.APPLE_SPRING)
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
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(container, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 42)))
        }
    }

    // ---------- iOS 滑杆（SeekBar） ----------

    /** 自定义进度 Drawable：轨道 + 品牌蓝填充 */
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

        override fun draw(canvas: Canvas) {
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

    /** 自定义拇指 Drawable：白色圆 + 投影 */
    private class IosThumbDrawable(private val sizePx: Int) : android.graphics.drawable.Drawable() {
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        override fun draw(canvas: Canvas) {
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

    fun iosSeekBar(
        context: Context,
        max: Int,
        progress: Int,
        onProgress: (Int) -> Unit,
    ): SeekBar {
        val trackH = dp(context, 4)
        val thumbD = dp(context, 18)
        val progressDrawable = IosProgressDrawable(TRACK, BRAND, trackH / 2)
        return SeekBar(context).apply {
            this.max = max
            this.progress = progress
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
