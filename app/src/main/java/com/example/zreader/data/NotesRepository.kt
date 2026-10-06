package com.example.zreader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 每本书一个 notes/{bookId}.json：批注（高亮/笔记）与书签 */
class NotesRepository(context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val dir = File(context.filesDir, "notes").apply { mkdirs() }
    private val mutex = Mutex()
    private val flows = HashMap<Long, MutableStateFlow<NotesFile>>()

    private fun flowFor(bookId: Long): MutableStateFlow<NotesFile> = synchronized(flows) {
        flows.getOrPut(bookId) { MutableStateFlow(load(bookId)) }
    }

    fun observe(bookId: Long): StateFlow<NotesFile> = flowFor(bookId)

    private fun fileFor(bookId: Long) = File(dir, "$bookId.json")

    private fun load(bookId: Long): NotesFile = try {
        val f = fileFor(bookId)
        if (f.exists()) json.decodeFromString<NotesFile>(f.readText()) else NotesFile()
    } catch (e: Exception) {
        NotesFile()
    }

    private fun NotesFile.withNextId(): NotesFile {
        val maxAnn = annotations.maxOfOrNull { it.id } ?: 0
        val maxBm = bookmarks.maxOfOrNull { it.id } ?: 0
        return copy(nextId = maxOf(maxAnn, maxBm) + 1)
    }

    private fun save(bookId: Long, flow: MutableStateFlow<NotesFile>, next: NotesFile) {
        flow.value = next
        val data = json.encodeToString(next.withNextId())
        runBlocking {
            withContext(Dispatchers.IO) {
                val f = fileFor(bookId)
                val tmp = File(dir, "$bookId.json.tmp")
                tmp.writeText(data)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }
        }
    }

    private fun <T> edit(bookId: Long, block: (NotesFile) -> Pair<NotesFile, T>): T {
        val flow = flowFor(bookId)
        var out: T? = null
        runBlocking {
            mutex.withLock {
                val (next, result) = block(flow.value)
                out = result
                save(bookId, flow, next)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    fun upsertAnnotation(bookId: Long, ann: Annotation) {
        edit(bookId) { cur ->
            val list = if (cur.annotations.any { it.id == ann.id }) {
                cur.annotations.map { if (it.id == ann.id) ann else it }
            } else {
                cur.annotations + ann
            }
            NotesFile(list, cur.bookmarks, cur.allocateId()) to Unit
        }
    }

    /** 与给定原文区间有任何重叠的批注整条删除 */
    fun removeAnnotationsOverlapping(bookId: Long, start: Int, end: Int): Boolean =
        edit(bookId) { cur ->
            val list = cur.annotations.filterNot { it.start < end && it.end > start }
            NotesFile(list, cur.bookmarks, cur.allocateId()) to (list.size != cur.annotations.size)
        }

    fun removeAnnotation(bookId: Long, id: Long) {
        edit(bookId) { cur ->
            NotesFile(cur.annotations.filterNot { it.id == id }, cur.bookmarks, cur.allocateId()) to Unit
        }
    }

    fun addBookmark(bookId: Long, bookmark: Bookmark) {
        edit(bookId) { cur ->
            NotesFile(cur.annotations, cur.bookmarks + bookmark, cur.allocateId()) to Unit
        }
    }

    fun removeBookmark(bookId: Long, id: Long) {
        edit(bookId) { cur ->
            NotesFile(cur.annotations, cur.bookmarks.filterNot { it.id == id }, cur.allocateId()) to Unit
        }
    }

    fun nextId(bookId: Long): Long {
        val n = observe(bookId).value
        val maxAnn = n.annotations.maxOfOrNull { it.id } ?: 0
        val maxBm = n.bookmarks.maxOfOrNull { it.id } ?: 0
        return maxOf(maxAnn, maxBm) + 1
    }
}
