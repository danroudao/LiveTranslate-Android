package com.example.livetranslate.asr

import android.content.Context
import android.util.Log
import com.example.livetranslate.net.AsrResult
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * 本地 ASR 引擎 —— sherpa-onnx，支持 SenseVoice / Whisper（自动推断模型类型）。
 *
 * 模型目录约定（<externalFilesDir>/models/）：
 *   - sense-voice/: model.int8.onnx + tokens.txt（中/英/日/韩/粤）
 *   - whisper-tiny/、whisper-base/ 等：*encoder*.onnx + *decoder*.onnx + *tokens.txt（英文口音鲁棒性更好）
 */
class LocalAsrEngine(
    context: Context,
    modelDir: String,
    private val language: String = "auto",
    numThreads: Int = 4,
) : AsrEngine {

    companion object {
        private const val TAG = "LocalAsrEngine"

        /** 从目录推断模型类型：含 encoder*.onnx 为 whisper，否则 sense-voice */
        fun detectType(dir: String): String {
            val files = File(dir).listFiles() ?: return "sense-voice"
            return if (files.any { it.name.contains("encoder") && it.name.endsWith(".onnx") }) "whisper" else "sense-voice"
        }
    }

    private val recognizer: OfflineRecognizer

    init {
        val isWhisper = detectType(modelDir) == "whisper"
        val modelConfig: OfflineModelConfig
        if (isWhisper) {
            val encoder = File(modelDir).listFiles { f -> f.name.contains("encoder") && f.name.endsWith(".onnx") }!!.first()
            val decoder = File(modelDir).listFiles { f -> f.name.contains("decoder") && f.name.endsWith(".onnx") }!!.first()
            val tokens = File(modelDir).listFiles { f -> f.name.endsWith("tokens.txt") }!!.first()
            val whisper = OfflineWhisperModelConfig(
                encoder.absolutePath,
                decoder.absolutePath,
                language = "en",        // .en 模型仅英文（口音鲁棒）
                task = "transcribe",
                tailPaddings = 0,        // 段级识别无尾部 padding（避免 30s 窗口填充耗时）
                enableTokenTimestamps = false,
                enableSegmentTimestamps = false,
            )
            modelConfig = OfflineModelConfig(
                whisper = whisper,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
                tokens = tokens.absolutePath,
            )
            Log.i(TAG, "Whisper 本地引擎就绪: $modelDir (threads=$numThreads)")
        } else {
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
            modelConfig = OfflineModelConfig(
                senseVoice = senseVoice,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
                modelType = "sense-voice",
                tokens = tokens.absolutePath,
            )
            Log.i(TAG, "SenseVoice 本地引擎就绪: $modelDir (lang=$language, threads=$numThreads)")
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )
        recognizer = OfflineRecognizer(null, config)
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
