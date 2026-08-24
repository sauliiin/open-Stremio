package com.mdblisthub.tv.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubMotion
import com.mdblisthub.tv.core.ui.theme.HubShapes

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
    /**
     * Lets the host park focus here when it has nowhere else to put it.
     *
     * The rail collapses to zero width while something else holds focus, which
     * is the whole point of it — but a screen whose content ends up with no
     * focusable at all (every row resolved empty) then has no focus anywhere,
     * and a D-pad press has no origin to search from. The event goes unhandled,
     * leaves the app, and from the sofa the box looks frozen. The rail is the
     * one thing always present, so it is the safe place to land.
     */
    focusRequester: FocusRequester? = null,
    /**
     * Explicit exit for Right from a rail item.
     *
     * The rail can be painted over content, so geometric focus search no
     * longer has a dependable sibling width to use as its exit route.
     */
    onMoveFocusRight: (() -> Boolean)? = null,
    /**
     * Holds the rail open even though nothing here has focus yet.
     *
     * Needed because the collapsed rail is `width(0.dp)`, and a zero-width node
     * cannot take focus — so "just call `requestFocus()` when the screen has
     * none" quietly does nothing, which is exactly what it did the first time
     * this was attempted. The host raises this first, the rail gains real
     * width, and only then is the focus request able to land.
     */
    forceExpanded: Boolean = false,
) {
    var focusExpanded by remember { mutableStateOf(false) }
    val expanded = focusExpanded || forceExpanded

    Column(
        modifier = modifier
            .fillMaxHeight()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusGroup()
            .onFocusChanged {
                focusExpanded = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            // A tween here, not the default spring: the posters it shares
            // the screen with ease in on the same curve (`railFocusTween`),
            // and a spring's overshoot next to a tween's flat arrival is
            // exactly the kind of mismatch that reads as inconsistent.
            .animateContentSize(animationSpec = railFocusTween())
            .width(if (expanded) 248.dp else 0.dp)
            // The menu is an actual panel, not a veil over the Home. Keeping
            // this fully opaque prevents artwork and synopsis text from
            // bleeding through its labels and icons.
            .background(HubColors.Background)
            .padding(vertical = 30.dp, horizontal = if (expanded) 18.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        items.forEach { item ->
            RailRow(
                item = item,
                expanded = expanded,
                selected = item.key == selectedKey,
                onSelect = { onSelect(item) },
                onMoveFocusRight = onMoveFocusRight,
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
    onMoveFocusRight: (() -> Boolean)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val background by animateColorAsState(
        targetValue = when {
            focused -> HubColors.Accent
            selected -> HubColors.SurfaceStrong
            else -> Color.Transparent
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
                val cornerRadius = if (HubColors.isCyberpunk) 0.dp else HubShapes.Pill
                it.clip(RoundedCornerShape(cornerRadius))
                  .let { mod ->
                      if (HubColors.isCyberpunk && focused) {
                          mod.animatedCyberpunkGlow(shape = RoundedCornerShape(cornerRadius))
                      } else mod
                  }
            }
            .background(background)
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionRight &&
                    (onMoveFocusRight?.invoke() == true)
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 11.dp),
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
private fun <T> railFocusTween() = tween<T>(durationMillis = HubMotion.Focus, easing = HubMotion.StandardEasing)
