package com.example.zreader.ui.reader

import android.app.Activity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.example.zreader.App
import com.example.zreader.data.LangMode
import com.example.zreader.reader.BookText
import com.example.zreader.reader.ReaderSkins
import com.example.zreader.reader.ReaderView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 音量键翻页事件总线（MainActivity 转发） */
object ReaderKeyBus {
    var handler: ((keyCode: Int, action: Int) -> Boolean)? = null
}

data class NoteEditRequest(val startRaw: Int, val endRaw: Int, val existing: String?)

@Composable
fun ReaderScreen(bookId: Long, onExit: () -> Unit) {
    val app = App.get()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bookList by app.library.books.collectAsState()
    val book = bookList.firstOrNull { it.id == bookId }
    val settings by app.settingsRepo.settings.collectAsState()
    val notesFile by app.notes.observe(bookId).collectAsState()
    val skin = ReaderSkins.get(settings.themeIndex)

    var menuOpen by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var noteEdit by remember { mutableStateOf<NoteEditRequest?>(null) }
    var bookText by remember { mutableStateOf<BookText?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf<ReaderView.ReaderPosition?>(null) }

    val readerView = remember { mutableStateOf<ReaderView?>(null) }

    // ---------- 数据加载 ----------
    LaunchedEffect(bookId) {
        bookText = null
        loadError = false
        val b = app.library.books.value.firstOrNull { it.id == bookId }
        if (b == null) {
            loadError = true
            return@LaunchedEffect
        }
        val t = app.importer.loadBookText(b)
        if (t == null) loadError = true else bookText = t
    }

    LaunchedEffect(bookText) {
        val t = bookText ?: return@LaunchedEffect
        val b = app.library.books.value.firstOrNull { it.id == bookId } ?: return@LaunchedEffect
        readerView.value?.load(b, t, settings, notesFile.annotations, app.notes)
    }

    LaunchedEffect(settings) { readerView.value?.updateSettings(settings) }
    LaunchedEffect(notesFile.annotations) { readerView.value?.setAnnotations(notesFile.annotations) }
    LaunchedEffect(book?.langMode) { book?.let { readerView.value?.applyLangMode(it.langMode) } }

    // ---------- 进度持久化 ----------
    var lastPos by remember { mutableStateOf<ReaderView.ReaderPosition?>(null) }
    var persistJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(bookId) {
        onDispose {
            val p = lastPos
            if (p != null) {
                app.appScope.launch {
                    app.library.updateProgress(
                        bookId,
                        if (p.atVeryEnd) p.totalChars else p.rawOffset,
                        System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    // ---------- 音量键翻页 ----------
    DisposableEffect(settings.volumeKey) {
        ReaderKeyBus.handler = if (settings.volumeKey) {
            { code, action ->
                if (action == KeyEvent.ACTION_DOWN) {
                    when (code) {
                        KeyEvent.KEYCODE_VOLUME_DOWN -> { readerView.value?.turnForward(); true }
                        KeyEvent.KEYCODE_VOLUME_UP -> { readerView.value?.turnBack(); true }
                        else -> false
                    }
                } else false
            }
        } else null
        onDispose {
            if (ReaderKeyBus.handler != null) ReaderKeyBus.handler = null
        }
    }

    // ---------- 亮度 / 常亮 / 状态栏图标 ----------
    LaunchedEffect(settings.brightnessFollowSystem, settings.brightness) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val lp = window.attributes
        lp.screenBrightness = if (settings.brightnessFollowSystem)
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        else
            0.06f + settings.brightness * 0.94f
        window.attributes = lp
    }
    LaunchedEffect(settings.keepScreenOn) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(skin.darkIcons) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = skin.darkIcons
    }

    BackHandler {
        when {
            noteEdit != null -> { readerView.value?.noteDialogCanceled(); noteEdit = null }
            showToc -> showToc = false
            showNotes -> showNotes = false
            showSearch -> showSearch = false
            menuOpen -> { menuOpen = false; showAppearance = false }
            else -> onExit()
        }
    }

    val barBg = if (skin.darkIcons) Color(0xF21C1C1E) else Color(0xFFF7F3E9)
    val barFg = if (skin.darkIcons) Color(0xFFE8E4DC) else Color(0xFF2B2B28)

    Box(Modifier.fillMaxSize().background(Color(skin.bg))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ReaderView(ctx).also { v ->
                    readerView.value = v
                    v.onCenterTap = {
                        if (menuOpen) { menuOpen = false; showAppearance = false } else menuOpen = true
                    }
                    v.onPositionChanged = { pos ->
                        position = pos
                        lastPos = pos
                        persistJob?.cancel()
                        persistJob = scope.launch {
                            delay(900)
                            app.library.updateProgress(
                                bookId,
                                if (pos.atVeryEnd) pos.totalChars else pos.rawOffset,
                                System.currentTimeMillis(),
                            )
                        }
                    }
                    v.onEditNoteRequested = { s, e, existing ->
                        noteEdit = NoteEditRequest(s, e, existing)
                    }
                }
            },
        )

        // 菜单打开时，点阅读区收起
        if (menuOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.12f))
                    .pointerInput(Unit) {
                        detectTapGestures {
                            menuOpen = false
                            showAppearance = false
                        }
                    },
            )
        }

        // 顶部栏
        AnimatedVisibility(
            visible = menuOpen,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = book?.title ?: "",
                author = book?.author ?: "",
                barBg = barBg,
                barFg = barFg,
                bookmarked = position?.let { p -> notesFile.bookmarks.any { it.offset == p.rawOffset } } == true,
                onBack = onExit,
                onSearch = { showSearch = true },
                onToggleBookmark = {
                    val pos = position
                    if (pos != null) {
                        val existing = notesFile.bookmarks.firstOrNull { it.offset == pos.rawOffset }
                        if (existing != null) {
                            app.notes.removeBookmark(bookId, existing.id)
                            android.widget.Toast.makeText(context, "已移除本页书签", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            readerView.value?.addBookmarkHere { }
                            android.widget.Toast.makeText(context, "已添加本页书签", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }

        // 底部栏 / 排版设置面板
        AnimatedVisibility(
            visible = menuOpen && !showAppearance,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                position = position,
                book = book,
                barBg = barBg,
                barFg = barFg,
                onJumpRaw = { raw -> readerView.value?.jumpToRaw(raw) },
                onToc = { showToc = true },
                onNotes = { showNotes = true },
                onAppearance = { showAppearance = true },
                onLangMode = { mode ->
                    book?.let { b -> scope.launch { app.library.update(b.copy(langMode = mode)) } }
                    readerView.value?.applyLangMode(mode)
                },
            )
        }

        AnimatedVisibility(
            visible = menuOpen && showAppearance,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            AppearancePanel(
                settings = settings,
                barBg = barBg,
                barFg = barFg,
                accent = barFg,
                onChange = { transform -> app.settingsRepo.update(transform) },
                onClose = { showAppearance = false },
            )
        }

        if (bookText == null && !loadError) {
            Box(Modifier.fillMaxSize().background(Color(skin.bg)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(skin.secondary))
            }
        }
        if (loadError || book == null) {
            Box(Modifier.fillMaxSize().background(Color(skin.bg)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("书籍不存在或无法解析", color = Color(skin.secondary))
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onExit) { Text("返回书架") }
                }
            }
        }
    }

    // ---------- 对话框 ----------
    if (showToc) {
        TocDialog(
            chapters = readerView.value?.chapterList() ?: emptyList(),
            currentIndex = position?.chapterIndex ?: 0,
            bookmarks = notesFile.bookmarks,
            onSelect = { idx ->
                readerView.value?.jumpToChapter(idx)
                showToc = false
            },
            onJumpBookmark = { raw ->
                readerView.value?.jumpToRaw(raw)
                showToc = false
            },
            onDeleteBookmark = { app.notes.removeBookmark(bookId, it) },
            onDismiss = { showToc = false },
        )
    }
    if (showNotes) {
        NotesDialog(
            annotations = notesFile.annotations,
            onJump = { raw -> readerView.value?.jumpToRaw(raw); showNotes = false },
            onDeleteAnnotation = { app.notes.removeAnnotation(bookId, it) },
            onEditNote = { ann -> readerView.value?.beginNoteEdit(ann.start, ann.end, ann.note) },
            onDismiss = { showNotes = false },
        )
    }
    if (showSearch) {
        SearchDialog(
            rawText = { readerView.value?.rawText() },
            chapterTitleFor = { raw -> readerView.value?.chapterTitleFor(raw) ?: "" },
            onJump = { raw -> readerView.value?.jumpToRaw(raw); showSearch = false },
            onDismiss = { showSearch = false },
        )
    }
    noteEdit?.let { req ->
        NoteDialog(
            initial = req.existing ?: "",
            excerpt = readerView.value?.rawText()
                ?.let { raw -> raw.substring(req.startRaw.coerceIn(0, raw.length), req.endRaw.coerceIn(0, raw.length)) }
                ?.take(120) ?: "",
            onSave = { text ->
                if (text.isNotBlank()) readerView.value?.saveNoteFromDialog(text)
                else readerView.value?.noteDialogCanceled()
                noteEdit = null
            },
            onCancel = { readerView.value?.noteDialogCanceled(); noteEdit = null },
        )
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    author: String,
    barBg: Color,
    barFg: Color,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(barBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = barFg)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = barFg, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(author, color = barFg.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "搜索", tint = barFg)
            }
            IconButton(onClick = onToggleBookmark) {
                // 自绘书签图标（本页已加书签时高亮）
                val bookmarkPath = remember {
                    android.graphics.Path().apply {
                        moveTo(17f, 3f)
                        lineTo(7f, 3f)
                        cubicTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
                        lineTo(5f, 21f)
                        lineTo(12f, 18f)
                        lineTo(19f, 21f)
                        lineTo(19f, 5f)
                        cubicTo(19f, 3.9f, 18.1f, 3f, 17f, 3f)
                        close()
                    }
                }
                val tint = if (bookmarked) Color(0xFFE0A526) else barFg.copy(alpha = 0.85f)
                Canvas(Modifier.size(18.dp)) {
                    withTransform({ scale(size.width / 24f, size.height / 24f) }) {
                        drawPath(bookmarkPath.asComposePath(), tint)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    position: ReaderView.ReaderPosition?,
    book: com.example.zreader.data.Book?,
    barBg: Color,
    barFg: Color,
    onJumpRaw: (Int) -> Unit,
    onToc: () -> Unit,
    onNotes: () -> Unit,
    onAppearance: () -> Unit,
    onLangMode: (LangMode) -> Unit,
) {
    var sliderValue by remember { mutableStateOf<Float?>(null) }
    val total = book?.totalChars ?: 1
    val fraction = sliderValue ?: (position?.rawOffset?.toFloat()?.div(total) ?: 0f)
    Column(Modifier.fillMaxWidth().background(barBg).navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                position?.chapterTitle ?: "…",
                color = barFg,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                position?.let { "${it.page}/${it.pages}  ${it.percent.toInt()}%" } ?: "",
                color = barFg.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                sliderValue?.let { onJumpRaw((it * total).toInt()) }
                sliderValue = null
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onToc) { Text("目录", color = barFg, fontSize = 14.sp) }
            TextButton(onClick = onNotes) { Text("批注", color = barFg, fontSize = 14.sp) }
            TextButton(onClick = onAppearance) { Text("设置", color = barFg, fontSize = 14.sp) }
        }
        if (book?.bilingual == true) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LangMode.entries.forEach { mode ->
                    val selected = book.langMode == mode
                    Text(
                        mode.label,
                        color = if (selected) MaterialTheme.colorScheme.primary else barFg.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .background(
                                if (selected) barFg.copy(alpha = 0.15f) else Color.Transparent,
                                MaterialTheme.shapes.small,
                            )
                            .clickable { onLangMode(mode) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
