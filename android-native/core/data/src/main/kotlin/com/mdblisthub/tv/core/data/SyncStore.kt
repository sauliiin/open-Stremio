package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync")

/**
 * Whether Firebase addon sync is on for the current Google session. It is
 * deliberately disabled on sign-out so a newly selected account cannot push
 * the previous account's local state before its own cloud copy is restored.
 */
class SyncStore(context: Context) {

    private val store = context.applicationContext.syncDataStore

    val firebaseSyncEnabled: Flow<Boolean> = store.data.map { it[KEY_SYNC_ON] == true }

    suspend fun setFirebaseSyncEnabled(enabled: Boolean) {
        store.edit { it[KEY_SYNC_ON] = enabled }
    }

    private companion object {
        val KEY_SYNC_ON = booleanPreferencesKey("firebase_sync_on")
    }
}
