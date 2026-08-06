package com.example.livetranslate.net

import com.example.livetranslate.asr.AsrEngine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** ASR 结果 */
data class AsrResult(val text: String, val language: String, val elapsed: Double)

/**
 * 远程 ASR 客户端 —— 与 LiveTranslate asr_remote.py / asr_server.py 完全兼容。
 *
 * Wire protocol:
 *   POST /transcribe
 *   body = [uint32 lang_len][lang utf-8 bytes][float32 PCM 16kHz mono]
 *   resp = {"text","language","elapsed"}
 *   GET  /health -> {"status":"ok","model":...}
 */
class RemoteAsrClient(private val serverUrl: String, timeoutSec: Long = 60) : AsrEngine {

    private val base = serverUrl.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val octetMedia = "application/octet-stream".toMediaType()

    /** 服务器健康检查；不可达返回 null */
    fun health(): String? = try {
        val req = Request.Builder().url("$base/health").get().build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) JSONObject(resp.body!!.string()).optString("model") else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 转录一段 16kHz 单声道 float32 音频。
     * @param audio PCM float32 采样
     * @param langHint 语言提示（"auto"/null = 自动检测），如 "en"/"ja"
     */
    override fun transcribe(audio: FloatArray, langHint: String?): AsrResult? {
        val lang = if (langHint.isNullOrBlank() || langHint == "auto") "" else langHint
        val langBytes = lang.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + langBytes.size + audio.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(langBytes.size)
        buf.put(langBytes)
        val audioBuf = buf.asFloatBuffer()
        audioBuf.put(audio)
        val body = buf.array().toRequestBody(octetMedia)

        val req = Request.Builder()
            .url("$base/transcribe")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body!!.string())
            // 服务器对空音频返回 {"text": null}；org.json 的 optString 会把它变成 "null"
            if (json.isNull("text")) return null
            val text = json.optString("text").trim()
            if (text.isEmpty() || text == "null" || text == "None") return null
            return AsrResult(
                text = text,
                language = json.optString("language", "unknown"),
                elapsed = json.optDouble("elapsed", 0.0),
            )
        }
    }

    override fun close() {
        client.connectionPool.evictAll()
    }
}
