package com.mdblisthub.tv.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.Text

private val OmniStreamGradient = listOf(
    Color(0xFF00F3FF),
    Color(0xFF7C5CFF),
    Color(0xFFFF00AA),
    Color(0xFFBFFFFF),
    Color(0xFF00F3FF),
)

/** Brand title with a neon gradient that continuously travels left to right. */
@Composable
fun AnimatedOmniStreamTitle(
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val transition = rememberInfiniteTransition(label = "omnistream-title")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "omnistream-gradient-position",
    )
    val span = widthPx.coerceAtLeast(1f)
    val startX = -span + progress * span * 2f
    val brush = Brush.linearGradient(
        colors = OmniStreamGradient,
        start = Offset(startX, 0f),
        end = Offset(startX + span, 0f),
        tileMode = TileMode.Mirror,
    )

    Text(
        text = "OmniStream",
        style = style.copy(brush = brush, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        modifier = modifier.onSizeChanged { widthPx = it.width.toFloat() },
    )
}

/** Backward-compatibility alias for [AnimatedOmniStreamTitle]. */
@Composable
fun AnimatedOpenStreamTitle(
    style: TextStyle,
    modifier: Modifier = Modifier,
) = AnimatedOmniStreamTitle(style = style, modifier = modifier)
