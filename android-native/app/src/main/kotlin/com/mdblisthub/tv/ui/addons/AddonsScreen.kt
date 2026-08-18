package com.mdblisthub.tv.ui.addons

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.Addon
import com.mdblisthub.tv.core.model.AppError
import com.mdblisthub.tv.core.model.AppException
import com.mdblisthub.tv.core.model.StremioAccount
import com.mdblisthub.tv.core.model.StremioImportReport
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.component.text
import com.mdblisthub.tv.ui.hubViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The two addons the "Para começar" section recommends — one for subtitles,
 * one for streams, which between them get a fresh install working. Mirrors
 * mdblisthub.netlify.app/addons.
 *
 * OpenSubtitles v3 has a fixed manifest that works for anyone, so its button
 * installs directly. AIOStreams does not: its root answers with the site's
 * own PWA manifest, not an addon one — confirmed by hand, the per-user URL
 * only exists after configuring — so its button opens that page instead.
 */
private data class QuickAddon(
    val name: String,
    // Resource ids, not text. These are declared in a top-level `val` that is
    // built once at class-init time, long before any composition and with no
    // Context in reach — so the string has to stay unresolved until the row
    // that draws it can call `stringResource` in the interface's language.
    @StringRes val what: Int,
    val host: String? = null,
    val url: String? = null,
    val configureUrl: String? = null,
    @StringRes val unconfigured: Int? = null,
)

private val QUICK_ADDONS = listOf(
    QuickAddon(
        name = "OpenSubtitles v3",
        what = R.string.addons_quick_opensubtitles_what,
        url = "https://opensubtitles-v3.strem.io/manifest.json",
    ),
    QuickAddon(
        name = "AIOStreams",
        host = "ElfHosted",
        what = R.string.addons_quick_aiostreams_what,
        configureUrl = "https://aiostreams.elfhosted.com/configure",
        unconfigured = R.string.addons_quick_aiostreams_unconfigured,
    ),
)

// -------------------------------------------------------------- view model

data class InstallState(
    val url: String = "",
    val busy: Boolean = false,
    val error: AppError? = null,
)

data class FirebaseSyncUi(
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val error: AppError? = null,
    val preferencesError: AppError? = null,
    val lastSync: String? = null,
    val lastDelta: Int? = null,
)

data class StremioSyncUi(
    val account: StremioAccount? = null,
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: AppError? = null,
    val report: StremioImportReport? = null,
)

data class MdblistCatalogUi(
    val linked: Boolean = false,
    val enabled: Boolean = false,
    val apiKey: String = "",
    val busy: Boolean = false,
    val error: AppError? = null,
    val message: MdblistCatalogMessage? = null,
)

/**
 * The outcome of a catalogue action, as facts rather than as a sentence.
 *
 * These used to be built here with `appContext.getString`, which resolved
 * against the *system* locale rather than the in-app language setting — the
 * application context never sees the locale override the UI runs under. Types
 * push the wording to the one place that does.
 */
sealed interface MdblistCatalogMessage {
    data object Linked : MdblistCatalogMessage
    data object Disabled : MdblistCatalogMessage
    data class Removed(val count: Int) : MdblistCatalogMessage
    data class Enabled(val count: Int, val failed: List<String>) : MdblistCatalogMessage
    data class Updated(val count: Int, val failed: List<String>) : MdblistCatalogMessage
}

class AddonsViewModel(private val graph: DataGraph) : ViewModel() {

    val addons: StateFlow<List<Addon>> = graph.addons.observeAddons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _install = MutableStateFlow(InstallState())
    val install: StateFlow<InstallState> = _install.asStateFlow()

    private val _firebase = MutableStateFlow(FirebaseSyncUi())
    val firebase: StateFlow<FirebaseSyncUi> = _firebase.asStateFlow()

    private val _stremio = MutableStateFlow(StremioSyncUi())
    val stremio: StateFlow<StremioSyncUi> = _stremio.asStateFlow()

