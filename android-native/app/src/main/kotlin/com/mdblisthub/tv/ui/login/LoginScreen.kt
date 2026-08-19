package com.mdblisthub.tv.ui.login

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.LocalHostActivity
import com.mdblisthub.tv.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.ui.component.HubSpinner
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.ui.component.AnimatedOpenStreamTitle
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.hubViewModel
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    graph: DataGraph,
    onSignedIn: () -> Unit,
) {
    val viewModel = hubViewModel { LoginViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // Read from the composition local rather than unwrapped from the context:
    // `MainActivity` replaces `LocalContext` with a locale-wrapped
    // `ContextImpl`, which has no Activity anywhere in its chain.
    val hostActivity = LocalHostActivity.current
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val focusRequester = remember { FocusRequester() }

    // Local and never persisted: it only decides which *form* this screen
    // shows before any account exists. Once a credential is actually saved,
    // `AuthRepository` is what remembers which mode the session is in.
    var mdblistOnlyMode by remember { mutableStateOf(false) }

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) onSignedIn()
    }
    LaunchedEffect(state.google, state.busy, mdblistOnlyMode) {
        if (!state.busy) focusRequester.requestFocus()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            AnimatedOpenStreamTitle(style = MaterialTheme.typography.displayLarge)

            if (state.google == null && !mdblistOnlyMode) {
                Text(
                    text = stringResource(R.string.login_google_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = HubColors.TextDim,
                )

                if (state.busy) {
                    HubSpinner()
                } else {
                    HubButton(
                        text = stringResource(R.string.login_google_button),
                        primary = true,
                        modifier = Modifier.focusRequester(focusRequester),
                        onClick = {
                            viewModel.beginGoogleSignIn()
                            scope.launch {
                                try {
                                    val googleOption = GetGoogleIdOption.Builder()
                                        .setServerClientId(resources.getString(R.string.default_web_client_id))
                                        .setNonce(java.util.UUID.randomUUID().toString())
                                        .setFilterByAuthorizedAccounts(false)
                                        .setAutoSelectEnabled(false)
                                        .build()

                                    // Must be an Activity: the provider starts
                                    // its picker with `startActivity`, and from
                                    // a non-Activity context that throws for
                                    // want of FLAG_ACTIVITY_NEW_TASK — which
                                    // surfaced only as the generic "could not
                                    // open Google sign-in" below.
                                    val activity = hostActivity ?: run {
                                        viewModel.reportGoogleError(
                                            resources.getString(R.string.login_error_no_activity),
                                        )
                                        return@launch
                                    }

                                    val result = credentialManager.getCredential(
                                        context = activity,
                                        request = GetCredentialRequest.Builder()
                                            .addCredentialOption(googleOption)
                                            .build(),
                                    )
                                    val credential = result.credential
                                    if (credential is CustomCredential &&
                                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                    ) {
                                        val google = GoogleIdTokenCredential.createFrom(credential.data)
                                        viewModel.signInWithGoogle(google.idToken)
                                    } else {
                                        viewModel.reportGoogleError(
                                            resources.getString(R.string.login_error_no_credential),
                                        )
                                    }
                                } catch (_: GetCredentialCancellationException) {
                                    viewModel.reportGoogleError(
                                        resources.getString(R.string.login_error_cancelled),
                                    )
                                } catch (_: NoCredentialException) {
                                    viewModel.reportGoogleError(
                                        resources.getString(R.string.login_error_no_accounts),
                                    )
                                } catch (error: Exception) {
                                    viewModel.reportGoogleError(
                                        resources.getString(
                                            R.string.login_error_open_failed,
                                            error.message
                                                ?: resources.getString(R.string.login_error_no_detail),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    // The Fire-TV door: no Credential Manager call happens on
                    // this path at all, because there is usually no Google
                    // account on the box to offer it one.
                    HubButton(
                        text = stringResource(R.string.login_mdblist_only_button),
                        onClick = { mdblistOnlyMode = true },
                    )
                }
            } else if (mdblistOnlyMode) {
                Text(
                    text = stringResource(R.string.login_mdblist_only_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = HubColors.TextDim,
                )

                if (state.busy) {
                    HubSpinner()
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(HubColors.Surface)
                            .border(1.dp, HubColors.Border, RoundedCornerShape(10.dp))
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        if (state.key.isEmpty()) {
                            Text(
                                stringResource(R.string.login_key_placeholder),
                                style = MaterialTheme.typography.titleLarge,
                                color = HubColors.TextFaint,
                            )
                        }
                        BasicTextField(
                            value = state.key,
                            onValueChange = viewModel::onKeyChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge.copy(color = HubColors.Text),
                            cursorBrush = SolidColor(HubColors.Accent2),
                            // Shown in the clear. Masking it bought nothing —
                            // this is an API key typed once on a television,
                            // usually by pecking at an on-screen keyboard with
                            // a remote, and a single wrong character is
                            // impossible to spot behind dots. Anyone close
                            // enough to read the screen already has the box.
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { viewModel.signInWithMdblistOnly() },
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        )
                    }

                    HubButton(
                        text = stringResource(R.string.login_mdblist_only_submit),
                        primary = true,
                        enabled = state.key.isNotBlank(),
                        onClick = viewModel::signInWithMdblistOnly,
                    )
                    HubButton(
                        text = stringResource(R.string.login_mdblist_only_back),
                        onClick = { mdblistOnlyMode = false },
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.login_google_connected, state.google?.email.orEmpty()),
                    style = MaterialTheme.typography.titleMedium,
                    color = HubColors.Accent2,
                )

                if (state.checkingSavedKey) {
                    Text(
                        text = stringResource(R.string.login_checking_key),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                    )
                    HubSpinner()
                } else {
                    Text(
                        text = stringResource(R.string.login_mdblist_intro),
                        style = MaterialTheme.typography.titleLarge,
                        color = HubColors.Text,
                    )
                    Text(
                        text = stringResource(R.string.login_mdblist_signup),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(HubColors.Surface)
                            .border(1.dp, HubColors.Border, RoundedCornerShape(10.dp))
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        if (state.key.isEmpty()) {
                            Text(
                                stringResource(R.string.login_key_placeholder),
                                style = MaterialTheme.typography.titleLarge,
                                color = HubColors.TextFaint,
                            )
                        }
                        BasicTextField(
                            value = state.key,
                            onValueChange = viewModel::onKeyChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge.copy(color = HubColors.Text),
                            cursorBrush = SolidColor(HubColors.Accent2),
                            // Same reasoning as the mdblist-only field above.
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { viewModel.linkMdblist() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        )
                    }

                    if (state.busy) {
                        HubSpinner()
                    } else {
                        HubButton(
                            text = stringResource(R.string.login_link_mdblist),
                            primary = true,
                            enabled = state.key.isNotBlank(),
                            onClick = viewModel::linkMdblist,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HubButton(
                                text = stringResource(R.string.login_continue_without),
                                onClick = viewModel::continueWithoutMdblist,
                            )
                            HubButton(
                                text = stringResource(R.string.login_create_account),
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, "https://mdblist.com/".toUri())
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    } catch (_: ActivityNotFoundException) {
                                        viewModel.reportGoogleError(
                                            resources.getString(R.string.login_error_no_browser),
                                        )
                                    }
                                },
                            )
                        }
                        HubButton(
                            text = stringResource(R.string.login_change_google_account),
                            onClick = viewModel::changeGoogleAccount,
                        )
                    }
                }
            }

            // Two sources, one line: free text the screen already resolved, and
            // a resource id the ViewModel raised without a locale to resolve it
            // against. Resolving here is what keeps both in the selected
            // language.
            val error = state.error ?: state.errorRes?.let { stringResource(it) }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = HubColors.Rotten)
            }
        }
    }
}
