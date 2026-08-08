package com.example.livetranslate.asr

import android.util.Log

/**
 * 本地 LLM 推理引擎 —— 内嵌 llama.cpp（JNI，libllmengine.so）。
 * 对应 llama-server 独立进程方案的 App 内嵌版：无 HTTP 开销、进程内常驻。
 *
 * 特性：
 * - LLAMA_LOAD_MODE_NONE 加载（规避 Android fscrypt 分区 mmap 缺页解密性能灾难）
 * - 进程级模型句柄缓存（同模型只加载一次，换模型自动加载）
 * - 串行生成（mutex 单槽语义）+ 流式 token 回调
 */
object LocalLlmEngine {

    private const val TAG = "LocalLlmEngine"
    private const val N_CTX = 1024
    private const val N_THREADS = 4

    init {
        try {
            System.loadLibrary("llmengine")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libllmengine 加载失败: ${e.message}")
        }
    }

    private external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeChat(handle: Long, system: String, user: String, cb: TokenCallback?): String?
    private external fun nativeClose(handle: Long)

    interface TokenCallback {
        fun onToken(t: String)
    }

    private val engines = HashMap<String, Long>()

    /** 模型是否已加载（避免重复初始化） */
    fun isModelLoaded(modelPath: String): Boolean = synchronized(engines) { engines.containsKey(modelPath) }

    fun isLoaded(): Boolean = synchronized(engines) { engines.isNotEmpty() }

    /** 阻塞生成（调用方放后台线程）。返回完整译文，失败返回 null（onError 说明原因）。 */
    fun chat(
        modelPath: String,
        system: String,
        user: String,
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit = {},
    ): String? {
        val handle = synchronized(engines) {
            engines.getOrPut(modelPath) {
                val h = nativeInit(modelPath, N_CTX, N_THREADS)
                if (h == 0L) {
                    engines.remove(modelPath)
                    throw IllegalStateException("nativeInit 失败")
                }
                h
            }
        }
        return try {
            val result = nativeChat(handle, system, user, object : TokenCallback {
                override fun onToken(t: String) = onPartial(t)
            })
            result?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            Log.w(TAG, "chat failed: ${e.message}")
            onError(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /** 释放全部引擎（切换模型前可调用） */
    fun closeAll() {
        synchronized(engines) {
            for ((_, h) in engines) {
                try {
                    nativeClose(h)
                } catch (e: Throwable) {
                }
            }
            engines.clear()
        }
    }
}
