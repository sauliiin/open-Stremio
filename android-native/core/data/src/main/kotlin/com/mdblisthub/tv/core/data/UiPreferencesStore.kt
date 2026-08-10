package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mdblisthub.tv.core.model.HubThemeVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.uiPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "ui-preferences")

/**
 * How the box is set up to look, as opposed to who is signed in on it.
 *
 * Deliberately its own DataStore rather than two more keys in [SessionStore]:
 * signing out calls `SessionStore.clear()`, and nobody expects signing out to
 * reset their theme. Keeping the two files apart is what makes that guarantee
 * structural instead of a rule someone has to remember when editing `clear()`.
 */
class UiPreferencesStore(context: Context) {

    private val store = context.applicationContext.uiPreferencesDataStore

    /**
     * An unreadable or unrecognised value resolves to [HubThemeVariant.NORMAL]
     * rather than throwing — a preference is never worth failing a start over,
     * and a renamed enum constant would otherwise do exactly that.
     */
    val theme: Flow<HubThemeVariant> = store.data.map { prefs ->
        prefs[KEY_THEME]
            ?.let { name -> runCatching { HubThemeVariant.valueOf(name) }.getOrNull() }
            ?: HubThemeVariant.NORMAL
    }

    suspend fun currentTheme(): HubThemeVariant = theme.first()

    suspend fun saveTheme(variant: HubThemeVariant) {
        store.edit { it[KEY_THEME] = variant.name }
    }

    val language: Flow<String> = store.data.map { prefs ->
        prefs[KEY_LANGUAGE] ?: "pt" // Default language
    }
    suspend fun saveLanguage(lang: String) {
        store.edit { it[KEY_LANGUAGE] = lang }
    }

    val subtitleAutoDownload: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_SUBTITLE_AUTO_DOWNLOAD] ?: true
    }
    suspend fun saveSubtitleAutoDownload(enabled: Boolean) {
        store.edit { it[KEY_SUBTITLE_AUTO_DOWNLOAD] = enabled }
    }

    val subtitleLanguage: Flow<String> = store.data.map { prefs ->
        prefs[KEY_SUBTITLE_LANGUAGE] ?: "pt"
    }
    suspend fun saveSubtitleLanguage(lang: String) {
        store.edit { it[KEY_SUBTITLE_LANGUAGE] = lang }
    }

    val subtitleColor: Flow<String> = store.data.map { prefs ->
        prefs[KEY_SUBTITLE_COLOR] ?: "white"
    }
    suspend fun saveSubtitleColor(color: String) {
        store.edit { it[KEY_SUBTITLE_COLOR] = color }
    }

    val audioLanguage: Flow<String> = store.data.map { prefs ->
        prefs[KEY_AUDIO_LANGUAGE] ?: "en"
    }
    suspend fun saveAudioLanguage(lang: String) {
        store.edit { it[KEY_AUDIO_LANGUAGE] = lang }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_SUBTITLE_AUTO_DOWNLOAD = booleanPreferencesKey("subtitle_auto_download")
        val KEY_SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        val KEY_SUBTITLE_COLOR = stringPreferencesKey("subtitle_color")
        val KEY_AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
    }
}

