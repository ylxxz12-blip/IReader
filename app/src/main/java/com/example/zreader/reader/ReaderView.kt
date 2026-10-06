package com.example.zreader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Bitmap
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.example.zreader.data.Annotation
import com.example.zreader.data.Book
import com.example.zreader.data.LangMode
import com.example.zreader.data.NotesRepository
import com.example.zreader.data.ReadSettings
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 阅读渲染视图：双缓存页面位图 + 4 种翻页动画 + 长按选择批注。
 * 所有进度/批注坐标为 raw 坐标，由 ReaderEngine 换算。
 */
class ReaderView(context: Context) : View(context) {

    companion object {
        private const val HANDLE_SLOP_DP = 20f
    }

    data class ReaderPosition(
        val chapterIndex: Int,
        val chapterTitle: String,
        val page: Int,
        val pages: Int,
        val rawOffset: Int,
        val totalChars: Int,
        val percent: Float,
        val atVeryEnd: Boolean,
    )

    /** 渲染一页所需的全部材料 */
    private class Renderable(
        val page: Page,
        val chapterIndex: Int,
        val chapterTitle: String,
        val pageNo: Int,
        val pagesTotal: Int,
        val fc: FilteredChapter,
    )

    private class Sel(var startF: Int, var endF: Int)

    // ---- 外部依赖与回调 ----
    private var engine: ReaderEngine? = null
    private var book: Book? = null
    private var notes: NotesRepository? = null
    private var settings: ReadSettings = ReadSettings()
    private var skin: ReaderSkin = ReaderSkins.get(1)
    private var anns: List<Annotation> = emptyList()
    private var annsVersion: Int = 0

    var onCenterTap: (() -> Unit)? = null
    var onPositionChanged: ((ReaderPosition) -> Unit)? = null
    var onEditNoteRequested: ((startRaw: Int, endRaw: Int, existing: String?) -> Unit)? = null

    // ---- 画笔与度量 ----
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkPaint = Paint().apply {
        colorFilter = LightingColorFilter(0xFFB2B2B2.toInt(), 0x00141414)
    }
    private var topInset = 0
    private var bottomInset = 0
    private var headerH = 0f
    private var footerH = 0f
    private var sideMargin = 0f
    private var textTop = 0f
    private var textBottom = 0f
    private var metricsHash: Int = 0

    // ---- 页面位图缓存 ----
    private class PageCache {
        var key: String? = null
        var bmp: Bitmap? = null
    }

    private val cacheCur = PageCache()
    private val cacheFwd = PageCache()
    private val cacheBwd = PageCache()
    private var curRenderable: Renderable? = null
    private var fwdRenderable: Renderable? = null
    private var bwdRenderable: Renderable? = null

    // 手势与动画
    private var dragDir = 0 // 1 前进 -1 后退 0 无
    private var dragProgress = 0f
    private var dragStartX = 0f
    private var flingVx = 0f
    private var finger = PointF()
    private var cornerY = 0f
    private var anim: android.animation.ValueAnimator? = null
    private var streamConsumed = false

    // ---- 选择 ----
    private var selection: Sel? = null
    private var selDrag = 0 // 0 无 1 拖起始 2 拖结束
    private var pendingNote: Pair<Int, Int>? = null
    private var popup: SelectionPopup? = null

    private val density get() = resources.displayMetrics.density
    private val scaledDensity get() = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density

    init {
        smallPaint.textSize = 12f * scaledDensity
    }

    // ------------------------------------------------------------------ 公开 API

    fun load(book: Book, text: BookText, settings: ReadSettings, anns: List<Annotation>, notes: NotesRepository) {
        this.book = book
        this.notes = notes
        this.settings = settings
        this.skin = ReaderSkins.get(settings.themeIndex)
        this.anns = anns.sortedBy { it.start }
        annsVersion++
        engine = ReaderEngine(book, text)
        setupPaints()
        updateMetrics()
        firePositionChanged()
        invalidate()
    }

    fun updateSettings(s: ReadSettings) {
        if (s == settings) return
        val layoutChanged = s.fontSizeSp != settings.fontSizeSp || s.lineSpacingSp != settings.lineSpacingSp ||
            s.paragraphSpacingSp != settings.paragraphSpacingSp || s.serifFont != settings.serifFont ||
            s.marginHDp != settings.marginHDp
        settings = s
        skin = ReaderSkins.get(s.themeIndex)
        if (!layoutChanged) {
            invalidateCaches()
            invalidate()
            return
        }
        setupPaints()
        updateMetrics()
    }

    fun setAnnotations(list: List<Annotation>) {
        if (anns === list || anns == list) return
        anns = list.sortedBy { it.start }
        annsVersion++
        invalidateCaches()
        invalidate()
    }

    fun applyLangMode(mode: LangMode) {
        val e = engine ?: return
        if (e.langMode == mode) return
        e.setLangMode(mode)
        clearSelection()
        invalidateCaches()
        firePositionChanged()
        invalidate()
    }

    fun jumpToRaw(raw: Int) {
        engine?.jumpToRaw(raw)
        clearSelection()
        invalidateCaches()
        firePositionChanged()
        invalidate()
    }

    fun jumpToChapter(index: Int) {
        engine?.jumpToChapter(index)
        clearSelection()
        invalidateCaches()
        firePositionChanged()
        invalidate()
    }

