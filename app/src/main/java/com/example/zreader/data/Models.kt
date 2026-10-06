package com.example.zreader.data

import kotlinx.serialization.Serializable

/** 书籍文件格式 */
enum class BookFormat { TXT, EPUB }

/** 双语阅读模式（仅对识别为双语的书籍生效） */
enum class LangMode(val label: String) {
    BOTH("双语"),
    ZH("中文"),
    EN("英文");

    companion object {
        fun fromOrdinalSafe(v: Int): LangMode = entries.getOrElse(v) { BOTH }
    }
}

/** 翻页方式 */
enum class PageMode(val label: String) {
    SIMULATION("仿真"),
    COVER("覆盖"),
    SLIDE("平移"),
    NONE("无动画");

    companion object {
        fun fromOrdinalSafe(v: Int): PageMode = entries.getOrElse(v) { SIMULATION }
    }
}

@Serializable
data class Book(
    val id: Long,
    val title: String,
    val author: String,
    /** 应用私有目录内的文件绝对路径 */
    val path: String,
    val format: BookFormat,
    val charset: String = "UTF-8",
    val fileSize: Long = 0,
    val totalChars: Int = 0,
    /** 阅读进度：全书原始字符偏移（当前页起始） */
    val progress: Int = 0,
    val coverColor: Int = 0,
    val bilingual: Boolean = false,
    val langMode: LangMode = LangMode.BOTH,
    val addedAt: Long = 0,
    val lastReadAt: Long = 0,
) {
    val percent: Int
        get() = if (totalChars <= 0) 0 else (progress * 100L / totalChars).toInt().coerceIn(0, 100)

    val finished: Boolean get() = totalChars > 0 && progress >= totalChars - 1
}

/** 批注：原文高亮，note 非空时同时是一条笔记 */
@Serializable
data class Annotation(
    val id: Long,
    val bookId: Long,
    /** 原始字符偏移，含头不含尾 */
    val start: Int,
    val end: Int,
    /** 高亮颜色索引 */
    val color: Int = 0,
    val note: String? = null,
    /** 创建时保存的原文摘录 */
    val excerpt: String = "",
    val createdAt: Long = 0,
)

/** 书签 */
@Serializable
data class Bookmark(
    val id: Long,
    val bookId: Long,
    val offset: Int,
    val excerpt: String = "",
    val createdAt: Long = 0,
)

@Serializable
data class NotesFile(
    val annotations: List<Annotation> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    private val nextId: Long = 1,
) {
    fun allocateId(): Long = nextId
}

@Serializable
data class LibraryFile(val books: List<Book> = emptyList(), private val nextId: Long = 1)

/** 阅读排版设置 */
data class ReadSettings(
    val fontSizeSp: Float = 19f,
    /** 行间距附加（sp） */
    val lineSpacingSp: Float = 5f,
    /** 段间距（sp） */
    val paragraphSpacingSp: Float = 8f,
    val pageMode: PageMode = PageMode.SIMULATION,
    /** 阅读主题索引，见 ReaderSkins */
    val themeIndex: Int = 1,
    /** 音量键翻页 */
    val volumeKey: Boolean = true,
    val keepScreenOn: Boolean = false,
    /** 衬线字体 */
    val serifFont: Boolean = false,
    /** 页面左右边距（dp） */
    val marginHDp: Float = 18f,
    val brightnessFollowSystem: Boolean = true,
    /** 0f..1f，仅在不跟随系统时生效 */
    val brightness: Float = 0.5f,
)
