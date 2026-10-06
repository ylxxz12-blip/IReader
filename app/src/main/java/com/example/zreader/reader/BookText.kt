package com.example.zreader.reader

import com.example.zreader.data.LangMode
import com.example.zreader.parser.TxtParser

data class ChapterInfo(val title: String, val start: Int, val end: Int)

/** raw 文本中的段落，[start, end) 不含换行符 */
class Para(val start: Int, val end: Int)

object Bilingual {
    const val ZH = 0
    const val EN = 1
    const val MIXED = 2

    fun classify(raw: String, start: Int, end: Int): Int {
        var cjk = 0
        var latin = 0
        for (i in start until end) {
            val c = raw[i]
            when {
                c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code == 0x3007 -> cjk++
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
            }
        }
        return when {
            cjk > 0 && latin == 0 -> ZH
            latin > 0 && cjk == 0 -> EN
            else -> MIXED
        }
    }
}

/**
 * 整本书的内存表示：原文 + 章节 + 段落语言分类 + 双语过滤。
 * 所有的进度/批注偏移都以 raw 坐标存储，切换语言模式后仍然有效。
 */
class BookText(val raw: String, fallbackTitle: String) {

    val chapters: List<ChapterInfo>
    val paras: List<Para> = ArrayList()
    val paraLang: IntArray
    val bilingual: Boolean

    init {
        val starts = TxtParser.normalizeChapters(raw, fallbackTitle)
        chapters = starts.mapIndexed { i, (title, off) ->
            val end = if (i + 1 < starts.size) starts[i + 1].second else raw.length
            ChapterInfo(title, off, end)
        }
        // 段落扫描
        val list = paras as ArrayList<Para>
        var i = 0
        val n = raw.length
        while (i < n) {
            var e = raw.indexOf('\n', i)
            if (e < 0) e = n
            if (e > i) list.add(Para(i, e))
            i = e + 1
        }
        paraLang = IntArray(list.size)
        val seq = IntArrayList()
        for (k in list.indices) {
            val lang = Bilingual.classify(raw, list[k].start, list[k].end)
            paraLang[k] = lang
            if (lang == Bilingual.ZH || lang == Bilingual.EN) seq.add(lang)
        }
        bilingual = detectBilingual(seq)
    }

    /** 中英文段落交替出现则认定为双语书 */
    private fun detectBilingual(seq: IntArrayList): Boolean {
        if (seq.size() < 8) return false
        var zh = 0
        var en = 0
        var alt = 0
        for (i in 0 until seq.size()) {
            if (seq[i] == Bilingual.ZH) zh++ else en++
            if (i > 0 && seq[i] != seq[i - 1]) alt++
        }
        if (zh < 4 || en < 4) return false
        return alt >= (seq.size() - 1) * 0.5
    }

    fun chapterIndexFor(rawOffset: Int): Int {
        var lo = 0
        var hi = chapters.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chapters[mid].start <= rawOffset) {
                ans = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return ans
    }

    /** 构造某章节在给定语言模式下的可见文本及 filtered->raw 映射 */
    fun filteredChapter(chapterIndex: Int, mode: LangMode): FilteredChapter {
        val ch = chapters[chapterIndex]
        if (!bilingual || mode == LangMode.BOTH) {
            return FilteredChapter(
                chapterIndex, raw.substring(ch.start, ch.end), null, ch.start,
                parasOf(ch), langSlice(ch),
            )
        }
        val sb = StringBuilder()
        val map = IntArrayList()
        val outParas = ArrayList<Para>()
        val outLangs = IntArrayList()
        val hide = if (mode == LangMode.ZH) Bilingual.EN else Bilingual.ZH
        // 二分定位章节内首个段落
        var pi = paras.binarySearchBy(ch.start) { it.start }.let { if (it < 0) -it - 1 else it }
        while (pi < paras.size && paras[pi].start < ch.end) {
            val lang = paraLang[pi]
            if (lang != hide) {
                val p = paras[pi]
                val startInFiltered = sb.length
                for (k in p.start until p.end) {
                    sb.append(raw[k]); map.add(k)
                }
                sb.append('\n')
                // 过滤文本中的换行映射到原文中该段落的换行位置
                map.add(p.end.coerceAtMost(raw.length - 1))
                outParas.add(Para(startInFiltered, sb.length - 1))
                outLangs.add(lang)
            }
            pi++
        }
        return FilteredChapter(chapterIndex, sb.toString(), map.toArray(), ch.start, outParas, outLangs.toArray())
    }

    private fun parasOf(ch: ChapterInfo): List<Para> {
        var pi = paras.binarySearchBy(ch.start) { it.start }.let { if (it < 0) -it - 1 else it }
        val out = ArrayList<Para>()
        while (pi < paras.size && paras[pi].start < ch.end) {
            out.add(paras[pi]); pi++
        }
        return out
    }

    private fun langSlice(ch: ChapterInfo): IntArray {
        var pi = paras.binarySearchBy(ch.start) { it.start }.let { if (it < 0) -it - 1 else it }
        val out = IntArrayList()
        while (pi < paras.size && paras[pi].start < ch.end) {
            out.add(paraLang[pi]); pi++
        }
        return out.toArray()
    }
}

/** 某章节过滤后的文本；map 为 null 表示与 raw 一一对应（raw = rawStart + idx） */
class FilteredChapter(
    val chapterIndex: Int,
    val text: String,
    val map: IntArray?,
    val rawStart: Int,
    val paras: List<Para>,
    val paraLangs: IntArray,
) {
    val length: Int get() = text.length

    fun rawOf(filteredIdx: Int): Int {
        val m = map
        return if (m == null) rawStart + filteredIdx
        else if (filteredIdx < 0) rawStart
        else if (filteredIdx >= m.size) rawStart + m.size
        else m[filteredIdx]
    }

    /** 不大于 raw 的最大 filtered 偏移（用于进度恢复时向前吸附） */
    fun filteredOf(raw: Int): Int {
        val m = map ?: return (raw - rawStart).coerceIn(0, length)
        var lo = 0
        var hi = m.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (m[mid] <= raw) {
                ans = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return (ans + 1).coerceAtMost(length)
    }

    /** 第一个 >= raw 的 filtered 偏移（用于批注区间换算，无则返回 length） */
    fun filteredLowerBound(raw: Int): Int {
        val m = map ?: return (raw - rawStart).coerceIn(0, length)
        var lo = 0
        var hi = m.size - 1
        var ans = m.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (m[mid] >= raw) {
                ans = mid; hi = mid - 1
            } else lo = mid + 1
        }
        return ans
    }
}

/** 简单的 Int 动态数组，避免装箱 */
internal class IntArrayList(cap: Int = 64) {
    private var data = IntArray(cap)
    private var sz = 0

    operator fun get(i: Int): Int = data[i]

    fun add(v: Int) {
        if (sz == data.size) data = data.copyOf(data.size * 2)
        data[sz++] = v
    }

    fun setAt(i: Int, v: Int) {
        data[i] = v
    }

    fun size(): Int = sz

    fun toArray(): IntArray = data.copyOf(sz)
}
