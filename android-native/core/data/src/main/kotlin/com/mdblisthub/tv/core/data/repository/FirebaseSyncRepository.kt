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
 *
 * The automatic push is triggered directly by `AddonsRepository` — see
 * [pushIfEnabled] and `AddonsRepository.onLocalChange` — right after install,
 * remove or an import commits, the same way Stremio's own sync fires
 * `AddonCollectionSet` as a direct consequence of the action that changed the
 * list rather than by noticing later that something did. The previous design
 * watched Room's `observeAddons()` Flow instead and used a `applying` guard
 * to tell "a local edit" apart from "the pull that was just applied" —
 * workable, but only because Room's own change notification can lag a beat
 * behind the DAO call that caused it, which is exactly the race that guard
 * existed to paper over. Calling `pushIfEnabled` from the mutation itself
 * removes the need to guess: a remote-applied change (`pull`, `enable`'s
 * merge) never goes through `install`/`remove`/`importCollection`, so it has
 * no path back to a push at all, guard or not.
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

    /** The in-flight (or most recently scheduled) automatic push, if any. */
    private var pushJob: Job? = null

    /**
     * Called by `AddonsRepository` right after a local mutation commits.
     * A no-op when sync is off, or for a device that never turned it on.
     *
     * Retries a few times before giving up — a phone that just installed an
     * addon and lost Wi-Fi for a moment should not have to wait for the next
     * local change before trying again. Superseded by cancelling and
     * restarting rather than queuing: only the latest state is ever worth
     * sending, so a second local change while the first push is still
     * retrying should send the second's (newer) snapshot, not queue behind
     * a stale one.
     */
    suspend fun pushIfEnabled() {
        if (!store.firebaseSyncEnabled.first()) return

        pushJob?.cancel()
        pushJob = scope.launch {
            busyState.value = true
            var lastFailure: Throwable? = null
            for ((attempt, retryDelay) in RETRY_DELAYS_MS.withIndex()) {
                if (retryDelay > 0) delay(retryDelay)
                try {
                    writeCurrentAddons()
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
            failureState.value = lastFailure?.message ?: "Não foi possível sincronizar os addons."
        }
    }

    /**
     * Brings this device in line with the cloud at startup, when sync is on.
     *
     * Without this, a device only ever learned about another device's addons
     * if someone opened Addons and pressed "Baixar" by hand: `pull` had
     * exactly one caller, that button. Installing an addon on the television
     * pushed it correctly and the phone simply never looked, which reads as
     * "sync is broken" and effectively was.
     *
     * The cloud wins here, deliberately. `push` writes the whole list, so the
     * stored copy is the account's state, not a partial delta — the same
     * model Stremio uses, where signing in re-fetches the addon collection and
     * the account is the source of truth. An empty stored node is the one
     * exception: it means no device has ever pushed, so this one seeds it
     * instead of wiping itself against nothing.
     *
     * Failures are swallowed on purpose. This runs before the first frame; a
     * device that opens offline should start with its cached addons, not an
     * error, and the next start or an explicit "Baixar" will catch it up.
     */
    suspend fun restore() {
        if (!store.firebaseSyncEnabled.first()) return
        runCatching {
            val (uid, token) = auth.firebaseSession()
            val remote = read(uid, token)
            if (remote == null) {
                write(uid, token, addons.entities().map { it.toSyncedDto() })
            } else {
                addons.replaceAll(remote)
            }
            lastState.value = nowIso()
        }.onFailure { Log.w(TAG, "Falha ao restaurar addons na abertura.", it) }
    }

    /**
     * Turns sync on by joining the two lists: whatever is stored plus
     * whatever this device already had, pushed back so both sides agree.
     */
    suspend fun enable(): Result<Int> = request {
        val (uid, token) = auth.firebaseSession()
        val remote = read(uid, token).orEmpty()

        val fresh = addons.merge(remote)
        write(uid, token, addons.entities().map { it.toSyncedDto() })
        // Enabled only after the join above lands: flipping it first would
        // let a concurrent `pushIfEnabled` (from some unrelated local change
        // racing this same call) read an empty or half-merged local list and
        // overwrite the cloud collection this is in the middle of joining.
        store.setFirebaseSyncEnabled(true)
        fresh
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
        addons.replaceAll(remote)
        kotlin.math.abs(remote.size - before)
    }

    /** Writes this device's list out, replacing whatever was stored. */
    suspend fun push(): Result<Int> = request { writeCurrentAddons() }

    /** The write itself, shared by the manual button and [pushIfEnabled]'s retry loop. */
    private suspend fun writeCurrentAddons(): Int {
        val (uid, token) = auth.firebaseSession()
        val entities = addons.entities()
        write(uid, token, entities.map { it.toSyncedDto() })
        return entities.size
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
        val RETRY_DELAYS_MS = longArrayOf(0L, 2_000L, 8_000L)
    }
}
