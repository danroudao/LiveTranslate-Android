package com.example.livetranslate.pipeline

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 系统音频捕获 —— 对应 audio_capture.py 的 WASAPI loopback。
 *
 * MediaProjection + AudioPlaybackCapture（API 29+）：
 * 捕获设备上其他 App（及本 App）的媒体/游戏音频输出，等价于 Windows 的 WASAPI loopback。
 * 输出 48kHz 立体声 float32 → 混单声道 → 线性插值重采样到 16kHz mono → 32ms chunk 回调。
 */
class AudioCapturer(
    private val projection: MediaProjection,
    private val sampleRate: Int = 16000,
    private val chunkDuration: Double = 0.032,
    private val onChunk: (FloatArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "AudioCapturer"
        private const val NATIVE_RATE = 48000
    }

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    val chunkSamples: Int get() = (sampleRate * chunkDuration).toInt()

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(NATIVE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(NATIVE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf * 2, NATIVE_RATE * 4 * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                onError("AudioRecord 启动失败（recordingState=${record.recordingState}）")
                return false
            }
            this.record = record
            running.set(true)
            thread = Thread({ readLoop(record) }, "audio-capture").also { it.start() }
            Log.i(TAG, "capture started, chunk=${chunkSamples} samples")
            true
        } catch (e: Exception) {
            onError("捕获启动失败: ${e.message}")
            false
        }
    }

    fun stop() {
        running.set(false)
        thread?.join(2000)
        thread = null
        try {
            record?.stop()
            record?.release()
        } catch (e: Exception) {
        }
        record = null
    }

    private fun readLoop(record: AudioRecord) {
        val nativeChunk = (NATIVE_RATE * chunkDuration).toInt()  // 1536 @48k
        val buf = FloatArray(nativeChunk * 2)  // stereo
        val mono = FloatArray(nativeChunk)
        val chunk = FloatArray(chunkSamples)
        var chunkFill = 0

        // 预计算重采样索引表（48k stereo->mono 后再 48k->16k）
        val ratio = sampleRate.toDouble() / NATIVE_RATE
        val resampleLen = (nativeChunk * ratio).toInt()
        val idxFloor = IntArray(resampleLen)
        val frac = FloatArray(resampleLen)
        for (i in 0 until resampleLen) {
            val pos = i / ratio
            idxFloor[i] = pos.toInt()
            frac[i] = (pos - idxFloor[i]).toFloat()
        }

        while (running.get()) {
            val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) continue
            val frames = n / 2
            // 立体声 -> 单声道
            for (i in 0 until frames) {
                mono[i] = (buf[i * 2] + buf[i * 2 + 1]) * 0.5f
            }
            // 重采样 48k -> 16k
            val rn = minOf(resampleLen, frames / 3 + 1)
            val resampled = FloatArray(rn)
            for (i in 0 until rn) {
                val fi = idxFloor[i]
                if (fi + 1 < frames) {
                    resampled[i] = mono[fi] * (1 - frac[i]) + mono[fi + 1] * frac[i]
                } else {
                    resampled[i] = mono[minOf(fi, frames - 1)]
                }
            }
            // 按 512 样本组装 chunk
            var i = 0
            while (i < rn && running.get()) {
                val take = minOf(chunk.size - chunkFill, rn - i)
                System.arraycopy(resampled, i, chunk, chunkFill, take)
                chunkFill += take
                i += take
                if (chunkFill >= chunk.size) {
                    onChunk(chunk.copyOf())
                    chunkFill = 0
                }
            }
        }
    }
}
