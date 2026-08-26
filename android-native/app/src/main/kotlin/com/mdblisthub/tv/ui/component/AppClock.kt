package com.mdblisthub.tv.ui.component

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.model.ClockPosition
import com.mdblisthub.tv.core.ui.theme.HubColors
import kotlinx.coroutines.delay
import java.util.Date

/** Where the home and the player anchor the clock along the top edge. */
val ClockPosition.alignment: Alignment
    get() = when (this) {
        ClockPosition.LEFT -> Alignment.TopStart
        ClockPosition.CENTER -> Alignment.TopCenter
        ClockPosition.RIGHT -> Alignment.TopEnd
    }

private val ClockPosition.textAlign: TextAlign
    get() = when (this) {
        ClockPosition.LEFT -> TextAlign.Start
        ClockPosition.CENTER -> TextAlign.Center
        ClockPosition.RIGHT -> TextAlign.End
    }

private val ClockPosition.horizontalAlignment: Alignment.Horizontal
    get() = when (this) {
        ClockPosition.LEFT -> Alignment.Start
        ClockPosition.CENTER -> Alignment.CenterHorizontally
        ClockPosition.RIGHT -> Alignment.End
    }

/**
 * The time overlay, plus — in the player — the wall-clock time the film is
 * going to finish at.
 *
 * Drawn straight onto the screen with no plate behind it, which is what the
 * soft shadow is for: this sits over artwork that can be any colour at all,
 * including white, and a drop shadow keeps the glyphs readable there without
 * putting a box on top of the picture.
 *
 * Ticks on the minute rather than on a second, and lands *on* the boundary
 * instead of every sixty seconds from whenever it happened to start: an
 * overlay that flips from 13:04 to 13:05 half a minute late is worse than no
 * clock at all, and this is drawn on top of the home, where a repeating
 * one-second timer would be a recomposition per second for the whole time the
 * screen is open.
 *
 * [remainingMs] is what is *left* of the film, not the instant it ends,
 * deliberately. The end time is then computed here against the same `now`
 * that paints the clock, which is what keeps the two lines agreeing while a
 * paused film pushes its own ending further away — a caller passing an
 * absolute instant would have to re-derive it on a timer of its own.
 */
@Composable
internal fun AppClock(
    position: ClockPosition,
    modifier: Modifier = Modifier,
    remainingMs: Long? = null,
) {
    val context = LocalContext.current
    // Rebuilt on a configuration change, which is what covers both a language
    // switch and the system's own 12/24-hour setting being flipped.
    val configuration = LocalConfiguration.current
    val timeFormat = remember(configuration) { DateFormat.getTimeFormat(context) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            now = current
            delay(MINUTE_MS - current % MINUTE_MS)
        }
    }

    val shadow = remember {
        Shadow(color = Color.Black.copy(alpha = 0.65f), offset = Offset.Zero, blurRadius = 14f)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = position.horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = timeFormat.format(Date(now)),
            // Deliberately not the headline style as-is: every headline in
            // this app is semi-bold or bolder, and a clock read at a glance
            // wants the plain weight — the size is what carries it across the
            // room, not the stroke.
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = TIME_SIZE,
                fontWeight = FontWeight.Normal,
                shadow = shadow,
            ),
            color = HubColors.Text,
            textAlign = position.textAlign,
        )
        if (remainingMs != null && remainingMs > 0) {
            Text(
                text = stringResource(
                    R.string.player_ends_at,
                    timeFormat.format(Date(now + remainingMs)),
                ),
                style = MaterialTheme.typography.bodyLarge.copy(shadow = shadow),
                color = HubColors.TextDim,
                textAlign = position.textAlign,
            )
        }
    }
}

private val TIME_SIZE = 44.sp

private const val MINUTE_MS = 60_000L
