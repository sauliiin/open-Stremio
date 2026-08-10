package com.mdblisthub.tv.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val error: String? = null,
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
        _state.update { it.copy(key = value, error = null) }
    }

    fun beginGoogleSignIn() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, checkingSavedKey = false, error = null) }
    }

    fun reportGoogleError(message: String) {
        _state.update { it.copy(busy = false, checkingSavedKey = false, error = message) }
    }

    fun signInWithGoogle(idToken: String) {
        _state.update { it.copy(checkingSavedKey = true, error = null) }
        viewModelScope.launch {
            graph.auth.signInWithGoogleIdToken(idToken).fold(
                onSuccess = {
                    graph.listPreferencesSync.restore()
                    _state.update { it.copy(busy = false, checkingSavedKey = false, error = null) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            checkingSavedKey = false,
                            error = "Não consegui entrar com o Google. " +
                                "(${error.message ?: "sem detalhe"})",
                        )
                    }
                },
            )
        }
    }

    fun linkMdblist() {
        val key = _state.value.key.trim()
        if (key.isEmpty() || _state.value.busy || _state.value.google == null) return

        _state.update { it.copy(busy = true, error = null) }
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
                            error = "Não consegui vincular a MDBList. Confira a chave e tente de novo. " +
                                "(${error.message ?: "sem detalhe"})",
                        )
                    }
                },
            )
        }
    }

    fun continueWithoutMdblist() {
        if (_state.value.busy || _state.value.google == null) return
        _state.update { it.copy(busy = true, error = null) }
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
                            error = "Não foi possível prosseguir sem MDBList. " +
                                "(${error.message ?: "sem detalhe"})",
                        )
                    }
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
