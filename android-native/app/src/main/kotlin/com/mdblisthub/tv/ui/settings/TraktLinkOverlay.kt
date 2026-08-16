package com.mdblisthub.tv.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.model.TraktLinkFailure
import com.mdblisthub.tv.core.model.TraktLinkState
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.ui.component.HubButton

/**
 * The Trakt device link, as one screen.
 *
 * No QR code: the code is typed by hand at [TraktDeviceCode.verificationUrl],
 * on whatever other device the user already has a browser open on. That is
 * one fewer moving part than scanning — no camera permission, no dependency
 * on decoding a bitmap — for a code that is eight characters and read once.
 *
 * Same `Dialog` over a full-screen scrim as [LanguagePickerOverlay], so the
 * two read as the same kind of thing.
 */
@Composable
fun TraktLinkOverlay(
    state: TraktLinkState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(HubColors.Surface)
                    .padding(28.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    stringResource(R.string.trakt_link_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = HubColors.Text,
                    textAlign = TextAlign.Center,
                )

                // Shown while the link is still in progress — before the
                // switch actually commits, which only happens on `Linked`.
                // Trakt's own limit, not a rule this app imposes, so it needs
                // to be read before the user spends their one free-account
                // slot on this app rather than on something else they use.
                if (state is TraktLinkState.Requesting || state is TraktLinkState.AwaitingUser) {
                    Text(
                        stringResource(R.string.trakt_link_vip_limit_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = HubColors.Rotten,
                        textAlign = TextAlign.Center,
                    )
                }

                when (state) {
                    TraktLinkState.Requesting -> Text(
                        stringResource(R.string.trakt_link_requesting),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                        textAlign = TextAlign.Center,
                    )

                    is TraktLinkState.AwaitingUser -> {
                        val context = LocalContext.current
                        val linkInteraction = remember { MutableInteractionSource() }
                        val linkFocused by linkInteraction.collectIsFocusedAsState()
                        val linkColor by animateColorAsState(
                            if (linkFocused) HubColors.Accent else HubColors.AccentSoft,
                            label = "trakt-link-color",
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.trakt_link_instruction_prefix),
                                style = MaterialTheme.typography.bodyLarge,
                                color = HubColors.TextDim,
                            )
                            // Only the address is a tap target / D-pad stop;
                            // the surrounding instruction remains plain text.
                            Text(
                                state.code.verificationUrl.removePrefix("https://"),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    textDecoration = TextDecoration.Underline,
                                ),
                                color = linkColor,
                                modifier = Modifier.clickable(
                                    interactionSource = linkInteraction,
                                    indication = null,
                                    onClick = { openUrl(context, state.code.verificationUrl) },
                                ),
                            )
                            Text(
                                stringResource(R.string.trakt_link_instruction_suffix),
                                style = MaterialTheme.typography.bodyLarge,
                                color = HubColors.TextDim,
                            )
                        }

                        // Wide letter spacing and a large size on purpose: this
                        // is what the whole flow reduces to, read off a screen
                        // several metres away and copied character by
                        // character, where `0`/`O` and `1`/`I` are the whole
                        // difficulty.
                        SelectionContainer {
                            Text(
                                state.code.userCode,
                                style = MaterialTheme.typography.displayMedium.copy(
                                    fontSize = 48.sp,
                                    letterSpacing = 8.sp,
                                ),
                                color = HubColors.Text,
                                textAlign = TextAlign.Center,
                            )
                        }

                        Text(
                            stringResource(
                                R.string.trakt_link_expires,
                                state.secondsRemaining / 60,
                                state.secondsRemaining % 60,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HubColors.TextFaint,
                            textAlign = TextAlign.Center,
                        )
                    }

                    is TraktLinkState.Linked -> Text(
                        stringResource(R.string.trakt_link_linked, state.account.handle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.Text,
                        textAlign = TextAlign.Center,
                    )

                    is TraktLinkState.Failed -> Text(
                        stringResource(state.reason.messageRes()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                        textAlign = TextAlign.Center,
                    )
                }

                // A failure that a new code could fix gets a retry; one that
                // needs the build changed does not, because pressing it again
                // would fail identically.
                if (state is TraktLinkState.Failed &&
                    state.reason != TraktLinkFailure.MISSING_CREDENTIALS
                ) {
                    HubButton(
                        text = stringResource(R.string.trakt_link_retry),
                        primary = true,
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HubButton(
                    text = stringResource(
                        if (state is TraktLinkState.AwaitingUser) {
                            R.string.trakt_link_cancel
                        } else {
                            R.string.trakt_link_close
                        },
                    ),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun TraktLinkFailure.messageRes(): Int = when (this) {
    TraktLinkFailure.MISSING_CREDENTIALS -> R.string.trakt_link_error_credentials
    TraktLinkFailure.EXPIRED -> R.string.trakt_link_error_expired
    TraktLinkFailure.DENIED -> R.string.trakt_link_error_denied
    TraktLinkFailure.UNAVAILABLE -> R.string.trakt_link_error_unavailable
}

/** Same guard as `AddonsScreen`'s: most set-top boxes have no browser at all. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // The code is still on screen to type by hand — this is a shortcut,
        // not the only way through.
    }
}
