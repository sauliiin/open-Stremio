package com.mdblisthub.tv.core.data.repository

import android.util.Log
import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.HttpClients
import com.mdblisthub.tv.core.network.SyncApi
import com.mdblisthub.tv.core.network.dto.FirebaseListPreferenceDto
import com.mdblisthub.tv.core.network.dto.FirebaseListPreferencesDto
import com.mdblisthub.tv.core.model.AppError
import com.mdblisthub.tv.core.model.AppException
import com.mdblisthub.tv.core.model.fail
import com.mdblisthub.tv.core.model.requireOrFail
import com.mdblisthub.tv.core.network.dto.FirebaseCatalogPreferenceDto
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull

/**
 * Keeps list display preferences under the signed-in Google UID.
 *
 * A remote snapshot wins when a device joins the account. After that initial
 * pull, every local Room/DataStore mutation is pushed as a complete snapshot;
 * deletions remain in that snapshot as tombstones, so a later MDBList refresh
 * cannot recreate the row on this or another device.
 */
class ListPreferencesSyncRepository(
    private val api: SyncApi,
    private val auth: AuthRepository,
    private val session: SessionStore,
    private val lists: ListsRepository,
    private val scope: CoroutineScope,
) {
    @Volatile private var activeUid: String? = null
    @Volatile private var applying = false
    private var pushJob: Job? = null
    private val requestMutex = Mutex()

    private val busyState = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyState.asStateFlow()

    private val failureState = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = failureState.asStateFlow()

    private val lastState = MutableStateFlow<String?>(null)
    val lastSync: StateFlow<String?> = lastState.asStateFlow()

    init {
        scope.launch {
            combine(
                auth.googleAccount,
                session.listPreferences,
                session.catalogPreferences,
                session.listPreferencesDirty,
            ) { account, preferences, catalogs, dirty ->
                PendingPreferences(account?.uid, preferences, catalogs, dirty)
            }.collect { pending ->
                    val uid = pending.uid
                    if (uid == null) {
                        activeUid = null
                        pushJob?.cancel()
                        failureState.value = null
                        return@collect
                    }
                    if (uid != activeUid) {
                        activeUid = uid
                        failureState.value = null
                    }
                    if (applying || !pending.dirty) return@collect
                    schedulePush(pending)
                }
        }
    }

    /** Restores cloud preferences, or seeds the cloud from this device once. */
    suspend fun restore(): Result<Int> {
        val account = auth.googleAccount.value ?: return Result.success(0)
        // Claim the account before the first network request. Previously this
        // was assigned only after a successful restore, which permanently
        // disabled reactive pushes when that initial request failed once.
        activeUid = account.uid
        val mdblistUser = session.currentUser()
        pushJob?.cancel()
        applying = true
        val result = try {
            request {
                requestMutex.withLock {
                    val (uid, token) = auth.firebaseSession()
                    requireOrFail(uid == account.uid) { AppError.GoogleAccountChangedDuringRestore }
                    if (session.areListPreferencesDirty()) {
                        val localLists = session.currentListPreferences()
                        val localCatalogs = session.currentCatalogPreferences()
                        write(uid, token, localLists, localCatalogs, mdblistUser?.userId ?: 0)
                        session.markListPreferencesSyncedIfUnchanged(localLists, localCatalogs)
                        localLists.size + localCatalogs.size
                    } else {
                        val remote = read(uid, token)
                        if (remote != null) {
                            session.saveCatalogPreferences(remote.catalogs, dirty = false)
                            if (mdblistUser != null && remote.mdblistUserId == mdblistUser.userId) {
                                session.saveListPreferences(remote.lists, dirty = false)
                                lists.applyStoredPreferences().getOrThrow()
                            } else if (mdblistUser != null) {
                                // A newly linked MDBList account owns a different set of numeric IDs.
                                // Keep the Google-owned Stremio catalog choices and reseed only MDBList.
                                val localLists = lists.capturePreferences()
                                write(uid, token, localLists, remote.catalogs, mdblistUser.userId)
                                session.markListPreferencesSyncedIfUnchanged(localLists, remote.catalogs)
                            }
                            remote.catalogs.size +
                                remote.lists.size.takeIf {
                                    mdblistUser != null && remote.mdblistUserId == mdblistUser.userId
                                }.orZero()
                        } else {
                            val localLists = if (mdblistUser != null) lists.capturePreferences() else emptyList()
                            val localCatalogs = session.currentCatalogPreferences()
                            write(uid, token, localLists, localCatalogs, mdblistUser?.userId ?: 0)
                            session.markListPreferencesSyncedIfUnchanged(localLists, localCatalogs)
                            localLists.size + localCatalogs.size
                        }
                    }
                }
            }
        } finally {
            applying = false
        }

        // A failed initial pull must not strand a dirty snapshot forever.
        // Queue the same bounded retry path used by subsequent edits.
        if (result.isFailure && session.areListPreferencesDirty()) {
            scheduleCurrent(account.uid)
        }
        return result
    }

    /** Explicit retry used by the Addons screen's Enviar action. */
    suspend fun pushNow(): Result<Int> {
        val uid = auth.googleAccount.value?.uid
            ?: return Result.failure(AppException(AppError.GoogleSignInRequired))
        activeUid = uid
        pushJob?.cancel()
        return request {
            requestMutex.withLock { writeCurrentSnapshot(uid) }
        }
    }

    private fun schedulePush(pending: PendingPreferences) {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
            pushWithRetry(pending.uid ?: return@launch, pending.lists, pending.catalogs)
        }
    }

    private fun scheduleCurrent(uid: String) {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
            pushWithRetry(
                uid,
                session.currentListPreferences(),
                session.currentCatalogPreferences(),
            )
        }
    }

    private suspend fun pushWithRetry(
        uid: String,
        initialLists: List<FirebaseListPreferenceDto>,
        initialCatalogs: List<FirebaseCatalogPreferenceDto>,
    ) {
        busyState.value = true
        var lastFailure: Throwable? = null
        for ((attempt, retryDelay) in RETRY_DELAYS_MS.withIndex()) {
            if (retryDelay > 0) delay(retryDelay)
            try {
                requestMutex.withLock {
                    writeSnapshot(uid, initialLists, initialCatalogs)
                }
                failureState.value = null
                lastState.value = nowIso()
                busyState.value = false
                return
            } catch (cancelled: CancellationException) {
                busyState.value = false
                throw cancelled
            } catch (error: Throwable) {
                lastFailure = error
                Log.w(TAG, "Falha ao sincronizar preferências (tentativa ${attempt + 1}).", error)
            }
        }
        busyState.value = false
        failureState.value = (lastFailure as? AppException)?.error ?: AppError.ListOrderSyncFailed
    }

    private suspend fun writeCurrentSnapshot(uid: String): Int = writeSnapshot(
        uid,
        session.currentListPreferences(),
        session.currentCatalogPreferences(),
    )

    private suspend fun writeSnapshot(
        uid: String,
        initialLists: List<FirebaseListPreferenceDto>,
        initialCatalogs: List<FirebaseCatalogPreferenceDto>,
    ): Int {
        var currentLists = initialLists
        var currentCatalogs = initialCatalogs
        repeat(MAX_SNAPSHOT_PASSES) {
            val (sessionUid, token) = auth.firebaseSession()
            requireOrFail(sessionUid == uid && activeUid == uid) {
                AppError.GoogleAccountChangedDuringSync
            }
            write(
                uid,
                token,
                currentLists,
                currentCatalogs,
                session.currentUser()?.userId ?: 0,
            )
            if (session.markListPreferencesSyncedIfUnchanged(currentLists, currentCatalogs)) {
                return currentLists.size + currentCatalogs.size
            }
            currentLists = session.currentListPreferences()
            currentCatalogs = session.currentCatalogPreferences()
        }
        fail(AppError.PreferencesChangedRepeatedly)
    }

    private suspend fun request(call: suspend () -> Int): Result<Int> {
        busyState.value = true
        failureState.value = null
        val result = try {
            try {
                Result.success(call())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
        } finally {
            busyState.value = false
        }
        result.onSuccess { lastState.value = nowIso() }
        result.onFailure {
            failureState.value = (it as? AppException)?.error ?: AppError.PreferencesSyncFailed
            Log.w(TAG, "Falha na sincronização das preferências.", it)
        }
        return result
    }

    private suspend fun read(uid: String, token: String): FirebaseListPreferencesDto? {
        val element = api.readListPreferences(url(uid), token)
        if (element is JsonNull) return null
        return HttpClients.json.decodeFromJsonElement(FirebaseListPreferencesDto.serializer(), element)
    }

    private suspend fun write(
        uid: String,
        token: String,
        preferences: List<FirebaseListPreferenceDto>,
        catalogs: List<FirebaseCatalogPreferenceDto>,
        mdblistUserId: Long,
    ) {
        val response = api.writeListPreferences(
            url(uid),
            token,
            FirebaseListPreferencesDto(
                updatedAt = nowIso(),
                mdblistUserId = mdblistUserId,
                lists = preferences,
                catalogs = catalogs,
            ),
        )
        requireOrFail(response.isSuccessful) {
            AppError.FirebasePreferencesSyncRejected(response.code())
        }
    }

    private fun url(uid: String) =
        "${ApiConfig.FIREBASE_BASE}${ApiConfig.FIREBASE_USERS_ROOT}/$uid/listPreferences.json"

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    private companion object {
        const val TAG = "ListPreferencesSync"
        const val PUSH_DELAY_MS = 900L
        const val MAX_SNAPSHOT_PASSES = 3
        val RETRY_DELAYS_MS = longArrayOf(0L, 2_000L, 8_000L)
    }

    private data class PendingPreferences(
        val uid: String?,
        val lists: List<FirebaseListPreferenceDto>,
        val catalogs: List<FirebaseCatalogPreferenceDto>,
        val dirty: Boolean,
    )
}

private fun Int?.orZero(): Int = this ?: 0
