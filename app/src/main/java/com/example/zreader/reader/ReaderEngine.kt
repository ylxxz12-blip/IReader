package com.example.zreader.reader

import android.text.TextPaint
import com.example.zreader.data.Book
import com.example.zreader.data.LangMode

/**
 * 阅读引擎：管理当前章节的分页缓存与阅读位置。
 * 对外位置统一使用 raw 坐标；语言模式/排版参数变化后自动重新定位。
 */
class ReaderEngine(book: Book, val text: BookText) {

    var langMode: LangMode = if (text.bilingual) book.langMode else LangMode.BOTH
        private set

    var metrics: PageMetrics? = null
        private set
    private var paint: TextPaint? = null

    var positionRaw: Int = book.progress.coerceIn(0, text.raw.length)
        private set

    var chapterIndex: Int = 0
        private set
    var pageIndex: Int = 0
        private set

    var filteredChapter: FilteredChapter? = null
        private set
    var pages: List<Page> = emptyList()
        private set

    val chapterCount: Int get() = text.chapters.size
    val totalPages: Int get() = pages.size
    val isVeryEnd: Boolean
        get() = chapterIndex == text.chapters.size - 1 && pageIndex >= pages.size - 1

    fun chapters(): List<ChapterInfo> = text.chapters

    fun setMetrics(m: PageMetrics, p: TextPaint) {
        if (metrics == m && paint === p) return
        metrics = m
        paint = p
        rebuild(keepPosition = true)
    }

    private fun effectiveMode(): LangMode = if (text.bilingual) langMode else LangMode.BOTH

    private fun rebuild(keepPosition: Boolean, targetChapter: Int = -1) {
        val m = metrics ?: return
        val tp = paint ?: return
        val ch = if (targetChapter >= 0) targetChapter else text.chapterIndexFor(positionRaw)
        chapterIndex = ch.coerceIn(0, text.chapters.size - 1)
        val fc = text.filteredChapter(chapterIndex, effectiveMode())
        filteredChapter = fc
        pages = Paginator.paginate(fc, m, tp)
        if (keepPosition) {
            val f = fc.filteredOf(positionRaw).coerceIn(0, fc.length)
            pageIndex = pageForFiltered(f)
        } else {
            pageIndex = 0
        }
        syncPositionFromPage()
    }

    private fun pageForFiltered(f: Int): Int {
        var ans = 0
        for (i in pages.indices) {
            if (pages[i].start <= f) ans = i else break
        }
        return ans
    }

    private fun syncPositionFromPage() {
        val page = pages.getOrNull(pageIndex) ?: return
        val fc = filteredChapter ?: return
        positionRaw = fc.rawOf(page.start)
    }

    fun currentPage(): Page? = pages.getOrNull(pageIndex)

    fun setPage(i: Int): Boolean {
        val idx = i.coerceIn(0, pages.size - 1)
        if (idx == pageIndex) return false
        pageIndex = idx
        syncPositionFromPage()
        return true
    }

    fun turnForward(): Boolean {
        if (pageIndex < pages.size - 1) return setPage(pageIndex + 1)
        if (chapterIndex < text.chapters.size - 1) {
            rebuild(keepPosition = false, targetChapter = chapterIndex + 1)
            return true
        }
        return false
    }

    fun turnBack(): Boolean {
        if (pageIndex > 0) return setPage(pageIndex - 1)
        if (chapterIndex > 0) {
            rebuild(keepPosition = false, targetChapter = chapterIndex - 1)
            pageIndex = (pages.size - 1).coerceAtLeast(0)
            syncPositionFromPage()
            return true
        }
        return false
    }

    fun jumpToRaw(raw: Int) {
        positionRaw = raw.coerceIn(0, text.raw.length)
        rebuild(keepPosition = true)
    }

    fun jumpToChapter(index: Int) {
        val ch = text.chapters.getOrNull(index) ?: return
        positionRaw = ch.start
        rebuild(keepPosition = false, targetChapter = index)
    }

    fun setLangMode(mode: LangMode) {
        if (mode == langMode || !text.bilingual) return
        langMode = mode
        rebuild(keepPosition = true)
    }

    /** 到达全书末尾（用于进度记满） */
    fun markFinished() {
        positionRaw = text.raw.length
        rebuild(keepPosition = true, targetChapter = text.chapters.size - 1)
        pageIndex = (pages.size - 1).coerceAtLeast(0)
    }

    fun rawOfFiltered(idx: Int): Int = filteredChapter?.rawOf(idx) ?: idx

    fun filteredOfRaw(raw: Int): Int = filteredChapter?.filteredOf(raw) ?: raw
}
