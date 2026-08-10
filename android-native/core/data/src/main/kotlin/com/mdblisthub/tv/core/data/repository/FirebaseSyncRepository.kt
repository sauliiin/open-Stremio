package com.mdblisthub.tv.core.data.repository

import android.util.Log
import com.mdblisthub.tv.core.data.SyncStore
import com.mdblisthub.tv.core.data.mapper.toEntityOrNull
import com.mdblisthub.tv.core.data.mapper.toSyncedDto
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.HttpClients
import com.mdblisthub.tv.core.network.SyncApi
import com.mdblisthub.tv.core.network.dto.SyncPayloadDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Cross-device addon sync over authenticated Firebase REST calls. Every list
 * lives below the Google UID and the database rules prevent one signed-in
 * account from reading or replacing another account's addons.
 */
class FirebaseSyncRepository(
    private val api: SyncApi,
    private val store: SyncStore,
    private val auth: AuthRepository,
    private val addons: AddonsRepository,
    private val scope: CoroutineScope,
) {
    val enabled: Flow<Boolean> = store.firebaseSyncEnabled

    private val busyState = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyState.asStateFlow()

    private val failureState = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = failureState.asStateFlow()

    private val lastState = MutableStateFlow<String?>(null)
    val lastSync: StateFlow<String?> = lastState.asStateFlow()

    /** Set while a pull is being applied, so it does not echo straight back. */
    @Volatile private var applying = false
    private var pushJob: Job? = null

    init {
        // Any local change — installed, removed, imported from Stremio —
        // becomes a write, debounced so a bulk import is one request.
        scope.launch {
            combine(
                store.firebaseSyncEnabled,
                auth.googleAccount,
                addons.observeAddons(),
            ) { on, account, _ -> on to account?.uid }
                .collect { (on, scheduledUid) ->
                    if (!on || scheduledUid == null) {
                        pushJob?.cancel()
                        return@collect
                    }
                    if (applying) return@collect
                    pushJob?.cancel()
                    pushJob = scope.launch {
                        delay(PUSH_DELAY_MS)
                        busyState.value = true
                        var lastFailure: Throwable? = null
                        for ((attempt, retryDelay) in RETRY_DELAYS_MS.withIndex()) {
                            if (retryDelay > 0) delay(retryDelay)
                            try {
                                val (uid, token) = auth.firebaseSession()
                                check(uid == scheduledUid) {
                                    "A conta Google mudou durante a sincronização."
                                }
                                write(uid, token, addons.entities().map { it.toSyncedDto() })
                                failureState.value = null
                                lastState.value = nowIso()
                                busyState.value = false
                                return@launch
                            } catch (cancelled: CancellationException) {
                                busyState.value = false
                                throw cancelled
                            } catch (error: Throwable) {
                                lastFailure = error
                                Log.w(TAG, "Falha no envio automático (tentativa ${attempt + 1}).", error)
                            }
                        }
                        busyState.value = false
                        failureState.value = lastFailure?.message
                            ?: "Não foi possível sincronizar os addons."
                    }
                }
        }
    }

    /**
     * Turns sync on by joining the two lists: whatever is stored plus
     * whatever this device already had, pushed back so both sides agree.
     */
    suspend fun enable(): Result<Int> = request {
        val (uid, token) = auth.firebaseSession()
        val remote = read(uid, token).orEmpty()

        // The write-back has to sit inside the same guarded block as the
        // merge: Room's change notification can lag a beat behind the DAO
        // call returning, and closing the guard right after `merge()` leaves
        // a window where that notification reaches the reactive pusher below
        // and queues a redundant write.
        applyLocally {
            val fresh = addons.merge(remote)
            write(uid, token, addons.entities().map { it.toSyncedDto() })
            // Enable the reactive writer only after cloud and local data have
            // been joined. Enabling first leaves a slow-read race where an
            // empty local database can replace the user's cloud collection.
            store.setFirebaseSyncEnabled(true)
            fresh
        }
    }

    suspend fun disable() {
        store.setFirebaseSyncEnabled(false)
        pushJob?.cancel()
        failureState.value = null
    }

    /**
     * Makes this device match what is stored, removals included.
     *
     * A replace, not a merge: `push` writes the whole list, so an addon
     * removed elsewhere is simply absent from the stored copy. Merging here
     * would read that absence as "nothing to add", and the deletion would
     * never arrive.
     *
     * A missing node is kept distinct from an existing payload whose addons
     * list is intentionally empty. That distinction lets a removal of the
     * final addon propagate instead of being silently undone by another
     * device, without treating Firebase's literal `null` as a deletion.
     */
    suspend fun pull(): Result<Int> = request {
        val (uid, token) = auth.firebaseSession()
        val remote = read(uid, token)
        check(remote != null) {
            "Ainda não há uma lista de addons na nuvem, então não mexi nos daqui. " +
                "Use \"Enviar\" no aparelho que tem os addons certos primeiro."
        }

        val before = addons.entities().size
        applyLocally { addons.replaceAll(remote) }
        kotlin.math.abs(remote.size - before)
    }

    /** Writes this device's list out, replacing whatever was stored. */
    suspend fun push(): Result<Int> = request {
        val (uid, token) = auth.firebaseSession()
        val entities = addons.entities()
        write(uid, token, entities.map { it.toSyncedDto() })
        entities.size
    }

    private suspend fun read(uid: String, idToken: String): List<com.mdblisthub.tv.core.database.entity.AddonEntity>? {
        val element = api.read(url(uid), idToken)
        if (element is JsonNull) return null
        return HttpClients.json
            .decodeFromJsonElement(SyncPayloadDto.serializer(), element)
            .addons
            .mapNotNull { it.toEntityOrNull(System.currentTimeMillis()) }
    }

    private suspend fun write(
        uid: String,
        idToken: String,
        entries: List<com.mdblisthub.tv.core.network.dto.SyncedAddonDto>,
    ) {
        val response = api.write(
            url(uid),
            idToken,
            SyncPayloadDto(updatedAt = nowIso(), addons = entries),
        )
        check(response.isSuccessful) { "O Firebase recusou a sincronização (${response.code()})." }
    }

    private fun url(uid: String) =
        "${ApiConfig.FIREBASE_BASE}${ApiConfig.FIREBASE_USERS_ROOT}/$uid/addons.json"

    /**
     * Runs a local mutation with the write-back guard held. The guard has to
     * span the mutation itself, not just the call, or the reactive push above
     * would send straight back what was only just read.
     */
    private suspend fun <T> applyLocally(mutate: suspend () -> T): T {
        applying = true
        try {
            return mutate()
        } finally {
            applying = false
        }
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
        result.onFailure { failureState.value = it.message ?: "Não foi possível falar com o Firebase." }
        return result
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    private companion object {
        const val TAG = "FirebaseAddonSync"
        /** Collapses a burst of installs into a single write. */
        const val PUSH_DELAY_MS = 1500L
        val RETRY_DELAYS_MS = longArrayOf(0L, 2_000L, 8_000L)
    }
}
