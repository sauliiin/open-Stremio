package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.StremioAccountStore
import com.mdblisthub.tv.core.model.AppError
import com.mdblisthub.tv.core.model.AppException
import com.mdblisthub.tv.core.model.fail
import com.mdblisthub.tv.core.model.orFail
import com.mdblisthub.tv.core.model.requireOrFail
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
        requireOrFail(cleanEmail.isNotBlank() && password.isNotBlank()) { AppError.StremioCredentialsRequired }
        val result = api.login(StremioLoginRequest(email = cleanEmail, password = password)).unwrap()
        requireOrFail(result.authKey.isNotBlank()) { AppError.StremioNoSession }
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
        val account = store.current().orFail { AppError.StremioNotLinked }
        return try {
            val collection = api.collection(
                StremioCollectionRequest(authKey = account.authKey),
            ).unwrap()
            addons.importCollection(collection.addons)
        } catch (error: AppException) {
            // A typed check, not a substring match on the message: the old
            // code looked for the word "session"/"sessão" inside whatever
            // text `unwrap()` had already translated, which broke the moment
            // that text stopped being English or Portuguese specifically.
            if (error.error == AppError.StremioSessionExpired) store.clear()
            throw error
        }
    }
}



private fun <T> StremioApiResponse<T>.unwrap(): T {
    error?.let { fail(translateStremioError(it.message)) }
    return result ?: fail(AppError.StremioUnexpectedResponse)
}

private fun translateStremioError(message: String): AppError = when (message) {
    "Wrong passphrase" -> AppError.StremioWrongPassword
    "User not found" -> AppError.StremioUserNotFound
    "Session does not exist" -> AppError.StremioSessionExpired
    else -> AppError.StremioRequestRejected(message.takeIf { it.isNotBlank() })
}
