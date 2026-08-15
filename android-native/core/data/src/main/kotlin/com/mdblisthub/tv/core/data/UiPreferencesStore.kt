package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mdblisthub.tv.core.model.HubThemeVariant
import com.mdblisthub.tv.core.model.LibraryProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.uiPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "ui-preferences")

/**
 * How the box is set up, as opposed to who is signed in on it.
 *
 * Deliberately its own DataStore rather than more keys in [SessionStore]:
 * signing out calls `SessionStore.clear()`, and nobody expects signing out to
 * reset their theme — or, since [libraryProvider] moved in here, to silently
 * put their library back on a provider they had switched away from. Keeping
 * the two files apart is what makes that guarantee structural instead of a
 * rule someone has to remember when editing `clear()`.
 */
class UiPreferencesStore(context: Context) {

    private val store = context.applicationContext.uiPreferencesDataStore

    /**
     * A one-key mirror of the theme, kept only so the cold start can read it
     * without blocking.
     *
     * The palette is global state that the very first composition reads, so it
     * has to be known before the first frame — but resolving it from DataStore
     * meant `runBlocking` on the main thread during `Application.onCreate`:
     * file I/O plus protobuf parsing at the most latency-sensitive moment of
     * the whole lifecycle. `SharedPreferences` is the one storage API on
     * Android designed to be read synchronously, and its backing file is
     * loaded on a background thread the moment it is opened.
     *
     * DataStore stays the source of truth — this is written alongside it and
     * only ever read at startup, so the two cannot meaningfully diverge.
     */
    private val startupMirror =
        context.applicationContext.getSharedPreferences(STARTUP_MIRROR, Context.MODE_PRIVATE)

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

    /**
     * The persisted palette, readable synchronously — what
     * `Application.onCreate` uses so the first frame is painted in the right
     * theme without a blocking read. Same tolerance for a bad value as
     * [theme]: a preference is never worth failing a start over.
     */
    fun startupTheme(): HubThemeVariant =
        startupMirror.getString(KEY_THEME.name, null)
            ?.let { name -> runCatching { HubThemeVariant.valueOf(name) }.getOrNull() }
            ?: HubThemeVariant.NORMAL

    suspend fun saveTheme(variant: HubThemeVariant) {
        // `apply`, not `commit`: nothing this launch depends on it having
        // landed, and the next cold start is far away.
        startupMirror.edit().putString(KEY_THEME.name, variant.name).apply()
        store.edit { it[KEY_THEME] = variant.name }
    }

    val language: Flow<String> = store.data.map { prefs ->
        prefs[KEY_LANGUAGE] ?: "en" // Default language
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

    /**
     * Who answers for watchlist, collection, watched, up next and continue
     * watching. mdblist unless the user deliberately switched, and mdblist
     * again if the stored value is one this build no longer recognises — a
     * preference is never worth failing a read over, same as [theme].
     */
    val libraryProvider: Flow<LibraryProvider> = store.data.map { prefs ->
        prefs[KEY_LIBRARY_PROVIDER]
            ?.let { name -> runCatching { LibraryProvider.valueOf(name) }.getOrNull() }
            ?: LibraryProvider.MDBLIST
    }

    suspend fun currentLibraryProvider(): LibraryProvider = libraryProvider.first()

    suspend fun saveLibraryProvider(provider: LibraryProvider) {
        store.edit { it[KEY_LIBRARY_PROVIDER] = provider.name }
    }

    private companion object {
        const val STARTUP_MIRROR = "ui-preferences-startup"
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_LIBRARY_PROVIDER = stringPreferencesKey("library_provider")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_SUBTITLE_AUTO_DOWNLOAD = booleanPreferencesKey("subtitle_auto_download")
        val KEY_SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        val KEY_SUBTITLE_COLOR = stringPreferencesKey("subtitle_color")
        val KEY_AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
    }
}
