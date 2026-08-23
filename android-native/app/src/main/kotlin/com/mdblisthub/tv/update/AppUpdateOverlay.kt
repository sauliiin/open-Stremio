package com.mdblisthub.tv.update

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.ui.component.HubButton

@Composable
fun AppUpdateOverlay(manager: AppUpdateManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    if (state is UpdateUiState.Hidden) return

    Dialog(
        onDismissRequest = manager::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 580.dp)
                    .background(HubColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.update_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = HubColors.Text,
                )

                when (val current = state) {
                    is UpdateUiState.Available -> AvailableContent(current, manager)
                    is UpdateUiState.Downloading -> DownloadingContent(current, manager)
                    is UpdateUiState.AwaitingInstallPermission -> PermissionContent(current, manager)
                    is UpdateUiState.Failed -> FailureContent(current, manager)
                    UpdateUiState.Hidden -> Unit
                }
            }
        }
    }
}

@Composable
private fun AvailableContent(state: UpdateUiState.Available, manager: AppUpdateManager) {
    val updateButtonFocus = remember { FocusRequester() }
    LaunchedEffect(state.release.tag) { updateButtonFocus.requestFocus() }

    Text(
        text = stringResource(R.string.update_available, state.release.tag),
        style = MaterialTheme.typography.bodyLarge,
        color = HubColors.TextDim,
    )
    ReleaseNotes(state.release.notes)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HubButton(
            text = stringResource(R.string.update_now),
            primary = true,
            onClick = { manager.downloadAndInstall(state.release) },
            modifier = Modifier.focusRequester(updateButtonFocus),
        )
        HubButton(text = stringResource(R.string.update_later), onClick = manager::dismiss)
    }
}

@Composable
private fun ReleaseNotes(notes: String?) {
    Text(
        text = stringResource(R.string.update_whats_new),
        style = MaterialTheme.typography.titleMedium,
        color = HubColors.Text,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .background(HubColors.SurfaceStrong, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState())
            .focusable()
            .padding(14.dp),
    ) {
        Text(
            text = notes ?: stringResource(R.string.update_notes_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = HubColors.TextDim,
        )
    }
}

@Composable
private fun DownloadingContent(state: UpdateUiState.Downloading, manager: AppUpdateManager) {
    val percent = if (state.totalBytes > 0L) {
        ((state.bytesDownloaded * 100L) / state.totalBytes).coerceIn(0L, 100L).toInt()
    } else {
        0
    }
    Text(
        text = stringResource(R.string.update_downloading, percent),
        style = MaterialTheme.typography.bodyLarge,
        color = HubColors.TextDim,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(HubColors.SurfaceStrong, RoundedCornerShape(4.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percent / 100f)
                .height(8.dp)
                .background(HubColors.Accent, RoundedCornerShape(4.dp)),
        )
    }
    HubButton(text = stringResource(R.string.update_cancel), onClick = manager::dismiss)
}

@Composable
private fun PermissionContent(
    state: UpdateUiState.AwaitingInstallPermission,
    manager: AppUpdateManager,
) {
    Text(
        text = stringResource(R.string.update_permission, state.release.tag),
        style = MaterialTheme.typography.bodyLarge,
        color = HubColors.TextDim,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HubButton(
            text = stringResource(R.string.update_open_settings),
            primary = true,
            onClick = manager::openInstallPermissionSettings,
        )
        HubButton(text = stringResource(R.string.update_later), onClick = manager::dismiss)
    }
}

@Composable
private fun FailureContent(state: UpdateUiState.Failed, manager: AppUpdateManager) {
    Text(
        text = buildString {
            append(stringResource(R.string.update_failed))
            state.detail?.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
        },
        style = MaterialTheme.typography.bodyLarge,
        color = HubColors.TextDim,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HubButton(
            text = stringResource(R.string.update_retry),
            primary = true,
            onClick = manager::retry,
        )
        HubButton(text = stringResource(R.string.update_later), onClick = manager::dismiss)
    }
}
