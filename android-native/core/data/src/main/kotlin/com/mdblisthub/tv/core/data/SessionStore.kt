package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mdblisthub.tv.core.model.HubUser
import com.mdblisthub.tv.core.network.dto.FirebaseCatalogPreferenceDto
import com.mdblisthub.tv.core.network.dto.FirebaseListPreferenceDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * The signed-in account.
 *
 * The API key belongs to whoever typed it and never ships in the binary, so
 * this is the only place it lives. DataStore rather than a Room table because
 * it has to be readable before the database is opened — the very first thing a
 * cold start does is decide whether to show the login screen.
 */
class SessionStore(context: Context) {

    private val store = context.applicationContext.sessionDataStore
    private val json = Json { ignoreUnknownKeys = true }

    val apiKey: Flow<String> = store.data.map { it[KEY_API].orEmpty() }

    val user: Flow<HubUser?> = store.data.map { prefs ->
        prefs[KEY_USER]?.let { raw -> runCatching { json.decodeFromString<HubUser>(raw) }.getOrNull() }
    }

    /** UID that owns the cached MDBList credential. Prevents cross-account leaks. */
    val firebaseUid: Flow<String> = store.data.map { it[KEY_FIREBASE_UID].orEmpty() }

    /** True when Google is signed in but MDBList was intentionally skipped. */
    val mdblistSkipped: Flow<Boolean> = store.data.map { it[KEY_MDBLIST_SKIPPED] == true }

    /**
     * True for a session that was never authenticated with Google at all —
     * the MDBList key itself is the identity.
     *
     * Exists for boxes that have no Google account to offer, Fire TV chief
     * among them: Amazon's own account is what is signed in there, and
     * Credential Manager has nothing to show. Everything anchored to a
     * Firebase UID — addon sync, list-order sync — has no identity to anchor
     * to in this mode and simply stays off; see `AuthRepository.signedIn` and
     * the two sync repositories, which already no-op without a Google UID.
     */
    val mdblistOnly: Flow<Boolean> = store.data.map { it[KEY_MDBLIST_ONLY] == true }

    /** All row customizations, including deletion tombstones, owned by this Google session. */
    val listPreferences: Flow<List<FirebaseListPreferenceDto>> = store.data.map(::decodeListPreferences)

    /** Customizations for rows supplied by installed Stremio addon manifests. */
    val catalogPreferences: Flow<List<FirebaseCatalogPreferenceDto>> =
        store.data.map(::decodeCatalogPreferences)

    /** Protects offline edits from being overwritten by an older cloud snapshot. */
    val listPreferencesDirty: Flow<Boolean> =
        store.data.map { it[KEY_LIST_PREFERENCES_DIRTY] == true }

    /** MDBList rows the user removed from Open Stream without deleting them upstream. */
    val deletedListIds: Flow<Set<Long>> = listPreferences.map { preferences ->
        preferences.filter { it.deleted }.mapTo(mutableSetOf()) { it.id }
    }

    suspend fun currentKey(): String = apiKey.first()

    suspend fun currentUser(): HubUser? = user.first()

    suspend fun currentFirebaseUid(): String = firebaseUid.first()

    suspend fun isMdblistSkipped(): Boolean = mdblistSkipped.first()

    suspend fun isMdblistOnly(): Boolean = mdblistOnly.first()

    suspend fun currentListPreferences(): List<FirebaseListPreferenceDto> = listPreferences.first()

    suspend fun currentCatalogPreferences(): List<FirebaseCatalogPreferenceDto> =
        catalogPreferences.first()

    suspend fun areListPreferencesDirty(): Boolean = listPreferencesDirty.first()

    suspend fun currentDeletedListIds(): Set<Long> = deletedListIds.first()

    suspend fun saveListPreferences(
        preferences: List<FirebaseListPreferenceDto>,
        dirty: Boolean = true,
    ) {
        store.edit {
            it[KEY_LIST_PREFERENCES] = json.encodeToString(preferences.sortedBy { row -> row.position })
            it[KEY_LIST_PREFERENCES_DIRTY] = dirty
            it.remove(KEY_DELETED_LIST_IDS)
        }
    }

    suspend fun saveCatalogPreferences(
        preferences: List<FirebaseCatalogPreferenceDto>,
        dirty: Boolean = true,
    ) {
        store.edit {
            it[KEY_CATALOG_PREFERENCES] = json.encodeToString(
                preferences.sortedWith(compareBy(nullsLast()) { row -> row.position }),
            )
            it[KEY_LIST_PREFERENCES_DIRTY] = dirty
        }
    }

