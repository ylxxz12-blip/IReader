package com.example.zreader.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 排版与阅读偏好，SharedPreferences 持久化 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("read_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ReadSettings> = _settings

    private fun read(): ReadSettings {
        return ReadSettings(
            fontSizeSp = prefs.getFloat("fontSizeSp", 19f),
            lineSpacingSp = prefs.getFloat("lineSpacingSp", 5f),
            paragraphSpacingSp = prefs.getFloat("paragraphSpacingSp", 8f),
            pageMode = PageMode.fromOrdinalSafe(prefs.getInt("pageMode", PageMode.SIMULATION.ordinal)),
            themeIndex = prefs.getInt("themeIndex", 1).coerceIn(0, 4),
            volumeKey = prefs.getBoolean("volumeKey", true),
            keepScreenOn = prefs.getBoolean("keepScreenOn", false),
            serifFont = prefs.getBoolean("serifFont", false),
            marginHDp = prefs.getFloat("marginHDp", 18f),
            brightnessFollowSystem = prefs.getBoolean("brightnessFollowSystem", true),
            brightness = prefs.getFloat("brightness", 0.5f),
        )
    }

    fun update(transform: (ReadSettings) -> ReadSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putFloat("fontSizeSp", next.fontSizeSp)
            .putFloat("lineSpacingSp", next.lineSpacingSp)
            .putFloat("paragraphSpacingSp", next.paragraphSpacingSp)
            .putInt("pageMode", next.pageMode.ordinal)
            .putInt("themeIndex", next.themeIndex)
            .putBoolean("volumeKey", next.volumeKey)
            .putBoolean("keepScreenOn", next.keepScreenOn)
            .putBoolean("serifFont", next.serifFont)
            .putFloat("marginHDp", next.marginHDp)
            .putBoolean("brightnessFollowSystem", next.brightnessFollowSystem)
            .putFloat("brightness", next.brightness)
            .apply()
    }
}
