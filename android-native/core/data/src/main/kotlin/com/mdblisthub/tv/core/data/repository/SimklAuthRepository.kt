package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.SimklTokenStore
import com.mdblisthub.tv.core.network.SimklApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.jsonPrimitive

data class SimklPin(
    val code: String,
    val url: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

sealed interface SimklLinkState {
    data object Requesting : SimklLinkState
    data class Awaiting(val pin: SimklPin, val remaining: Int) : SimklLinkState
    data object Linked : SimklLinkState
    data object Failed : SimklLinkState
}

class SimklAuthRepository(private val api: SimklApi, private val store: SimklTokenStore) {
    suspend fun start(): SimklPin {
        val body = api.requestPin()
        val code = body["user_code"]?.jsonPrimitive?.content.orEmpty()
        val url = (body["verification_uri"] ?: body["verification_url"])
            ?.jsonPrimitive?.content.orEmpty()
        check(code.isNotBlank() && url.isNotBlank()) { "Simkl did not return a valid PIN" }
        val interval = (body["interval"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5)
            .coerceAtLeast(1)
        val expiresIn = (body["expires_in"]?.jsonPrimitive?.content?.toIntOrNull() ?: 900)
            .coerceAtLeast(interval)
        return SimklPin(code, url, interval, expiresIn)
    }

    fun poll(pin: SimklPin) = flow {
        val deadline = System.currentTimeMillis() + pin.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val remaining = ((deadline - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
            emit(SimklLinkState.Awaiting(pin, remaining))
            delay(pin.intervalSeconds * 1000L)
            val body = runCatching { api.pollPin(pin.code) }.getOrNull() ?: continue
            val token = body["access_token"]?.jsonPrimitive?.content.orEmpty()
            if (token.isNotBlank()) {
                store.save(token)
                emit(SimklLinkState.Linked)
                return@flow
            }
            // Unknown, expired and already-consumed codes make this endpoint
            // return a fresh PIN response. That new code was never shown to
            // the user, so continuing to poll it would only waste requests.
            if (body["device_code"] != null) break
        }
        emit(SimklLinkState.Failed)
    }

    suspend fun unlink() = store.clear()
}
