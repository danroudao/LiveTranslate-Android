package com.example.livetranslate.model

/**
 * 模型文件定义 —— 对应原项目 model_manager.py 的 ASR_MODEL_IDS / FUNASR_MODEL_PROFILES。
 * 多下载源（国内 hf-mirror 优先，HuggingFace 兜底）。
 */
data class ModelFile(
    val id: String,          // 模型组 id
    val fileName: String,    // 文件名
    val urls: List<String>,  // 下载源（按顺序尝试）
    val sizeBytes: Long,     // 预期大小（校验用）
    val relDir: String,      // 相对 filesDir 的目录
)

object ModelRepository {

    const val SENSE_VOICE_BASE_HF = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
    const val SENSE_VOICE_BASE_HF_ORG = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"

    /** SenseVoice int8 主模型 + 词表（LocalAsrEngine 使用，目录约定 models/sense-voice/） */
    val SENSE_VOICE = listOf(
        ModelFile(
            id = "sense-voice",
            fileName = "model.int8.onnx",
            urls = listOf(
                "$SENSE_VOICE_BASE_HF/model.int8.onnx",
                "$SENSE_VOICE_BASE_HF_ORG/model.int8.onnx",
            ),
            sizeBytes = 239_233_841,
            relDir = "models/sense-voice",
        ),
        ModelFile(
            id = "sense-voice",
            fileName = "tokens.txt",
            urls = listOf(
                "$SENSE_VOICE_BASE_HF/tokens.txt",
                "$SENSE_VOICE_BASE_HF_ORG/tokens.txt",
            ),
            sizeBytes = 315_894,
            relDir = "models/sense-voice",
        ),
    )

    /** Silero VAD 模型（SileroVadEngine 从 assets 读取；下载版用于模型管理页展示） */
    val SILERO_VAD = ModelFile(
        id = "silero-vad",
        fileName = "silero_vad.onnx",
        urls = listOf(
            "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx",
            "https://hf-mirror.com/snakers4/silero-vad/resolve/main/src/silero_vad/data/silero_vad.onnx",
        ),
        sizeBytes = 2_327_524,
        relDir = "models",
    )

    // ---------- 本地 LLM（Qwen3.5 GGUF，目录约定 models/llm/） ----------

    /** Qwen3.5 GGUF 下载源（unsloth 量化版，hf-mirror 优先） */
    private fun qwen3Gguf(size: String, sizeBytes: Long): ModelFile = ModelFile(
        id = "qwen3.5-llm",
        fileName = "Qwen3.5-$size-Q4_K_M.gguf",
        urls = listOf(
            "https://hf-mirror.com/unsloth/Qwen3.5-$size-GGUF/resolve/main/Qwen3.5-$size-Q4_K_M.gguf?download=true",
            "https://huggingface.co/unsloth/Qwen3.5-$size-GGUF/resolve/main/Qwen3.5-$size-Q4_K_M.gguf?download=true",
        ),
        sizeBytes = sizeBytes,
        relDir = "models/llm",
    )

    /** 本地翻译 LLM 模型（Qwen3.5 0.8B/2B/4B Q4_K_M，实测模拟器 426ms/848ms/1092ms 每句） */
    val LLM = listOf(
        qwen3Gguf("0.8B", 532_517_120),
        qwen3Gguf("2B", 1_280_835_840),
        qwen3Gguf("4B", 2_740_937_888),
    )

    /** 模型管理页展示的全部条目 */
    val ALL: List<ModelFile> = SENSE_VOICE + SILERO_VAD + LLM
}
