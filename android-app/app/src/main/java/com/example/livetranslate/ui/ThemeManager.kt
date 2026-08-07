package com.example.livetranslate.ui

import android.content.Context
import com.example.livetranslate.model.SettingsStore

/**
 * 主题引擎 —— 多主题可切换。
 *
 * 内置主题：
 *  - DARK_LIQUID（默认，第一版暗色）：深色液态玻璃（iOS 26 Liquid Glass）
 *  - APPLE_LIGHT（第二版）：浅色 Apple 官网（极光 + 毛玻璃 Bento Grid）
 *  - VTUBER（第三版）：VTuber 紫色系（参考图风格）
 *
 * 切换方式：设置主题名 → recreate Activity；悬浮窗/菜单在下次创建时自动跟随。
 */
data class AppTheme(
    val name: String,
    val label: String,
    val icon: String,
    // 画布
    val bg: Int,
    // 玻璃/卡片
    val cardBase: Int,          // 卡片底色（半透明）
    val cardSheen: Int,         // 顶部光泽强度
    val cardEdge: Int,          // 描边色
    val cardRadiusDp: Int = 16,
    // 文字
    val text: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    // 主色（按钮/强调）
    val primary: Int,
    val primaryGradA: Int? = null,   // 渐变（null=纯色）
    val primaryGradB: Int? = null,
    // 输入框
    val inputBg: Int,
    val inputBorder: Int,
    // 滑杆
    val track: Int,
    // 分段控件
    val segContainer: Int,
    val segHighlight: Int,      // 高亮胶囊底色
    val segHighlightEdge: Int,
    val segTextSel: Int,
    // 图标默认色
    val iconColor: Int,
    // 极光光球（颜色数组，按 alpha 预乘）
    val aurora: List<Int>,
    // 是否显示极光背景（第一版暗色为纯色无背景）
    val showAurora: Boolean = true,
    // 菜单面板玻璃参数（样式菜单专用，不随卡片纯色化）
    val menuBase: Int = 0xD1121420.toInt(),
    val menuSheen: Int = 0x33,
    val menuEdge: Int = 0x59FFFFFF.toInt(),
    // 对话框主题（0=系统深色）
    val dialogThemeRes: Int = 0,
)

object ThemeManager {

    /** 第一版暗色主题（v0.8.0 原版：纯色背景 + 纯色卡片 + 蓝色分段胶囊） */
    val DARK_LIQUID = AppTheme(
        name = "dark",
        label = "暗色液态玻璃",
        icon = "🌙",
        bg = 0xFF101014.toInt(),
        // 第一版：不透明纯色卡片（无光泽/无描边）
        cardBase = 0xFF1E1E23.toInt(),
        cardSheen = 0x00000000,
        cardEdge = 0x00000000,
        text = 0xFFFFFFFF.toInt(),
        textSecondary = 0xFF9A9AA5.toInt(),
        textTertiary = 0xFF5E5E6B.toInt(),
        primary = 0xFF0A84FF.toInt(),
        primaryGradA = null,
        primaryGradB = null,
        inputBg = 0xFF26262E.toInt(),
        inputBorder = 0xFF33333C.toInt(),
        track = 0xFF3A3A44.toInt(),
        // 第一版：蓝色高亮胶囊 + 白字
        segContainer = 0xFF26262E.toInt(),
        segHighlight = 0xFF0A84FF.toInt(),
        segHighlightEdge = 0x00000000,
        segTextSel = 0xFFFFFFFF.toInt(),
        iconColor = 0xFFFFFFFF.toInt(),
        aurora = emptyList(),
        showAurora = false,                 // 第一版：纯色背景无极光
        menuBase = 0xD1121420.toInt(),      // 样式菜单保留液态玻璃（优化项）
        menuSheen = 0x33,
        menuEdge = 0x59FFFFFF.toInt(),
    )

    /** 第二版浅色 Apple 官网（极光 + 毛玻璃） */
    val APPLE_LIGHT = AppTheme(
        name = "light",
        label = "浅色 Apple",
        icon = "☀️",
        bg = 0xFFF5F5F7.toInt(),
        cardBase = 0xB8FFFFFF.toInt(),      // 白色毛玻璃
        cardSheen = 0x00000000,             // 浅色无需光泽
        cardEdge = 0x0D000000.toInt(),      // 6% 黑 hairline
        text = 0xFF1D1D1F.toInt(),
        textSecondary = 0xFF86868B.toInt(),
        textTertiary = 0xFFB0B0B8.toInt(),
        primary = 0xFF0071E3.toInt(),
        primaryGradA = null,
        primaryGradB = null,
        inputBg = 0xFFF2F2F7.toInt(),
        inputBorder = 0x1F000000.toInt(),
        track = 0xFFE5E5EA.toInt(),
        segContainer = 0x0D000000.toInt(),
        segHighlight = 0xFFFFFFFF.toInt(),  // 白色胶囊
        segHighlightEdge = 0x1F000000.toInt(),
        segTextSel = 0xFF0071E3.toInt(),
        iconColor = 0xFF1D1D1F.toInt(),
        aurora = listOf(0x66A2D2FF.toInt(), 0x59C8B4FF.toInt(), 0x52A0E6FF.toInt()),
        dialogThemeRes = com.example.livetranslate.R.style.LightDialogAlert,
    )

    /** 第三版 VTuber 紫色系 */
    val VTUBER = AppTheme(
        name = "vtuber",
        label = "VTuber 紫",
        icon = "💜",
        bg = 0xFF14101E.toInt(),
        cardBase = 0xA81E1628.toInt(),      // 深紫玻璃
        cardSheen = 0x16,
        cardEdge = 0x40BF5AF2.toInt(),      // 紫描边
        text = 0xFFFFFFFF.toInt(),
        textSecondary = 0xFFB8A8CC.toInt(),
        textTertiary = 0xFF6E5F80.toInt(),
        primary = 0xFFA855F7.toInt(),
        primaryGradA = 0xFFA855F7.toInt(),
        primaryGradB = 0xFF6D28D9.toInt(),
        inputBg = 0xFF2A2138.toInt(),
        inputBorder = 0xFF4A3A5C.toInt(),
        track = 0xFF4A3A5C.toInt(),
        segContainer = 0x8033264A.toInt(),
        segHighlight = 0xE6F5E9FF.toInt(),
        segHighlightEdge = 0x80BF5AF2.toInt(),
        segTextSel = 0xFF8B5CF6.toInt(),
        iconColor = 0xFFFFFFFF.toInt(),
        aurora = listOf(0x4DBF5AF2.toInt(), 0x457C6CF0.toInt(), 0x3DFF7EB6.toInt()),
    )

    val themes = listOf(DARK_LIQUID, APPLE_LIGHT, VTUBER)

    /** 当前主题（从设置读取） */
    @Volatile
    var current: AppTheme = DARK_LIQUID
        private set

    fun init(context: Context) {
        val name = SettingsStore(context).themeName
        current = themes.firstOrNull { it.name == name } ?: DARK_LIQUID
    }

    /** 切换主题并持久化（调用方负责 recreate） */
    fun set(context: Context, name: String): AppTheme {
        val t = themes.firstOrNull { it.name == name } ?: DARK_LIQUID
        current = t
        SettingsStore(context).themeName = t.name
        return t
    }
}
