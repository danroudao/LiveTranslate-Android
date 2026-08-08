package com.example.livetranslate.net

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 多协议 LLM 翻译客户端 —— 支持三种 API 格式（对应 DMX 等聚合平台文档的接入方式）：
 *
 * 1. OpenAI 兼容（deepseek / openai / DMX 中转等）：POST /chat/completions + SSE data:
 * 2. Anthropic（Claude 直连）：POST /v1/messages + x-api-key + SSE content_block_delta
 * 3. Gemini（Google 直连）：POST /v1beta/models/{m}:streamGenerateContent?alt=sse + x-goog-api-key
 *
 * 通用能力：流式逐字 / 上下文历史 / 重复检测 / 400 参数降级重试 / JSON null 防御。
 */
class LlmTranslator(
    val apiBase: String,
    val apiKey: String,
    val model: String,
    val targetLanguage: String = "zh",
    val maxTokens: Int = 256,
    val temperature: Double = 0.3,
    val streaming: Boolean = true,
    val systemPrompt: String? = null,
    val noSystemRole: Boolean = false,
    val noThink: Boolean = true,
    val jsonResponse: Boolean = false,
    val contextTurns: Int = 0,
    val timeoutSec: Long = 30,
    val protocol: String = "openai",  // "openai" | "anthropic" | "gemini"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 上下文历史：(原文, 译文)，最多保留 contextTurns + 2 */
    private val history = ArrayDeque<Pair<String, String>>()

    companion object {
        private const val TAG = "LlmTranslator"

        val LANGUAGE_DISPLAY = mapOf(
            "en" to "English", "ja" to "Japanese", "zh" to "Chinese", "ko" to "Korean",
            "fr" to "French", "de" to "German", "es" to "Spanish", "ru" to "Russian",
            "th" to "Thai", "vi" to "Vietnamese", "id" to "Indonesian", "ar" to "Arabic",
        )

        val DEFAULT_PROMPT = """
            You are a real-time subtitle translator. Translate {source_lang} into {target_lang}.
            Rules:
            - Output ONLY one single best translation, nothing else.
            - Never include alternatives, parenthetical options, annotations, or explanations.
            - Keep proper nouns, names, and brand names untranslated.
            - Keep subtitles fluent and natural; avoid overly literal or stiff phrasing.
            - Auto-correct likely ASR errors based on context and common sense.
        """.trimIndent()

        /** 常见平台接入模板（对应 DMX 文档展示的接入方式） */
        val PLATFORM_PRESETS = mapOf(
            "DMX 中转" to Triple("https://www.dmxapi.cn/v1", "openai", "openai"),
            "DeepSeek" to Triple("https://api.deepseek.com/v1", "openai", "deepseek-chat"),
            "OpenAI" to Triple("https://api.openai.com/v1", "openai", "gpt-4o-mini"),
            "Anthropic Claude" to Triple("https://api.anthropic.com", "anthropic", "claude-sonnet-4-5"),
            "Google Gemini" to Triple("https://generativelanguage.googleapis.com", "gemini", "gemini-2.5-flash"),
        )
    }

    fun withTargetLanguage(lang: String): LlmTranslator =
        LlmTranslator(apiBase, apiKey, model, lang, maxTokens, temperature, streaming,
            systemPrompt, noSystemRole, noThink, jsonResponse, contextTurns, timeoutSec, protocol)

    private fun buildSystemPrompt(sourceLang: String): String {
        val src = LANGUAGE_DISPLAY[sourceLang] ?: sourceLang
        val tgt = LANGUAGE_DISPLAY[targetLanguage] ?: targetLanguage
        return (systemPrompt ?: DEFAULT_PROMPT)
            .replace("{source_lang}", src)
            .replace("{target_lang}", tgt)
            .replace("{context}", "")
    }

    // ---------- 请求构造（按协议） ----------

    /** OpenAI 兼容：POST /chat/completions */
    private fun buildOpenAiBody(systemPrompt: String, text: String, stream: Boolean, useJsonSchema: Boolean, useNoThink: Boolean): JSONObject {
        val messages = JSONArray()
        if (noSystemRole) {
            messages.put(JSONObject().put("role", "user").put("content", "$systemPrompt\n$text"))
        } else {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            if (contextTurns > 0 && history.isNotEmpty()) {
                for ((src, tgt) in history.takeLast(contextTurns)) {
                    messages.put(JSONObject().put("role", "user").put("content", src))
                    messages.put(JSONObject().put("role", "assistant").put("content", tgt))
                }
            }
            messages.put(JSONObject().put("role", "user").put("content", text))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .put("stream", stream)
        if (useNoThink) {
            // 顶层 enable_thinking：DMX/Qwen 系有效（extra_body 对 DMX 无效，实测）
            body.put("enable_thinking", false)
            // extra_body：DeepSeek 旧接口风格（两者都发最大化兼容）
            body.put("extra_body", JSONObject().put("enable_thinking", false))
            // chat_template_kwargs：本地 llama.cpp / llama-server（Qwen 模板）唯一识别的关思考参数
            body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        }
        if (useJsonSchema) {
            body.put("response_format", JSONObject()
                .put("type", "json_schema")
                .put("json_schema", JSONObject()
                    .put("name", "translation")
                    .put("strict", true)
                    .put("schema", JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("t", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("t"))
                        .put("additionalProperties", false))))
        }
        return body
    }

    /** Anthropic：POST /v1/messages */
    private fun buildAnthropicBody(systemPrompt: String, text: String, stream: Boolean): JSONObject {
        val messages = JSONArray()
        if (contextTurns > 0 && history.isNotEmpty()) {
            for ((src, tgt) in history.takeLast(contextTurns)) {
                messages.put(JSONObject().put("role", "user").put("content", src))
                messages.put(JSONObject().put("role", "assistant").put("content", tgt))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", text))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .put("stream", stream)
            .put("messages", messages)
        if (systemPrompt.isNotBlank()) {
            body.put("system", systemPrompt)
        }
        return body
    }

    /** Gemini：POST /v1beta/models/{model}:streamGenerateContent */
    private fun buildGeminiBody(systemPrompt: String, text: String): JSONObject {
        val body = JSONObject()
        if (systemPrompt.isNotBlank()) {
            body.put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
        }
        body.put("contents", JSONArray().put(JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))))
        body.put("generationConfig", JSONObject()
            .put("temperature", temperature)
            .put("maxOutputTokens", maxTokens))
        return body
    }

    // ---------- 响应解析（按协议） ----------

    /** OpenAI SSE 解析结果：文本 + 是否出现过思考内容 */
    private data class OpenAiSse(val text: String, val sawReasoning: Boolean)

    /** OpenAI SSE：data: {...choices[].delta.content} */
    private fun parseOpenAiSse(raw: java.io.Reader, jsonResponse: Boolean, onPartial: (String) -> Unit): OpenAiSse {
        val sb = StringBuilder()
        var sawReasoning = false
        val reader = raw.buffered()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line!!
            if (!l.startsWith("data:")) continue
            val payload = l.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            try {
                val chunk = JSONObject(payload)
                val choices = chunk.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                    // 思考型模型（qwen3.5-flash 等）：内容在 reasoning_content，content 为 JSON null
                    if (delta != null && delta.has("reasoning_content") && !delta.isNull("reasoning_content") &&
                        delta.optString("reasoning_content").isNotEmpty()
                    ) {
                        sawReasoning = true
                    }
                    // content 显式判空（optString 会把 JSON null 变成 "null"）
                    val content = if (delta == null || delta.isNull("content")) null else delta.optString("content")
                    if (!content.isNullOrEmpty()) {
                        sb.append(content)
                        if (!jsonResponse) onPartial(sb.toString())
                    }
                }
            } catch (e: Exception) {
            }
        }
        return OpenAiSse(sb.toString().trim(), sawReasoning)
    }

    /** Anthropic SSE：event: content_block_delta → data: {delta:{text}} */
    private fun parseAnthropicSse(raw: java.io.Reader, onPartial: (String) -> Unit): String {
        val sb = StringBuilder()
        val reader = raw.buffered()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line!!
            if (!l.startsWith("data:")) continue
            val payload = l.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            try {
                val evt = JSONObject(payload)
                when (evt.optString("type")) {
                    "content_block_delta" -> {
                        val text = evt.optJSONObject("delta")?.optString("text") ?: ""
                        if (text.isNotEmpty()) {
                            sb.append(text)
                            onPartial(sb.toString())
                        }
                    }
                    // Anthropic 流结束标记
                    "message_stop" -> break
                    "error" -> break
                }
            } catch (e: Exception) {
            }
        }
        return sb.toString().trim()
    }

    /** Gemini SSE：data: {candidates[].content.parts[].text} */
    private fun parseGeminiSse(raw: java.io.Reader, onPartial: (String) -> Unit): String {
        val sb = StringBuilder()
        val reader = raw.buffered()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line!!
            if (!l.startsWith("data:")) continue
            val payload = l.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            try {
                val evt = JSONObject(payload)
                val candidates = evt.optJSONArray("candidates")
                if (candidates != null && candidates.length() == 0) {
                    break  // Gemini 流结束标记
                }
                if (candidates != null && candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0)
                        .optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val text = parts.getJSONObject(i).optString("text")
                            if (text.isNotEmpty()) {
                                sb.append(text)
                                onPartial(sb.toString())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
        return sb.toString().trim()
    }

    /** 重复循环检测（≥2 次完整循环） */
    private fun checkRepetition(text: String): Boolean {
        if (text.length < 40) return false
        for (plen in 8..text.length / 3) {
            if (text.regionMatches(plen, text, 0, plen) &&
                text.regionMatches(plen * 2, text, 0, plen)
            ) {
                return true
            }
        }
        return false
    }

    private fun extractJsonTranslation(raw: String): String {
        return try {
            val json = JSONObject(raw)
            if (json.has("t")) json.getString("t") else raw
        } catch (e: Exception) {
            raw
        }
    }

    private fun appendHistory(source: String, result: String) {
        if (contextTurns <= 0 || result.isEmpty()) return
        history.addLast(source to result)
        while (history.size > contextTurns + 2) history.removeFirst()
    }

    /** 结果统一收尾：空/重复检查 + 历史 + 回调 */
    private fun finalizeResult(raw: String, originalText: String, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        var result = raw.trim()
        if (jsonResponse && protocol == "openai") result = extractJsonTranslation(result)
        if (result.isBlank() || result == "null") {
            onError("API 返回空内容（模型可能还在思考或请求参数不兼容）")
        } else if (checkRepetition(result)) {
            onError("翻译结果疑似重复，请检查模型输出")
        } else {
            appendHistory(originalText, result)
            onFinal(result)
        }
    }

    /**
     * 翻译文本（按协议分发）。
     */
    fun translateStreaming(
        text: String,
        sourceLang: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val systemPrompt = buildSystemPrompt(sourceLang)
        try {
            when (protocol) {
                "anthropic" -> translateAnthropic(systemPrompt, text, sourceLang, onPartial, onFinal, onError)
                "gemini" -> translateGemini(systemPrompt, text, sourceLang, onPartial, onFinal, onError)
                else -> translateOpenAi(systemPrompt, text, sourceLang, onPartial, onFinal, onError)
            }
        } catch (e: Exception) {
            onError(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- OpenAI 协议 ----------

    private fun translateOpenAi(
        systemPrompt: String, text: String, sourceLang: String,
        onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit,
    ) {
        // 兼容降级：400 且含 response_format 相关报错时去掉该参数重试（enable_thinking 全平台兼容，保留）
        var useJsonSchema = jsonResponse
        var useNoThink = noThink
        var attempt = 0
        while (true) {
            attempt++
            val req = Request.Builder()
                .url(apiBase.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(buildOpenAiBody(systemPrompt, text, streaming, useJsonSchema, useNoThink).toString().toRequestBody(jsonMedia))
                .build()

            val shouldRetry = arrayOf(false)
            val retryReason = arrayOf("")
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string() ?: ""
                        if (attempt < 2 && resp.code == 400 &&
                            (errBody.contains("response_format") || errBody.contains("json_schema") ||
                             errBody.contains("json_object"))
                        ) {
                            shouldRetry[0] = true
                            Log.w(TAG, "response_format 不被支持（HTTP 400），降级重试: ${errBody.take(120)}")
                            return@use
                        }
                        onError("HTTP ${resp.code}: ${errBody.take(200)}")
                        return
                    }
                    val raw = resp.body!!.charStream()

                    if (!streaming) {
                        val json = JSONObject(raw.readText())
                        val message = json.optJSONArray("choices")
                            ?.optJSONObject(0)?.optJSONObject("message")
                        val content = if (message == null || message.isNull("content")) ""
                                      else message.optString("content")
                        if (content.isBlank() && attempt < 3) {
                            // 非流式空内容：可能是思考型模型（reasoning_content 耗尽 max_tokens）
                            val sawReasoning = message != null && message.has("reasoning_content") &&
                                !message.isNull("reasoning_content") &&
                                message.optString("reasoning_content").isNotEmpty()
                            if (sawReasoning) {
                                Log.w(TAG, "思考型模型无输出内容，强制关闭思考重试")
                                retryReason[0] = "nothink"
                                return@use
                            }
                            if (attempt < 2) {
                                retryReason[0] = "empty"
                                return@use
                            }
                        }
                        finalizeResult(content, text, onFinal, onError)
                        return
                    }

                    val parsed = parseOpenAiSse(raw, jsonResponse, onPartial)
                    if (parsed.text.isBlank() && attempt < 3) {
                        if (parsed.sawReasoning) {
                            // 思考型模型且无最终内容（思考耗尽 max_tokens）：强制关闭思考重试
                            Log.w(TAG, "思考型模型无输出内容，强制关闭思考重试")
                            retryReason[0] = "nothink"
                            return@use
                        }
                        if (attempt < 2) {
                            retryReason[0] = "empty"
                            return@use
                        }
                    }
                    finalizeResult(parsed.text, text, onFinal, onError)
                }
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
                return
            }
            if (shouldRetry[0]) {
                // 降级：仅去掉 response_format（enable_thinking 保留，否则回到思考模式导致超时/空内容）
                useJsonSchema = false
                continue
            }
            if (retryReason[0] == "nothink") {
                useNoThink = true
                continue
            }
            if (retryReason[0] == "empty") {
                continue
            }
            return
        }
    }

    // ---------- Anthropic 协议 ----------

    private fun translateAnthropic(
        systemPrompt: String, text: String, sourceLang: String,
        onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit,
    ) {
        val req = Request.Builder()
            .url(apiBase.trimEnd('/') + "/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(buildAnthropicBody(systemPrompt, text, streaming).toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                onError("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                return
            }
            if (!streaming) {
                // 一次性响应：content[].text
                val json = JSONObject(resp.body!!.string())
                val content = json.optJSONArray("content")
                    ?.optJSONObject(0)?.optString("text") ?: ""
                finalizeResult(content, text, onFinal, onError)
                return
            }
            val result = parseAnthropicSse(resp.body!!.charStream(), onPartial)
            finalizeResult(result, text, onFinal, onError)
        }
    }

    // ---------- Gemini 协议 ----------

    private fun translateGemini(
        systemPrompt: String, text: String, sourceLang: String,
        onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit,
    ) {
        val url = if (streaming) {
            "${apiBase.trimEnd('/')}/v1beta/models/${model}:streamGenerateContent?alt=sse"
        } else {
            "${apiBase.trimEnd('/')}/v1beta/models/${model}:generateContent"
        }
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(buildGeminiBody(systemPrompt, text).toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                onError("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                return
            }
            if (!streaming) {
                val json = JSONObject(resp.body!!.string())
                val textOut = json.optJSONArray("candidates")
                    ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?.optJSONObject(0)?.optString("text") ?: ""
                finalizeResult(textOut, text, onFinal, onError)
                return
            }
            val result = parseGeminiSse(resp.body!!.charStream(), onPartial)
            finalizeResult(result, text, onFinal, onError)
        }
    }

    /** 一次性（非流式）翻译 */
    fun translate(text: String, sourceLang: String): String? {
        var result: String? = null
        translateStreaming(text, sourceLang, {}, { result = it }, {})
        return result
    }
}
