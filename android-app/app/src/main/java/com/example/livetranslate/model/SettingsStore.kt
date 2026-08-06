package com.example.livetranslate.model

import android.content.Context
import org.json.JSONArray

/**
 * 设置持久化 —— 对应原项目 user_settings.json（SharedPreferences 简化版）。
 * 键名与原项目保持一致，便于配置互通。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("lt_settings", Context.MODE_PRIVATE)

    /** 模型列表（对应 user_settings.json "models"） */
    var models: List<ModelConfig>
        get() = ModelConfig.listFromJson(prefs.getString(KEY_MODELS, null))
        set(value) = prefs.edit().putString(KEY_MODELS, ModelConfig.listToJson(value)).apply()

    /** 活动模型索引（对应 "active_model"） */
    var activeModelIndex: Int
        get() = prefs.getInt(KEY_ACTIVE_MODEL, 0)
        set(value) = prefs.edit().putInt(KEY_ACTIVE_MODEL, value).apply()

    /** 远程 ASR 服务器地址 */
    var asrUrl: String
        get() = prefs.getString(KEY_ASR_URL, "http://172.17.0.1:8765")!!
        set(value) = prefs.edit().putString(KEY_ASR_URL, value).apply()

    /** VAD 模式: silero / energy */
    var vadMode: String
        get() = prefs.getString(KEY_VAD_MODE, "silero")!!
        set(value) = prefs.edit().putString(KEY_VAD_MODE, value).apply()

    /** 字幕条样式（悬浮窗 + 无障碍条共享） */
    var subtitleStyle: SubtitleStyle
        get() {
            val raw = prefs.getString(KEY_SUBTITLE_STYLE, null)
            return if (raw.isNullOrBlank()) SubtitleStyle() else SubtitleStyle.fromJson(org.json.JSONObject(raw))
        }
        set(value) = prefs.edit().putString(KEY_SUBTITLE_STYLE, value.toJson().toString()).apply()

    /** 悬浮窗宽度 px（0=自动） */
    var overlayWidthPx: Int
        get() = prefs.getInt(KEY_OVERLAY_W, 0)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_W, value).apply()

    /** 悬浮窗高度 px（0=自动/内容自适应） */
    var overlayHeightPx: Int
        get() = prefs.getInt(KEY_OVERLAY_H, 0)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_H, value).apply()

    /** 活动模型；无配置时返回默认 DeepSeek 配置 */
    fun activeModel(): ModelConfig {
        val list = models
        return if (list.isNotEmpty() && activeModelIndex in list.indices) {
            list[activeModelIndex]
        } else {
            DEFAULT_MODEL
        }
    }

    fun setActiveModel(index: Int, model: ModelConfig) {
        val list = models.toMutableList()
        if (index in list.indices) list[index] = model else list.add(model)
        models = list
        activeModelIndex = index.coerceIn(0, list.size - 1)
    }

    fun addModel(model: ModelConfig) {
        models = models + model
        activeModelIndex = models.size - 1
    }

    fun removeModel(index: Int) {
        val list = models.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        models = list
        if (list.isEmpty()) {
            activeModelIndex = 0
        } else {
            activeModelIndex = activeModelIndex.coerceIn(0, list.size - 1)
        }
    }

    companion object {
        private const val KEY_MODELS = "models"
        private const val KEY_ACTIVE_MODEL = "active_model"
        private const val KEY_ASR_URL = "asr_url"
        private const val KEY_VAD_MODE = "vad_mode"
        private const val KEY_SUBTITLE_STYLE = "subtitle_style"
        private const val KEY_OVERLAY_W = "overlay_w"
        private const val KEY_OVERLAY_H = "overlay_h"

        /** 默认配置（与 config.yaml 的 translation 段对齐） */
        val DEFAULT_MODEL = ModelConfig(
            name = "DeepSeek",
            apiBase = "https://api.deepseek.com/v1",
            apiKey = "",
            model = "deepseek-chat",
            targetLanguage = "zh",
            streaming = true,
            noThink = true,
            timeout = 30,
        )
    }
}
