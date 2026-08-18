package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.TraktTokenStore
import com.mdblisthub.tv.core.model.TraktAccount
import com.mdblisthub.tv.core.model.TraktDeviceCode
import com.mdblisthub.tv.core.model.TraktLinkFailure
import com.mdblisthub.tv.core.model.TraktLinkState
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.TraktApi
import com.mdblisthub.tv.core.network.TraktAuthApi
import com.mdblisthub.tv.core.network.TraktTokens
import com.mdblisthub.tv.core.network.dto.TraktDeviceCodeRequestDto
import com.mdblisthub.tv.core.network.dto.TraktDeviceTokenRequestDto
import com.mdblisthub.tv.core.network.dto.TraktRefreshRequestDto
import com.mdblisthub.tv.core.model.AppError
import com.mdblisthub.tv.core.model.requireOrFail
import com.mdblisthub.tv.core.network.dto.TraktRevokeRequestDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Linking, refreshing and unlinking a Trakt account.
 *
 * Also the app's [TraktTokens] — the seam `:core:network` reads the credential
 * through. Implementing it here rather than in a separate adapter keeps the
 * single-use refresh token in one object: the mutex below is the only thing
 * standing between two simultaneous `401`s and a spent chain of refresh
 * tokens, and it cannot do that job from two places at once.
 */
