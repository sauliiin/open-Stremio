package com.mdblisthub.tv.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.ui.theme.HubColors

/**
 * A focusable button.
 *
 * Written by hand rather than taken from tv-material so that focus reads the
 * same as a poster card does — filled accent, not an outline. Consistency of
 * the focus cue matters more on a remote than component provenance.
 */
@Composable
fun HubButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val background by animateColorAsState(
        targetValue = when {
            !enabled -> HubColors.Surface.copy(alpha = 0.4f)
            focused -> HubColors.Accent
            primary -> HubColors.SurfaceStrong
            else -> HubColors.Surface.copy(alpha = 0.6f)
        },
        label = "button-background",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(
                width = 1.dp,
                color = if (focused) background else HubColors.Border,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 26.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                !enabled -> HubColors.TextFaint
                focused -> HubColors.Text
                else -> HubColors.TextDim
            },
        )
    }
}
