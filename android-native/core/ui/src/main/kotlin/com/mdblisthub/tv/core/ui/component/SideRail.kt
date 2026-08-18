package com.mdblisthub.tv.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.ui.theme.HubColors

data class RailItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * Estuary's side menu.
 *
 * Fully hidden while a poster elsewhere on screen holds focus, so browsing
 * rows gets the whole width; the moment focus lands back on the rail it
 * widens and the labels appear. That behaviour is the signature of the skin
 * this interface is modelled on, and it earns its place: it costs no screen
 * while you are browsing posters, and needs no discovery when you want it.
 */
@Composable
fun SideRail(
    items: List<RailItem>,
    selectedKey: String,
    onSelect: (RailItem) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether the rail as a whole holds focus. The home screen listens so it
     * can hold its row list still while focus is over here — see its
     * `pinnedForRail`.
     */
    onFocusChanged: (Boolean) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .onFocusChanged {
                expanded = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            // A tween here, not the default spring: the posters it shares
            // the screen with ease in on the same curve (`railFocusTween`),
            // and a spring's overshoot next to a tween's flat arrival is
            // exactly the kind of mismatch that reads as inconsistent.
            .animateContentSize(animationSpec = railFocusTween())
            .width(if (expanded) 232.dp else 0.dp)
            .background(
                Brush.horizontalGradient(
                    0f to HubColors.Background,
                    1f to HubColors.Background.copy(alpha = if (expanded) 0.92f else 0f),
                )
            )
            .padding(vertical = 32.dp, horizontal = if (expanded) 16.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            RailRow(
                item = item,
                expanded = expanded,
                selected = item.key == selectedKey,
                onSelect = { onSelect(item) },
            )
        }
    }
}

@Composable
private fun RailRow(
    item: RailItem,
    expanded: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val background by animateColorAsState(
        targetValue = when {
            focused -> HubColors.Accent
            selected -> HubColors.SurfaceStrong
            else -> HubColors.Background.copy(alpha = 0f)
        },
        animationSpec = railFocusTween(),
        label = "rail-background",
    )
    val tint by animateColorAsState(
        targetValue = when {
            focused -> HubColors.Text
            selected -> HubColors.AccentSoft
            else -> HubColors.TextFaint
        },
        animationSpec = railFocusTween(),
        label = "rail-tint",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .let {
                val cornerRadius = if (HubColors.isCyberpunk) 0.dp else 10.dp
                it.clip(RoundedCornerShape(cornerRadius))
                  .let { mod ->
                      if (HubColors.isCyberpunk && focused) {
                          mod.animatedCyberpunkGlow(shape = RoundedCornerShape(cornerRadius))
                      } else mod
                  }
            }
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        if (expanded) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleMedium,
                color = tint,
            )
        }
    }
}

/** Matches [posterFocusTween] in `PosterCard` — one motion language for the screen. */
private fun <T> railFocusTween() = tween<T>(durationMillis = 200, easing = FastOutSlowInEasing)
