package com.example.livetranslate.pipeline

import android.util.Log

/**
 * 增量 ASR 文本后处理 —— main.py 增量逻辑的 Kotlin 移植。
 *
 * - splitSentences: pysbd 的轻量等价（句末标点切分）+ 逗号兜底
 *   - CJK 顿号「、」25 字符阈值 / 西文逗号 60 字符阈值
 *   - 兜底要求 before > 15 字符且 after > 3 字符，避免切出碎片
 * - stripCommittedOverlap: 回音去重（匹配已提交文本尾部与新文本前缀的重叠）
 * - isShortUtterance: ≤8 个字母数字字符视为噪声/填充（短句缓冲）
 */
class InterimSplitter {

    companion object {
        private const val TAG = "InterimSplitter"

        /** 句末标点（中日英常用）——按此切分并保留标点 */
        private val SENTENCE_END = Regex("(?<=[。！？!?.])\\s*")
        private const val SHORT_UTTERANCE_ALNUM = 8
        private const val CJK_COMMA_MIN = 25
        private const val WESTERN_COMMA_MIN = 60
    }

    /** 切分为句子列表；无法切分时返回单元素列表 */
    fun splitSentences(text: String): List<String> {
        val parts = text.split(SENTENCE_END).filter { it.isNotBlank() }
        if (parts.size > 1) return parts

        // 逗号兜底（长句降低延迟）：CJK「、」25 字符；全部逗号 60 字符
        val minLen = if (text.contains('、')) CJK_COMMA_MIN else WESTERN_COMMA_MIN
        if (text.length > minLen) {
            var i = text.length - 8
            while (i > 5) {
                if (text[i] in ",，;；、") {
                    val before = text.substring(0, i + 1).trim()
                    val after = text.substring(i + 1).trim()
                    if (before.isNotEmpty() && after.isNotEmpty() &&
                        before.length > 15 && after.length > 3
                    ) {
                        return listOf(before, after)
                    }
                }
                i--
            }
        }
        return parts
    }

    /** ≤8 个字母数字字符（可能为噪声/语气词/碎片） */
    fun isShortUtterance(text: String): Boolean {
        var alnum = 0
        for (c in text) if (c.isLetterOrDigit()) alnum++
        return alnum <= SHORT_UTTERANCE_ALNUM
    }

    /**
     * 回音去重：新识别文本若以已提交文本的尾部为前缀，剥掉重叠部分。
     * 对应 main.py _strip_committed_overlap（去重 interim 重复识别）。
     */
    fun stripCommittedOverlap(text: String, committedTail: String): String {
        if (committedTail.isEmpty()) return text
        val tail = committedTail.lowercase().trimEnd()
        val textLower = text.lowercase()
        val maxCheck = minOf(tail.length, textLower.length)
        var overlapLen = maxCheck
        while (overlapLen > 2) {
            if (textLower.startsWith(tail.substring(tail.length - overlapLen))) {
                val stripped = text.substring(overlapLen).trim()
                if (stripped.isNotEmpty()) {
                    Log.d(TAG, "stripped echo overlap ($overlapLen chars): '${text.substring(0, overlapLen)}…'")
                    return stripped
                }
                return ""
            }
            overlapLen--
        }
        return text
    }
}
