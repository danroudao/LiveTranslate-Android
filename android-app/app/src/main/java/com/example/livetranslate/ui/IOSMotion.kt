package com.example.livetranslate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * iOS 风格动画库 —— 曲线/时长/动效助手。
 *
 * 曲线参照 iOS 系统：
 *  - standard   cubic-bezier(0.25, 0.1, 0.25, 1)   常规
 *  - decelerate cubic-bezier(0, 0, 0.2, 1)         进入（由快变慢）
 *  - accelerate cubic-bezier(0.4, 0, 1, 1)         退出（由慢变快）
 *  - spring     阻尼比 ~0.86（Overshoot 近似）      交互回弹
 *
 * 时长参照 iOS：
 *  - 按压反馈 100ms / 快速反馈 180ms
 *  - 常规转场 300ms / 慢速 420ms（弹层、卡片）
 */
object IOSMotion {

    /** iOS standard */
    val STANDARD: TimeInterpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    /** iOS decelerate（进入） */
    val DECELERATE: TimeInterpolator = PathInterpolator(0f, 0f, 0.2f, 1f)

    /** iOS accelerate（退出） */
    val ACCELERATE: TimeInterpolator = PathInterpolator(0.4f, 0f, 1f, 1f)

    /** 弹簧（轻微过冲 ≈ spring damping 0.86） */
    val SPRING: TimeInterpolator = OvershootInterpolator(0.35f)

    /** 强弹簧（分段控件高亮条跟随） */
    val SPRING_STRONG: TimeInterpolator = OvershootInterpolator(0.6f)

    const val PRESS_MS = 100L
    const val FAST_MS = 180L
    const val BASE_MS = 300L
    const val SLOW_MS = 420L

    /** 淡入 + 轻微上滑（进入动效） */
    fun enter(v: View, duration: Long = BASE_MS, slideDp: Float = 10f, onEnd: (() -> Unit)? = null) {
        val dy = slideDp * v.resources.displayMetrics.density
        v.alpha = 0f
        v.translationY = dy
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(DECELERATE)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            .start()
    }

    /** 淡出 + 上滑（退出动效） */
    fun exit(v: View, duration: Long = FAST_MS, slideDp: Float = 8f, onEnd: (() -> Unit)? = null) {
        val dy = -slideDp * v.resources.displayMetrics.density
        v.animate()
            .alpha(0f)
            .translationY(dy)
            .setDuration(duration)
            .setInterpolator(ACCELERATE)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            .start()
    }

    /** 弹层弹出：scale 0.92→1.0 + 淡入，弹簧曲线（iOS sheet 弹出效果） */
    fun popIn(v: View, duration: Long = SLOW_MS, onEnd: (() -> Unit)? = null) {
        v.pivotX = v.width / 2f
        v.pivotY = 0f
        v.scaleX = 0.92f
        v.scaleY = 0.92f
        v.alpha = 0f
        v.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(SPRING)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            .start()
    }

    /** 弹层收起：缩小 + 淡出 */
    fun popOut(v: View, duration: Long = FAST_MS, onEnd: (() -> Unit)? = null) {
        v.animate()
            .scaleX(0.94f)
            .scaleY(0.94f)
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(ACCELERATE)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            .start()
    }

    /** 按压反馈：快速缩小（按下时调用） */
    fun press(v: View) {
        v.animate().cancel()
        v.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(PRESS_MS)
            .setInterpolator(STANDARD)
            .start()
    }

    /** 松手回弹（抬起时调用） */
    fun release(v: View) {
        v.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(BASE_MS)
            .setInterpolator(SPRING)
            .start()
    }

    /** 呼吸动画（状态圆点）：alpha 0.4↔1 循环 */
    fun breathe(v: View, durationMs: Long = 1200L): ValueAnimator {
        val anim = ValueAnimator.ofFloat(0.4f, 1f).apply {
            duration = durationMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = STANDARD
            addUpdateListener { v.alpha = it.animatedValue as Float }
        }
        anim.start()
        return anim
    }

    /** 交叉淡化文本：旧内容淡出后替换并淡入（字幕更新动效） */
    fun crossfadeText(v: android.widget.TextView, newText: String, fadeMs: Long = FAST_MS, slideDp: Float = 6f) {
        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .translationY(slideDp * v.resources.displayMetrics.density)
            .setDuration(fadeMs)
            .setInterpolator(STANDARD)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.text = newText
                    v.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(fadeMs)
                        .setInterpolator(DECELERATE)
                        .start()
                }
            })
            .start()
    }
}
