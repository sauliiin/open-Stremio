package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme")
    }
}
