package com.mdblisthub.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.material3.Icon
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubMotion
import com.mdblisthub.tv.core.ui.theme.HubShapes
import com.mdblisthub.tv.core.ui.theme.HubStrokes
import com.mdblisthub.tv.navigation.Routes
import com.mdblisthub.tv.player.ExoVideoSurface
import com.mdblisthub.tv.player.VideoScaleType

private val MINI_PLAYER_SIZE = androidx.compose.ui.unit.DpSize(280.dp, 158.dp)

/**
 * The floating window a title is left running in when a viewer steps out of
 * the player with time still on the clock — see `PlayerViewModel.minimize`
 * and [MiniPlayerCoordinator].
 *
 * Hosted once, above `HubNavHost`'s own content, rather than per-screen: the
 * whole point is that it survives whichever screen the viewer wanders to
 * next. `currentRoute` exists only to hide it for the one screen where
 * showing it would be redundant — the player itself, the moment it reclaims
 * this exact session back.
 */
@Composable
fun MiniPlayerOverlay(graph: DataGraph, navController: NavController, currentRoute: String?) {
    val session by MiniPlayerCoordinator.session.collectAsStateWithLifecycle()
    val subtitleColorCode by graph.uiPreferences.subtitleColor
        .collectAsStateWithLifecycle(initialValue = "white")
    val subtitleTextOpacity by graph.uiPreferences.subtitleTextOpacity
        .collectAsStateWithLifecycle(initialValue = 100)
    val subtitleBackgroundEnabled by graph.uiPreferences.subtitleBackgroundEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val subtitleBackgroundOpacity by graph.uiPreferences.subtitleBackgroundOpacity
        .collectAsStateWithLifecycle(initialValue = 40)
    val subtitleColor = when (subtitleColorCode) {
        "yellow" -> Color.Yellow
        "red" -> Color.Red
        "blue" -> Color.Blue
        "black" -> Color.Black
        else -> Color.White
    }.copy(alpha = subtitleTextOpacity.coerceIn(0, 100) / 100f)
    val active = session ?: return
    if (currentRoute == Routes.PLAYER) return

    var miniPlayerFocused by remember { mutableStateOf(false) }
    val fullscreenFocusRequester = remember { FocusRequester() }

    // The first Back press while the window is floating goes to it instead
    // of whatever the screen underneath would otherwise do with it — a
    // viewer has to be able to reach "tela cheia"/"parar" from the remote
    // without first fighting through Home's own back stack. Once the window
    // actually holds focus, Back is unclaimed again: this only ever eats the
    // one press needed to arrive here.
    BackHandler(enabled = !miniPlayerFocused) {
        fullscreenFocusRequester.requestFocus()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Box(
            Modifier
                .padding(end = 40.dp, bottom = 40.dp)
                .size(MINI_PLAYER_SIZE)
                .clip(RoundedCornerShape(10.dp))
                .background(HubColors.Background)
                .onFocusChanged { miniPlayerFocused = it.hasFocus }
                .focusGroup(),
        ) {
            ExoVideoSurface(
                controller = active.controller,
                scaleType = VideoScaleType.ZOOM,
                modifier = Modifier.size(MINI_PLAYER_SIZE),
                subtitleColor = subtitleColor,
                subtitleBackgroundOpacity = if (subtitleBackgroundEnabled) {
                    subtitleBackgroundOpacity.coerceIn(0, 100) / 100f
                } else {
                    0f
                },
            )

            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .size(width = MINI_PLAYER_SIZE.width, height = 56.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.78f),
                        ),
                    ),
            )

            BasicText(
                text = active.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                MiniPlayerButton(
                    icon = Icons.Filled.Fullscreen,
                    label = stringResource(R.string.mini_player_fullscreen),
                    primary = true,
                    onClick = {
                        navController.navigate(
                            Routes.player(active.type, active.tmdbId, active.season, active.episode),
                        )
                    },
                    modifier = Modifier.focusRequester(fullscreenFocusRequester),
                )
                MiniPlayerButton(
                    icon = Icons.Filled.Stop,
                    label = stringResource(R.string.mini_player_stop),
                    destructive = true,
                    onClick = { MiniPlayerCoordinator.close(graph) },
                )
            }
        }
    }
}

/**
 * Compact TV control for the floating player.
 *
 * Focus uses the same filled cue as the rest of the app, while the destructive
 * action gets its own red treatment so it cannot be mistaken for navigation.
 * The visible chrome stays icon-only; [label] is retained for accessibility.
 */
@Composable
private fun MiniPlayerButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val motion = tween<Float>(HubMotion.Focus, easing = HubMotion.StandardEasing)
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = motion,
        label = "mini-player-button-scale",
    )
    val background by animateColorAsState(
        targetValue = when {
            focused && destructive -> HubColors.Rotten
            focused -> HubColors.Accent
            primary -> HubColors.Accent.copy(alpha = 0.82f)
            else -> Color.Black.copy(alpha = 0.72f)
        },
        animationSpec = tween(HubMotion.Focus, easing = HubMotion.StandardEasing),
        label = "mini-player-button-background",
    )
    val border by animateColorAsState(
        targetValue = when {
            focused -> Color.White.copy(alpha = 0.92f)
            destructive -> HubColors.Rotten.copy(alpha = 0.58f)
            else -> HubColors.AccentSoft.copy(alpha = 0.66f)
        },
        animationSpec = tween(HubMotion.Focus, easing = HubMotion.StandardEasing),
        label = "mini-player-button-border",
    )
    val foreground by animateColorAsState(
        targetValue = if (focused || primary) Color.White else HubColors.TextDim,
        animationSpec = tween(HubMotion.Focus, easing = HubMotion.StandardEasing),
        label = "mini-player-button-content",
    )
    val shape = RoundedCornerShape(HubShapes.Pill)

    Row(
        modifier
            .scale(scale)
            .size(42.dp)
            .clip(shape)
            .background(background)
            .border(
                width = if (focused) HubStrokes.Focus else HubStrokes.Hairline,
                color = border,
                shape = shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = foreground,
            modifier = Modifier.size(21.dp),
        )
    }
}
