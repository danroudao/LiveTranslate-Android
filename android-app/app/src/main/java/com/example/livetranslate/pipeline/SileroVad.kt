package com.example.livetranslate.pipeline

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Silero VAD v5 推理封装 —— 基于 sherpa-onnx Vad.compute()（对应 vad_processor.py 中 torch Silero 部分）。
 *
 * sherpa-onnx 内部使用 onnxruntime 跑 silero_vad.onnx：
 *  - compute(float[512]) 返回该窗口的语音概率（纯推理，不改变状态机）
 *  - 模型文件 silero_vad.onnx（2.3MB）放在 assets/，通过 asset:/// 协议加载
 *
 * 优点：与 sherpa-onnx（本地 ASR）共用同一份 onnxruntime 原生库，无依赖冲突。
 */
class SileroVadEngine(context: Context) {

    companion object {
        private const val TAG = "SileroVad"
        const val WINDOW = 512  // 16kHz 下 32ms
    }

    private val vad: Vad

    /** 前 64 样本上下文（silero v5: 推理窗口 = 512 + 64） */
    private val prev64 = FloatArray(64)

    init {
        // sherpa-onnx 需要真实文件路径（asset:/// 协议不支持），复制 assets 模型到 filesDir
        val modelFile = java.io.File(context.filesDir, "silero_vad.onnx")
        if (!modelFile.exists()) {
            context.assets.open("silero_vad.onnx").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val sileroCfg = SileroVadModelConfig(
            modelFile.absolutePath,  // model
            0.45f,                   // threshold（0.5→0.45：口音/弱音量更易触发，快语速适配）
            0.6f,                    // minSilenceDuration（0.8→0.6：快语速停顿短，切段更及时）
            0.3f,                    // minSpeechDuration
            WINDOW,                  // windowSize (Int)
            8.0f,                    // maxSpeechDuration（5→8：长句不截断）
        )
        val cfg = VadModelConfig(
            sileroCfg,           // SileroVadModelConfig
            TenVadModelConfig(), // TenVadModelConfig
            16000,               // sampleRate
            WINDOW,              // windowSize
            "",                  // provider
            false,               // debug
        )
        vad = Vad(null, cfg)
        Log.i(TAG, "Silero VAD (sherpa-onnx) 加载成功: ${modelFile.absolutePath}")
    }

    /**
     * 预测语音概率（0~1）。silero v5 推理窗口 = 512 + 64 上下文 = 576 样本。
     * sherpa-onnx Vad.compute() 内部维护 LSTM state，连续调用语义正确。
     */
    @Synchronized
    fun predict(chunk: FloatArray, sampleRate: Int = 16000): Float {
        val input = FloatArray(576)
        System.arraycopy(prev64, 0, input, 0, 64)
        System.arraycopy(chunk, 0, input, 64, minOf(chunk.size, 512))
        // 保存当前窗口最后 64 样本作为下次的上下文
        System.arraycopy(chunk, maxOf(0, chunk.size - 64), prev64, 0, 64)
        return vad.compute(input)
    }

    /** 重置内部状态（新会话开始时调用） */
    fun resetState() {
        vad.reset()
    }

    fun close() {
        vad.release()
    }
}
