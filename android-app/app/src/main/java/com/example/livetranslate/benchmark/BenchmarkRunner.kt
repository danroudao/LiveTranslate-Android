package com.example.livetranslate.benchmark

import android.util.Log
import com.example.livetranslate.net.LlmTranslator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 翻译基准测试 —— benchmark.py 的 Kotlin 移植（简化版）。
 * 对固定句子集逐句翻译，统计延迟与成功率。
 */
class BenchmarkRunner(private val translator: LlmTranslator) {

    companion object {
        private const val TAG = "Benchmark"

        /** 基准句子（多语种短句，对齐 benchmark.py 风格） */
        val BENCH_SENTENCES = listOf(
            "Hello everyone, this is a live translation test.",
            "We are testing the audio capture pipeline on Android.",
            "The weather today is very nice, perfect for outdoor activities.",
            "Artificial intelligence is changing the way we live and work.",
            "Please check the subtitle overlay on top of the screen.",
            "Neural networks can recognize patterns in images and speech.",
        )
    }

    data class BenchResult(
        val sentence: String,
        val translated: String,
        val latencyMs: Long,
        val success: Boolean,
    )

    data class BenchSummary(
        val results: List<BenchResult>,
        val avgMs: Double,
        val maxMs: Long,
        val minMs: Long,
        val successRate: Double,
        val totalTokens: Long,
    )

    private val pool = Executors.newFixedThreadPool(4)

    fun run(sourceLang: String = "en", timeoutPer: Long = 30): BenchSummary {
        val results = java.util.Collections.synchronizedList(mutableListOf<BenchResult>())
        val latch = CountDownLatch(BENCH_SENTENCES.size)
        var promptTokens = 0L
        var completionTokens = 0L
        val tokenLock = Any()
        for (sentence in BENCH_SENTENCES) {
            pool.execute {
                val t0 = System.currentTimeMillis()
                var translated = ""
                var ok = false
                val done = CountDownLatch(1)
                try {
                    translator.translateStreaming(
                        sentence, sourceLang,
                        onPartial = {},
                        onFinal = { r ->
                            translated = r
                            ok = true
                            done.countDown()
                        },
                        onError = { e ->
                            Log.w(TAG, "bench failed: $e")
                            done.countDown()
                        },
                    )
                    done.await(timeoutPer, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.w(TAG, "bench exception: ${e.message}")
                }
                val latency = System.currentTimeMillis() - t0
                results.add(BenchResult(sentence, translated, latency, ok && translated.isNotEmpty()))
                latch.countDown()
            }
        }
        latch.await(timeoutPer + 5, TimeUnit.SECONDS)

        val sorted = results.sortedBy { it.latencyMs }
        val okList = sorted.filter { it.success }
        return BenchSummary(
            results = sorted,
            avgMs = if (okList.isNotEmpty()) okList.map { it.latencyMs }.average() else 0.0,
            maxMs = okList.maxOfOrNull { it.latencyMs } ?: 0,
            minMs = okList.minOfOrNull { it.latencyMs } ?: 0,
            successRate = okList.size.toDouble() / BENCH_SENTENCES.size,
            totalTokens = 0,  // 简化：token 统计由 Translator 内部维护（后续扩展）
        )
    }

    fun shutdown() {
        pool.shutdown()
    }
}