    private val _mdblistCatalog = MutableStateFlow(MdblistCatalogUi())
    val mdblistCatalog: StateFlow<MdblistCatalogUi> = _mdblistCatalog.asStateFlow()

    /**
     * Hides the cloud sync card entirely for an MDBList-only session: there is
     * no Firebase UID for it to sync under, so `graph.firebaseSync.enable()`
     * would only ever fail with "sign in with Google first".
     */
    val hasGoogleAccount: StateFlow<Boolean> = graph.auth.googleAccount
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.auth.googleAccount.value != null)

    init {
        viewModelScope.launch {
            combine(
                graph.firebaseSync.enabled,
                graph.firebaseSync.busy,
                graph.firebaseSync.error,
                graph.listPreferencesSync.busy,
                graph.listPreferencesSync.error,
            ) { enabled, addonBusy, addonError, preferencesBusy, preferencesError ->
                FirebaseSyncUi(
                    enabled = enabled,
                    busy = addonBusy || preferencesBusy,
                    error = addonError,
                    preferencesError = preferencesError,
                )
            }.collect { status ->
                _firebase.update {
                    status.copy(lastSync = it.lastSync, lastDelta = it.lastDelta)
                }
            }
        }
        viewModelScope.launch {
            graph.stremioAccount.account.distinctUntilChanged().collect { account ->
                _stremio.update { it.copy(account = account) }
            }
        }
        viewModelScope.launch {
            graph.addons.observeAddons().collect { addons ->
                val enabled = addons.any { it.base.contains("stremio-mdblist.baby-beamup.club", ignoreCase = true) }
                _mdblistCatalog.update { it.copy(enabled = enabled) }
            }
        }
        viewModelScope.launch {
            graph.auth.mdblistLinked.collect { linked ->
                _mdblistCatalog.update { it.copy(linked = linked) }
            }
        }
    }

    // ------------------------------------------------------------- install

    fun onUrlChange(value: String) = _install.update { it.copy(url = value, error = null) }

    fun installFromField() = install(_install.value.url)

    /** Takes an explicit URL so the quick-add buttons can install directly. */
    fun install(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isEmpty() || _install.value.busy) return

        _install.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            graph.addons.install(url).fold(
                onSuccess = { _install.update { s -> s.copy(busy = false, url = "") } },
                onFailure = { e -> _install.update { s -> s.copy(busy = false, error = (e as? AppException)?.error ?: AppError.Unexpected) } },
            )
        }
    }

    fun remove(addon: Addon) {
        viewModelScope.launch { graph.addons.remove(addon.base) }
    }

    // --------------------------------------------------------- firebase sync

    fun toggleFirebaseSync() {
        _firebase.update { it.copy(lastDelta = null) }
        viewModelScope.launch {
            if (_firebase.value.enabled) {
                graph.firebaseSync.disable()
            } else {
                graph.firebaseSync.enable()
                    .onSuccess { delta -> _firebase.update { it.copy(lastDelta = delta) } }
            }
        }
    }

    fun pullFirebase() {
        _firebase.update { it.copy(lastDelta = null) }
        viewModelScope.launch {
            graph.listPreferencesSync.restore()
            graph.firebaseSync.pull().onSuccess { delta -> _firebase.update { it.copy(lastDelta = delta) } }
        }
    }

    fun pushFirebase() {
        _firebase.update { it.copy(lastDelta = null) }
        viewModelScope.launch {
            // Addons are optional, but row customizations always belong to
            // the Google account. The same explicit action retries both so a
            // previously rejected rename/order is never silently left local.
            if (_firebase.value.enabled) {
                graph.firebaseSync.push().onSuccess { delta ->
                    _firebase.update { it.copy(lastDelta = delta) }
                }
            }
            graph.listPreferencesSync.pushNow()
        }
    }

    // ---------------------------------------------------------- Stremio sync

    fun onStremioEmail(value: String) =
        _stremio.update { it.copy(email = value, error = null, report = null) }

    fun onStremioPassword(value: String) =
        _stremio.update { it.copy(password = value, error = null, report = null) }

    fun signInStremio() {
        val state = _stremio.value
        if (state.email.isBlank() || state.password.isBlank() || state.busy) return
        _stremio.update { it.copy(busy = true, error = null, report = null) }
        viewModelScope.launch {
            graph.stremioAccount.login(state.email, state.password).fold(
                onSuccess = { report ->
                    _stremio.update {
                        it.copy(busy = false, password = "", report = report)
                    }
                },
                onFailure = { error ->
                    _stremio.update {
                        it.copy(busy = false, password = "", error = (error as? AppException)?.error ?: AppError.Unexpected)
                    }
                },
            )
        }
    }

    fun syncStremio() {
        if (_stremio.value.busy) return
        _stremio.update { it.copy(busy = true, error = null, report = null) }
        viewModelScope.launch {
            graph.stremioAccount.sync().fold(
                onSuccess = { report -> _stremio.update { it.copy(busy = false, report = report) } },
                onFailure = { error -> _stremio.update { it.copy(busy = false, error = (error as? AppException)?.error ?: AppError.Unexpected) } },
            )
        }
    }

    fun disconnectStremio() {
        viewModelScope.launch {
            graph.stremioAccount.logout()
            _stremio.update { StremioSyncUi() }
        }
    }

    fun onMdblistApiKeyChange(value: String) =
        _mdblistCatalog.update { it.copy(apiKey = value, error = null, message = null) }

    fun linkMdblist() {
        val key = _mdblistCatalog.value.apiKey.trim()
        if (key.isBlank() || _mdblistCatalog.value.busy) return

        _mdblistCatalog.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            graph.auth.linkMdblist(key).fold(
                onSuccess = {
                    graph.listPreferencesSync.restore()
                    graph.scheduler.onSignedIn()
                    _mdblistCatalog.update {
                        it.copy(
                            linked = true,
                            apiKey = "",
                            busy = false,
                            message = MdblistCatalogMessage.Linked,
                        )
                    }
                },
                onFailure = { error ->
                    _mdblistCatalog.update {
                        it.copy(
                            busy = false,
                            error = (error as? AppException)?.error ?: AppError.Unexpected,
                        )
                    }
                },
            )
        }
    }

    fun toggleMdblistCatalogs() {
        val state = _mdblistCatalog.value
        if (!state.linked || state.busy) return

        _mdblistCatalog.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            if (state.enabled) {
                graph.addons.disableMdblistAddons().fold(
                    onSuccess = { removed ->
                        _mdblistCatalog.update {
                            it.copy(
                                enabled = false,
                                busy = false,
                                message = if (removed > 0) {
                                    MdblistCatalogMessage.Removed(removed)
                                } else {
                                    MdblistCatalogMessage.Disabled
                                },
                            )
                        }
                    },
                    onFailure = { error ->
                        _mdblistCatalog.update { it.copy(busy = false, error = (error as? AppException)?.error ?: AppError.Unexpected) }
                    },
                )
            } else {
                graph.addons.exportAllMdblistLists().fold(
                    onSuccess = { report ->
                        _mdblistCatalog.update {
                            it.copy(
                                enabled = true,
                                busy = false,
                                message = MdblistCatalogMessage.Enabled(
                                    count = report.exported.size,
                                    failed = report.failed,
                                ),
                            )
                        }
                    },
                    onFailure = { error ->
                        _mdblistCatalog.update { it.copy(busy = false, error = (error as? AppException)?.error ?: AppError.Unexpected) }
                    },
                )
            }
        }
    }

    fun exportMdblistLists() {
        if (!_mdblistCatalog.value.enabled || _mdblistCatalog.value.busy) return
        _mdblistCatalog.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            graph.addons.exportAllMdblistLists().fold(
                onSuccess = { report ->
                    _mdblistCatalog.update {
                        it.copy(
                            busy = false,
                            message = MdblistCatalogMessage.Updated(
                                count = report.exported.size,
                                failed = report.failed,
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    _mdblistCatalog.update { it.copy(busy = false, error = (error as? AppException)?.error ?: AppError.Unexpected) }
                },
            )
        }
    }
}

// -------------------------------------------------------------------- UI

@Composable
fun AddonsScreen(graph: DataGraph, onBack: () -> Unit) {
    val viewModel = hubViewModel { AddonsViewModel(graph) }
    val addons by viewModel.addons.collectAsStateWithLifecycle()
    val install by viewModel.install.collectAsStateWithLifecycle()
    val firebase by viewModel.firebase.collectAsStateWithLifecycle()
    val stremio by viewModel.stremio.collectAsStateWithLifecycle()
    val mdblistCatalog by viewModel.mdblistCatalog.collectAsStateWithLifecycle()
    val hasGoogleAccount by viewModel.hasGoogleAccount.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned outside the LazyColumn on purpose: the list's initial focus
        // lands on the first focusable card below, and Compose's focus-driven
        // scroll-into-view then shifts the whole list up to seat that card
        // near the top — taking this non-focusable title with it, off the
        // top of the screen, before the user ever presses a key. A fixed
        // header can't be scrolled away by a focus change it isn't part of.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HubDimens.ScreenPaddingHorizontal)
                .padding(top = HubDimens.ScreenPaddingVertical * 2, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.addons_title), style = MaterialTheme.typography.displayLarge, color = HubColors.Text)
            Text(
                text = stringResource(R.string.addons_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = HubColors.TextDim,
                modifier = Modifier.widthIn(max = 940.dp).fillMaxWidth(),
            )
        }

        LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = HubDimens.ScreenPaddingHorizontal,
            end = HubDimens.ScreenPaddingHorizontal,
            bottom = HubDimens.ScreenPaddingVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (hasGoogleAccount) item(key = "firebase") {
            FirebaseSyncCard(
                state = firebase,
                onToggle = viewModel::toggleFirebaseSync,
                onPull = viewModel::pullFirebase,
                onPush = viewModel::pushFirebase,
            )
        }


        item(key = "stremio-account") {
            StremioSyncCard(
                state = stremio,
                onEmailChange = viewModel::onStremioEmail,
                onPasswordChange = viewModel::onStremioPassword,
                onSignIn = viewModel::signInStremio,
                onSync = viewModel::syncStremio,
                onDisconnect = viewModel::disconnectStremio,
            )
        }

        item(key = "mdblist-catalogs") {
            MdblistCatalogCard(
                state = mdblistCatalog,
                onApiKeyChange = viewModel::onMdblistApiKeyChange,
                onLink = viewModel::linkMdblist,
                onToggle = viewModel::toggleMdblistCatalogs,
                onExport = viewModel::exportMdblistLists,
            )
        }

        item(key = "install") {
            InstallCard(
                state = install,
                onUrlChange = viewModel::onUrlChange,
                onSubmit = viewModel::installFromField,
            )
        }

        item(key = "installed-head") {
            Text(
                text = "${stringResource(R.string.addons_installed)} (${addons.size})",
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
            )
        }

        if (addons.isEmpty()) {
            item(key = "installed-empty") {
                Text(
                    text = stringResource(R.string.addons_none_installed),
                    style = MaterialTheme.typography.titleMedium,
                    color = HubColors.TextFaint,
                )
            }
        } else {
            items(addons, key = { it.base }) { addon ->
                AddonRow(addon = addon, onRemove = { viewModel.remove(addon) })
            }
        }

        item(key = "getting-started") {
            GettingStartedSection(busy = install.busy, onInstall = viewModel::install)
        }

        item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ----------------------------------------------------------- Stremio account

@Composable
private fun StremioSyncCard(
    state: StremioSyncUi,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val passwordFocusRequester = remember { FocusRequester() }
    val signInFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    SyncCard(accent = HubColors.Accent) {
        if (state.account != null) {
            Text(
                stringResource(R.string.addons_stremio_connected),
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
            )
            Text(
                state.account.email,
                style = MaterialTheme.typography.bodyMedium,
                color = HubColors.TextDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HubButton(
                    text = if (state.busy) stringResource(R.string.addons_syncing) else stringResource(R.string.addons_sync_now),
                    primary = true,
                    enabled = !state.busy,
                    onClick = onSync,
                )
                HubButton(text = stringResource(R.string.addons_disconnect), enabled = !state.busy, onClick = onDisconnect)
            }
        } else {
            Text(
                stringResource(R.string.addons_fetch_stremio),
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
            )
            Text(
                text = stringResource(R.string.addons_stremio_import_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = HubColors.TextDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HubTextField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    placeholder = stringResource(R.string.addons_email_placeholder),
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    onImeAction = passwordFocusRequester::requestFocus,
                    modifier = Modifier.weight(1f),
                )
                HubTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    placeholder = stringResource(R.string.addons_password_placeholder),
                    keyboardType = KeyboardType.Password,
                    obscure = true,
                    imeAction = ImeAction.Next,
                    onImeAction = {
                        keyboardController?.hide()
                        signInFocusRequester.requestFocus()
                    },
                    focusRequester = passwordFocusRequester,
                    modifier = Modifier.weight(1f),
                )
                HubButton(
                    text = if (state.busy) stringResource(R.string.addons_signing_in) else stringResource(R.string.addons_sign_in),
                    primary = true,
                    enabled = state.email.isNotBlank() && state.password.isNotBlank() && !state.busy,
                    onClick = onSignIn,
                    modifier = Modifier.focusRequester(signInFocusRequester),
                )
            }
        }

        state.error?.let { InlineMessage(it.text(), isError = true) }
        state.report?.let { report ->
            InlineMessage(
                stringResource(R.string.addons_import_report_count, report.imported.size, report.received),
                isError = false,
            )
            if (report.imported.isNotEmpty()) {
                Text(
                    stringResource(R.string.addons_import_arrived, report.imported.joinToString(", ")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HubColors.TextDim,
                )
            }
            if (report.skipped.isNotEmpty()) {
                // Resolved outside the `joinToString` transform, which is a
                // plain `(T) -> CharSequence` lambda and cannot itself call a
                // @Composable function like `AddonImportSkipReason.text()`.
                val entries = report.skipped.map { "${it.name} (${it.reason.text()})" }
                Text(
                    text = entries.joinToString(
                        ", ",
                        prefix = stringResource(R.string.addons_not_imported),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HubColors.Rotten,
                )
            }
        }
    }
}

// ---------------------------------------------------------- MDBList catalogs

@Composable
private fun MdblistCatalogCard(
    state: MdblistCatalogUi,
    onApiKeyChange: (String) -> Unit,
    onLink: () -> Unit,
    onToggle: () -> Unit,
    onExport: () -> Unit,
) {
    SyncCard(accent = HubColors.Accent2) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.addons_use_mdblist),
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
            )
            StatusPill(on = state.enabled)
        }
        Text(
            text = stringResource(R.string.addons_mdblist_catalog_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = HubColors.TextDim,
        )

        if (!state.linked) {
            Text(
                stringResource(R.string.addons_mdblist_key_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = HubColors.TextDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HubTextField(
                    value = state.apiKey,
                    onValueChange = onApiKeyChange,
                    placeholder = stringResource(R.string.addons_mdblist_key_placeholder),
                    keyboardType = KeyboardType.Password,
                    obscure = true,
                    imeAction = ImeAction.Done,
                    onImeAction = onLink,
                    modifier = Modifier.weight(1f),
                )
                HubButton(
                    text = if (state.busy) stringResource(R.string.addons_linking) else stringResource(R.string.addons_link_mdblist),
                    primary = true,
                    enabled = state.apiKey.isNotBlank() && !state.busy,
                    onClick = onLink,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HubButton(
                    text = when {
                        state.busy -> stringResource(R.string.addons_waiting)
                        state.enabled -> stringResource(R.string.addons_turn_off)
                        else -> stringResource(R.string.addons_turn_on)
                    },
                    primary = !state.enabled,
                    enabled = !state.busy,
                    onClick = onToggle,
                )
                if (state.enabled) {
                    HubButton(
                        text = if (state.busy) stringResource(R.string.addons_syncing) else stringResource(R.string.addons_sync_now),
                        enabled = !state.busy,
                        onClick = onExport,
                    )
                }
            }
        }

        state.error?.let {
            InlineMessage(stringResource(R.string.addons_mdblist_link_error, it), isError = true)
        }
        state.message?.let { InlineMessage(it.text(), isError = false) }
    }
}

// ------------------------------------------------------------ firebase card

@Composable
private fun FirebaseSyncCard(
    state: FirebaseSyncUi,
    onToggle: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
) {
    // Stacked — title, description, then a button row of its own — rather
    // than side by side. A Row here once put the description and the button
    // row in a width tug-of-war that, on a phone screen far narrower than
    // this was designed for, squeezed the buttons to nothing and wrapped
    // their labels one letter per line. Stacking removes the contest.
    SyncCard(accent = HubColors.Accent2) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.addons_sync_between_devices), style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
            StatusPill(on = state.enabled)
        }
        Text(
            text = stringResource(R.string.addons_sync_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = HubColors.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HubButton(text = if (state.enabled) stringResource(R.string.addons_turn_off) else stringResource(R.string.addons_turn_on), enabled = !state.busy, onClick = onToggle)
            if (state.enabled) {
                HubButton(text = stringResource(R.string.addons_pull), enabled = !state.busy, onClick = onPull)
                HubButton(text = stringResource(R.string.addons_push), enabled = !state.busy, onClick = onPush)
            } else if (state.preferencesError != null) {
                HubButton(text = stringResource(R.string.addons_retry), enabled = !state.busy, onClick = onPush)
            }
        }

        state.error?.let { InlineMessage(it.text(), isError = true) }
        state.preferencesError?.let { error ->
            InlineMessage(
                stringResource(R.string.addons_preferences_error, error.text()),
                isError = true,
            )
        }
        state.lastDelta?.let { delta ->
            InlineMessage(
                if (delta > 0) stringResource(R.string.addons_delta_changed, delta)
                else stringResource(R.string.addons_delta_unchanged),
                isError = false,
            )
        }
    }
}

