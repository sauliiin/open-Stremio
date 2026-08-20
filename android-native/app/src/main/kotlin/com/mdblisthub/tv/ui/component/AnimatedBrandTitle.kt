package com.mdblisthub.tv.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mdblisthub.tv.R

private val OmniStreamGradient = listOf(
    Color(0xFF00F3FF),
    Color(0xFF7C5CFF),
    Color(0xFFFF00AA),
    Color(0xFFBFFFFF),
    Color(0xFF00F3FF),
)

/** Brand title with an infinity icon and a neon gradient that continuously travels left to right. */
@Composable
fun AnimatedOmniStreamTitle(
    style: TextStyle,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
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

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (showIcon) {
            Image(
                painter = painterResource(id = R.drawable.ic_omnistream_infinity),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(32.dp)
                    .width(56.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        Text(
            text = "OmniStream",
            style = style.copy(brush = brush, fontWeight = FontWeight.Bold),
            modifier = Modifier.onSizeChanged { widthPx = it.width.toFloat() },
        )
    }
}

/** Backward-compatibility alias for [AnimatedOmniStreamTitle]. */
@Composable
fun AnimatedOpenStreamTitle(
    style: TextStyle,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
) = AnimatedOmniStreamTitle(style = style, modifier = modifier, showIcon = showIcon)

/** Alias for [AnimatedOmniStreamTitle]. */
@Composable
fun AnimatedBrandTitle(
    style: TextStyle,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
) = AnimatedOmniStreamTitle(style = style, modifier = modifier, showIcon = showIcon)
