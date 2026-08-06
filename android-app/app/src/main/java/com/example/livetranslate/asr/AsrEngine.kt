package com.example.livetranslate.asr

import com.example.livetranslate.net.AsrResult

/**
 * ASR 引擎统一接口 —— 远程 / 本地引擎可无缝切换（对应 asr_client.py 的接口抽象）。
 */
interface AsrEngine {
    /** 转录一段 16kHz 单声道 float32 音频 */
    fun transcribe(audio: FloatArray, langHint: String? = null): AsrResult?

    /** 释放资源 */
    fun close() {}
}
