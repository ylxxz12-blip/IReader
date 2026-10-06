package com.example.zreader.ui.bookshelf

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zreader.App
import com.example.zreader.data.Book
import com.example.zreader.data.BookFormat
import com.example.zreader.reader.ReaderSkins
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(openBook: (Long) -> Unit) {
    val app = App.get()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by app.library.books.collectAsState()
    val sorted = remember(books) { books.sortedByDescending { it.lastReadAt } }

    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<Book?>(null) }
    var detailTarget by remember { mutableStateOf<Book?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importing = true
                val result = app.importer.importUris(uris)
                importing = false
                val msg = buildString {
                    if (result.imported.isNotEmpty()) append("已导入 ${result.imported.size} 本")
                    if (result.skipped.isNotEmpty()) append("，跳过重复 ${result.skipped.size} 本")
                    if (result.failed.isNotEmpty()) append("，失败 ${result.failed.size} 本")
                }
                android.widget.Toast.makeText(context, msg.ifEmpty { "未导入任何文件" }, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 首次启动导入示例双语书
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("sample_imported", false)) {
            prefs.edit().putBoolean("sample_imported", true).apply()
            app.importer.importSample()
        }
    }

    val filtered = if (query.isBlank()) sorted else sorted.filter { it.title.contains(query, true) || it.author.contains(query, true) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶栏
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Column(Modifier.statusBarsPadding()) {
                if (searchMode) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("搜索书架", fontSize = 14.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { searchMode = false; query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "书架",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${books.size}",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { searchMode = true }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                        TextButton(onClick = {
                            launcher.launch(arrayOf("text/*", "application/epub+zip", "application/octet-stream"))
                        }) { Text("导入") }
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📖", fontSize = 52.sp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (books.isEmpty()) "书架空空如也" else "没有匹配的书籍",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "支持导入 TXT / EPUB 本地文件\n中英对照 TXT 会自动识别为双语书",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                    )
                    if (books.isEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = {
                            launcher.launch(arrayOf("text/*", "application/epub+zip", "application/octet-stream"))
                        }) { Text("导入本地图书") }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(filtered, key = { it.id }) { book ->
                    BookCover(
                        book = book,
                        onClick = { openBook(book.id) },
                        onLongPress = { detailTarget = book },
                    )
                }
            }
        }
    }

    if (importing) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(12.dp)) {
                Column(
                    Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("正在导入…", fontSize = 14.sp)
                }
            }
        }
    }

    // 详情 / 移除
    detailTarget?.let { book ->
        var confirmRemove by remember(book) { mutableStateOf(false) }
        var deleteFile by remember(book) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { detailTarget = null },
            title = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                if (confirmRemove) {
                    Column {
                        Text("确定将《${book.title}》移出书架吗？", fontSize = 14.sp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it })
                            Text("同时删除导入的文件副本", fontSize = 13.sp)
                        }
                    }
                } else {
                    Column {
                        DetailRow("作者", book.author)
                        DetailRow("格式", if (book.format == BookFormat.EPUB) "EPUB" else "TXT (${book.charset})")
                        DetailRow("大小", formatSize(book.fileSize))
                        DetailRow("字数", "${book.totalChars / 10000} 万字")
                        DetailRow("进度", if (book.finished) "已读完" else "${book.percent}%")
                        DetailRow("双语", if (book.bilingual) "是（可切换中/英阅读）" else "否")
                        DetailRow("导入时间", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(book.addedAt)))
                    }
                }
            },
            confirmButton = {
                if (confirmRemove) {
                    TextButton(onClick = {
                        scope.launch { app.library.remove(book.id, deleteFile) }
                        detailTarget = null
                    }) { Text("确定") }
                } else {
                    TextButton(onClick = { confirmRemove = true }) { Text("移出书架") }
                }
            },
            dismissButton = {
                TextButton(onClick = { detailTarget = null }) { Text(if (confirmRemove) "取消" else "关闭") }
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCover(
    book: Book,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val (c1, c2) = ReaderSkins.COVER_COLORS[book.coverColor.coerceIn(0, ReaderSkins.COVER_COLORS.size - 1)]
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .background(
                    Brush.linearGradient(listOf(Color(c1), Color(c2))),
                    RoundedCornerShape(8.dp),
                )
                .padding(10.dp),
        ) {
            Text(
                book.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart),
            )
            if (book.bilingual) {
                Text(
                    "中英",
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                if (book.progress > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(book.percent / 100f)
                                .height(3.dp)
                                .background(Color.White, RoundedCornerShape(2.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (book.finished) "已读完" else if (book.progress > 0) "${book.percent}%" else "未读",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    bytes >= 1 shl 10 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