class TraktAuthRepository(
    private val authApi: TraktAuthApi,
    private val api: TraktApi,
    private val store: TraktTokenStore,
) : TraktTokens {

    val account: Flow<TraktAccount?> = store.account
    val linked: Flow<Boolean> = store.linked

    /** False when the build ships no Trakt registration — see [ApiConfig.TRAKT_CLIENT_ID]. */
    val configured: Boolean get() = ApiConfig.traktConfigured

    private val refreshLock = Mutex()

    // ------------------------------------------------------------- linking

    /**
     * Asks Trakt for a code pair. Ten minutes of validity, and the interval it
     * wants to be polled at — both come from the answer rather than from a
     * constant here, because they are Trakt's to change.
     */
    suspend fun startLink(): Result<TraktDeviceCode> = runCatching {
        // Redundant with the UI-level `configured` gate in SettingsScreen (which
        // never reaches this call in the first place), but kept as a typed
        // backstop for any future caller that skips it.
        requireOrFail(configured) { AppError.TraktNotConfigured }
        val dto = authApi.deviceCode(TraktDeviceCodeRequestDto(ApiConfig.TRAKT_CLIENT_ID))
        requireOrFail(dto.deviceCode.isNotBlank() && dto.userCode.isNotBlank()) { AppError.TraktUnavailable }
        TraktDeviceCode(
            userCode = dto.userCode,
            // Include the code in Trakt's public activation URL so opening the
            // link takes the user directly to the matching activation flow.
            verificationUrl = "${ApiConfig.TRAKT_ACTIVATE_URL}/${dto.userCode.trim()}",
            deviceCode = dto.deviceCode,
            intervalSeconds = dto.interval.coerceAtLeast(1),
            expiresInSeconds = dto.expiresIn.coerceAtLeast(1),
        )
    }

    /**
     * Waits for the user to approve the code, reporting the countdown as it
     * goes so the screen can show one.
     *
     * The whole loop is driven by status codes rather than by a decoded body —
     * see [TraktAuthApi.deviceToken]. A thrown request (no network, DNS still
     * cold after a resume) is *not* an ending: those recover on their own, and
     * killing a ten-minute flow over one of them would send the user back to
     * the start for nothing. Only Trakt actually answering ends it.
     */
    fun poll(code: TraktDeviceCode): Flow<TraktLinkState> = flow {
        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        var intervalMs = code.intervalSeconds * 1000L

        while (true) {
            val remaining = ((deadline - System.currentTimeMillis()) / 1000).toInt()
            if (remaining <= 0) {
                emit(TraktLinkState.Failed(TraktLinkFailure.EXPIRED))
                return@flow
            }
            emit(TraktLinkState.AwaitingUser(code, remaining))
            delay(intervalMs)

            val response = runCatching {
                authApi.deviceToken(
                    TraktDeviceTokenRequestDto(
                        code = code.deviceCode,
                        clientId = ApiConfig.TRAKT_CLIENT_ID,
                        clientSecret = ApiConfig.TRAKT_CLIENT_SECRET,
                    ),
                )
            }.getOrNull() ?: continue

            when (response.code()) {
                200 -> {
                    val token = response.body()
                    if (token == null || token.accessToken.isBlank()) {
                        emit(TraktLinkState.Failed(TraktLinkFailure.UNAVAILABLE))
                        return@flow
                    }
                    store.save(token)
                    val account = fetchAccount()
                    if (account == null) {
                        // The token is good — it is the profile call that
                        // failed. Linking anyway is right: the account name is
                        // decoration, and the next refresh picks it up.
                        emit(TraktLinkState.Linked(TraktAccount(username = "trakt")))
                    } else {
                        store.saveAccount(account)
                        emit(TraktLinkState.Linked(account))
                    }
                    return@flow
                }

                // Waiting for the user. The overwhelmingly common answer.
                400 -> Unit

                // Polling too fast. Trakt asks for a slower cadence rather than
                // a pause, so widen the interval instead of giving up.
                429 -> intervalMs += 1000

                418 -> {
                    emit(TraktLinkState.Failed(TraktLinkFailure.DENIED))
                    return@flow
                }

                // 404 invalid, 410 expired — both mean this code pair is dead
                // and the user has to start over.
                404, 410 -> {
                    emit(TraktLinkState.Failed(TraktLinkFailure.EXPIRED))
                    return@flow
                }

                else -> {
                    emit(TraktLinkState.Failed(TraktLinkFailure.UNAVAILABLE))
                    return@flow
                }
            }
        }
    }

    /**
     * Drops the link. The revoke is best effort: a token this app has thrown
     * away is useless to it whether or not Trakt was told, and a failure there
     * must not leave the user stuck with an account they asked to disconnect.
     */
    suspend fun unlink() {
        val token = store.accessToken()
        if (token.isNotBlank() && configured) {
            runCatching {
                authApi.revoke(
                    TraktRevokeRequestDto(
                        token = token,
                        clientId = ApiConfig.TRAKT_CLIENT_ID,
                        clientSecret = ApiConfig.TRAKT_CLIENT_SECRET,
                    ),
                )
            }
        }
        store.clear()
    }

    private suspend fun fetchAccount(): TraktAccount? = runCatching {
        val user = api.settings().user ?: return@runCatching null
        user.username.takeIf { it.isNotBlank() }
            ?.let { TraktAccount(username = it, slug = user.ids?.slug) }
    }.getOrNull()

    // ------------------------------------------------------ TraktTokens

    override fun accessToken(): String = runBlocking { store.accessToken() }

    /**
     * Called from OkHttp's authenticator, on the call's own thread, after a
     * `401`.
     *
     * The mutex plus the re-read is what makes a burst of parallel calls —
     * which is exactly what a home refresh is — spend one refresh token
     * instead of six. Whoever wins the lock exchanges; everyone behind it
     * finds a token that is no longer the one they were rejected for, and
     * simply retries with it.
     */
    override fun refreshed(expired: String): String? = runBlocking {
        refreshLock.withLock {
            val current = store.accessToken()
            if (current.isNotBlank() && current != expired) return@withLock current

            val refresh = store.refreshToken()
            if (refresh.isBlank() || !configured) return@withLock null

            val response = runCatching {
                authApi.refresh(
                    TraktRefreshRequestDto(
                        refreshToken = refresh,
                        clientId = ApiConfig.TRAKT_CLIENT_ID,
                        clientSecret = ApiConfig.TRAKT_CLIENT_SECRET,
                    ),
                )
            }.getOrNull()

            val token = response?.body()?.takeIf { response.isSuccessful && it.accessToken.isNotBlank() }
            if (token == null) {
                // `invalid_grant` here means the refresh token is spent or the
                // authorization was revoked on trakt.tv. Either way the link is
                // over, and leaving dead tokens behind would have every call
                // pay for a doomed refresh. A network blip returns null too and
                // costs a re-link — rare enough, and far better than the
                // alternative of never noticing a genuinely dead grant.
                store.clear()
                return@withLock null
            }

            store.save(token)
            token.accessToken
        }
    }
}
