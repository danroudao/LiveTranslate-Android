package com.example.livetranslate.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 翻译模型配置 —— 对应原项目 user_settings.json 中 models 列表的单个条目
 * 及 control_panel.py 翻译标签页 / ModelEditDialog 的字段。
 */
data class ModelConfig(
    val name: String,
    val apiBase: String,
    val apiKey: String,
    val model: String,
    val targetLanguage: String = "zh",
    val streaming: Boolean = true,
    val jsonResponse: Boolean = false,
    val noThink: Boolean = true,
    val noSystemRole: Boolean = false,
    val contextTurns: Int = 0,
    val systemPrompt: String? = null,
    val timeout: Int = 30,
    val extraLanguages: List<String> = emptyList(),
    /** API 协议: openai 兼容 / anthropic / gemini */
    val protocol: String = "openai",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("api_base", apiBase)
        .put("api_key", apiKey)
        .put("model", model)
        .put("target_language", targetLanguage)
        .put("streaming", streaming)
        .put("json_response", jsonResponse)
        .put("no_think", noThink)
        .put("no_system_role", noSystemRole)
        .put("context_turns", contextTurns)
        .put("system_prompt", systemPrompt ?: "")
        .put("timeout", timeout)
        .put("extra_languages", JSONArray(extraLanguages))
        .put("protocol", protocol)

    companion object {
        fun fromJson(o: JSONObject): ModelConfig = ModelConfig(
            name = o.optString("name", "未命名模型"),
            apiBase = o.optString("api_base", "https://api.deepseek.com/v1"),
            apiKey = o.optString("api_key", ""),
            model = o.optString("model", "deepseek-chat"),
            targetLanguage = o.optString("target_language", "zh"),
            streaming = o.optBoolean("streaming", true),
            jsonResponse = o.optBoolean("json_response", false),
            noThink = o.optBoolean("no_think", true),
            noSystemRole = o.optBoolean("no_system_role", false),
            contextTurns = o.optInt("context_turns", 0),
            systemPrompt = o.optString("system_prompt", "").ifBlank { null },
            timeout = o.optInt("timeout", 30),
            extraLanguages = run {
                val arr = o.optJSONArray("extra_languages")
                if (arr == null) emptyList()
                else (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
            },
            // 旧版本地 LLM 配置（protocol=local）已废弃，回退 OpenAI 兼容
            protocol = o.optString("protocol", "openai").takeIf { it in setOf("openai", "anthropic", "gemini") } ?: "openai",
        )

        fun listToJson(models: List<ModelConfig>): String {
            val arr = JSONArray()
            for (m in models) arr.put(m.toJson())
            return arr.toString()
        }

        fun listFromJson(json: String?): List<ModelConfig> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
