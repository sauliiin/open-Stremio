package com.mdblisthub.tv.core.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.mdblisthub.tv.core.ui.theme.HubColors

/**
 * The full-bleed artwork behind every screen.
 *
 * This is the single most Kodi thing in the app: in Estuary the fanart of
 * whatever is focused fills the screen, dimmed almost to black, and the
 * interface floats over it. It costs nothing to read and makes browsing feel
 * like the library is about films rather than about rows.
 *
 * The crossfade is slow on purpose — focus moves fast on a remote, and a
 * quick swap would strobe.
 */
@Composable
fun FanartBackdrop(
    url: String?,
    modifier: Modifier = Modifier,
    // Callers that need heavy contrast for a modal veil (resolving/failure
    // screens, the detail page's head) pass their own, higher scrim; this
    // default is what the home screen gets, and it was dark enough that the
    // artwork barely read as a photo — lowered so it actually shows.
    scrim: Float = 0.3f,
) {
    val actualScrim = if (HubColors.isCyberpunk) scrim * 0.3f else scrim
    val baseVerticalAlpha1 = if (HubColors.isCyberpunk) 0.1f else 0.35f
    val baseVerticalAlpha2 = if (HubColors.isCyberpunk) 0.2f else 0.5f

    Box(modifier.fillMaxSize().background(HubColors.Background)) {
        Crossfade(
            targetState = url,
            animationSpec = tween(durationMillis = 600),
            label = "fanart",
        ) { current ->
            if (current != null) {
                AsyncImage(
                    model = current,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Two gradients rather than a flat overlay: the left edge has to carry
        // white text over whatever the artwork happens to be there, while the
        // bottom has to fade into the rows without a visible seam.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to HubColors.Background.copy(alpha = actualScrim),
                        0.55f to HubColors.Background.copy(alpha = actualScrim * 0.78f),
                        1f to HubColors.Background.copy(alpha = actualScrim * 0.5f),
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to HubColors.Background.copy(alpha = baseVerticalAlpha1),
                        0.45f to HubColors.Background.copy(alpha = baseVerticalAlpha2),
                        1f to HubColors.Background,
                    )
                )
        )
    }
}
