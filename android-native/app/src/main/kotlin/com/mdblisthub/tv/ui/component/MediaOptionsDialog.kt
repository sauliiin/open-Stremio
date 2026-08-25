package com.mdblisthub.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.ui.theme.HubColors

/** The actions shared by every poster's held-OK context menu. */
@Composable
fun MediaOptionsDialog(
    isWatched: Boolean,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    onToggleWatched: () -> Unit,
    onChooseSource: () -> Unit,
    onReset: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val firstActionFocus = remember { FocusRequester() }
    // The long-press that opened this dialog is still physically held when
    // focus moves to Play. Consume its matching release so the TV Button does
    // not interpret it as a new click on the first action.
    var consumeOpeningConfirmRelease by remember { mutableStateOf(true) }
    val actionScale = ButtonDefaults.scale(focusedScale = 1.03f)
    LaunchedEffect(Unit) {
        repeat(3) {
            withFrameNanos { }
            if (firstActionFocus.requestFocus()) return@LaunchedEffect
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(230.dp)
                .background(HubColors.Surface, RoundedCornerShape(14.dp))
                .onPreviewKeyEvent { event ->
                    val isConfirm = event.key == Key.DirectionCenter ||
                        event.key == Key.Enter ||
                        event.key == Key.NumPadEnter ||
                        event.key == Key.Spacebar
                    if (consumeOpeningConfirmRelease && isConfirm && event.type == KeyEventType.KeyUp) {
                        consumeOpeningConfirmRelease = false
                        true
                    } else {
                        false
                    }
                }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.home_item_options_title),
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPlay,
                    scale = actionScale,
                    modifier = Modifier.fillMaxWidth().focusRequester(firstActionFocus),
                ) { Text(stringResource(R.string.media_options_play)) }
                Button(
                    onClick = onChooseSource,
                    scale = actionScale,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.detail_select_source))
                }
                Button(onClick = onInfo, scale = actionScale, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.media_options_info))
                }
                Button(
                    onClick = onToggleWatched,
                    scale = actionScale,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (isWatched) R.string.media_options_mark_unwatched
                            else R.string.detail_mark_watched,
                        ),
                    )
                }
                if (onReset != null) {
                    Button(
                        onClick = onReset,
                        scale = actionScale,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.media_options_reset))
                    }
                }
            }
        }
    }
}
