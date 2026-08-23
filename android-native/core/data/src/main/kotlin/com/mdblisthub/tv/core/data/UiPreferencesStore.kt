package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
        val autotrailer = prefs[KEY_AUTOTRAILER] ?: false
        val variant = prefs[KEY_THEME]
            ?.let { name -> runCatching { HubThemeVariant.valueOf(name) }.getOrNull() }
            ?: HubThemeVariant.NORMAL
        when {
            autotrailer && variant == HubThemeVariant.NETFLIXY -> HubThemeVariant.CYBERFLIX
            autotrailer && variant == HubThemeVariant.PRIMEFLY -> HubThemeVariant.OPTIMUS_PRIME
            !autotrailer && variant == HubThemeVariant.CYBERFLIX -> HubThemeVariant.NETFLIXY
            !autotrailer && variant == HubThemeVariant.OPTIMUS_PRIME -> HubThemeVariant.PRIMEFLY
            else -> variant
        }
    }

    suspend fun currentTheme(): HubThemeVariant = theme.first()

    /**
     * The persisted palette, readable synchronously — what
     * `Application.onCreate` uses so the first frame is painted in the right
     * theme without a blocking read. Same tolerance for a bad value as
     * [theme]: a preference is never worth failing a start over.
     */
    fun startupTheme(): HubThemeVariant {
        val autotrailer = startupMirror.getBoolean(KEY_AUTOTRAILER.name, false)
        val variant = startupMirror.getString(KEY_THEME.name, null)
            ?.let { name -> runCatching { HubThemeVariant.valueOf(name) }.getOrNull() }
            ?: HubThemeVariant.NORMAL
        return when {
            autotrailer && variant == HubThemeVariant.NETFLIXY -> HubThemeVariant.CYBERFLIX
            autotrailer && variant == HubThemeVariant.PRIMEFLY -> HubThemeVariant.OPTIMUS_PRIME
            !autotrailer && variant == HubThemeVariant.CYBERFLIX -> HubThemeVariant.NETFLIXY
            !autotrailer && variant == HubThemeVariant.OPTIMUS_PRIME -> HubThemeVariant.PRIMEFLY
            else -> variant
        }
    }

    suspend fun saveTheme(variant: HubThemeVariant) {
        // `apply`, not `commit`: nothing this launch depends on it having
        // landed, and the next cold start is far away.
        startupMirror.edit().putString(KEY_THEME.name, variant.name).apply()
        store.edit { it[KEY_THEME] = variant.name }
    }

    /** True after the first-access theme chooser has been completed. */
    val setupCompleted: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_SETUP_COMPLETED] ?: (KEY_THEME in prefs)
    }

    suspend fun saveSetupCompleted(completed: Boolean) {
        store.edit { it[KEY_SETUP_COMPLETED] = completed }
    }

    val autotrailer: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_AUTOTRAILER] ?: false
    }

    /** Whether focused poster cards may expand into a landscape preview. */
    val posterLandscapeTransformation: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_POSTER_LANDSCAPE_TRANSFORMATION] ?: true
    }

    suspend fun savePosterLandscapeTransformation(enabled: Boolean) {
        store.edit { it[KEY_POSTER_LANDSCAPE_TRANSFORMATION] = enabled }
    }

    /** Whether the bundled opening video is shown when the app starts. */
    val introEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_INTRO_ENABLED] ?: true
    }

    /**
     * Read before the first composition so a disabled intro never flashes
     * while DataStore is loading.
     */
    fun startupIntroEnabled(): Boolean =
        startupMirror.getBoolean(KEY_INTRO_ENABLED.name, true)

    suspend fun saveIntroEnabled(enabled: Boolean) {
        startupMirror.edit().putBoolean(KEY_INTRO_ENABLED.name, enabled).apply()
        store.edit { it[KEY_INTRO_ENABLED] = enabled }
    }

    /**
     * Whether the home opens on the "destaques" hero.
     *
     * Defaults to on: it is the home screen's own shape, not an extra, and a
     * new install should look the way the app is meant to look.
     */
    val spotlightHero: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_SPOTLIGHT_HERO] ?: true
    }

    /**
     * The same preference, readable synchronously.
     *
     * Exists for the one frame that cannot wait for DataStore: the home
     * decides whether to lay out a hero before the flow has emitted, and
     * without this a viewer who turned the hero *off* would still get a
     * screen's worth of skeleton on every cold start, followed by the rows
     * jumping up to replace it. Same mirror, and same reason, as
     * [startupTheme].
     */
    fun startupSpotlightHero(): Boolean =
        startupMirror.getBoolean(KEY_SPOTLIGHT_HERO.name, true)

    suspend fun saveSpotlightHero(enabled: Boolean) {
        startupMirror.edit().putBoolean(KEY_SPOTLIGHT_HERO.name, enabled).apply()
        store.edit { it[KEY_SPOTLIGHT_HERO] = enabled }
    }

    suspend fun saveAutotrailer(enabled: Boolean) {
        startupMirror.edit().putBoolean(KEY_AUTOTRAILER.name, enabled).apply()
        store.edit { it[KEY_AUTOTRAILER] = enabled }
        val current = currentTheme()
        if (enabled) {
            if (current == HubThemeVariant.NETFLIXY) {
                saveTheme(HubThemeVariant.CYBERFLIX)
            } else if (current == HubThemeVariant.PRIMEFLY) {
                saveTheme(HubThemeVariant.OPTIMUS_PRIME)
            }
        } else {
            if (current == HubThemeVariant.CYBERFLIX) {
                saveTheme(HubThemeVariant.NETFLIXY)
            } else if (current == HubThemeVariant.OPTIMUS_PRIME) {
                saveTheme(HubThemeVariant.PRIMEFLY)
            }
        }
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

    val subtitleTextOpacity: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_SUBTITLE_TEXT_OPACITY] ?: 100
    }
    suspend fun saveSubtitleTextOpacity(opacity: Int) {
        store.edit { it[KEY_SUBTITLE_TEXT_OPACITY] = opacity }
    }

    val subtitleBackgroundEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_SUBTITLE_BACKGROUND_ENABLED] ?: false
    }
    suspend fun saveSubtitleBackgroundEnabled(enabled: Boolean) {
        store.edit { it[KEY_SUBTITLE_BACKGROUND_ENABLED] = enabled }
    }

    val subtitleBackgroundOpacity: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_SUBTITLE_BACKGROUND_OPACITY] ?: 100
    }
    suspend fun saveSubtitleBackgroundOpacity(opacity: Int) {
        store.edit { it[KEY_SUBTITLE_BACKGROUND_OPACITY] = opacity }
    }

    val audioLanguage: Flow<String> = store.data.map { prefs ->
        prefs[KEY_AUDIO_LANGUAGE] ?: "en"
    }
    suspend fun saveAudioLanguage(lang: String) {
        store.edit { it[KEY_AUDIO_LANGUAGE] = lang }
    }

    /**
     * Whether an unwatched episode's still is blurred on the detail screen, so
     * what is left to watch stands apart from what is already seen. Off by
     * default: `WatchedBadge` already marks watched episodes card by card,
     * and this is an opt-in for viewers who want that told at a glance across
     * a whole season grid instead.
     *
     * On API 31+ a Gaussian blur is used; older devices fall back to reduced
     * opacity for the same visual signal at no extra cost.
     */
    val dimUnwatchedEpisodes: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_DIM_UNWATCHED_EPISODES] ?: false
    }
    suspend fun saveDimUnwatchedEpisodes(enabled: Boolean) {
        store.edit { it[KEY_DIM_UNWATCHED_EPISODES] = enabled }
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
        val KEY_SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        val KEY_AUTOTRAILER = booleanPreferencesKey("autotrailer")
        val KEY_POSTER_LANDSCAPE_TRANSFORMATION =
            booleanPreferencesKey("poster_landscape_transformation")
        val KEY_INTRO_ENABLED = booleanPreferencesKey("intro_enabled")
        val KEY_SPOTLIGHT_HERO = booleanPreferencesKey("spotlight_hero")
        val KEY_LIBRARY_PROVIDER = stringPreferencesKey("library_provider")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_SUBTITLE_AUTO_DOWNLOAD = booleanPreferencesKey("subtitle_auto_download")
        val KEY_SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        val KEY_SUBTITLE_COLOR = stringPreferencesKey("subtitle_color")
        val KEY_SUBTITLE_TEXT_OPACITY = intPreferencesKey("subtitle_text_opacity")
        val KEY_SUBTITLE_BACKGROUND_ENABLED = booleanPreferencesKey("subtitle_background_enabled")
        val KEY_SUBTITLE_BACKGROUND_OPACITY = intPreferencesKey("subtitle_background_opacity")
        val KEY_AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
        val KEY_DIM_UNWATCHED_EPISODES = booleanPreferencesKey("dim_unwatched_episodes")
    }
}
