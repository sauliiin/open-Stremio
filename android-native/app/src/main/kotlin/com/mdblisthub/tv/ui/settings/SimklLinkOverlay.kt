package com.mdblisthub.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.data.repository.SimklLinkState
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.ui.component.HubButton

@Composable
fun SimklLinkOverlay(state: SimklLinkState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .9f)).padding(24.dp), Alignment.Center) {
            Column(
                Modifier.widthIn(max = 460.dp).background(HubColors.Surface, RoundedCornerShape(16.dp)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(stringResource(R.string.simkl_link_title), style = MaterialTheme.typography.headlineSmall, color = HubColors.Text)
                when (state) {
                    SimklLinkState.Requesting -> Text(stringResource(R.string.simkl_link_requesting), color = HubColors.TextDim)
                    is SimklLinkState.Awaiting -> {
                        Text(stringResource(R.string.simkl_link_instruction, state.pin.url.removePrefix("https://")), color = HubColors.TextDim, textAlign = TextAlign.Center)
                        Text(state.pin.code, style = MaterialTheme.typography.displayMedium.copy(fontSize = 48.sp, letterSpacing = 8.sp), color = HubColors.Text)
                        Text(stringResource(R.string.simkl_link_expires, state.remaining / 60, state.remaining % 60), color = HubColors.TextFaint)
                    }
                    SimklLinkState.Linked -> Text(stringResource(R.string.simkl_link_connected), color = HubColors.Text)
                    SimklLinkState.Failed -> { Text(stringResource(R.string.simkl_link_failed), color = HubColors.TextDim); HubButton(text = stringResource(R.string.trakt_link_retry), primary = true, onClick = onRetry, modifier = Modifier.fillMaxWidth()) }
                }
                HubButton(stringResource(if (state is SimklLinkState.Awaiting) R.string.trakt_link_cancel else R.string.trakt_link_close), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