    /**
     * Clears the dirty marker only when the exact snapshot that was sent is
     * still current. A rename made while the Firebase request is in flight
     * must remain pending instead of being accidentally acknowledged by the
     * older request.
     */
    suspend fun markListPreferencesSyncedIfUnchanged(
        expectedLists: List<FirebaseListPreferenceDto>,
        expectedCatalogs: List<FirebaseCatalogPreferenceDto>,
    ): Boolean {
        var matched = false
        store.edit { preferences ->
            // Array order is only a storage detail; the explicit `position`
            // fields are the actual row order. Compare by identity so a
            // tombstone sorted during DataStore encoding does not make an
            // otherwise identical snapshot look dirty forever.
            matched = decodeListPreferences(preferences).sortedBy { it.id } ==
                expectedLists.sortedBy { it.id } &&
                decodeCatalogPreferences(preferences).sortedBy { it.key } ==
                expectedCatalogs.sortedBy { it.key }
            if (matched) preferences[KEY_LIST_PREFERENCES_DIRTY] = false
        }
        return matched
    }

    suspend fun markListDeleted(listId: Long) {
        val current = currentListPreferences()
        val previous = current.firstOrNull { it.id == listId }
        saveListPreferences(
            current.filterNot { it.id == listId } +
                (previous ?: FirebaseListPreferenceDto(id = listId)).copy(deleted = true),
        )
    }

    suspend fun clearDeletedLists() {
        saveListPreferences(currentListPreferences().filterNot { it.deleted })
    }

    suspend fun clearListPreferences() {
        store.edit {
            it.remove(KEY_LIST_PREFERENCES)
            it.remove(KEY_LIST_PREFERENCES_DIRTY)
            it.remove(KEY_DELETED_LIST_IDS)
        }
    }

    suspend fun save(key: String, user: HubUser, firebaseUid: String) {
        store.edit {
            it[KEY_API] = key
            it[KEY_USER] = json.encodeToString(user)
            it[KEY_FIREBASE_UID] = firebaseUid
            it[KEY_MDBLIST_SKIPPED] = false
            it[KEY_MDBLIST_ONLY] = false
        }
    }

    /**
     * Signs in with nothing but the MDBList key — no Google, no Firebase UID.
     * `firebaseUid` is cleared rather than left stale, since a leftover value
     * from a previous Google session would otherwise satisfy the ownership
     * check other code paths run against it.
     */
    suspend fun saveMdblistOnly(key: String, user: HubUser) {
        store.edit {
            it[KEY_API] = key
            it[KEY_USER] = json.encodeToString(user)
            it.remove(KEY_FIREBASE_UID)
            it[KEY_MDBLIST_SKIPPED] = false
            it[KEY_MDBLIST_ONLY] = true
        }
    }

    suspend fun markMdblistSkipped(firebaseUid: String) {
        store.edit {
            it.remove(KEY_API)
            it.remove(KEY_USER)
            it[KEY_FIREBASE_UID] = firebaseUid
            it[KEY_MDBLIST_SKIPPED] = true
            it.remove(KEY_DELETED_LIST_IDS)
            it.remove(KEY_LIST_PREFERENCES)
            it.remove(KEY_LIST_PREFERENCES_DIRTY)
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val KEY_API = stringPreferencesKey("api_key")
        val KEY_USER = stringPreferencesKey("user")
        val KEY_FIREBASE_UID = stringPreferencesKey("firebase_uid")
        val KEY_MDBLIST_SKIPPED = booleanPreferencesKey("mdblist_skipped")
        val KEY_MDBLIST_ONLY = booleanPreferencesKey("mdblist_only")
        val KEY_DELETED_LIST_IDS = stringSetPreferencesKey("deleted_list_ids")
        val KEY_LIST_PREFERENCES = stringPreferencesKey("list_preferences")
        val KEY_CATALOG_PREFERENCES = stringPreferencesKey("catalog_preferences")
        val KEY_LIST_PREFERENCES_DIRTY = booleanPreferencesKey("list_preferences_dirty")
    }

    private fun decodeListPreferences(preferences: Preferences): List<FirebaseListPreferenceDto> {
        val encoded = preferences[KEY_LIST_PREFERENCES]
        if (encoded != null) {
            return runCatching {
                json.decodeFromString<List<FirebaseListPreferenceDto>>(encoded)
            }.getOrDefault(emptyList())
        }

        // One-time compatibility with builds that persisted only deleted IDs.
        return preferences[KEY_DELETED_LIST_IDS].orEmpty().mapNotNull { raw ->
            raw.toLongOrNull()?.let { FirebaseListPreferenceDto(id = it, deleted = true) }
        }
    }

    private fun decodeCatalogPreferences(preferences: Preferences): List<FirebaseCatalogPreferenceDto> =
        preferences[KEY_CATALOG_PREFERENCES]?.let { encoded ->
            runCatching {
                json.decodeFromString<List<FirebaseCatalogPreferenceDto>>(encoded)
            }.getOrDefault(emptyList())
        }.orEmpty()
}