    fun chapterList(): List<ChapterInfo> = engine?.chapters() ?: emptyList()

    fun rawText(): String? = engine?.text?.raw

    fun currentPosition(): ReaderPosition? = buildPosition()

    fun clearSelection() {
        if (selection != null || popup?.isShowing == true) {
            selection = null
            selDrag = 0
            pendingNote = null
            popup?.dismiss()
            invalidate()
        }
    }

    /** 音量键/菜单翻页入口 */
    fun turnForward() {
        if (selection != null) { clearSelection(); return }
        if (dragDir != 0 || anim != null) return
        if (settings.pageMode == com.example.zreader.data.PageMode.NONE) commitTurn(1) else startFlip(1)
    }

    fun turnBack() {
        if (selection != null) { clearSelection(); return }
        if (dragDir != 0 || anim != null) return
        if (settings.pageMode == com.example.zreader.data.PageMode.NONE) commitTurn(-1) else startFlip(-1)
    }

    fun addBookmarkHere(onDone: (Boolean) -> Unit) {
        val e = engine ?: return
        val b = book ?: return
        val start = e.positionRaw
        val excerpt = e.text.raw.substring(start, minOf(start + 60, e.text.raw.length)).replace('\n', ' ')
        val id = notes?.nextId(b.id) ?: return
        notes?.addBookmark(b.id, com.example.zreader.data.Bookmark(id, b.id, start, excerpt, System.currentTimeMillis()))
        onDone(true)
    }

    /** 笔记对话框保存 */
    fun saveNoteFromDialog(text: String) {
        val range = pendingNote ?: return
        val b = book ?: return
        val n = notes ?: return
        val start = range.first
        val end = range.second
        val existing = anns.firstOrNull { it.start <= start && it.end >= end && it.note != null }
        val ann = if (existing != null) existing.copy(note = text)
        else Annotation(
            id = n.nextId(b.id), bookId = b.id, start = start, end = end,
            color = 0, note = text,
            excerpt = engine?.text?.raw?.substring(start, minOf(end, start + 200)) ?: "",
            createdAt = System.currentTimeMillis(),
        )
        n.upsertAnnotation(b.id, ann)
        // 本地立即刷新（repository 是同步的）
        pendingNote = null
        clearSelection()
    }

    fun noteDialogCanceled() {
        pendingNote = null
        clearSelection()
    }

    // ------------------------------------------------------------------ 度量

    private fun setupPaints() {
        textPaint.textSize = settings.fontSizeSp * scaledDensity
        textPaint.typeface = if (settings.serifFont) android.graphics.Typeface.SERIF else android.graphics.Typeface.SANS_SERIF
        textPaint.color = skin.text
        smallPaint.textSize = 12f * scaledDensity
        smallPaint.color = skin.secondary
    }

    @Suppress("DEPRECATION")
    private fun updateMetrics() {
        val e = engine ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        sideMargin = dp(settings.marginHDp)
        headerH = dp(38f)
        footerH = dp(34f)
        textTop = topInset + headerH
        textBottom = h - bottomInset - footerH
        val fm = textPaint.fontMetrics
        val fontSpacing = fm.descent - fm.ascent
        val lineH = fontSpacing + settings.lineSpacingSp * scaledDensity
        val indent = settings.fontSizeSp * scaledDensity * 2f
        val metrics = PageMetrics(
            width = w - sideMargin * 2,
            height = textBottom - textTop,
            fontSizePx = textPaint.textSize,
            serif = settings.serifFont,
            lineHeight = lineH,
            indentFirst = indent,
            paragraphGap = settings.paragraphSpacingSp * scaledDensity,
        )
        metricsHash = metrics.hashCode()
        e.setMetrics(metrics, textPaint)
        invalidateCaches()
        firePositionChanged()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMetrics()
        excludeSystemGestures()
    }

