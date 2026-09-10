package com.example.livetranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.PopupWindow

/**
 * Liquid Glass（液态玻璃）—— iOS 26 风格玻璃材质工具。
 *
 * 核心特征：
 *  - 半透明深色玻璃面板 + 顶部光泽（sheen，模拟玻璃受光）
 *  - 1px 白色高光描边（模拟玻璃边缘折射）
 *  - 窗口级背景模糊（blurBehindRadius，API 31+）= 真正的毛玻璃
 *  - 深色极光背景（主页氛围层，让玻璃透出色彩）
 */
object LiquidGlass {

    /** 玻璃面板底色（深色半透明） */
    const val GLASS_BASE = 0xC92A2C3A.toInt()    // rgba(42,44,58,79%) 亮玻璃灰（避免黑块感）
    const val GLASS_SOFT = 0xA82A2C3A.toInt()    // rgba(42,44,58,66%) 更透

    /** 高光描边色（玻璃边缘） */
    const val EDGE_HI = 0x59FFFFFF.toInt()       // 白 35%
    const val EDGE_SOFT = 0x2EFFFFFF.toInt()     // 白 18%

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /** 顶部光泽层（玻璃受光）：白色渐变从顶向下衰减 */
    private fun sheenLayer(radius: Float, topAlpha: Int): GradientDrawable {
        val mid = (topAlpha * 0.35f).toInt()
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topAlpha shl 24, mid shl 24, 0x00000000)).apply {
            cornerRadius = radius
        }
    }

    /**
     * 玻璃面板：半透明深色 + 顶部光泽 + 高光描边。
     * @param base 底色（半透明深色）
     * @param sheenAlpha 顶部光泽强度（0-255，0=关闭）
     * @param edgeColor 描边颜色
     */
    fun panel(
        context: Context,
        radiusDp: Int,
        base: Int = GLASS_BASE,
        sheenAlpha: Int = 0x1F,
        edgeColor: Int = EDGE_HI,
    ): LayerDrawable {
        val radius = dp(context, radiusDp).toFloat()
        // 层 0：玻璃底
        val baseLayer = GradientDrawable().apply {
            cornerRadius = radius
            setColor(base)
        }
        // 层 1：顶部光泽（玻璃受光）
        val sheenLayer = sheenLayer(radius, sheenAlpha)
        // 层 2：高光描边
        val ringLayer = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0x00000000)
            setStroke(dp(context, 1), edgeColor)
        }
        return LayerDrawable(arrayOf(baseLayer, sheenLayer, ringLayer))
    }

    /** 细玻璃条（字幕条用，顶部光泽更弱） */
    fun bar(
        context: Context,
        radiusDp: Int,
        base: Int = GLASS_SOFT,
        sheenAlpha: Int = 0x14,
    ): LayerDrawable = panel(context, radiusDp, base, sheenAlpha, EDGE_HI)

    /**
     * 窗口背景模糊（API 31+，Android 12 起支持）——悬浮窗/字幕条的真毛玻璃。
     * 低版本自动跳过（视觉上由半透明底色承担）。
     */
    fun blurWindow(params: WindowManager.LayoutParams, radiusDp: Int, context: Context) {
        if (Build.VERSION.SDK_INT >= 31) {
            params.blurBehindRadius = dp(context, radiusDp)
        }
    }

    /**
     * 运行中动态更新窗口背景模糊（0 = 关闭，API 31+）。
     * 用于字幕条切到全透明模式时同步关闭毛玻璃，避免透出灰雾并省 GPU。
     */
    fun updateWindowBlur(
        params: WindowManager.LayoutParams,
        radiusDp: Int,
        wm: WindowManager,
        view: View,
    ) {
        if (Build.VERSION.SDK_INT < 31) return
        val px = dp(view.context, radiusDp)
        if (params.blurBehindRadius == px) return
        params.blurBehindRadius = px
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            // 窗口已移除等场景：忽略
        }
    }

    /**
     * PopupWindow 背景模糊（API 31+）。
     * PopupWindow 未公开 blur API：通过 decorView 的 LayoutParams 更新，失败自动回退。
     */
    fun blurPopup(popup: PopupWindow, radiusDp: Int, context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val decor = popup.contentView.parent as? View ?: return
            val lp = decor.layoutParams as? WindowManager.LayoutParams ?: return
            lp.blurBehindRadius = dp(context, radiusDp)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.updateViewLayout(decor, lp)
        } catch (e: Exception) {
            // 回退：仅半透明底色
        }
    }

    // ---------- 深色极光背景（主页氛围层） ----------

    /**
     * 深色版极光背景：低亮度蓝/紫光球缓慢浮动，玻璃面板透出其色彩。
     * 用法：根布局第一个子 View，MATCH_PARENT。
     */
    class AuroraView(context: Context) : View(context) {
        private val orbs = mutableListOf<Orb>()
        private val animators = mutableListOf<android.animation.ValueAnimator>()
        private val density = resources.displayMetrics.density

        /** 生命周期控制：后台时暂停动画（避免无限动画空耗 CPU/内存） */
        fun pause() = animators.forEach { it.pause() }
        fun resume() = animators.forEach { if (it.isPaused) it.resume() }

        private class Orb(
            val cx: Float, val cy: Float,
            val radius: Float,
            val colors: IntArray,
            val driftX: Float, val driftY: Float,
            val duration: Long,
        ) {
            var phase: Float = 0f
        }

        init {
            // 光球色随主题（暗色=紫系 / 浅色=淡蓝紫 / VTuber=紫系）
            val colors = ThemeManager.current.aurora
            orbs.add(Orb(0.12f, -0.10f, 0.72f,
                intArrayOf(colors.getOrElse(0) { 0x4DBF5AF2.toInt() }, 0x00000000),
                40f * density, 30f * density, 16000))
            orbs.add(Orb(1.05f, 0.30f, 0.62f,
                intArrayOf(colors.getOrElse(1) { 0x457C6CF0.toInt() }, 0x00000000),
                -50f * density, 25f * density, 19000))
            orbs.add(Orb(0.45f, 1.08f, 0.75f,
                intArrayOf(colors.getOrElse(2) { 0x3DFF7EB6.toInt() }, 0x00000000),
                35f * density, -36f * density, 22000))
            orbs.forEachIndexed { i, o ->
                val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = o.duration
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.DecelerateInterpolator(1.2f)
                    startDelay = i * 2500L
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
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(),
                Paint().apply { color = UIKit.BG })
            val w = width.toFloat()
            for (o in orbs) {
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
}
