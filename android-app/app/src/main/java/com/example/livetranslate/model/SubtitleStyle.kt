package com.example.livetranslate.model

import org.json.JSONObject

/**
 * 字幕条样式 —— 悬浮窗与无障碍字幕条共享。
 * 支持：背景透明度 / 字体 / 字号 / 圆角 / 粗体。
 */
data class SubtitleStyle(
    /** 背景透明度 0-255（255=不透明） */
    val alpha: Int = 210,
    /** 字号 sp；0 = 按屏幕自适应 */
    val fontSize: Float = 0f,
    /** 字体族: default / serif / monospace / cursive / sans-serif-medium */
    val fontFamily: String = "default",
    /** 圆角 dp */
    val cornerRadius: Int = 14,
    /** 粗体 */
    val bold: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("alpha", alpha)
        .put("font_size", fontSize)
        .put("font_family", fontFamily)
        .put("corner_radius", cornerRadius)
        .put("bold", bold)

    companion object {
        fun fromJson(o: JSONObject): SubtitleStyle = SubtitleStyle(
            alpha = o.optInt("alpha", 210).coerceIn(0, 255),
            fontSize = o.optDouble("font_size", 0.0).toFloat(),
            fontFamily = o.optString("font_family", "default"),
            cornerRadius = o.optInt("corner_radius", 14).coerceIn(0, 48),
            bold = o.optBoolean("bold", false),
        )

        /** 屏幕自适应字号（sp）：宽屏大字号，窄屏小字号 */
        fun autoFontSize(screenWidthDp: Float): Float {
            // 360dp 手机 → 20sp；平板 800dp → 28sp（封顶）
            return (screenWidthDp * 0.055f).coerceIn(14f, 28f)
        }
    }
}
