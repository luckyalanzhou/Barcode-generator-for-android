package com.luckyalanzhou.barcodegenerator

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.settingsDataStore by preferencesDataStore(name = "barcode_settings")

class SettingsStore(private val context: Context) {
    companion object {
        val BAR_COLOR = intPreferencesKey("style_bar_color")
        val BG_COLOR = intPreferencesKey("style_bg_color")
        val SHOW_TEXT = booleanPreferencesKey("style_show_text")
        val TEXT_POSITION = stringPreferencesKey("style_text_position")
        val TEXT_SIZE = floatPreferencesKey("style_text_size")
        val BAR_HEIGHT = intPreferencesKey("style_bar_height")
        val BAR_WIDTH = floatPreferencesKey("style_bar_width")
        val MARGIN = intPreferencesKey("style_margin")
        val SHOW_FORMAT = booleanPreferencesKey("style_show_format")
        val COLOR_SCHEME = stringPreferencesKey("style_color_scheme")
        val LAST_UPDATE_ERROR = stringPreferencesKey("last_update_error")
        val SETTINGS_MIGRATED = booleanPreferencesKey("settings_datastore_migrated")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cachedValues: Preferences = emptyPreferences()

    suspend fun load() {
        cachedValues = context.settingsDataStore.data.first()
    }

    fun <T> get(key: Preferences.Key<T>, default: T): T = cachedValues[key] ?: default

    fun setUpdateError(error: String): Job = write { it[LAST_UPDATE_ERROR] = error }

    fun saveStyle(style: StyleSettings): Job = write {
        it[BAR_COLOR] = style.barColor; it[BG_COLOR] = style.bgColor; it[SHOW_TEXT] = style.showText
        it[TEXT_POSITION] = style.textPosition; it[TEXT_SIZE] = style.textSize; it[BAR_HEIGHT] = style.barHeight
        it[BAR_WIDTH] = style.barWidth; it[MARGIN] = style.margin; it[SHOW_FORMAT] = style.showFormat
        it[COLOR_SCHEME] = style.colorScheme
    }

    fun markMigrated(): Job = write { it[SETTINGS_MIGRATED] = true }

    private fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit): Job {
        return scope.launch { context.settingsDataStore.edit { preferences -> block(preferences) } }
    }
}