// -------------------------------------------------------------- install card

@Composable
private fun InstallCard(
    state: InstallState,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val installFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.addons_add_manually), style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // `weight(1f)`, not a fixed width: the button beside it is
            // measured for its natural size first, and the field takes
            // whatever is left — the same fix as the sync cards above.
            HubTextField(
                value = state.url,
                onValueChange = onUrlChange,
                placeholder = stringResource(R.string.addons_url_placeholder),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                onImeAction = {
                    keyboardController?.hide()
                    installFocusRequester.requestFocus()
                },
                modifier = Modifier.weight(1f),
            )
            HubButton(
                text = if (state.busy) stringResource(R.string.addons_reading) else stringResource(R.string.addons_install),
                primary = true,
                enabled = state.url.isNotBlank() && !state.busy,
                onClick = onSubmit,
                modifier = Modifier.focusRequester(installFocusRequester),
            )
        }

        state.error?.let { InlineMessage(it.text(), isError = true) }
    }
}

// -------------------------------------------------------- getting started

/** Mirrors the "Para começar" section on mdblisthub.netlify.app/addons. */
@Composable
private fun GettingStartedSection(busy: Boolean, onInstall: (String) -> Unit) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.addons_getting_started), style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QUICK_ADDONS.forEach { quick ->
                GettingStartedCard(
                    quick = quick,
                    busy = busy,
                    onInstall = { quick.url?.let(onInstall) },
                    onConfigure = { quick.configureUrl?.let { openUrl(context, it) } },
                )
            }
        }
    }
}

