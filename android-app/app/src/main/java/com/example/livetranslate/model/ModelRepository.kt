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

    /** 模型管理页展示的全部条目 */
    val ALL: List<ModelFile> = SENSE_VOICE + SILERO_VAD
}
