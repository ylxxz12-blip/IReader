package com.example.zreader

import android.app.Application
import com.example.zreader.data.LibraryRepository
import com.example.zreader.data.NotesRepository
import com.example.zreader.data.SettingsRepository
import com.example.zreader.parser.BookImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var settingsRepo: SettingsRepository
    lateinit var library: LibraryRepository
    lateinit var notes: NotesRepository
    lateinit var importer: BookImporter

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepo = SettingsRepository(this)
        library = LibraryRepository(this)
        notes = NotesRepository(this)
        importer = BookImporter(this, library)
    }

    companion object {
        private lateinit var instance: App
        fun get(): App = instance
    }
}
