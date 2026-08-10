package com.mdblisthub.tv.ui.login

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) onSignedIn()
    }
    LaunchedEffect(state.google, state.busy) {
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

            if (state.google == null) {
                Text(
                    text = "Entre com sua conta Google. Ela protege seus dados no Firebase e " +
                        "identifica a mesma conta em todos os aparelhos.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = HubColors.TextDim,
                )

                if (state.busy) {
                    HubSpinner()
                } else {
                    HubButton(
                        text = "Entrar com o Google",
                        primary = true,
                        modifier = Modifier.focusRequester(focusRequester),
                        onClick = {
                            viewModel.beginGoogleSignIn()
                            scope.launch {
                                try {
                                    val googleOption = GetGoogleIdOption.Builder()
                                        .setServerClientId(resources.getString(R.string.default_web_client_id))
                                        .setFilterByAuthorizedAccounts(false)
                                        .setAutoSelectEnabled(false)
                                        .build()
                                    val result = credentialManager.getCredential(
                                        context = context,
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
                                        viewModel.reportGoogleError("A conta escolhida não devolveu uma credencial Google.")
                                    }
                                } catch (_: GetCredentialCancellationException) {
                                    viewModel.reportGoogleError("Login com o Google cancelado.")
                                } catch (_: NoCredentialException) {
                                    viewModel.reportGoogleError(
                                        "Nenhuma conta Google está disponível neste aparelho.",
                                    )
                                } catch (error: Exception) {
                                    viewModel.reportGoogleError(
                                        "Não consegui abrir o login do Google. " +
                                            "(${error.message ?: "sem detalhe"})",
                                    )
                                }
                            }
                        },
                    )
                }
            } else {
                Text(
                    text = "Google conectado: ${state.google?.email.orEmpty()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = HubColors.Accent2,
                )

                if (state.checkingSavedKey) {
                    Text(
                        text = "Verificando se já existe uma chave MDBList salva…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                    )
                    HubSpinner()
                } else {
                    Text(
                        text = "Insira uma chave API MDBList para avaliações, trailers, " +
                            "buscas e para trazer suas listas.",
                        style = MaterialTheme.typography.titleLarge,
                        color = HubColors.Text,
                    )
                    Text(
                        text = "Não possui conta? Entre em https://mdblist.com/ e crie uma " +
                            "conta gratuita.",
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
                                "Chave API MDBList",
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
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { viewModel.linkMdblist() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        )
                    }

                    if (state.busy) {
                        HubSpinner()
                    } else {
                        HubButton(
                            text = "Vincular MDBList",
                            primary = true,
                            enabled = state.key.isNotBlank(),
                            onClick = viewModel::linkMdblist,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HubButton(
                                text = "Prosseguir sem MDBList",
                                onClick = viewModel::continueWithoutMdblist,
                            )
                            HubButton(
                                text = "Criar conta gratuita",
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("https://mdblist.com/"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    } catch (_: ActivityNotFoundException) {
                                        viewModel.reportGoogleError(
                                            "Nenhum navegador disponível. Abra https://mdblist.com/ " +
                                                "em outro aparelho.",
                                        )
                                    }
                                },
                            )
                        }
                        HubButton(text = "Trocar conta Google", onClick = viewModel::changeGoogleAccount)
                    }
                }
            }

            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = HubColors.Rotten)
            }
        }
    }
}
