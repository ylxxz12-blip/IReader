package com.example.zreader.parser

import java.nio.charset.Charset

/** TXT 解码与章节识别 */
object TxtParser {

    data class Decoded(val text: String, val charset: String)

    fun decode(bytes: ByteArray, forced: String? = null): Decoded {
        val charsetName = forced ?: CharsetDetector.detect(bytes)
        val cs = try {
            Charset.forName(charsetName)
        } catch (e: Exception) {
            Charset.forName("UTF-8")
        }
        val decoded = String(bytes, cs).replace("\uFEFF", "")
        // 统一换行为 \n，避免 \r\n 产生空段落
        return Decoded(decoded.replace("\r\n", "\n").replace("\r", "\n"), cs.name())
    }

    private val CHAPTER_PATTERNS = listOf(
        // 第X章/节/卷/回/部/集/篇
        Regex("^\\s{0,8}第\\s*[0-9０-９零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\\s*[章节卷回部集篇幕]\\s*[^\\n]{0,30}$"),
        // 序章/楔子/引子/番外/尾声/后记/终章
        Regex("^\\s{0,8}(序章|序言|楔子|引子|前言|尾声|后记|番外|终章|附录)\\s*[^\\n]{0,20}$"),
        // Chapter 1 / CHAPTER I
        Regex("^\\s{0,8}[Cc][Hh][Aa][Pp][Tt][Ee][Rr]\\s+([0-9]+|[IVXLCDM]+)\\s*.{0,40}$"),
        // Part One / Part 1
        Regex("^\\s{0,8}[Pp][Aa][Rr][Tt]\\s+([0-9]+|[Oo]ne|[Tt]wo|[Tt]hree|[Ff]our|[Ff]ive|[Ss]ix)\\s*.{0,30}$"),
        // 1. / 1、 标题（要求后面有文字，且行较短）
        Regex("^\\s{0,8}[0-9]{1,3}[、.．]\\s*\\S{1,20}$"),
    )

    /** @return 章节起始偏移列表（含标题），若全书无匹配则返回空 */
    fun scanChapterStarts(text: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var i = 0
        val n = text.length
        while (i < n) {
            val lineEnd = text.indexOf('\n', i).let { if (it < 0) n else it }
            val line = text.substring(i, lineEnd)
            if (line.length in 2..40 && isChapterTitle(line)) {
                out.add(line.trim() to i)
            }
            i = lineEnd + 1
        }
        // 章节数量过少视为无章节
        if (out.size < 2) return emptyList()
        // 合并间距过近的（同页多个伪标题）：保留第一个
        val filtered = ArrayList<Pair<String, Int>>()
        var lastStart = Int.MIN_VALUE
        for ((title, off) in out) {
            if (off - lastStart < 200 && filtered.isNotEmpty()) continue
            filtered.add(title to off)
            lastStart = off
        }
        return filtered
    }

    private fun isChapterTitle(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        return CHAPTER_PATTERNS.any { it.matches(t) }
    }

    /** 长章节切分上限，避免无分章大文件一次排版过慢 */
    const val MAX_CHAPTER_CHARS = 60_000

    /** 规格化章节：无章节时整书一章，超长章节按段落边界切分 */
    fun normalizeChapters(text: String, fallbackTitle: String): List<Pair<String, Int>> {
        val found = scanChapterStarts(text)
        val starts: List<Pair<String, Int>> = if (found.isEmpty()) {
            listOf(fallbackTitle to 0)
        } else if (found.first().second > 0) {
            listOf("开头" to 0) + found
        } else found

        val result = ArrayList<Pair<String, Int>>()
        for (idx in starts.indices) {
            val (title, start) = starts[idx]
            val end = if (idx + 1 < starts.size) starts[idx + 1].second else text.length
            if (end - start <= MAX_CHAPTER_CHARS || end - start <= 0) {
                if (result.isEmpty() || result.last().second != start) result.add(title to start)
                continue
            }
            // 超长章节切分
            var cut = start
            var part = 1
            while (cut < end) {
                var target = (cut + MAX_CHAPTER_CHARS).coerceAtMost(end)
                if (target < end) {
                    // 回退到最近的段落边界
                    var p = target
                    while (p > cut + MAX_CHAPTER_CHARS / 2 && text[p] != '\n') p--
                    if (text[p] == '\n') target = p + 1
                }
                val realEnd = target.coerceAtMost(end)
                val t = if (cut == start) title else "$title（${++part - 1}）"
                result.add(t to cut)
                cut = realEnd
                if (realEnd >= end) break
            }
        }
        return result
    }
}
