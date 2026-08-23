package com.mdblisthub.tv.core.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.dp

/**
 * The small visual vocabulary shared by every screen.
 *
 * These values intentionally describe the Open Stremio interface rather than
 * any individual page. Keeping focus, corners and motion here prevents login,
 * home and the player from slowly becoming three different products while
 * still allowing each screen to keep its current structure and behaviour.
 */
object HubMotion {
    const val Quick = 125
    const val Focus = 180
    const val Content = 350
    const val Scene = 450
    /** Dwell used when no autoplay trailer is driving the card expansion. */
    const val FocusedCardDelayMillis = 3_000L

    const val FocusScale = 1.02f
    const val PressedScale = 0.98f

    val StandardEasing: Easing = FastOutSlowInEasing
}

object HubShapes {
    val Field = 14.dp
    val Control = 14.dp
    val Card = 16.dp
    val Panel = 22.dp
    val Dialog = 24.dp
    val Pill = 999.dp
}

object HubStrokes {
    val Hairline = 1.dp
    val Focus = 2.dp
    val StrongFocus = 3.dp
}

object HubEffects {
    const val GlassSurfaceAlpha = 0.76f
    const val MutedSurfaceAlpha = 0.58f
    const val SoftBorderAlpha = 0.72f
    const val SelectedWashAlpha = 0.18f
    const val FocusGlowAlpha = 0.24f
}
