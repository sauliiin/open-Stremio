package com.mdblisthub.tv.core.data.repository

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseUser
import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.StremioAccountStore
import com.mdblisthub.tv.core.data.SyncStore
import com.mdblisthub.tv.core.data.mapper.toDomain
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.model.AppError
import com.mdblisthub.tv.core.model.HubUser
import com.mdblisthub.tv.core.model.fail
import com.mdblisthub.tv.core.model.orFail
import com.mdblisthub.tv.core.model.requireOrFail
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.HttpClients
import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.SyncApi
import com.mdblisthub.tv.core.network.dto.FirebaseProfileDto
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull

data class GoogleAccountInfo(
    val uid: String,
    val email: String,
    val displayName: String?,
    val photoUrl: String?,
)

/**
 * Google/Firebase owns the app session. MDBList remains a linked data source:
 * its API still requires a personal API key, which is validated once and then
 * stored below the authenticated Firebase UID for use on the user's devices.
 */
class AuthRepository(
    private val api: MdblistApi,
    private val syncApi: SyncApi,
    private val session: SessionStore,
    private val syncStore: SyncStore,
    private val stremioAccountStore: StremioAccountStore,
    private val database: HubDatabase,
) {
    private val firebase = FirebaseAuth.getInstance()
    private val googleState = MutableStateFlow(firebase.currentUser?.toAccount())

    val googleAccount: StateFlow<GoogleAccountInfo?> = googleState.asStateFlow()
    val user: Flow<HubUser?> = session.user
    val mdblistLinked: Flow<Boolean> = session.apiKey.map { it.isNotBlank() }

    /**
     * True for a session with no Google account behind it — see
     * `SessionStore.mdblistOnly`. Read by the UI to hide the two things that
     * have nothing to sync to in this mode: the addon cloud-sync card and the
     * account-switch action.
     */
    val isMdblistOnly: Flow<Boolean> = session.mdblistOnly

    val signedIn: Flow<Boolean> = combine(
        googleAccount,
        session.apiKey,
        session.firebaseUid,
        session.mdblistSkipped,
        session.mdblistOnly,
    ) { google, key, ownerUid, skipped, mdblistOnly ->
        when {
            // The key alone is the whole session in this mode — there is no
            // UID to check it against.
            mdblistOnly -> key.isNotBlank()
            google != null -> ownerUid == google.uid && (key.isNotBlank() || skipped)
            else -> false
        }
    }

    init {
        firebase.addAuthStateListener { auth ->
            googleState.value = auth.currentUser?.toAccount()
        }
    }

    /** Exchanges the Google ID token for a Firebase session, then restores the linked key. */
    suspend fun signInWithGoogleIdToken(idToken: String): Result<GoogleAccountInfo> = runCatching {
        val previousUid = session.currentFirebaseUid()
        val result = firebase
            .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
            .awaitResult()
        val firebaseUser = result.user.orFail { AppError.FirebaseNoAccount }

        if (previousUid.isNotBlank() && previousUid != firebaseUser.uid) {
            syncStore.setFirebaseSyncEnabled(false)
            stremioAccountStore.clear()
            session.clear()
            clearAllData()
        }

        googleState.value = firebaseUser.toAccount()
        restoreLinkedMdblist(firebaseUser, allowLegacyMigration = previousUid.isBlank())
        firebaseUser.toAccount()
    }

    /** Validates and links the user's MDBList credential to the current Google UID. */
    suspend fun linkMdblist(rawKey: String): Result<HubUser> = runCatching {
        val firebaseUser = firebase.currentUser.orFail { AppError.GoogleSignInRequired }
        val key = rawKey.trim()
        requireOrFail(key.isNotEmpty()) { AppError.MdblistKeyRequired }

        val user = api.user(key).toDomain()
        val oldKey = session.currentKey()
        writeProfile(firebaseUser, key)
        if (oldKey != key) {
            session.clearListPreferences()
            clearMdblistData()
        }
        session.save(key, user, firebaseUser.uid)
        user
    }

    /**
     * Enters with nothing but a validated MDBList key — no Google account
     * involved at any point.
     *
     * For a box with no Google account to offer: everything this app can do
     * *without* a Firebase UID still works — lists, watchlist and resume all
     * live on the MDBList account itself and already cross devices through
     * it. What is lost is addon and list-order sync, which the two sync
     * repositories key by Firebase UID; with none here, they simply see no
     * account and stay idle rather than failing.
     */
    suspend fun signInWithMdblistOnly(rawKey: String): Result<HubUser> = runCatching {
        val key = rawKey.trim()
        requireOrFail(key.isNotEmpty()) { AppError.MdblistKeyRequired }

        val user = api.user(key).toDomain()
        session.saveMdblistOnly(key, user)
        user
    }

    /** Enters with Google only, leaving MDBList-dependent rows disabled. */
    suspend fun continueWithoutMdblist(): Result<Unit> = runCatching {
        val firebaseUser = firebase.currentUser.orFail { AppError.GoogleSignInRequired }
        val token = firebaseUser.getIdToken(false).awaitResult().token
            ?: fail(AppError.FirebaseNoToken)
        val response = syncApi.deleteProfile(profileUrl(firebaseUser.uid), token)
        requireOrFail(response.isSuccessful || response.code() == 404) {
            AppError.FirebaseUnlinkRejected(response.code())
        }
        clearMdblistData()
        session.markMdblistSkipped(firebaseUser.uid)
    }

    suspend fun signOut() {
        // Stop the reactive addon writer before changing or clearing any
        // account-owned local state.
        syncStore.setFirebaseSyncEnabled(false)
        firebase.signOut()
        googleState.value = null
        session.clear()
        stremioAccountStore.clear()
        clearAllData()
    }

    /**
     * Restores both layers. Network failures keep an already validated local
     * key usable, while a definitive MDBList rejection unlinks it.
     */
    suspend fun restore(): Boolean {
        // Checked before touching Firebase at all: this session was never
        // signed into it, so `firebase.currentUser` is always null here and
        // would otherwise read as "nothing to restore".
        if (session.isMdblistOnly()) {
            val key = session.currentKey()
            if (key.isBlank()) return false

            val validation = runCatching { api.user(key).toDomain() }
            validation.getOrNull()?.let {
                session.saveMdblistOnly(key, it)
                return true
            }

            val error = validation.exceptionOrNull()
            if (error is retrofit2.HttpException && error.code() in 401..403) {
                session.clear()
                clearMdblistData()
                return false
            }
            // Same reasoning as the Google path below: a TV box routinely
            // boots before Wi-Fi is up, and a transient failure must not sign
            // out a credential that was valid a moment ago.
            return true
        }

        val firebaseUser = firebase.currentUser ?: return false
        googleState.value = firebaseUser.toAccount()

        val localKey = session.currentKey()
        val localOwner = session.currentFirebaseUid()
        if (localOwner == firebaseUser.uid && session.isMdblistSkipped()) return true
        if (localKey.isNotBlank() && localOwner == firebaseUser.uid) {
            val validation = runCatching { api.user(localKey).toDomain() }
            validation.getOrNull()?.let {
                session.save(localKey, it, firebaseUser.uid)
                return true
            }

            val error = validation.exceptionOrNull()
            if (error is retrofit2.HttpException && error.code() in 401..403) {
                session.clear()
                clearMdblistData()
                return false
            }
            // A TV box routinely boots before Wi-Fi. The locally validated
            // credential remains usable for transient network failures.
            return true
        }

        return runCatching {
            restoreLinkedMdblist(firebaseUser, allowLegacyMigration = localOwner.isBlank())
        }.getOrDefault(false)
    }

    /** Token and UID used by every protected Realtime Database request. */
    suspend fun firebaseSession(): Pair<String, String> {
        val user = firebase.currentUser.orFail { AppError.GoogleSignInRequired }
        val token = user.getIdToken(false).awaitResult().token
        requireOrFail(!token.isNullOrBlank()) { AppError.FirebaseNoToken }
        return user.uid to token
    }

    private suspend fun restoreLinkedMdblist(
        firebaseUser: FirebaseUser,
        allowLegacyMigration: Boolean,
    ): Boolean {
        val token = firebaseUser.getIdToken(false).awaitResult().token
            ?: fail(AppError.FirebaseNoToken)
        val remoteKey = syncApi.readProfile(profileUrl(firebaseUser.uid), token)
            .takeUnless { it is JsonNull }
        ?.let { HttpClients.json.decodeFromJsonElement(FirebaseProfileDto.serializer(), it).mdblistApiKey }
            ?.trim()
            .orEmpty()

        val legacyKey = session.currentKey().takeIf {
            allowLegacyMigration && it.isNotBlank()
        }.orEmpty()
        val key = remoteKey.ifBlank { legacyKey }
        if (key.isBlank()) return false

        val user = api.user(key).toDomain()
        if (remoteKey.isBlank()) writeProfile(firebaseUser, key)
        clearMdblistData()
        session.save(key, user, firebaseUser.uid)
        return true
    }

    private suspend fun writeProfile(user: FirebaseUser, key: String) {
        val token = user.getIdToken(false).awaitResult().token
            ?: fail(AppError.FirebaseNoToken)
        val response = syncApi.writeProfile(
            profileUrl(user.uid),
            token,
            FirebaseProfileDto(mdblistApiKey = key),
        )
        requireOrFail(response.isSuccessful) { AppError.FirebaseLinkRejected(response.code()) }
    }

    private fun profileUrl(uid: String) =
        "${ApiConfig.FIREBASE_BASE}${ApiConfig.FIREBASE_USERS_ROOT}/$uid/profile.json"

    private suspend fun clearMdblistData() = withContext(Dispatchers.IO) {
        database.clearMdblistAccountData()
    }

    private suspend fun clearAllData() = withContext(Dispatchers.IO) {
        database.clearAllTables()
    }
}

private fun FirebaseUser.toAccount() = GoogleAccountInfo(
    uid = uid,
    email = email.orEmpty(),
    displayName = displayName,
    photoUrl = photoUrl?.toString(),
)

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        val error = task.exception
        if (error != null) continuation.resumeWithException(error)
        else continuation.resume(task.result)
    }
}
