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

        /** 句末标点：中文句号/叹问 + 英文句号/叹问 */
        private const val CJK_TERMINAL = "。！？"
        private const val EN_TERMINAL = "!?"

        /** 常见英文缩写（后跟句点不切分：Mr. Smith / U.S. / etc.） */
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "st", "sr", "jr", "prof", "rev", "gen", "col",
            "inc", "ltd", "co", "corp", "dept", "univ", "assn", "bros",
            "etc", "vs", "e.g", "i.e", "u.s", "u.k", "u.n", "no", "fig", "al",
            "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
            "approx", "est", "min", "max", "hr", "sec", "mph", "kg", "cm", "mm",
        )

        private const val SHORT_UTTERANCE_ALNUM = 8
        private const val CJK_COMMA_MIN = 25
        private const val WESTERN_COMMA_MIN = 60
    }

    /**
     * 判断位置 i 是否为句末切分点（带缩写/数字/连续标点保护）。
     */
    private fun shouldSplitAt(text: String, i: Int): Boolean {
        val c = text[i]
        if (c in CJK_TERMINAL || c in EN_TERMINAL) return true
        if (c != '.') return false
        // 点后紧跟字母：缩写中间点（U.S.）或粘连文本（hello.World）不切
        if (i < text.length - 1 && text[i + 1].isLetter()) return false
        // 数字小数点不切：3.14 / 1.5
        if (i > 0 && i < text.length - 1 && text[i - 1].isDigit() && text[i + 1].isDigit()) return false
        // 连续点（省略号 …/...）只在最后一个点切
        if (i > 0 && text[i - 1] == '.') return false
        if (i < text.length - 1 && text[i + 1] == '.') return false
        // 常见缩写不切：Mr. / U.S. / etc.
        return !isAbbreviation(text, i)
    }

    /** 取句点前的单词片段（含中间点如 u.s.），匹配缩写表 */
    private fun isAbbreviation(text: String, dotIndex: Int): Boolean {
        var start = dotIndex - 1
        while (start >= 0 && (text[start].isLetter() || text[start] == '.')) start--
        start++
        if (dotIndex - start > 16) return false
        val token = text.substring(start, dotIndex).lowercase()
        return token in ABBREVIATIONS
    }

    /**
     * 切分为句子列表（手写扫描，O(n)）；无法切分时返回单元素列表。
     * 改进：英文缩写（Mr./U.S./etc.）、数字小数点（3.14）、省略号（…）不再误切。
     */
    fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (shouldSplitAt(text, i)) {
                var j = i + 1
                // 吞掉连续句末标点（What?! / 真的吗？！）归属前句
                while (j < text.length && (text[j] in CJK_TERMINAL || text[j] in EN_TERMINAL)) j++
                // 吞掉闭合引号（他说“你好。” → 引号归属前句）
                while (j < text.length && text[j] in "\u201d\"\u2019\u300d\u300f") j++
                while (j < text.length && text[j].isWhitespace()) j++
                val sentence = text.substring(start, j).trim()
                if (sentence.isNotEmpty()) result.add(sentence)
                start = j
                i = j
            } else {
                i++
            }
        }
        if (start < text.length) {
            val tail = text.substring(start).trim()
            if (tail.isNotEmpty()) result.add(tail)
        }
        if (result.isNotEmpty()) return result

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
        return listOf(text)
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
