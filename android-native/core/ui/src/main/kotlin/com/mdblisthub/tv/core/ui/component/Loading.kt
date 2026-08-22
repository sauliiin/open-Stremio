package com.mdblisthub.tv.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubEffects

@Composable
fun HubSpinner(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 34.dp) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "spinner-angle",
    )

    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = 3.dp.toPx())
        val inset = stroke.width / 2
        drawArc(
            // Dimmed rather than the theme's raw border colour. In Normal
            // that colour is already a near-black blue-grey, so full opacity
            // against the near-black background read as a faint track — but
            // Cyberpunk's border is a saturated pink at full brightness,
            // nearly as loud as the accent arc drawn on top of it, and the
            // two blended into what looked like a single solid disc rather
            // than a track with a moving indicator. Dimming it is what keeps
            // the track subordinate to the indicator in every palette,
            // instead of relying on each theme's border happening to be dark.
            color = HubColors.Border.copy(alpha = 0.35f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = Size(this.size.width - stroke.width, this.size.height - stroke.width),
        )
        drawArc(
            color = HubColors.Accent2,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            style = stroke,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = Size(this.size.width - stroke.width, this.size.height - stroke.width),
        )
    }
}

/** Centred spinner with a line of text, for a screen that has nothing yet. */
@Composable
fun LoadingScreen(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .background(HubColors.Surface.copy(alpha = HubEffects.MutedSurfaceAlpha), CircleShape)
                .padding(horizontal = 26.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HubSpinner()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = HubColors.TextDim,
            )
        }
    }
}