@Composable
private fun GettingStartedCard(
    quick: QuickAddon,
    busy: Boolean,
    onInstall: () -> Unit,
    onConfigure: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HubColors.Surface.copy(alpha = 0.65f))
            .border(1.dp, HubColors.Border, RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(quick.name, style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
            quick.host?.let {
                Text("·  $it", style = MaterialTheme.typography.titleMedium, color = HubColors.TextFaint)
            }
            quick.unconfigured?.let {
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(HubColors.Rotten.copy(alpha = 0.14f))
                        .border(1.dp, HubColors.Rotten.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(text = stringResource(it), style = MaterialTheme.typography.labelSmall, color = HubColors.Rotten)
                }
            }
        }
        Text(stringResource(quick.what), style = MaterialTheme.typography.bodyMedium, color = HubColors.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (quick.url != null) {
                HubButton(
                    text = if (busy) stringResource(R.string.addons_installing) else stringResource(R.string.addons_install),
                    primary = true,
                    enabled = !busy,
                    onClick = onInstall,
                )
            }
            if (quick.configureUrl != null) {
                HubButton(text = stringResource(R.string.addons_open_config), onClick = onConfigure)
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Most set-top boxes have no browser at all; the configure page is
        // meant to be visited from a phone or PC in that case anyway.
    }
}

// -------------------------------------------------------------- installed

@Composable
private fun AddonRow(addon: Addon, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(HubColors.Surface.copy(alpha = 0.65f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(addon.name, style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
            Text(
                text = buildString {
                    append(addon.resources.joinToString(", ").ifBlank { stringResource(R.string.addons_no_resources_declared) })
                    if (addon.types.isNotEmpty()) append("  ·  ${addon.types.joinToString(", ")}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = HubColors.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HubButton(text = stringResource(R.string.addons_remove), onClick = onRemove)
    }
}

// ------------------------------------------------------------ shared bits

@Composable
private fun SyncCard(accent: androidx.compose.ui.graphics.Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, accent.copy(alpha = 0.26f), RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun StatusPill(on: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) HubColors.Accent2.copy(alpha = 0.16f) else HubColors.Surface)
            .border(
                1.dp,
                if (on) HubColors.Accent2.copy(alpha = 0.4f) else HubColors.Border,
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = if (on) stringResource(R.string.addons_status_on) else stringResource(R.string.addons_status_off),
            style = MaterialTheme.typography.labelSmall,
            color = if (on) HubColors.Accent2 else HubColors.TextFaint,
        )
    }
}

@Composable
private fun InlineMessage(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) HubColors.Rotten else HubColors.Accent2,
    )
}

@Composable
private fun HubTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    obscure: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(HubColors.Surface)
            .border(1.dp, HubColors.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.titleMedium, color = HubColors.TextFaint)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = if (focusRequester != null) {
                Modifier.focusRequester(focusRequester)
            } else {
                Modifier
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = HubColors.Text),
            cursorBrush = SolidColor(HubColors.Accent2),
            visualTransformation = if (obscure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onNext = { onImeAction?.invoke() },
                onDone = { onImeAction?.invoke() },
            ),
        )
    }
}

/** Renders a catalogue outcome in the language the interface is set to. */
@Composable
private fun MdblistCatalogMessage.text(): String = when (this) {
    MdblistCatalogMessage.Linked -> stringResource(R.string.addons_mdblist_linked)
    MdblistCatalogMessage.Disabled -> stringResource(R.string.addons_mdblist_disabled)
    is MdblistCatalogMessage.Removed ->
        pluralStringResource(R.plurals.addons_mdblist_removed, count, count)
    is MdblistCatalogMessage.Enabled ->
        pluralStringResource(R.plurals.addons_mdblist_enabled, count, count) + failedSuffix(failed)
    is MdblistCatalogMessage.Updated ->
        pluralStringResource(R.plurals.addons_mdblist_updated, count, count) + failedSuffix(failed)
}

/** Empty when nothing failed, so the caller can always concatenate it. */
@Composable
private fun failedSuffix(failed: List<String>): String =
    if (failed.isEmpty()) {
        ""
    } else {
        pluralStringResource(
            R.plurals.addons_mdblist_failed_suffix,
            failed.size,
            failed.size,
            failed.joinToString(", "),
        )
    }
