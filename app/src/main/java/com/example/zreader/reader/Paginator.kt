package com.example.zreader.reader

import android.text.TextPaint

/** 排版参数（作为分页缓存指纹参与相等判断） */
data class PageMetrics(
    val width: Float,
    val height: Float,
    val fontSizePx: Float,
    val serif: Boolean,
    val lineHeight: Float,
    val indentFirst: Float,
    val paragraphGap: Float,
) {
    val lineCount: Int get() = (height / lineHeight).toInt().coerceAtLeast(1)
}

/** 一行文字：[start, end) 为过滤后文本坐标 */
class Line(
    val start: Int,
    val end: Int,
    val left: Float,
    val top: Float,
    val baseline: Float,
    /** cum[i] = 行内前 i 个字符的总宽度，长度 = end-start+1 */
    val cum: FloatArray,
) {
    fun xAt(idx: Int): Float {
        val i = (idx - start).coerceIn(0, cum.size - 1)
        return left + cum[i]
    }
}

/** 一页 */
class Page(val start: Int, val end: Int, val lines: List<Line>)

/** 纯 canvas 排版分页器：首行缩进、段间距、按行断页 */
object Paginator {

    fun paginate(fc: FilteredChapter, metrics: PageMetrics, paint: TextPaint): List<Page> {
        val pages = ArrayList<Page>()
        val lines = ArrayList<Line>()
        var y = 0f
        var pageStart = -1
        val fm = paint.fontMetrics
        val baselineOff = -fm.ascent

        fun flush() {
            if (lines.isNotEmpty()) {
                pages.add(Page(pageStart, lines.last().end, ArrayList(lines)))
                lines.clear()
                pageStart = -1
                y = 0f
            }
        }

        for (i in fc.paras.indices) {
            val para = fc.paras[i]
            val indent = if (fc.paraLangs.getOrNull(i) == Bilingual.EN) 0f else metrics.indentFirst
            var left = indent
            var p = para.start
            val text = fc.text
            while (p < para.end) {
                if (lines.isNotEmpty() && y + metrics.lineHeight > metrics.height + 0.5f) flush()
                val avail = (metrics.width - left).coerceAtLeast(4f)
                var n = paint.breakText(text, p, para.end, false, avail, null)
                if (n <= 0) n = 1
                if (p + n > para.end) n = para.end - p
                val widths = FloatArray(n)
                paint.getTextWidths(text, p, p + n, widths)
                val cum = FloatArray(n + 1)
                var acc = 0f
                for (k in 0 until n) {
                    cum[k] = acc
                    acc += widths[k]
                }
                cum[n] = acc
                if (pageStart < 0) pageStart = p
                lines.add(Line(p, p + n, left, y, y + baselineOff, cum))
                y += metrics.lineHeight
                p += n
                left = 0f
            }
            y += metrics.paragraphGap
        }
        if (lines.isNotEmpty()) flush()
        if (pages.isEmpty()) pages.add(Page(0, 0, emptyList()))
        return pages
    }
}
