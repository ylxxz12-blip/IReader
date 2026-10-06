package com.example.zreader.ui.reader

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.zreader.data.Annotation
import com.example.zreader.data.Bookmark
import com.example.zreader.data.PageMode
import com.example.zreader.data.ReadSettings
import com.example.zreader.reader.ChapterInfo
import com.example.zreader.reader.ReaderSkins
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

// ------------------------------------------------------------------ 目录与书签

@Composable
fun TocDialog(
    chapters: List<ChapterInfo>,
    currentIndex: Int,
    bookmarks: List<Bookmark>,
    onSelect: (Int) -> Unit,
    onJumpBookmark: (Int) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) } // 0 目录 1 书签
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0),
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                // 页签：目录 / 书签
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    DialogTab("目录 ${chapters.size}", tab == 0) { tab = 0 }
                    Spacer(Modifier.width(16.dp))
                    DialogTab("书签 ${bookmarks.size}", tab == 1) { tab = 1 }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
                    }
                }
                if (tab == 0) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().height(480.dp),
                    ) {
                        items(chapters.size) { idx ->
                            val chapter = chapters[idx]
                            val current = idx == currentIndex
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(idx) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(width = 3.dp, height = 18.dp)
                                        .background(
                                            if (current) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            RoundedCornerShape(2.dp),
                                        ),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    chapter.title,
                                    fontSize = 14.sp,
                                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
                        if (bookmarks.isEmpty()) {
                            item {
                                Text(
                                    "暂无书签\n点击右上角书签图标可收藏当前页",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                        items(bookmarks.sortedBy { it.offset }, key = { "b${it.id}" }) { bm ->
                            BookmarkItem(
                                bm = bm,
                                onJump = onJumpBookmark,
                                onDelete = onDeleteBookmark,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .width(18.dp)
                .height(2.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(1.dp),
                ),
        )
    }
}

// ------------------------------------------------------------------ 批注

@Composable
fun NotesDialog(
    annotations: List<Annotation>,
    onJump: (Int) -> Unit,
    onDeleteAnnotation: (Long) -> Unit,
    onEditNote: (Annotation) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf(0) } // 0全部 1高亮 2笔记
    val context = LocalContext.current
    val tabs = listOf("全部", "高亮", "笔记")

    fun export() {
        val sb = StringBuilder()
        sb.appendLine("—— 高亮与笔记 ——")
        annotations.sortedBy { it.start }.forEach { ann ->
            sb.appendLine("· ${ann.excerpt}")
            ann.note?.let { sb.appendLine("  笔记：$it") }
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, sb.toString())
        context.startActivity(Intent.createChooser(intent, "导出批注"))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("批注", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { export() }) { Text("导出", fontSize = 13.sp) }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp)) {
                    tabs.forEachIndexed { i, label ->
                        val count = when (i) {
                            1 -> annotations.count { it.note == null }
                            2 -> annotations.count { it.note != null }
                            else -> annotations.size
                        }
                        Text(
                            if (i == 0) "全部 $count" else "$label $count",
                            fontSize = 13.sp,
                            color = if (filter == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (filter == i) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clickable { filter = i }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().height(460.dp)) {
                    val list = when (filter) {
                        1 -> annotations.filter { it.note == null }
                        2 -> annotations.filter { it.note != null }
                        else -> annotations
                    }.sortedBy { it.start }
                    items(list, key = { "a${it.id}" }) { ann ->
                        AnnotationItem(ann, onJump, onDeleteAnnotation, onEditNote)
                    }
                    if (annotations.isEmpty()) {
                        item {
                            Text(
                                "暂无批注\n阅读时长按文字即可添加高亮或笔记",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationItem(
    ann: Annotation,
    onJump: (Int) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (Annotation) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onJump(ann.start) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(10.dp)
                    .background(Color(ReaderSkins.highlightOpaque(ann.color)), CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                ann.excerpt,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (ann.note != null) {
                IconButton(onClick = { onEdit(ann) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑笔记", modifier = Modifier.size(15.dp))
                }
            }
            IconButton(onClick = { onDelete(ann.id) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(15.dp))
            }
        }
        if (ann.note != null) {
            Text(
                "✎ ${ann.note}",
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )
        }
        Text(
            dateFormat.format(Date(ann.createdAt)),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        )
    }
}

@Composable
private fun BookmarkItem(
    bm: Bookmark,
    onJump: (Int) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onJump(bm.offset) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔖", fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(bm.excerpt, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dateFormat.format(Date(bm.createdAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        IconButton(onClick = { onDelete(bm.id) }, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(15.dp))
        }
    }
}

// ------------------------------------------------------------------ 书内搜索

@Composable
fun SearchDialog(
    rawText: () -> String?,
    chapterTitleFor: (Int) -> String,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val text = remember { rawText() }
    val results = remember(query, text) {
        if (query.isBlank() || text == null || query.length < 1) emptyList()
        else searchAll(text, query.trim(), 300)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索书内内容", fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isNotBlank()) {
                    Text(
                        if (results.size >= 300) "找到 300+ 处" else "找到 ${results.size} 处",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().height(380.dp).padding(top = 4.dp)) {
                    items(results) { hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onJump(hit.first) }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Text(
                                chapterTitleFor(hit.first),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                hit.second,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun searchAll(text: String, query: String, limit: Int): List<Pair<Int, String>> {
    val out = ArrayList<Pair<Int, String>>()
    var idx = 0
    val q = query.lowercase()
    val lower = text.lowercase()
    while (out.size < limit) {
        val found = lower.indexOf(q, idx)
        if (found < 0) break
        val ctxStart = (found - 18).coerceAtLeast(0)
        val ctxEnd = (found + q.length + 18).coerceAtMost(text.length)
        val ctx = text.substring(ctxStart, ctxEnd).replace('\n', ' ').trim()
        out.add(found to ctx)
        idx = found + q.length
    }
    return out
}

// ------------------------------------------------------------------ 笔记编辑

@Composable
fun NoteDialog(
    initial: String,
    excerpt: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("笔记") },
        text = {
            Column {
                if (excerpt.isNotEmpty()) {
                    Text(
                        excerpt,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("写下你的想法…") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

// ------------------------------------------------------------------ 排版设置（底部小卡片）

@Composable
fun AppearancePanel(
    settings: ReadSettings,
    barBg: Color,
    barFg: Color,
    accent: Color,
    onChange: ((ReadSettings) -> ReadSettings) -> Unit,
    onClose: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) } // 0 排版 1 背景与更多
    Column(
        Modifier
            .fillMaxWidth()
            .background(barBg, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        // 页签 + 收起
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PanelTab("排版", tab == 0, accent, barFg) { tab = 0 }
            Spacer(Modifier.width(14.dp))
            PanelTab("背景", tab == 1, accent, barFg) { tab = 1 }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onClose,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) { Text("收起", color = barFg, fontSize = 13.sp) }
        }
        if (tab == 0) {
            SettingRow("字号", barFg) {
                Stepper(
                    label = "${settings.fontSizeSp.toInt()}",
                    fg = barFg,
                    onMinus = { onChange { it.copy(fontSizeSp = (it.fontSizeSp - 1).coerceIn(12f, 34f)) } },
                    onPlus = { onChange { it.copy(fontSizeSp = (it.fontSizeSp + 1).coerceIn(12f, 34f)) } },
                )
            }
            SettingRow("行间距", barFg) {
                Stepper(
                    label = "${settings.lineSpacingSp.toInt()}",
                    fg = barFg,
                    onMinus = { onChange { it.copy(lineSpacingSp = (it.lineSpacingSp - 1).coerceIn(0f, 16f)) } },
                    onPlus = { onChange { it.copy(lineSpacingSp = (it.lineSpacingSp + 1).coerceIn(0f, 16f)) } },
                )
            }
            SettingRow("段间距", barFg) {
                Stepper(
                    label = "${settings.paragraphSpacingSp.toInt()}",
                    fg = barFg,
                    onMinus = { onChange { it.copy(paragraphSpacingSp = (it.paragraphSpacingSp - 2).coerceIn(0f, 24f)) } },
                    onPlus = { onChange { it.copy(paragraphSpacingSp = (it.paragraphSpacingSp + 2).coerceIn(0f, 24f)) } },
                )
            }
            SettingRow("翻页", barFg) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PageMode.entries.forEach { mode ->
                        val selected = settings.pageMode == mode
                        Text(
                            mode.label,
                            fontSize = 12.sp,
                            color = if (selected) accent else barFg.copy(alpha = 0.7f),
                            modifier = Modifier
                                .border(
                                    width = if (selected) 1.dp else 0.dp,
                                    color = if (selected) accent else Color.Transparent,
                                    shape = RoundedCornerShape(50),
                                )
                                .clickable { onChange { it.copy(pageMode = mode) } }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            SettingRow("衬线字体", barFg) {
                CompactSwitch(settings.serifFont, onChange, { it.copy(serifFont = !it.serifFont) })
            }
        } else {
            SettingRow("背景", barFg) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderSkins.ALL.forEachIndexed { idx, skin ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(Color(skin.bg), CircleShape)
                                .border(
                                    width = if (settings.themeIndex == idx) 2.dp else 1.dp,
                                    color = if (settings.themeIndex == idx) accent else Color.Gray.copy(alpha = 0.4f),
                                    shape = CircleShape,
                                )
                                .clickable { onChange { it.copy(themeIndex = idx) } },
                        )
                    }
                }
            }
            SettingRow("亮度", barFg) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "跟随系统",
                        fontSize = 12.sp,
                        color = barFg.copy(alpha = 0.7f),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    CompactSwitch(settings.brightnessFollowSystem, onChange, { it.copy(brightnessFollowSystem = !it.brightnessFollowSystem) })
                }
            }
            if (!settings.brightnessFollowSystem) {
                Slider(
                    value = settings.brightness,
                    onValueChange = { v -> onChange { it.copy(brightness = v) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
            SettingRow("音量键翻页", barFg) {
                CompactSwitch(settings.volumeKey, onChange, { it.copy(volumeKey = !it.volumeKey) })
            }
            SettingRow("屏幕常亮", barFg) {
                CompactSwitch(settings.keepScreenOn, onChange, { it.copy(keepScreenOn = !it.keepScreenOn) })
            }
        }
    }
}

@Composable
private fun PanelTab(label: String, selected: Boolean, accent: Color, fg: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }.padding(top = 8.dp, bottom = 4.dp),
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) accent else fg.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .width(18.dp)
                .height(2.dp)
                .background(if (selected) accent else Color.Transparent, RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
private fun CompactSwitch(
    checked: Boolean,
    onChange: ((ReadSettings) -> ReadSettings) -> Unit,
    transform: (ReadSettings) -> ReadSettings,
) {
    Switch(
        checked = checked,
        onCheckedChange = { onChange(transform) },
        modifier = Modifier.scale(0.8f),
    )
}

@Composable
private fun SettingRow(label: String, fg: Color, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = fg, fontSize = 13.sp, modifier = Modifier.width(68.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.weight(1f),
            content = content,
        )
    }
}

@Composable
private fun Stepper(label: String, fg: Color, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(
            onClick = onMinus,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            modifier = Modifier.size(30.dp),
        ) { Text("−", color = fg, fontSize = 18.sp) }
        Text(label, color = fg, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(30.dp))
        TextButton(
            onClick = onPlus,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            modifier = Modifier.size(30.dp),
        ) { Text("＋", color = fg, fontSize = 16.sp) }
    }
}
