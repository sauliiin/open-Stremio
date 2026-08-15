package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mdblisthub.tv.core.model.TraktAccount
import com.mdblisthub.tv.core.network.dto.TraktTokenDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.traktDataStore: DataStore<Preferences> by preferencesDataStore(name = "trakt")

/**
 * The linked Trakt account's tokens.
 *
 * Its own DataStore file rather than two more keys in [SessionStore], for the
 * same reason [UiPreferencesStore] is separate: `SessionStore.clear()` is what
 * signing out of mdblist does, and nobody expects replacing an mdblist key to
 * silently unlink a Trakt account. Keeping the files apart makes that
 * structural instead of a rule someone has to remember when editing `clear()`.
 *
 * The pair is stored together and always replaced together — a refresh token
 * is single use, so keeping an old one beside a new access token would leave
 * a value that is guaranteed to fail the next time it is needed.
 */
class TraktTokenStore(context: Context) {

    private val store = context.applicationContext.traktDataStore
    private val json = Json { ignoreUnknownKeys = true }

    val account: Flow<TraktAccount?> = store.data.map { prefs ->
        prefs[KEY_ACCOUNT]?.let { raw ->
            runCatching { json.decodeFromString<TraktAccount>(raw) }.getOrNull()
        }
    }

    val linked: Flow<Boolean> = store.data.map { it[KEY_ACCESS].isNullOrBlank().not() }

    suspend fun currentAccount(): TraktAccount? = account.first()

    suspend fun isLinked(): Boolean = linked.first()

    suspend fun accessToken(): String = store.data.first()[KEY_ACCESS].orEmpty()

    suspend fun refreshToken(): String = store.data.first()[KEY_REFRESH].orEmpty()

    /** Unix milliseconds the access token stops being accepted at. */
    suspend fun expiresAt(): Long = store.data.first()[KEY_EXPIRES_AT] ?: 0

    /**
     * Stores a freshly issued pair.
     *
     * The deadline is computed from Trakt's own `created_at` when it sent one,
     * because a device that has been asleep may have a clock several seconds
     * off the server's and `now + expires_in` would inherit that drift.
     */
    suspend fun save(token: TraktTokenDto) {
        val issuedAtMs = token.createdAt.takeIf { it > 0 }?.times(1000)
            ?: System.currentTimeMillis()
        store.edit {
            it[KEY_ACCESS] = token.accessToken
            it[KEY_REFRESH] = token.refreshToken
            it[KEY_EXPIRES_AT] = issuedAtMs + token.expiresIn * 1000
        }
    }

    suspend fun saveAccount(account: TraktAccount) {
        store.edit { it[KEY_ACCOUNT] = json.encodeToString(account) }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val KEY_ACCESS = stringPreferencesKey("access_token")
        val KEY_REFRESH = stringPreferencesKey("refresh_token")
        val KEY_EXPIRES_AT = longPreferencesKey("expires_at")
        val KEY_ACCOUNT = stringPreferencesKey("account")
    }
}
