package com.example.livetranslate.asr

import android.content.Context
import android.util.Log
import com.example.livetranslate.net.AsrResult
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * 本地 ASR 引擎 —— sherpa-onnx SenseVoice（对应 asr_sensevoice.py）。
 *
 * 模型（官方 sherpa-onnx 导出版）：
 *   - model.int8.onnx（~240MB，int8 量化，中/英/日/韩/粤）
 *   - tokens.txt
 *
 * 模型目录约定：<externalFilesDir>/models/sense-voice/
 */
class LocalAsrEngine(
    context: Context,
    modelDir: String,
    private val language: String = "auto",
    numThreads: Int = 4,
) : AsrEngine {

    companion object {
        private const val TAG = "LocalAsrEngine"
    }

    private val recognizer: OfflineRecognizer

    init {
        val model = File(modelDir, "model.int8.onnx")
        val tokens = File(modelDir, "tokens.txt")
        require(model.exists()) { "模型文件不存在: ${model.absolutePath}" }
        require(tokens.exists()) { "词表文件不存在: ${tokens.absolutePath}" }

        // 注意：第 2 参是 language（非 tokens）；tokens.txt 由 sherpa-onnx 从模型同目录自动推断
        val senseVoice = OfflineSenseVoiceModelConfig(
            model.absolutePath,  // model
            language,            // language: auto/zh/en/ja/ko/yue
            false,               // useInverseTextNormalization
        )
        val modelConfig = OfflineModelConfig(
            senseVoice = senseVoice,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
            modelType = "sense-voice",
            tokens = tokens.absolutePath,
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )
        recognizer = OfflineRecognizer(null, config)
        Log.i(TAG, "SenseVoice 本地引擎就绪: $modelDir (lang=$language, threads=$numThreads)")
    }

    override fun transcribe(audio: FloatArray, langHint: String?): AsrResult? {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio, 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val text = result.text.trim()
            if (text.isEmpty()) return null
            return AsrResult(
                text = text,
                language = result.lang.ifBlank { language.ifBlank { "unknown" } },
                elapsed = 0.0,
            )
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer.release()
    }
}
