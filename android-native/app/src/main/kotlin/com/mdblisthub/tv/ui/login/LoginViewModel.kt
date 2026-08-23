package com.mdblisthub.tv.ui.login

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.data.repository.GoogleAccountInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginState(
    val key: String = "",
    val google: GoogleAccountInfo? = null,
    val busy: Boolean = false,
    val checkingSavedKey: Boolean = false,
    /**
     * Free text reported by the screen, which already resolved it against the
     * locale-wrapped context.
     */
    val error: String? = null,
    /**
     * A message this ViewModel raised itself, as a resource id.
     *
     * Not resolved here. `graph.appContext` is the *application* context, and
     * it never sees the locale override the interface runs under — resolving
     * through it produced errors in the system language while everything
     * around them followed the in-app setting.
     */
    @StringRes val errorRes: Int? = null,
    val signedIn: Boolean = false,
)

class LoginViewModel(private val graph: DataGraph) : ViewModel() {

    private val _state = MutableStateFlow(LoginState(google = graph.auth.googleAccount.value))
    val state: StateFlow<LoginState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.auth.googleAccount.collect { account ->
                _state.update { it.copy(google = account) }
            }
        }
        viewModelScope.launch {
            graph.auth.signedIn.collect { signedIn ->
                _state.update { it.copy(signedIn = signedIn) }
            }
        }
    }

    fun onKeyChange(value: String) {
        _state.update { it.copy(key = value, error = null, errorRes = null) }
    }

    fun beginGoogleSignIn() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, checkingSavedKey = false, error = null, errorRes = null) }
    }

    fun reportGoogleError(message: String) {
        _state.update { it.copy(busy = false, checkingSavedKey = false, error = message, errorRes = null) }
    }

    fun signInWithGoogle(idToken: String) {
        _state.update { it.copy(checkingSavedKey = true, error = null, errorRes = null) }
        viewModelScope.launch {
            graph.auth.signInWithGoogleIdToken(idToken).fold(
                onSuccess = {
                    graph.listPreferencesSync.restore()
                    _state.update { it.copy(busy = false, checkingSavedKey = false, error = null, errorRes = null) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            checkingSavedKey = false,
                            errorRes = R.string.login_error_google,
                        )
                    }
                },
            )
        }
    }

    /**
     * The Fire-TV path: no Google account is ever touched. Reuses the same
     * `key` field as [linkMdblist] — the two forms are never shown at once,
     * so there is nothing for them to collide over.
     */
    fun signInWithMdblistOnly() {
        val key = _state.value.key.trim()
        if (key.isEmpty() || _state.value.busy) return

        _state.update { it.copy(busy = true, error = null, errorRes = null) }
        viewModelScope.launch {
            graph.auth.signInWithMdblistOnly(key).fold(
                onSuccess = {
                    graph.scheduler.onSignedIn()
                    _state.update { it.copy(busy = false, signedIn = true, key = "") }
                },
                onFailure = {
                    _state.update {
                        it.copy(busy = false, errorRes = R.string.login_error_mdblist_link)
                    }
                },
            )
        }
    }

    fun linkMdblist() {
        val key = _state.value.key.trim()
        if (key.isEmpty() || _state.value.busy || _state.value.google == null) return

        _state.update { it.copy(busy = true, error = null, errorRes = null) }
        viewModelScope.launch {
            graph.auth.linkMdblist(key).fold(
                onSuccess = {
                    graph.listPreferencesSync.restore()
                    graph.scheduler.onSignedIn()
                    _state.update { it.copy(busy = false, signedIn = true, key = "") }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            errorRes = R.string.login_error_mdblist_link,
                        )
                    }
                },
            )
        }
    }

    fun continueWithoutMdblist() {
        if (_state.value.busy || _state.value.google == null) return
        _state.update { it.copy(busy = true, error = null, errorRes = null) }
        viewModelScope.launch {
            graph.auth.continueWithoutMdblist().fold(
                onSuccess = {
                    graph.scheduler.onSignedOut()
                    _state.update { it.copy(busy = false, signedIn = true, key = "") }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            errorRes = R.string.login_error_mdblist_required,
                        )
                    }
                },
            )
        }
    }

    fun continueAsGuest() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, errorRes = null) }
        viewModelScope.launch {
            graph.auth.continueAsGuest().fold(
                onSuccess = {
                    graph.scheduler.onSignedOut()
                    _state.update { it.copy(busy = false, signedIn = true, key = "") }
                },
                onFailure = {
                    _state.update { it.copy(busy = false, errorRes = R.string.login_error_guest) }
                },
            )
        }
    }

    fun changeGoogleAccount() {
        viewModelScope.launch {
            graph.auth.signOut()
            _state.value = LoginState()
        }
    }
}
