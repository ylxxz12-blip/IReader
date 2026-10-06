package com.example.zreader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.zreader.ui.bookshelf.BookshelfScreen
import com.example.zreader.ui.reader.ReaderKeyBus
import com.example.zreader.ui.reader.ReaderScreen
import com.example.zreader.ui.theme.ZReaderTheme

sealed class Screen {
    data object Shelf : Screen()
    data class Read(val bookId: Long) : Screen()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZReaderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var screen by remember { mutableStateOf<Screen>(Screen.Shelf) }
                    when (val s = screen) {
                        is Screen.Shelf -> BookshelfScreen(openBook = { screen = Screen.Read(it) })
                        is Screen.Read -> ReaderScreen(bookId = s.bookId, onExit = { screen = Screen.Shelf })
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = ReaderKeyBus.handler
        if (handler != null && handler(event.keyCode, event.action)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
