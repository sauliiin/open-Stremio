package com.mdblisthub.tv.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubEffects
import com.mdblisthub.tv.core.ui.theme.HubMotion
import com.mdblisthub.tv.core.ui.theme.HubShapes
import com.mdblisthub.tv.core.ui.theme.HubStrokes

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
    val motion = tween<Float>(HubMotion.Focus, easing = HubMotion.StandardEasing)
    val scale by animateFloatAsState(
        targetValue = if (focused && enabled) HubMotion.FocusScale else 1f,
        animationSpec = motion,
        label = "button-scale",
    )

    val background by animateColorAsState(
        targetValue = when {
            !enabled -> HubColors.Surface.copy(alpha = 0.4f)
            focused -> HubColors.Accent
            primary -> HubColors.Accent.copy(alpha = HubEffects.SelectedWashAlpha)
            else -> HubColors.Surface.copy(alpha = HubEffects.GlassSurfaceAlpha)
        },
        animationSpec = tween(HubMotion.Focus, easing = HubMotion.StandardEasing),
        label = "button-background",
    )
    val border by animateColorAsState(
        targetValue = when {
            focused -> HubColors.AccentSoft
            primary -> HubColors.Accent.copy(alpha = 0.56f)
            else -> HubColors.Border.copy(alpha = HubEffects.SoftBorderAlpha)
        },
        animationSpec = tween(HubMotion.Focus, easing = HubMotion.StandardEasing),
        label = "button-border",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> HubColors.TextFaint
            focused -> HubColors.Text
            primary -> HubColors.Text
            else -> HubColors.TextDim
        },
        animationSpec = tween(HubMotion.Focus, easing = HubMotion.StandardEasing),
        label = "button-content",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(HubShapes.Control))
            .background(background)
            .border(
                width = if (focused) HubStrokes.Focus else HubStrokes.Hairline,
                color = border,
                shape = RoundedCornerShape(HubShapes.Control),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 25.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
        )
    }
}