    /** 左右边缘排除出系统返回手势区，避免从屏幕边缘起手的翻页被 ROM 手势导航拦截退出 */
    private fun excludeSystemGestures() {
        if (width <= 0 || height <= 0) return
        val edge = dp(20f).toInt()
        setSystemGestureExclusionRects(
            listOf(
                Rect(0, 0, edge, height),
                Rect(width - edge, 0, width, height),
            )
        )
    }

    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        val sb = insets.getInsets(android.view.WindowInsets.Type.systemBars())
        if (sb.top != topInset || sb.bottom != bottomInset) {
            topInset = sb.top
            bottomInset = sb.bottom
            updateMetrics()
        }
        return super.onApplyWindowInsets(insets)
    }

    private fun invalidateCaches() {
        cacheCur.key = null; cacheCur.bmp?.recycle(); cacheCur.bmp = null
        cacheFwd.key = null; cacheFwd.bmp?.recycle(); cacheFwd.bmp = null
        cacheBwd.key = null; cacheBwd.bmp?.recycle(); cacheBwd.bmp = null
        curRenderable = null
    }

    // ------------------------------------------------------------------ Renderable

    private fun buildRenderable(e: ReaderEngine, offset: Int): Renderable? {
        if (offset == 0) {
            val page = e.currentPage() ?: return null
            val ch = e.text.chapters[e.chapterIndex]
            return Renderable(page, e.chapterIndex, ch.title, e.pageIndex + 1, e.pages.size, e.filteredChapter ?: return null)
        }
        if (offset > 0) {
            if (e.pageIndex + 1 < e.pages.size) {
                val page = e.pages[e.pageIndex + 1]
                val ch = e.text.chapters[e.chapterIndex]
                return Renderable(page, e.chapterIndex, ch.title, e.pageIndex + 2, e.pages.size, e.filteredChapter ?: return null)
            }
            // 跨章：临时构建下一章
            val nextIdx = e.chapterIndex + 1
            if (nextIdx >= e.text.chapters.size) return null
            val fc = e.text.filteredChapter(nextIdx, e.langMode)
            val m = e.metrics ?: return null
            val pages = Paginator.paginate(fc, m, textPaint)
            val page = pages.firstOrNull() ?: return null
            return Renderable(page, nextIdx, e.text.chapters[nextIdx].title, 1, pages.size, fc)
        } else {
            if (e.pageIndex > 0) {
                val page = e.pages[e.pageIndex - 1]
                val ch = e.text.chapters[e.chapterIndex]
                return Renderable(page, e.chapterIndex, ch.title, e.pageIndex, e.pages.size, e.filteredChapter ?: return null)
            }
            val prevIdx = e.chapterIndex - 1
            if (prevIdx < 0) return null
            val fc = e.text.filteredChapter(prevIdx, e.langMode)
            val m = e.metrics ?: return null
            val pages = Paginator.paginate(fc, m, textPaint)
            val page = pages.lastOrNull() ?: return null
            return Renderable(page, prevIdx, e.text.chapters[prevIdx].title, pages.size, pages.size, fc)
        }
    }

    private fun renderable(offset: Int): Renderable? {
        if (offset == 0) {
            val e = engine ?: return null
            if (curRenderable != null) return curRenderable
            curRenderable = buildRenderable(e, 0)
            return curRenderable
        }
        return engine?.let { buildRenderable(it, offset) }
    }

    private fun bitmapFor(r: Renderable, cache: PageCache): Bitmap? {
        val key = "${r.chapterIndex}:${r.pageNo}:${r.pagesTotal}:$metricsHash:$annsVersion:${skin.name}"
        if (cache.key == key && cache.bmp != null) return cache.bmp
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        cache.bmp?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawPage(canvas, r)
        cache.key = key
        cache.bmp = bmp
        return bmp
    }

    // ------------------------------------------------------------------ 页面绘制

    private fun drawPage(canvas: Canvas, r: Renderable) {
        val e = engine ?: return
        canvas.drawColor(skin.bg)
        // 页眉：章节标题
        smallPaint.color = skin.secondary
        val title = ellipsize(r.chapterTitle, smallPaint, width - dp(48f))
        val tw = smallPaint.measureText(title)
        canvas.drawText(title, (width - tw) / 2f, topInset + headerH - dp(12f), smallPaint)
        // 页脚：进度
        val footerBase = height - bottomInset - footerH + dp(16f)
        val pct = e.text.raw.length.takeIf { it > 0 }?.let { r.fc.rawOf(r.page.start) * 100f / it } ?: 0f
        val pctText = if (pct >= 99.9f) "100%" else "${(pct * 10).toInt() / 10f}%"
        canvas.drawText(pctText, sideMargin, footerBase, smallPaint)
        val pageText = "${r.pageNo}/${r.pagesTotal}"
        val pw = smallPaint.measureText(pageText)
        canvas.drawText(pageText, width - sideMargin - pw, footerBase, smallPaint)

        // 正文
        textPaint.color = skin.text
        val text = r.fc.text
        for (line in r.page.lines) {
            canvas.drawText(text, line.start, line.end, sideMargin + line.left, textTop + line.baseline, textPaint)
        }
        // 高亮与笔记角标
        val pageRawStart = r.fc.rawOf(r.page.start)
        val pageRawEnd = r.fc.rawOf(max(r.page.end - 1, r.page.start))
        for (ann in anns) {
            if (ann.end <= pageRawStart || ann.start >= pageRawEnd + 1) continue
            val fStart = r.fc.filteredLowerBound(ann.start).coerceAtLeast(r.page.start)
            val fEnd = r.fc.filteredLowerBound(ann.end).coerceAtMost(r.page.end)
            if (fEnd <= fStart) continue
            fillPaint.color = ReaderSkins.HIGHLIGHTS[ann.color.coerceIn(0, ReaderSkins.HIGHLIGHTS.size - 1)]
            var lastLine: Line? = null
            var lastX2 = 0f
            for (line in r.page.lines) {
                val a = max(fStart, line.start)
                val b = min(fEnd, line.end)
                if (b > a) {
                    val x1 = sideMargin + line.xAt(a)
                    val x2 = sideMargin + line.xAt(b)
                    val top = textTop + line.top + dp(2f)
                    val bottom = top + (e.metrics?.lineHeight ?: 0f) - dp(4f)
                    val rect = RectF(x1, top, x2, bottom)
                    canvas.drawRoundRect(rect, dp(3f), dp(3f), fillPaint)
                    lastLine = line
                    lastX2 = x2
                }
            }
            if (ann.note != null && lastLine != null) {
                drawNoteMark(canvas, lastX2 + dp(4f), textTop + lastLine.top)
            }
        }
    }

    private fun drawNoteMark(canvas: Canvas, x: Float, y: Float) {
        val s = dp(9f)
        fillPaint.color = 0xFFE0A526.toInt()
        val path = Path()
        path.moveTo(x, y + dp(4f))
        path.lineTo(x + s, y + dp(4f))
        path.lineTo(x + s, y + dp(4f) + s * 0.7f)
        path.lineTo(x + s * 0.6f, y + dp(4f) + s)
        path.lineTo(x, y + dp(4f) + s * 0.7f)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun ellipsize(s: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && paint.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }

    // ------------------------------------------------------------------ 选择

    private fun hitLine(y: Float): Line? {
        val page = curRenderable?.page ?: return null
        val ly = y - textTop
        for (line in page.lines) {
            val h = engine?.metrics?.lineHeight ?: return null
            if (ly >= line.top && ly < line.top + h) return line
        }
        return null
    }

    private fun offsetAt(x: Float, y: Float): Int? {
        val r = curRenderable ?: return null
        val line = hitLine(y) ?: return null
        val lx = x - sideMargin
        var lo = 0
        var hi = line.cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (line.left + line.cum[mid] <= lx) lo = mid else hi = mid - 1
        }
        return (line.start + lo).coerceIn(r.page.start, r.page.end)
    }

    private fun startSelection(x: Float, y: Float) {
        val r = curRenderable ?: return
        val off = offsetAt(x, y) ?: return
        val text = r.fc.text
        var s = off
        var e = min(off + 1, r.page.end)
        // 中英文词扩展
        if (e > s) {
            val c = text[s]
            if (c.isLetter()) {
                while (s > r.page.start && text[s - 1].isLetter()) s--
                while (e < r.page.end && text[e].isLetter()) e++
            } else if (c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF) {
                val stop = "，。！？；、：“”‘’（）《》…—,.!?;: \n\t"
                while (s > r.page.start && text[s - 1] !in stop && text[s - 1].code in 0x2E80..0x9FFF) s--
                while (e < r.page.end && text[e] !in stop && text[e].code in 0x2E80..0x9FFF) e++
            }
        }
        if (e <= s) return
        selection = Sel(s, e)
        showSelPopup()
        invalidate()
    }

    private fun selectAnnotationAt(x: Float, y: Float): Boolean {
        val r = curRenderable ?: return false
        val off = offsetAt(x, y) ?: return false
        val raw = r.fc.rawOf(off)
        val ann = anns.firstOrNull { raw >= it.start && raw < it.end } ?: return false
        val fStart = r.fc.filteredLowerBound(ann.start).coerceAtLeast(r.page.start)
        val fEnd = r.fc.filteredLowerBound(ann.end).coerceAtMost(r.page.end)
        if (fEnd <= fStart) return false
        selection = Sel(fStart, fEnd)
        showSelPopup()
        invalidate()
        return true
    }

    private fun selectionBounds(): RectF? {
        val sel = selection ?: return null
        val r = curRenderable ?: return null
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (line in r.page.lines) {
            val a = max(sel.startF, line.start)
            val b = min(sel.endF, line.end)
            if (b > a) {
                top = min(top, textTop + line.top)
                bottom = max(bottom, textTop + line.top + (engine?.metrics?.lineHeight ?: 0f))
                minX = min(minX, sideMargin + line.xAt(a))
                maxX = max(maxX, sideMargin + line.xAt(b))
            }
        }
        if (bottom < top) return null
        return RectF(minX, top, maxX, bottom)
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        val sel = selection ?: return
        val r = curRenderable ?: return
        val lineH = engine?.metrics?.lineHeight ?: return
        fillPaint.color = if (skin.darkIcons) 0x3D5A7DE8.toInt() else 0x5C5C8AF8.toInt()
        for (line in r.page.lines) {
            val a = max(sel.startF, line.start)
            val b = min(sel.endF, line.end)
            if (b > a) {
                val x1 = sideMargin + line.xAt(a)
                val x2 = sideMargin + line.xAt(b)
                canvas.drawRoundRect(RectF(x1, textTop + line.top + dp(2f), x2, textTop + line.top + lineH - dp(4f)), dp(3f), dp(3f), fillPaint)
            }
        }
        // 手柄
        val startLine = r.page.lines.firstOrNull { sel.startF >= it.start && sel.startF <= it.end }
        val endLine = r.page.lines.firstOrNull { sel.endF >= it.start && sel.endF <= it.end }
        val rad = dp(11f)
        fillPaint.color = 0xFF5C7CFA.toInt()
        if (startLine != null) {
            val x = sideMargin + startLine.xAt(sel.startF)
            val y = textTop + startLine.top
            canvas.drawCircle(x, y - rad * 0.6f, rad, fillPaint)
            canvas.drawRect(RectF(x - dp(1.5f), y - rad * 0.6f, x + dp(1.5f), y + dp(2f)), fillPaint)
        }
        if (endLine != null) {
            val x = sideMargin + endLine.xAt(sel.endF)
            val y = textTop + endLine.top + lineH
            canvas.drawCircle(x, y + rad * 0.6f, rad, fillPaint)
            canvas.drawRect(RectF(x - dp(1.5f), y - dp(2f), x + dp(1.5f), y + rad * 0.6f), fillPaint)
        }
    }

    private fun hitHandle(x: Float, y: Float): Int {
        val sel = selection ?: return 0
        val r = curRenderable ?: return 0
        val lineH = engine?.metrics?.lineHeight ?: return 0
        val rad = dp(HANDLE_SLOP_DP)
        val startLine = r.page.lines.firstOrNull { sel.startF >= it.start && sel.startF <= it.end }
        if (startLine != null) {
            val hx = sideMargin + startLine.xAt(sel.startF)
            val hy = textTop + startLine.top - dp(7f)
            if (abs(x - hx) < rad && abs(y - hy) < rad) return 1
        }
        val endLine = r.page.lines.firstOrNull { sel.endF >= it.start && sel.endF <= it.end }
        if (endLine != null) {
            val hx = sideMargin + endLine.xAt(sel.endF)
            val hy = textTop + endLine.top + lineH + dp(7f)
            if (abs(x - hx) < rad && abs(y - hy) < rad) return 2
        }
        return 0
    }

    private fun showSelPopup() {
        val bounds = selectionBounds() ?: return
        val b = book
        if (popup == null && b != null) {
            popup = SelectionPopup(
                context,
                onDismiss = {
                    if (pendingNote == null && selection != null) {
                        selection = null
                        selDrag = 0
                        invalidate()
                    }
                },
                onColor = { idx -> addHighlight(idx) },
                onNote = { requestNote() },
                onCopy = { copySelection() },
                onSelectAll = { expandSelectionToParagraph() },
                onDelete = { deleteSelectionAnnotations() },
            )
        }
        popup?.show(this, bounds)
    }

    private fun addHighlight(colorIdx: Int) {
        val sel = selection ?: return
        val r = curRenderable ?: return
        val b = book ?: return
        val n = notes ?: return
        val startRaw = r.fc.rawOf(sel.startF)
        val endRaw = r.fc.rawOf(max(sel.endF - 1, sel.startF)) + 1
        n.removeAnnotationsOverlapping(b.id, startRaw, endRaw)
        n.upsertAnnotation(
            b.id,
            Annotation(
                id = n.nextId(b.id), bookId = b.id, start = startRaw, end = endRaw,
                color = colorIdx, note = null,
                excerpt = r.fc.text.substring(sel.startF, sel.endF),
                createdAt = System.currentTimeMillis(),
            ),
        )
        android.widget.Toast.makeText(context, "已添加高亮", android.widget.Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    private fun requestNote() {
        val sel = selection ?: return
        val r = curRenderable ?: return
        val startRaw = r.fc.rawOf(sel.startF)
        val endRaw = r.fc.rawOf(max(sel.endF - 1, sel.startF)) + 1
        popup?.dismiss()
        selection = null
        beginNoteEdit(startRaw, endRaw, anns.firstOrNull { it.start <= startRaw && it.end >= endRaw && it.note != null }?.note)
    }

    /** 从批注列表等入口发起笔记编辑 */
    fun beginNoteEdit(startRaw: Int, endRaw: Int, existing: String?) {
        pendingNote = startRaw to endRaw
        onEditNoteRequested?.invoke(startRaw, endRaw, existing)
    }

    fun chapterTitleFor(raw: Int): String {
        val chapters = engine?.text?.chapters ?: return ""
        var ans = chapters.firstOrNull() ?: return ""
        for (c in chapters) if (c.start <= raw) ans = c else break
        return ans.title
    }

    private fun copySelection() {
        val sel = selection ?: return
        val r = curRenderable ?: return
        val text = r.fc.text.substring(sel.startF, sel.endF)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ZReader", text))
        android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    private fun expandSelectionToParagraph() {
        val sel = selection ?: return
        val r = curRenderable ?: return
        var s = sel.startF
        var e = sel.endF
        for (p in r.fc.paras) {
            if (p.start <= s && p.end >= s) s = p.start
            if (p.start < e && p.end >= e) e = p.end
        }
        if (e > s) {
            sel.startF = s
            sel.endF = e
            showSelPopup()
            invalidate()
        }
    }

    private fun deleteSelectionAnnotations() {
        val sel = selection ?: return
        val r = curRenderable ?: return
        val b = book ?: return
        val n = notes ?: return
        val startRaw = r.fc.rawOf(sel.startF)
        val endRaw = r.fc.rawOf(max(sel.endF - 1, sel.startF)) + 1
        val removed = n.removeAnnotationsOverlapping(b.id, startRaw, endRaw)
        android.widget.Toast.makeText(context, if (removed) "已删除批注" else "该区域没有批注", android.widget.Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    // ------------------------------------------------------------------ 触摸

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (engine == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (selection != null) {
                    val h = hitHandle(event.x, event.y)
                    if (h != 0) {
                        selDrag = h
                        return true
                    }
                    if (popup?.isShowing == true && hitPopupSafe(event)) return true
                    clearSelection()
                    streamConsumed = true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (selDrag != 0) {
                    dragSelHandle(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selDrag != 0) {
                    selDrag = 0
                    return true
                }
                if (streamConsumed) {
                    streamConsumed = false
                    return true
                }
            }
        }
        if (streamConsumed) return true
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            onRelease()
        }
        return handled || true
    }

    private fun hitPopupSafe(event: MotionEvent): Boolean {
        // PopupWindow 自行处理触摸，这里不做拦截
        return false
    }

    private fun dragSelHandle(x: Float, y: Float) {
        val sel = selection ?: return
        val off = offsetAt(x, y) ?: return
        if (selDrag == 1) {
            if (off < sel.endF) sel.startF = off
        } else {
            if (off > sel.startF) sel.endF = off
        }
        val b = selectionBounds()
        if (b != null) popup?.update(this, b)
        invalidate()
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (dragDir == 0 && anim == null) startSelection(e.x, e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            handleScroll(dx, dy, e2)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            flingVx = vx // 交给 onRelease 判断是否速度翻页
            if (dragDir != 0 || anim != null) return true
            if (abs(vx) > 1200 && abs(vx) > abs(vy) * 1.2f) {
                val dir = if (vx < 0) 1 else -1
                if (settings.pageMode == com.example.zreader.data.PageMode.NONE) commitTurn(dir) else startFlip(dir)
            }
            return true
        }
    })

    private fun handleTap(x: Float, y: Float) {
        if (dragDir != 0 || anim != null) return
        if (y in textTop..textBottom) {
            if (selectAnnotationAt(x, y)) return
        }
        when {
            x < width * 0.32f -> {
                if (settings.pageMode == com.example.zreader.data.PageMode.NONE) commitTurn(-1) else startFlip(-1)
            }
            x > width * 0.68f -> {
                if (settings.pageMode == com.example.zreader.data.PageMode.NONE) commitTurn(1) else startFlip(1)
            }
            else -> onCenterTap?.invoke()
        }
    }

    private fun handleScroll(dx: Float, dy: Float, e2: MotionEvent) {
        if (anim != null || settings.pageMode == com.example.zreader.data.PageMode.NONE) return
        if (dragDir == 0) {
            if (abs(dx) < dp(6f) || abs(dx) < abs(dy) * 1.2f) return
            // 向左滑动(dx>0)=下一页，向右滑动=上一页
            dragDir = if (dx > 0) 1 else -1
            val target = renderable(dragDir) ?: run { dragDir = 0; return }
            if (dragDir > 0) fwdRenderable = target else bwdRenderable = target
            dragProgress = 0f
            dragStartX = e2.x
            finger.set(e2.x, e2.y)
            // 仿真卷页抓取点在书页边缘的手指高度处
            cornerY = e2.y
        }
        if (settings.pageMode == com.example.zreader.data.PageMode.SIMULATION) {
            // 仿真：进度随手指位移，渲染坐标由进度推导（可越过屏幕边缘完成翻页）
            val delta = if (dragDir == 1) dragStartX - e2.x else e2.x - dragStartX
            dragProgress = delta / (width * 1.1f)
            finger.x = if (dragDir == 1) width * (1f - 2.1f * dragProgress) else width * 2.1f * dragProgress
            finger.y = e2.y
        } else {
            dragProgress += (if (dragDir == 1) dx else -dx) / width
            finger.set(e2.x, e2.y)
        }
        dragProgress = dragProgress.coerceIn(0f, 1f)
        invalidate()
    }

    private fun onRelease() {
        if (dragDir != 0) {
            val dir = dragDir
            // 位移足够，或松手时有与拖拽方向一致的甩动速度，即完成翻页
            val velCommit = if (dir == 1) flingVx < -800f else flingVx > 800f
            if (dragProgress > 0.22f || velCommit) {
                animateFlip(dir, dragProgress, 1f)
            } else {
                animateFlip(dir, dragProgress, 0f)
            }
            flingVx = 0f
        }
    }

    private fun startFlip(dir: Int) {
        val target = renderable(dir)
        if (target == null) {
            // 已是第一页/最后一页
            android.widget.Toast.makeText(context, if (dir > 0) "已经是最后一页" else "已经是第一页", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (dir > 0) fwdRenderable = target else bwdRenderable = target
        dragDir = dir
        cornerY = height / 2f
        finger.set(width.toFloat(), cornerY)
        animateFlip(dir, 0f, 1f)
    }

    private fun animateFlip(dir: Int, from: Float, to: Float) {
        anim?.cancel()
        if (from == to) {
            finishFlip(dir, to)
            return
        }
        val a = android.animation.ValueAnimator.ofFloat(from, to)
        a.duration = (260 * abs(to - from)).toLong().coerceIn(120, 320)
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { an ->
            dragProgress = an.animatedValue as Float
            // 手指位置随动画移动；仿真模式下需扫过屏幕边缘，让翻起的页背完全离开可视区
            finger.x = if (settings.pageMode == com.example.zreader.data.PageMode.SIMULATION) {
                if (dir > 0) width * (1f - 2.1f * dragProgress) else width * 2.1f * dragProgress
            } else {
                width * (1f - dragProgress)
            }
            finger.y = finger.y + (cornerY - finger.y) * 0.5f
            invalidate()
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                finishFlip(dir, to)
            }
        })
        anim = a
        a.start()
    }

    private fun finishFlip(dir: Int, to: Float) {
        anim = null
        if (to >= 1f) commitTurn(dir) else { dragDir = 0; dragProgress = 0f }
        invalidate()
    }

    private fun commitTurn(dir: Int) {
        val e = engine ?: return
        val changed = if (dir > 0) e.turnForward() else e.turnBack()
        dragDir = 0
        dragProgress = 0f
        invalidateCaches()
        if (changed) firePositionChanged()
        invalidate()
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val e = engine ?: return
        canvas.drawColor(skin.bg)
        val moving = dragDir != 0
        val page = curRenderable ?: renderable(0)
        if (page == null) {
            drawEmpty(canvas)
            return
        }
        if (!moving || settings.pageMode == com.example.zreader.data.PageMode.NONE) {
            bitmapFor(page, cacheCur)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            drawSelectionOverlay(canvas)
            return
        }
        val p = dragProgress
        val fwd = fwdRenderable
        val bwd = bwdRenderable
        when (settings.pageMode) {
            com.example.zreader.data.PageMode.COVER -> drawCover(canvas, p, page, fwd, bwd)
            com.example.zreader.data.PageMode.SLIDE -> drawSlide(canvas, p, page, fwd, bwd)
            com.example.zreader.data.PageMode.SIMULATION -> drawCurl(canvas, p, page, fwd, bwd)
            else -> bitmapFor(page, cacheCur)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
    }

    private fun drawEmpty(canvas: Canvas) {
        smallPaint.color = skin.secondary
        val msg = "本章无内容"
        val tw = smallPaint.measureText(msg)
        canvas.drawText(msg, (width - tw) / 2f, height / 2f, smallPaint)
    }

    private fun drawCover(canvas: Canvas, p: Float, cur: Renderable, fwd: Renderable?, bwd: Renderable?) {
        if (dragDir > 0 && fwd != null) {
            bitmapFor(cur, cacheCur)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            bitmapFor(fwd, cacheFwd)?.let {
                // 新页从右侧滑入，覆盖在当前页之上
                val dx = width - p * width
                canvas.drawBitmap(it, dx, 0f, null)
            }
            shadowLeftOf(canvas, width - p * width)
        } else if (dragDir < 0 && bwd != null) {
            bitmapFor(cur, cacheCur)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            bitmapFor(bwd, cacheBwd)?.let {
                // 上一页从左侧滑回，覆盖当前页
                val dx = -(1f - p) * width
                canvas.drawBitmap(it, dx, 0f, null)
            }
            shadowRightOf(canvas, p * width)
        }
    }

    /** 在 x 处向右渐隐的阴影带（页面右边缘投影到下方页面） */
    private fun shadowRightOf(canvas: Canvas, x: Float) {
        val w = dp(24f)
        val l = x.coerceIn(0f, width - 1f)
        val r = (l + w).coerceAtMost(width.toFloat())
        if (r <= l) return
        fillPaint.shader = LinearGradient(
            l, 0f, r, 0f,
            intArrayOf(0x36000000, 0x00000000), null, android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(RectF(l, 0f, r, height.toFloat()), fillPaint)
        fillPaint.shader = null
    }

    /** 在 x 处向左渐隐的阴影带（页面左边缘投影到下方页面） */
    private fun shadowLeftOf(canvas: Canvas, x: Float) {
        val w = dp(24f)
        val r = x.coerceIn(1f, width.toFloat())
        val l = (r - w).coerceAtLeast(0f)
        if (r <= l) return
        fillPaint.shader = LinearGradient(
            r, 0f, l, 0f,
            intArrayOf(0x36000000, 0x00000000), null, android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(RectF(l, 0f, r, height.toFloat()), fillPaint)
        fillPaint.shader = null
    }

    private fun drawSlide(canvas: Canvas, p: Float, cur: Renderable, fwd: Renderable?, bwd: Renderable?) {
        if (dragDir > 0 && fwd != null) {
            bitmapFor(fwd, cacheFwd)?.let { canvas.drawBitmap(it, width - p * width, 0f, null) }
            bitmapFor(cur, cacheCur)?.let { canvas.drawBitmap(it, -p * width, 0f, null) }
        } else if (dragDir < 0 && bwd != null) {
            bitmapFor(bwd, cacheBwd)?.let { canvas.drawBitmap(it, -(1f - p) * width, 0f, null) }
            bitmapFor(cur, cacheCur)?.let { canvas.drawBitmap(it, p * width, 0f, null) }
        }
    }

    /** 仿真翻页：沿手指与书角的垂直平分线折叠，镜像页背 + 阴影 */
    private fun drawCurl(canvas: Canvas, p: Float, cur: Renderable, fwd: Renderable?, bwd: Renderable?) {
        if (dragDir > 0 && fwd != null) {
            renderCurl(canvas, bitmapFor(cur, cacheCur), bitmapFor(fwd, cacheFwd), finger, cornerY)
        } else if (dragDir < 0 && bwd != null) {
            // 后退 = 水平镜像后的前进（前页=上一页，后页=当前页）
            canvas.save()
            canvas.scale(-1f, 1f, width / 2f, 0f)
            val mirroredFinger = PointF(width - finger.x, finger.y)
            renderCurl(canvas, bitmapFor(cur, cacheCur), bitmapFor(bwd, cacheBwd), mirroredFinger, cornerY)
            canvas.restore()
        }
    }

    private fun renderCurl(canvas: Canvas, front: Bitmap?, beneath: Bitmap?, fingerPt: PointF, cornerYPos: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (front == null) return
        val corner = PointF(w, cornerYPos)
        // 抓取点：跟随手指，翻页完成阶段允许越过屏幕左缘
        val fx = fingerPt.x.coerceIn(-w * 1.15f, w * 1.05f)
        val fy = fingerPt.y.coerceIn(0f, h)
        val dxv = corner.x - fx
        val dyv = corner.y - fy
        val dist = hypot(dxv, dyv)
        if (dist < 4f || fx >= w) {
            canvas.drawBitmap(front, 0f, 0f, null)
            return
        }
        // 折叠线 = 抓取点与书角连线的中垂线；n 指向书角一侧
        val mid = PointF((corner.x + fx) / 2f, (corner.y + fy) / 2f)
        val nx = dxv / dist
        val ny = dyv / dist

        // 有界剪裁多边形（屏幕矩形 × 折线半平面），避免大坐标 clipPath 在部分设备上失效
        val cornerPoly = clipScreenByFold(mid, nx, ny, keepPositive = true)
        val fingerPoly = clipScreenByFold(mid, nx, ny, keepPositive = false)

        // 1) 露出的下一页：书角一侧，靠近折线处有阴影
        if (beneath != null && cornerPoly.isNotEmpty()) {
            canvas.save()
            canvas.clipPath(pathOf(cornerPoly))
            canvas.drawBitmap(beneath, 0f, 0f, null)
            fillPaint.shader = LinearGradient(
                mid.x, mid.y, mid.x + nx * dp(28f), mid.y + ny * dp(28f),
                intArrayOf(0x40000000, 0x00000000), null, android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, fillPaint)
            fillPaint.shader = null
            canvas.restore()
        }

        // 2) 仍平铺的当前页：手指一侧
        if (fingerPoly.isNotEmpty()) {
            canvas.save()
            canvas.clipPath(pathOf(fingerPoly))
            canvas.drawBitmap(front, 0f, 0f, null)
            canvas.restore()
        }

        // 3) 翻起的页背：纸是双面印刷，背面是下一页的内容，随折页一起镜像翻转
        if (cornerPoly.isNotEmpty()) {
            val flapSource = beneath ?: front
            val thetaDeg = Math.toDegrees((atan2(dyv, dxv) + Math.PI / 2).toDouble()).toFloat()
            val m = Matrix()
            m.setRotate(-thetaDeg, mid.x, mid.y)
            m.postScale(1f, -1f)
            m.postRotate(thetaDeg, mid.x, mid.y)
            val pts = FloatArray(cornerPoly.size * 2)
            cornerPoly.forEachIndexed { i, p ->
                pts[i * 2] = p.x
                pts[i * 2 + 1] = p.y
            }
            m.mapPoints(pts)
            val flapPath = Path()
            flapPath.moveTo(pts[0], pts[1])
            for (i in 1 until cornerPoly.size) flapPath.lineTo(pts[i * 2], pts[i * 2 + 1])
            flapPath.close()
            canvas.save()
            canvas.clipPath(flapPath)
            canvas.drawBitmap(flapSource, m, darkPaint)
            // 页背靠近折线的加深 + 折痕投影，营造纸页立体感
            fillPaint.shader = LinearGradient(
                mid.x - nx * dp(36f), mid.y - ny * dp(36f), mid.x, mid.y,
                intArrayOf(0x00000000, 0x50000000), null, android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, fillPaint)
            fillPaint.shader = null
            canvas.restore()
        }
    }

    /** 屏幕矩形与折叠线某一侧半平面的交集（有界凸多边形） */
    private fun clipScreenByFold(mid: PointF, nx: Float, ny: Float, keepPositive: Boolean): List<PointF> {
        val sign = if (keepPositive) 1f else -1f
        val w = width.toFloat()
        val h = height.toFloat()
        val rect = listOf(PointF(0f, 0f), PointF(w, 0f), PointF(w, h), PointF(0f, h))
        fun side(p: PointF) = sign * ((p.x - mid.x) * nx + (p.y - mid.y) * ny)
        val out = ArrayList<PointF>(6)
        for (i in rect.indices) {
            val cur = rect[i]
            val nxt = rect[(i + 1) % rect.size]
            val sc = side(cur)
            val sn = side(nxt)
            if (sc >= 0) out.add(PointF(cur.x, cur.y))
            if ((sc > 0 && sn < 0) || (sc < 0 && sn > 0)) {
                val t = sc / (sc - sn)
                out.add(PointF(cur.x + (nxt.x - cur.x) * t, cur.y + (nxt.y - cur.y) * t))
            }
        }
        return out
    }

    private fun pathOf(poly: List<PointF>): Path {
        val path = Path()
        if (poly.isEmpty()) return path
        path.moveTo(poly[0].x, poly[0].y)
        for (i in 1 until poly.size) path.lineTo(poly[i].x, poly[i].y)
        path.close()
        return path
    }

    // ------------------------------------------------------------------ 位置回调

    private fun buildPosition(): ReaderPosition? {
        val e = engine ?: return null
        val ch = e.text.chapters.getOrNull(e.chapterIndex) ?: return null
        return ReaderPosition(
            chapterIndex = e.chapterIndex,
            chapterTitle = ch.title,
            page = e.pageIndex + 1,
            pages = e.pages.size,
            rawOffset = e.positionRaw,
            totalChars = e.text.raw.length,
            percent = if (e.text.raw.isEmpty()) 0f else e.positionRaw * 100f / e.text.raw.length,
            atVeryEnd = e.isVeryEnd,
        )
    }

    private fun firePositionChanged() {
        buildPosition()?.let { onPositionChanged?.invoke(it) }
    }
}
