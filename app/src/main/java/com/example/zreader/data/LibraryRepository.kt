package com.example.zreader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 书架：books.json 持久化，StateFlow 供 UI 观察 */
class LibraryRepository(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file: File get() = File(File(context.filesDir, "library"), "books.json")
    private val mutex = Mutex()

    private val _books = MutableStateFlow(load())
    val books: StateFlow<List<Book>> = _books

    private fun load(): List<Book> {
        return try {
            if (file.exists()) json.decodeFromString<LibraryFile>(file.readText()).books else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun persistLocked(books: List<Book>) {
        withContext(Dispatchers.IO) {
            val f = file
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(LibraryFile(books, nextIdOf(books))))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private fun nextIdOf(books: List<Book>): Long = (books.maxOfOrNull { it.id } ?: 0L) + 1

    fun allocateId(): Long = nextIdOf(_books.value)

    suspend fun add(book: Book) = mutex.withLock {
        val list = _books.value.toMutableList()
        list.add(book)
        _books.value = list
        persistLocked(list)
    }

    suspend fun update(book: Book) = mutex.withLock {
        val list = _books.value.map { if (it.id == book.id) book else it }
        _books.value = list
        persistLocked(list)
    }

    suspend fun updateProgress(id: Long, progress: Int, lastReadAt: Long) = mutex.withLock {
        val cur = _books.value.firstOrNull { it.id == id } ?: return@withLock
        if (cur.progress == progress && cur.lastReadAt == lastReadAt) return@withLock
        val list = _books.value.map { if (it.id == id) it.copy(progress = progress, lastReadAt = lastReadAt) else it }
        _books.value = list
        persistLocked(list)
    }

    /** @param deleteFile 同时删除应用内导入的文件副本 */
    suspend fun remove(id: Long, deleteFile: Boolean) = mutex.withLock {
        val cur = _books.value.firstOrNull { it.id == id }
        val list = _books.value.filterNot { it.id == id }
        _books.value = list
        persistLocked(list)
        if (deleteFile && cur != null) {
            withContext(Dispatchers.IO) {
                File(cur.path).delete()
                if (cur.format == BookFormat.EPUB) File(cur.path + ".txt").delete()
                File(File(context.filesDir, "notes"), "$id.json").delete()
            }
        }
    }

    fun bookFileDir(): File = File(context.filesDir, "books").apply { mkdirs() }
}
