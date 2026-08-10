package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.StremioAccountStore
import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.model.MdblistAddonExportReport
import com.mdblisthub.tv.core.model.StremioAccount
import com.mdblisthub.tv.core.model.StremioImportReport
import com.mdblisthub.tv.core.network.StremioAccountApi
import com.mdblisthub.tv.core.network.StremioApi
import com.mdblisthub.tv.core.network.dto.StremioApiResponse
import com.mdblisthub.tv.core.network.dto.StremioCollectionRequest
import com.mdblisthub.tv.core.network.dto.StremioCollectionEntryDto
import com.mdblisthub.tv.core.network.dto.StremioCollectionSetRequest
import com.mdblisthub.tv.core.network.dto.StremioLoginRequest
import com.mdblisthub.tv.core.network.dto.StremioLoginResult
import com.mdblisthub.tv.core.network.dto.StremioLogoutRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class StremioAccountRepository(
    private val api: StremioAccountApi,
    private val store: StremioAccountStore,
    private val addons: AddonsRepository,
    private val stremioTransport: StremioApi,
    private val lists: ListsRepository,
    private val session: SessionStore,
) {
    val account: Flow<StremioAccount?> = store.account
    val mdblistAddonsEnabled: Flow<Boolean> = store.mdblistAddonsEnabled

    suspend fun login(email: String, password: String): Result<StremioImportReport> = runCatching {
        val cleanEmail = email.trim()
        require(cleanEmail.isNotBlank() && password.isNotBlank()) { "Informe e-mail e senha." }
        val result = api.login(StremioLoginRequest(email = cleanEmail, password = password)).unwrap()
        require(result.authKey.isNotBlank()) { "A API do Stremio não devolveu uma sessão." }
        store.save(
            StremioAccount(
                authKey = result.authKey,
                email = result.user?.email ?: cleanEmail,
            ),
        )
        syncNow()
    }

    suspend fun sync(): Result<StremioImportReport> = runCatching { syncNow() }

    suspend fun logout() {
        val account = store.current()
        store.clear()
        if (account != null) {
            runCatching { api.logout(StremioLogoutRequest(authKey = account.authKey)) }
        }
    }



    private suspend fun syncNow(): StremioImportReport {
        val account = requireNotNull(store.current()) { "Entre na sua conta Stremio primeiro." }
        return try {
            val collection = api.collection(
                StremioCollectionRequest(authKey = account.authKey),
            ).unwrap()
            addons.importCollection(collection.addons)
        } catch (error: Exception) {
            if (error.message.orEmpty().contains("sessão", ignoreCase = true) ||
                error.message.orEmpty().contains("session", ignoreCase = true)
            ) {
                store.clear()
                throw IllegalStateException("Sua sessão do Stremio expirou. Entre novamente.")
            }
            throw error
        }
    }
}



private fun <T> StremioApiResponse<T>.unwrap(): T {
    error?.let { throw IllegalStateException(translateStremioError(it.message)) }
    return result ?: throw IllegalStateException("Resposta inesperada da API do Stremio.")
}

private fun translateStremioError(message: String): String = when (message) {
    "Wrong passphrase" -> "Senha incorreta."
    "User not found" -> "Não existe conta Stremio com esse e-mail."
    "Session does not exist" -> "Sessão do Stremio expirada. Entre novamente."
    else -> message.ifBlank { "A API do Stremio recusou a solicitação." }
}
