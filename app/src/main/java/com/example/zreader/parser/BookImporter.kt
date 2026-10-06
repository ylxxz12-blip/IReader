package com.example.zreader.parser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.zreader.data.Book
import com.example.zreader.data.BookFormat
import com.example.zreader.data.LibraryRepository
import com.example.zreader.data.LangMode
import com.example.zreader.reader.BookText
import com.example.zreader.sample.SampleBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 从 SAF 导入本地书籍到应用私有目录，并完成解析入库 */
class BookImporter(private val context: Context, private val library: LibraryRepository) {

    data class ImportResult(val imported: List<Book>, val skipped: List<String>, val failed: List<String>)

    suspend fun importUris(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val imported = ArrayList<Book>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<String>()
        for (uri in uris) {
            try {
                importOne(uri, imported, skipped, failed)
            } catch (e: Exception) {
                failed.add(queryName(uri) ?: "未知文件")
            }
        }
        ImportResult(imported, skipped, failed)
    }

    private suspend fun importOne(uri: Uri, imported: MutableList<Book>, skipped: MutableList<String>, failed: MutableList<String>) {
        val name = queryName(uri) ?: "未知文件.txt"
        val size = querySize(uri)
        val baseName = name.substringBeforeLast('.')
        if (size > 0 && library.books.value.any { it.title == baseName && it.fileSize == size }) {
            skipped.add(name)
            return
        }
        val id = library.allocateId()
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
        val target = File(library.bookFileDir(), "${id}_$safe")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("无法读取所选文件")

        val isEpub = name.endsWith(".epub", true)
        val book = if (isEpub) parseEpubFile(target, baseName) else parseTxtFile(target, baseName)
        library.add(book)
        imported.add(book)
    }

    private fun parseTxtFile(file: File, fallbackTitle: String): Book {
        val bytes = file.readBytes()
        val decoded = TxtParser.decode(bytes)
        val bt = BookText(decoded.text, fallbackTitle)
        return Book(
            id = file.name.substringBefore('_').toLongOrNull() ?: 0L,
            title = fallbackTitle,
            author = "未知作者",
            path = file.absolutePath,
            format = BookFormat.TXT,
            charset = decoded.charset,
            fileSize = file.length(),
            totalChars = decoded.text.length,
            coverColor = coverColorFor(fallbackTitle),
            bilingual = bt.bilingual,
            langMode = if (bt.bilingual) LangMode.BOTH else LangMode.BOTH,
            addedAt = System.currentTimeMillis(),
            lastReadAt = System.currentTimeMillis(),
        )
    }

    private fun parseEpubFile(file: File, fallbackTitle: String): Book {
        val epub = EpubParser.parse(file, fallbackTitle)
        File(file.absolutePath + ".txt").writeText(epub.text)
        val bt = BookText(epub.text, epub.title)
        return Book(
            id = file.name.substringBefore('_').toLongOrNull() ?: 0L,
            title = epub.title,
            author = epub.author.ifEmpty { "未知作者" },
            path = file.absolutePath,
            format = BookFormat.EPUB,
            charset = "UTF-8",
            fileSize = file.length(),
            totalChars = epub.text.length,
            coverColor = coverColorFor(epub.title),
            bilingual = bt.bilingual,
            addedAt = System.currentTimeMillis(),
            lastReadAt = System.currentTimeMillis(),
        )
    }

    /** 打开书籍时构建内存文本模型（EPUB 首次打开时生成纯文本缓存） */
    suspend fun loadBookText(book: Book): BookText? = withContext(Dispatchers.IO) {
        try {
            when (book.format) {
                BookFormat.TXT -> {
                    val bytes = File(book.path).readBytes()
                    val decoded = TxtParser.decode(bytes, book.charset)
                    BookText(decoded.text, book.title)
                }
                BookFormat.EPUB -> {
                    val sidecar = File(book.path + ".txt")
                    if (!sidecar.exists()) {
                        val text = EpubParser.parse(File(book.path), book.title).text
                        sidecar.writeText(text)
                    }
                    BookText(sidecar.readText(), book.title)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 首次启动导入示例双语书 */
    suspend fun importSample(): Book? = withContext(Dispatchers.IO) {
        if (library.books.value.any { it.title == SampleBook.TITLE }) return@withContext null
        val id = library.allocateId()
        val file = File(library.bookFileDir(), "${id}_sample_bilingual.txt")
        file.writeText(SampleBook.CONTENT)
        val bt = BookText(SampleBook.CONTENT, SampleBook.TITLE)
        val book = Book(
            id = id,
            title = SampleBook.TITLE,
            author = SampleBook.AUTHOR,
            path = file.absolutePath,
            format = BookFormat.TXT,
            charset = "UTF-8",
            fileSize = file.length(),
            totalChars = SampleBook.CONTENT.length,
            coverColor = coverColorFor(SampleBook.TITLE),
            bilingual = bt.bilingual,
            lastReadAt = System.currentTimeMillis(),
            addedAt = System.currentTimeMillis(),
        )
        library.add(book)
        book
    }

    private fun coverColorFor(title: String): Int = Math.floorMod(title.hashCode(), 8)

    private fun queryName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

    private fun querySize(uri: Uri): Long =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
        } ?: 0L
}
