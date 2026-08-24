package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.simklDataStore by preferencesDataStore(name = "simkl")

class SimklTokenStore(context: Context) {
    private val store = context.applicationContext.simklDataStore
    @Volatile private var cachedAccessToken: String? = null
    val linked = store.data.map { !it[KEY_ACCESS].isNullOrBlank() }
    suspend fun isLinked() = linked.first()
    suspend fun accessToken(): String = cachedAccessToken ?: store.data.first()[KEY_ACCESS].orEmpty().also {
        cachedAccessToken = it
    }
    suspend fun save(token: String) {
        store.edit { it[KEY_ACCESS] = token }
        cachedAccessToken = token
    }
    suspend fun clear() {
        store.edit { it.clear() }
        cachedAccessToken = ""
    }
    private companion object { val KEY_ACCESS = stringPreferencesKey("access_token") }
}
