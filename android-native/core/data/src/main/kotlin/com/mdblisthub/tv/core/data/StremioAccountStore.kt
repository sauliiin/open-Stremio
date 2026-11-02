package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mdblisthub.tv.core.model.StremioAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.stremioAccountDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "stremio_account")

class StremioAccountStore(context: Context) {
    private val store = context.applicationContext.stremioAccountDataStore

    val account: Flow<StremioAccount?> = store.data.map { preferences ->
        val authKey = preferences[KEY_AUTH].orEmpty()
        if (authKey.isBlank()) null
        else StremioAccount(authKey = authKey, email = preferences[KEY_EMAIL].orEmpty())
    }

    suspend fun current(): StremioAccount? = account.first()

    suspend fun save(account: StremioAccount) {
        store.edit {
            it[KEY_AUTH] = account.authKey
            it[KEY_EMAIL] = account.email
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val KEY_AUTH = stringPreferencesKey("auth_key")
        val KEY_EMAIL = stringPreferencesKey("email")
    }
}
