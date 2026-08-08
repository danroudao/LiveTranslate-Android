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

/**
 * 模型组 —— 管理页展示单元：一个引擎 = 一组必要文件，一键下载/删除。
 * 辅助文件（tokens.txt / decoder 等）随主文件自动下载，无需逐文件操作。
 */
data class ModelGroup(
    val key: String,          // 唯一标识（引擎名）
    val displayName: String,  // 直观名称（引擎 + 用途）
    val description: String,  // 说明（语种/特点/大小）
    val kind: String,         // 分类：asr（语音识别）/ llm（翻译）
    val files: List<ModelFile>,
) {
    val relDir: String get() = files.first().relDir
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** 组内已下载文件数（下载器状态判定在 UI 层注入） */
    fun downloadedCount(downloader: ModelDownloader): Int =
        files.count { downloader.status(it) is ModelDownloader.DownloadStatus.DONE }
}

object ModelRepository {

    // ---------- ASR：SenseVoice（多语种离线） ----------

    private val SENSE_VOICE_FILES = listOf(
        ModelFile(
            id = "sense-voice",
            fileName = "model.int8.onnx",
            urls = listOf(
                "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
                "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
            ),
            sizeBytes = 239_233_841,
            relDir = "models/sense-voice",
        ),
        ModelFile(
            id = "sense-voice",
            fileName = "tokens.txt",
            urls = listOf(
                "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
                "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
            ),
            sizeBytes = 315_894,
            relDir = "models/sense-voice",
        ),
    )

    // ---------- ASR：Whisper（英文口音鲁棒，辅助文件自动配套） ----------

    private fun whisperFiles(size: String, dir: String): List<ModelFile> = listOf(
        ModelFile(id = "whisper-$size", fileName = "$size.en-encoder.int8.onnx",
            urls = listOf(
                "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-$size.en/resolve/main/$size.en-encoder.int8.onnx?download=true",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$size.en/resolve/main/$size.en-encoder.int8.onnx?download=true",
            ), sizeBytes = if (size == "tiny") 12_937_772 else 29_120_534, relDir = dir),
        ModelFile(id = "whisper-$size", fileName = "$size.en-decoder.int8.onnx",
            urls = listOf(
                "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-$size.en/resolve/main/$size.en-decoder.int8.onnx?download=true",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$size.en/resolve/main/$size.en-decoder.int8.onnx?download=true",
            ), sizeBytes = if (size == "tiny") 4_425_606 else 130_669_978, relDir = dir),
        ModelFile(id = "whisper-$size", fileName = "$size.en-tokens.txt",
            urls = listOf(
                "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-$size.en/resolve/main/$size.en-tokens.txt?download=true",
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$size.en/resolve/main/$size.en-tokens.txt?download=true",
            ), sizeBytes = 835_554, relDir = dir),
    )

    // ---------- 翻译 LLM（Qwen3.5 GGUF） ----------

    private fun qwen3Gguf(size: String, sizeBytes: Long, dir: String): ModelFile = ModelFile(
        id = "qwen3.5-llm",
        fileName = "Qwen3.5-$size-Q4_K_M.gguf",
        urls = listOf(
            "https://hf-mirror.com/unsloth/Qwen3.5-$size-GGUF/resolve/main/Qwen3.5-$size-Q4_K_M.gguf?download=true",
            "https://huggingface.co/unsloth/Qwen3.5-$size-GGUF/resolve/main/Qwen3.5-$size-Q4_K_M.gguf?download=true",
        ),
        sizeBytes = sizeBytes,
        relDir = dir,
    )

    // ---------- 分组（管理页展示单元） ----------

    /** ASR 本地模型组 */
    val GROUP_SENSE_VOICE = ModelGroup(
        key = "sense-voice",
        displayName = "SenseVoice · 多语种",
        description = "中/英/日/韩/粤 · 离线快速 · 约 230MB",
        kind = "asr",
        files = SENSE_VOICE_FILES,
    )

    val GROUP_WHISPER_TINY = ModelGroup(
        key = "whisper-tiny",
        displayName = "Whisper Tiny · 英文快",
        description = "口音鲁棒 · 轻量约 18MB · 仅英文",
        kind = "asr",
        files = whisperFiles("tiny", "models/whisper-tiny"),
    )

    val GROUP_WHISPER_BASE = ModelGroup(
        key = "whisper-base",
        displayName = "Whisper Base · 英文准",
        description = "口音鲁棒 · 约 160MB · 仅英文",
        kind = "asr",
        files = whisperFiles("base", "models/whisper-base"),
    )

    /** 翻译 LLM 模型组 */
    val GROUP_LLM_08B = ModelGroup(
        key = "llm-0.8b",
        displayName = "Qwen3.5-0.8B · 翻译轻量",
        description = "约 0.4s/句（模拟器实测）· 0.5GB",
        kind = "llm",
        files = listOf(qwen3Gguf("0.8B", 532_517_120, "models/llm")),
    )

    val GROUP_LLM_2B = ModelGroup(
        key = "llm-2b",
        displayName = "Qwen3.5-2B · 翻译均衡",
        description = "约 0.85s/句（模拟器实测）· 1.2GB · 推荐",
        kind = "llm",
        files = listOf(qwen3Gguf("2B", 1_280_835_840, "models/llm")),
    )

    val GROUP_LLM_4B = ModelGroup(
        key = "llm-4b",
        displayName = "Qwen3.5-4B · 翻译高质量",
        description = "约 1.1s/句（模拟器实测）· 2.6GB",
        kind = "llm",
        files = listOf(qwen3Gguf("4B", 2_740_937_888, "models/llm")),
    )

    /** 管理页展示顺序：ASR 组在前，翻译 LLM 组在后 */
    val GROUPS: List<ModelGroup> = listOf(
        GROUP_SENSE_VOICE,
        GROUP_WHISPER_TINY,
        GROUP_WHISPER_BASE,
        GROUP_LLM_08B,
        GROUP_LLM_2B,
        GROUP_LLM_4B,
    )

    /** 兼容：全部文件（按组展开） */
    val ALL: List<ModelFile> get() = GROUPS.flatMap { it.files }
}
