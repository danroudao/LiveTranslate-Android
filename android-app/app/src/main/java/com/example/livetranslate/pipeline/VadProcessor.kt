package com.example.livetranslate.pipeline

import android.util.Log
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * VAD 状态机 —— vad_processor.py (387 行) 的 Kotlin 完整移植。
 *
 * 保留全部行为：
 *  - 三模式：silero（ONNX）/ energy（RMS）/ disabled
 *  - 渐进静音：buffer 越长接受越短的停顿切分（<3s=full, 3-6s=half, 6-10s=quarter）
 *  - 自适应静音：追踪最近停顿，P75 × 1.2，钳制在 0.3s~2.0s
 *  - 回溯切分：max_duration 时在平滑置信度曲线上找最低谷切分，余量保留到下一段
 *  - 语音密度过滤：<25% chunk 高于阈值 → 丢弃
 *  - 短段合并：低于 min_speech_duration 不丢弃，软复位保留 buffer 与下段语音合并
 *  - pre-speech 环缓冲（3 chunk ≈ 96ms）捕获起始辅音
 *  - trim_front / force_flush / peek_buffer 接口（interim ASR 用）
 */
class VadProcessor(
    val sampleRate: Int = 16000,
    var threshold: Double = 0.50,
    var minSpeechDuration: Double = 1.0,
    var maxSpeechDuration: Double = 8.0,
    val chunkDuration: Double = 0.032,
) {
    companion object {
        private const val TAG = "VadProcessor"
        private const val PRE_SPEECH_CHUNKS = 3
        private const val ADAPTIVE_MIN = 0.3
        private const val ADAPTIVE_MAX = 2.0
        private val PROGRESSIVE_TIERS: List<Pair<Double, Double>> = listOf(
            Pair(3.0, 1.0),   // < 3s: full silence limit
            Pair(6.0, 0.5),   // 3-6s: half
            Pair(10.0, 0.25), // 6-10s: quarter
        )
    }

    var mode = "silero"  // "silero" | "energy" | "disabled"
    var energyThreshold = 0.02
    var silenceMode = "auto"  // "auto" | "fixed"
    var fixedSilenceDur = 0.8

    /** Silero ONNX 引擎（mode=silero 时必需） */
    var silero: SileroVadEngine? = null
    private var debugFrameCount = 0

    private val minSpeechSamples = (minSpeechDuration * sampleRate).toInt()
    private val maxSpeechSamples = (maxSpeechDuration * sampleRate).toInt()

    private val speechBuffer = ArrayList<FloatArray>()
    private val confidenceHistory = ArrayList<Double>()
    private var speechSamplesInternal = 0
    private var isSpeakingInternal = false
    private var silenceCounter = 0
    private var wasTrimmed = false  // trim_front 后为 true（interim 活动）

    private val preBuffer = ArrayDeque<FloatArray>()  // max PRE_SPEECH_CHUNKS

    private var silenceLimit = secondsToChunks(0.8)
    private val pauseHistory = ArrayDeque<Double>()  // max 50

    var lastConfidence = 0.0
        private set

    /** 是否正在说话（interim 调度用） */
    val isSpeaking: Boolean get() = isSpeakingInternal

    /** 当前缓冲样本数（interim 计时用） */
    val speechSamples: Int get() = speechSamplesInternal

    private fun secondsToChunks(seconds: Double): Int =
        maxOf(1, (seconds / chunkDuration).roundToInt())

    // ---------- 配置 ----------

    fun updateSettings(
        vadMode: String? = null,
        vadThreshold: Double? = null,
        energyThreshold: Double? = null,
        minSpeech: Double? = null,
        maxSpeech: Double? = null,
        silenceMode: String? = null,
        silenceDuration: Double? = null,
    ) {
        vadMode?.let { mode = it }
        vadThreshold?.let { threshold = it }
        energyThreshold?.let { this.energyThreshold = it }
        minSpeech?.let { /* 保持简单，运行中不调整 */ }
        maxSpeech?.let { /* 同上 */ }
        silenceMode?.let { this.silenceMode = it }
        silenceDuration?.let {
            fixedSilenceDur = it
            if (this.silenceMode == "fixed") silenceLimit = secondsToChunks(it)
        }
        Log.i(TAG, "settings: mode=$mode threshold=$threshold energy=$energyThreshold silence=$silenceMode($silenceLimit chunks)")
    }

    // ---------- 置信度 ----------

    private fun getConfidence(chunk: FloatArray): Double {
        // 能量兜底：极低 RMS 直接判静音（防底噪被 silero 误判为语音）
        var sum = 0.0
        for (s in chunk) sum += s * s
        val rms = sqrt(sum / chunk.size)
        if (debugFrameCount % 50 == 0) {
            Log.i(TAG, "rms=${String.format("%.5f", rms)} (frame $debugFrameCount)")
        }
        if (rms < energyThreshold * 0.3) return 0.0
        return when (mode) {
            "silero" -> {
                val eng = silero ?: return 0.0
                try {
                    val prob = eng.predict(chunk, sampleRate).toDouble()
                    if (debugFrameCount % 50 == 0) {
                        Log.i(TAG, "silero confidence: ${"%.3f".format(prob)} (frame $debugFrameCount)")
                    }
                    debugFrameCount++
                    prob
                } catch (e: Exception) {
                    Log.w(TAG, "silero predict failed: ${e.message}")
                    0.0
                }
            }
            "energy" -> {
                minOf(1.0, rms / (energyThreshold * 2))
            }
            else -> 1.0  // disabled
        }
    }

    private val effectiveThreshold: Double get() = if (mode == "silero") threshold else 0.5

    // ---------- 静音策略 ----------

    private fun getEffectiveSilenceLimit(): Int {
        val bufSeconds = speechSamplesInternal.toDouble() / sampleRate
        var multiplier = 1.0
        for ((tierSec, tierMult) in PROGRESSIVE_TIERS) {
            if (bufSeconds < tierSec) break
            multiplier = tierMult
        }
        return maxOf(1, (silenceLimit * multiplier).roundToInt())
    }

    private fun updateAdaptiveLimit() {
        if (pauseHistory.size < 3) return
        val pauses = pauseHistory.sorted()
        val idx = (pauses.size * 0.75).toInt()
        val p75 = pauses[minOf(idx, pauses.size - 1)]
        val target = maxOf(ADAPTIVE_MIN, minOf(ADAPTIVE_MAX, p75 * 1.2))
        val newLimit = secondsToChunks(target)
        if (newLimit != silenceLimit) {
            Log.d(TAG, "adaptive silence: ${"%.2f".format(target)}s ($newLimit chunks), P75=${"%.2f".format(p75)}")
            silenceLimit = newLimit
        }
    }

    // ---------- 主入口 ----------

    /**
     * 处理 32ms chunk，返回完整语音段（16kHz mono float32）或 null。
     */
    fun processChunk(chunk: FloatArray): FloatArray? {
        val confidence = getConfidence(chunk)
        lastConfidence = confidence
        val effSilenceLimit = getEffectiveSilenceLimit()

        if (confidence >= effectiveThreshold) {
            // 记录停顿时长（自适应模式）
            if (isSpeakingInternal && silenceCounter > 0) {
                val pauseDur = silenceCounter * chunkDuration
                if (pauseDur >= 0.1) {
                    pauseHistory.addLast(pauseDur)
                    if (pauseHistory.size > 50) pauseHistory.removeFirst()
                    if (silenceMode == "auto") updateAdaptiveLimit()
                }
            }
            if (!isSpeakingInternal) {
                // 语音起始：把 pre-speech 缓冲并入（用阈值作置信度避免假低谷）
                while (preBuffer.isNotEmpty()) {
                    val pre = preBuffer.removeFirst()
                    speechBuffer.add(pre)
                    confidenceHistory.add(effectiveThreshold)
                    speechSamplesInternal += pre.size
                }
            }
            isSpeakingInternal = true
            silenceCounter = 0
            speechBuffer.add(chunk)
            confidenceHistory.add(confidence)
            speechSamplesInternal += chunk.size
        } else if (isSpeakingInternal) {
            silenceCounter++
            speechBuffer.add(chunk)
            confidenceHistory.add(confidence)
            speechSamplesInternal += chunk.size
        } else {
            // 未说话：喂 pre-speech 环缓冲
            preBuffer.addLast(chunk)
            while (preBuffer.size > PRE_SPEECH_CHUNKS) preBuffer.removeFirst()
        }

        // 达到最大时长 → 回溯找最佳切分点
        if (speechSamplesInternal >= maxSpeechSamples) {
            return splitAtBestPause()
        }

        // 静音足够 → 结束段
        if (isSpeakingInternal && silenceCounter >= effSilenceLimit) {
            if (speechSamplesInternal >= minSpeechSamples) {
                return flushSegment()
            } else if (wasTrimmed) {
                // interim ASR 裁剪过：返回剩余部分而非丢弃
                Log.d(TAG, "short segment after trim (${"%.1f".format(speechSamplesInternal / sampleRate.toDouble())}s), force flush")
                return forceFlush()
            } else {
                // 太短：保留 buffer，与下一次语音合并
                Log.d(TAG, "short segment ${"%.1f".format(speechSamplesInternal / sampleRate.toDouble())}s < min, keeping for merge")
                isSpeakingInternal = false
                silenceCounter = 0
                return null
            }
        }
        return null
    }

    // ---------- 回溯切分 ----------

    private fun findBestSplitIndex(): Int {
        val n = confidenceHistory.size
        if (n < 4) return -1

        // 滑动窗口平滑（~160ms = 5 chunks @32ms）
        val smoothWin = minOf(5, n / 2)
        val smoothed = ArrayList<Double>(n)
        for (i in 0 until n) {
            val lo = maxOf(0, i - smoothWin / 2)
            val hi = minOf(n, i + smoothWin / 2 + 1)
            var sum = 0.0
            for (j in lo until hi) sum += confidenceHistory[j]
            smoothed.add(sum / (hi - lo))
        }

        // 在后 70% 搜索（避免过早切分）
        val searchStart = maxOf(1, n * 3 / 10)

        var minVal = Double.POSITIVE_INFINITY
        var minIdx = -1
        for (i in searchStart until n) {
            if (smoothed[i] <= minVal) {
                minVal = smoothed[i]
                minIdx = i
            }
        }
        if (minIdx <= 0) return -1

        // 检查是否为有意义的低谷
        var avgSum = 0.0
        for (i in searchStart until n) avgSum += smoothed[i]
        val avgConf = avgSum / maxOf(1, n - searchStart)
        val dipRatio = minVal / maxOf(avgConf, 1e-6)

        if (minVal < effectiveThreshold || dipRatio < 0.8) {
            Log.d(TAG, "split at $minIdx/$n: smoothed=${"%.3f".format(minVal)} avg=${"%.3f".format(avgConf)} dip=${"%.2f".format(dipRatio)}")
            return minIdx
        }
        if (minVal < avgConf) {
            Log.d(TAG, "split (fallback) at $minIdx/$n: ${"%.3f".format(minVal)} < avg ${"%.3f".format(avgConf)}")
            return minIdx
        }
        return -1
    }

    private fun splitAtBestPause(): FloatArray? {
        if (speechBuffer.isEmpty()) return null
        val splitIdx = findBestSplitIndex()

        if (splitIdx <= 0) {
            Log.i(TAG, "max duration, no good split, hard flush ${"%.1f".format(speechSamplesInternal / sampleRate.toDouble())}s")
            return flushSegment()
        }

        // 切分：发出前半，保留后半继续累积
        val firstBufs = ArrayList(speechBuffer.subList(0, splitIdx))
        val remainBufs = ArrayList(speechBuffer.subList(splitIdx, speechBuffer.size))
        val remainConfs = ArrayList(confidenceHistory.subList(splitIdx, confidenceHistory.size))

        var firstSamples = 0
        for (b in firstBufs) firstSamples += b.size
        var remainSamples = 0
        for (b in remainBufs) remainSamples += b.size
        Log.i(TAG, "max duration split at ${"%.1f".format(firstSamples / sampleRate.toDouble())}s, keep ${"%.1f".format(remainSamples / sampleRate.toDouble())}s")

        val segment = concat(firstBufs, firstSamples)

        speechBuffer.clear()
        speechBuffer.addAll(remainBufs)
        confidenceHistory.clear()
        confidenceHistory.addAll(remainConfs)
        speechSamplesInternal = remainSamples
        isSpeakingInternal = true
        silenceCounter = 0
        return segment
    }

    // ---------- 输出 ----------

    private fun flushSegment(): FloatArray? {
        if (speechBuffer.isEmpty()) return null
        // 语音密度过滤
        if (confidenceHistory.size >= 4) {
            var voiced = 0
            for (c in confidenceHistory) if (c >= effectiveThreshold) voiced++
            val density = voiced.toDouble() / confidenceHistory.size
            if (density < 0.25) {
                val dur = speechSamplesInternal / sampleRate.toDouble()
                Log.d(TAG, "low density ${(density * 100).toInt()}% ($voiced/${confidenceHistory.size}), discard ${"%.1f".format(dur)}s")
                reset()
                return null
            }
        }
        val segment = concat(speechBuffer, speechSamplesInternal)
        reset()
        return segment
    }

    private fun concat(bufs: List<FloatArray>, total: Int): FloatArray {
        val out = FloatArray(total)
        var off = 0
        for (b in bufs) {
            System.arraycopy(b, 0, out, off, b.size)
            off += b.size
        }
        return out
    }

    private fun reset() {
        speechBuffer.clear()
        confidenceHistory.clear()
        speechSamplesInternal = 0
        isSpeakingInternal = false
        silenceCounter = 0
        wasTrimmed = false
    }

    // ---------- interim 接口 ----------

    /** 读取当前缓冲不切段。返回 (audio, duration) 或 null */
    fun peekBuffer(): Pair<FloatArray, Double>? {
        if (speechBuffer.isEmpty() || !isSpeakingInternal) return null
        val audio = concat(speechBuffer, speechSamplesInternal)
        return audio to (speechSamplesInternal.toDouble() / sampleRate)
    }

    /** 移除缓冲前 n_samples 样本 */
    fun trimFront(nSamples: Int) {
        if (nSamples <= 0) return
        var removed = 0
        while (speechBuffer.isNotEmpty() && removed < nSamples) {
            val chunk = speechBuffer[0]
            if (removed + chunk.size <= nSamples) {
                speechBuffer.removeAt(0)
                if (confidenceHistory.isNotEmpty()) confidenceHistory.removeAt(0)
                removed += chunk.size
            } else {
                val keep = removed + chunk.size - nSamples
                val keepArr = chunk.copyOfRange(chunk.size - keep, chunk.size)
                speechBuffer[0] = keepArr
                removed = nSamples
            }
        }
        speechSamplesInternal = 0
        for (b in speechBuffer) speechSamplesInternal += b.size
        wasTrimmed = true
        Log.d(TAG, "trim_front: removed $removed samples, remaining ${"%.2f".format(speechSamplesInternal / sampleRate.toDouble())}s")
    }

    /** 无视最小时长强制取出 */
    fun forceFlush(): FloatArray? {
        if (speechBuffer.isEmpty()) return null
        val segment = concat(speechBuffer, speechSamplesInternal)
        reset()
        return segment
    }

    /** 停止时冲刷：够长才取，否则丢弃 */
    fun flush(): FloatArray? {
        if (speechSamplesInternal >= minSpeechSamples) return flushSegment()
        reset()
        return null
    }

    /** 丢弃所有状态 */
    fun hardReset() {
        reset()
        preBuffer.clear()
        pauseHistory.clear()
        silenceLimit = secondsToChunks(0.8)
    }
}
