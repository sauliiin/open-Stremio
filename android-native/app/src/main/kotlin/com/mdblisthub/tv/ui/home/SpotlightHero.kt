package com.mdblisthub.tv.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.model.MediaDetail
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * How tall the hero is on this screen.
 *
 * Shared with `HomeScreen`, which needs the same number for a reason that has
 * nothing to do with drawing: its pivot scroll has to be able to tell "the
 * focused thing is inside the hero" from "the focused thing is a card below
 * it", and the hero's lower edge is where that line falls.
 */
@Composable
internal fun spotlightHeroHeight(): androidx.compose.ui.unit.Dp {
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    return with(LocalDensity.current) { windowHeightPx.toDp() } * HERO_HEIGHT_FRACTION
}

/**
 * The site's hero, on a television.
 *
 * This is a direct port of `mdblist-hub`'s `app-hero` — the block that opens
 * openstream.com.br — rather than a Compose design that resembles it: the
 * same stack (artwork under a two-part veil, copy along the bottom-left), the
 * same order within the copy (title, meta line, synopsis, two pill
 * actions), and the same camera move over the artwork. Where the web build's
 * `:host-context(html.tv)` block already answers a question for the ten-foot
 * case — a gentler pan, a veil pulled back to the corners so the artwork is
 * allowed to be the artwork — this follows that answer and not the desktop
 * one above it.
 *
 * Two things are deliberately *not* the web hero:
 *
 * **It rotates.** The site picks one title at random and offers "Surprise me"
 * to roll again; here the pick advances on its own every
 * [SPOTLIGHT_DWELL_MS], because the ask was for a hero showing *the*
 * destaques, plural, and a remote is a poor instrument for rolling dice with.
 * The button stays, doing what it always did — jump ahead now — which is why
 * the timer is keyed on the item rather than free-running: pressing it
 * restarts the dwell instead of leaving the next automatic change moments
 * away.
 *
 * **The pan is per title, not per hero.** The web build's TV keyframes cover
 * their whole path in 54 seconds because nothing ever replaces the image
 * under them. With a title only holding the screen for
 * [SPOTLIGHT_DWELL_MS], that same path would only ever show its first quarter
 * — the artwork would look static and the effect would be missing. So the
 * same start and end points are travelled in [KEN_BURNS_MS] instead: the move
 * completes, and it completes slightly *after* the crossfade takes the image
 * away, so the swap never lands on the frozen frame at the end of the path.
 */
@Composable
fun SpotlightHero(
    item: MediaItem?,
    detail: MediaDetail?,
    onOpen: (MediaItem) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = false,
    onInitialFocusHandled: () -> Unit = {},
    primaryFocusRequester: FocusRequester? = null,
) {
    val heroHeight = spotlightHeroHeight()

    // Keyed on the item, so "Surpreenda-me" restarts the dwell rather than
    // handing the viewer a title that changes again a second later.
    LaunchedEffect(item?.key) {
        if (item == null) return@LaunchedEffect
        delay(SPOTLIGHT_DWELL_MS)
        onNext()
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(heroHeight)
            // The pan scales the artwork past its own box by design — that
            // overhang is what gives the move somewhere to travel — and a
            // `Box` does not clip, so without this the backdrop paints a
            // bright band across the first row below, outside the veil that
            // would otherwise have faded it out.
            .clipToBounds(),
    ) {
        // Only the artwork crossfades. The copy below it is swapped outright,
        // the way the web hero swaps it — and the actions are outside this
        // entirely, because fading a focusable in and out of the tree takes
        // the D-pad's focus with it every time the spotlight advances.
        Crossfade(
            targetState = item?.backdropUrl,
            animationSpec = tween(durationMillis = REVEAL_MS),
            label = "spotlight-backdrop",
            modifier = Modifier.fillMaxSize(),
        ) { url ->
            if (url != null) KenBurnsBackdrop(url)
        }

        HeroVeil()

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = HubDimens.ScreenPaddingHorizontal,
                    end = HubDimens.ScreenPaddingHorizontal,
                    bottom = HERO_COPY_BOTTOM_PADDING,
                ),
        ) {
            if (item == null) {
                HeroSkeleton()
                return@Column
            }

            Text(
                text = item.title,
                // `h1 { text-shadow: 0 6px 40px rgba(0,0,0,0.7) }`, and not
                // decoration: the veil is calibrated for artwork with
                // something in it, and every so often a backdrop puts a blown
                // sky exactly where the title goes. The shadow is what keeps
                // that from being unreadable rather than merely bright.
                style = MaterialTheme.typography.displayLarge.copy(shadow = TITLE_SHADOW),
                color = HubColors.Text,
                // One line, for the same reason `AutoScrollOverview` below is
                // given a hard `height` rather than a `heightIn`: this
                // `Column` is bottom-aligned inside a hero of fixed
                // [spotlightHeroHeight], and the `Box` holding it clips. A
                // title that wrapped to a second line pushed the copy block
                // past the height it is measured against, and what went over
                // the edge was the last thing in the column —
                // [SpotlightActions]. The buttons were not merely nudged
                // down, they were clipped away outright, so a rotation
                // landing on a long title took "Surpreenda-me" off the screen
                // together with the D-pad's only way to advance past it.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(TITLE_WIDTH_FRACTION),
            )
            Spacer(Modifier.height(12.dp))

            SpotlightMeta(item = item, detail = detail)
            Spacer(Modifier.height(14.dp))

            // A hard `height`, not `heightIn`: fixing it does two jobs at
            // once — the two buttons below stop walking up and down the
            // screen as the synopsis changes length, and the text has
            // something to overflow, which is what there is to scroll.
            //
            // Five lines rather than the web hero's three. The clamp on the
            // site is a compromise with a page that has to work at 400 px
            // tall; a television has the room, and two more lines is most of
            // a typical synopsis on screen before the scroll has to start at
            // all.
            AutoScrollOverview(
                text = detail?.overview.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth(OVERVIEW_WIDTH_FRACTION)
                    .height(OVERVIEW_HEIGHT),
            )
            Spacer(Modifier.height(20.dp))

            SpotlightActions(
                onOpen = { onOpen(item) },
                onNext = onNext,
                requestInitialFocus = requestInitialFocus,
                onInitialFocusHandled = onInitialFocusHandled,
                primaryFocusRequester = primaryFocusRequester,
            )
        }
    }
}

/**
 * The artwork, panning.
 *
 * One animation drives the whole move rather than three running side by side:
 * scale and both translations come off the same clock, so they cannot drift
 * apart, and the three-point path — the thing that makes this read as a camera
 * rather than as a CSS zoom — is expressed once, in [pathAt].
 *
 * `Reverse` matters for the case where the spotlight has a single title in it:
 * the rotation then never fires, and a `Restart` here would snap the image
 * back to its starting frame every [KEN_BURNS_MS] instead of drifting back the
 * way it came.
 */
@Composable
private fun KenBurnsBackdrop(url: String) {
    val transition = rememberInfiniteTransition(label = "ken-burns")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = KEN_BURNS_MS, easing = KenBurnsEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ken-burns-progress",
    )

    // The web build fades the artwork in over its first beat; without it the
    // first frame of a fresh backdrop arrives at full brightness against a
    // black page and reads as a cut.
    val reveal by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = REVEAL_MS),
        label = "ken-burns-reveal",
    )

    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // Matches the web hero's `filter: brightness(1.34) saturate(1.16)`.
        // A dark palette needs the artwork lifted or every backdrop reads as
        // a grey smear behind the veil; the contrast component the CSS also
        // carries is 1.02 and is not worth a third term.
        colorFilter = remember { heroArtworkFilter() },
        alignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = reveal
                val scale = pathAt(progress, SCALE_FROM, SCALE_MID, SCALE_TO)
                scaleX = scale
                scaleY = scale
                translationX = pathAt(progress, PAN_X_FROM, PAN_X_MID, PAN_X_TO) * size.width
                translationY = pathAt(progress, PAN_Y_FROM, PAN_Y_MID, PAN_Y_TO) * size.height
            },
    )
}

/**
 * The web hero's `.veil`, in its television form.
 *
 * Two gradients, and both are load-bearing. The radial one sits over the
 * lower-left corner and exists so white copy stays legible over whatever the
 * artwork happens to be there; the vertical one runs the artwork into the page
 * background so the first row below reads as part of the same surface rather
 * than as a list stacked under a banner. Drawn against the live [size] rather
 * than through a `Modifier.background` so the radial can be placed where the
 * copy actually is instead of at the centre of the box.
 */
@Composable
private fun HeroVeil() {
    val background = HubColors.Background

    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val corner = Brush.radialGradient(
                    0f to background.copy(alpha = 0.60f),
                    0.48f to background.copy(alpha = 0.26f),
                    0.78f to Color.Transparent,
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.12f, size.height * 0.70f),
                    radius = size.width * 0.58f,
                )
                // The CSS reads `to top`, so its first stop is the bottom
                // edge; these offsets are that list turned the other way up.
                val seam = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.28f to Color.Transparent,
                    0.48f to background.copy(alpha = 0.06f),
                    0.78f to background.copy(alpha = 0.42f),
                    1f to background,
                )
                onDrawBehind {
                    drawRect(corner)
                    drawRect(seam)
                }
            },
    )
}

/** `.meta`: score, year, a dot, the type, then up to three genres. */
@Composable
private fun SpotlightMeta(item: MediaItem, detail: MediaDetail?) {
    val genres = remember(item, detail) {
        (detail?.genres.orEmpty().ifEmpty { item.genres })
            .filter { it.isNotBlank() }
            .take(MAX_GENRE_CHIPS)
    }
    val year = item.year ?: detail?.year

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.score?.takeIf { it > 0 }?.let { score ->
            Text(
                text = "$score%",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                // Not a fixed colour: the site's chip is dark ink on mint
                // because mint is *its* secondary accent, and the same chip
                // in Netflixy would be dark ink on brand red, which is
                // unreadable. [inkOn] makes that call per palette.
                color = inkOn(HubColors.Accent2),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(HubColors.Accent2, HubColors.Accent2.lighten(CHIP_HIGHLIGHT)),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        year?.let {
            Text(
                text = it.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = HubColors.TextDim,
            )
        }

        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(HubColors.TextFaint),
        )

        Text(
            text = stringResource(
                if (item.type == MediaType.SHOW) R.string.home_type_show else R.string.home_type_movie,
            ),
            style = MaterialTheme.typography.titleMedium,
            color = HubColors.TextDim,
        )

        genres.forEach { genre ->
            Text(
                text = genre,
                style = MaterialTheme.typography.titleMedium,
                color = HubColors.TextDim,
            )
        }
    }
}

/**
 * `.actions`: the primary pill and the ghost one beside it.
 *
 * Held outside every animation on this screen on purpose — see the note on
 * the crossfade in [SpotlightHero].
 */
@Composable
private fun SpotlightActions(
    onOpen: () -> Unit,
    onNext: () -> Unit,
    requestInitialFocus: Boolean,
    onInitialFocusHandled: () -> Unit,
    primaryFocusRequester: FocusRequester?,
) {
    val defaultPrimaryFocus = remember { FocusRequester() }
    val primaryFocus = primaryFocusRequester ?: defaultPrimaryFocus
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus && primaryFocus.requestFocus()) {
            onInitialFocusHandled()
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroPill(
            text = stringResource(R.string.home_spotlight_details),
            icon = Icons.Filled.Info,
            primary = true,
            onClick = onOpen,
            modifier = Modifier.focusRequester(primaryFocus),
        )
        HeroPill(
            text = stringResource(R.string.home_spotlight_surprise),
            icon = Icons.Filled.Shuffle,
            primary = false,
            onClick = onNext,
        )
    }
}

/**
 * One `.btn` from the web hero.
 *
 * `selectable` rather than `clickable`: on a television the D-pad's OK arrives
 * as a click on whatever holds focus, and this is the cheapest node that is
 * focusable, clickable and reports its own focus back — which the fill and the
 * lift both need, since a pointer `:hover` has no equivalent here and focus is
 * the only state a remote can express.
 */
@Composable
private fun HeroPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // The web button's `translateY(-2px)` on hover, which is the one piece of
    // its feedback that survives the translation to a remote unchanged.
    val lift by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "hero-pill-lift",
    )

    // The primary action rests on the lighter accent and becomes the solid,
    // darker accent on focus. This keeps the focused state visually anchored
    // instead of flashing brighter than the hero copy around it.
    val base = if (focused) HubColors.Accent else HubColors.AccentSoft
    val fill = when {
        primary -> Brush.linearGradient(listOf(base, base.lighten(PILL_HIGHLIGHT)))
        // The ghost pill is the theme's text colour at low alpha, not white:
        // over Cyberpunk's near-black violet a white wash reads as a grey
        // sticker, while its own yellow at 12% reads as part of the theme.
        else -> {
            val wash = HubColors.Text.copy(alpha = if (focused) 0.30f else 0.12f)
            Brush.linearGradient(listOf(wash, wash))
        }
    }

    // Animated because on the light end of a palette the readable ink flips
    // from white to the theme's own near-black between rest and focus, and an
    // unanimated flip on a 160 ms lift reads as a glitch rather than a state.
    val ink by animateColorAsState(
        targetValue = if (primary) inkOn(base) else HubColors.Text,
        animationSpec = tween(durationMillis = 160),
        label = "hero-pill-ink",
    )

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .clip(CircleShape)
            .background(fill)
            .border(
                width = 1.dp,
                color = when {
                    primary -> Color.Transparent
                    else -> HubColors.Text.copy(alpha = if (focused) 0.55f else 0.30f)
                },
                shape = CircleShape,
            )
            .selectable(
                selected = focused,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 26.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = ink,
        )
    }
}

/**
 * The synopsis, scrolling itself once the reader has had the visible part.
 *
 * There is already an `AutoScrollText` in `HomeScreen`, and this is
 * deliberately not it. That one drives the Netflixy and Primefly panels,
 * where the synopsis belongs to whatever card the D-pad is sitting on and
 * stays until the viewer moves — no deadline, so it can afford four seconds
 * before it starts and eighty milliseconds per pixel once it does. Here the
 * text is replaced on a timer, and that pacing would not get a typical
 * synopsis a third of the way down before [SPOTLIGHT_DWELL_MS] took it away.
 * The mechanism is worth sharing; the timing policy is the whole difference,
 * so the two stay apart rather than meeting in a function with six timing
 * parameters.
 *
 * Paced in **dp**, not in the raw pixels of [ScrollState.maxValue]. Speed per
 * pixel is speed per *device*: the same paragraph on a 4x panel overflows by
 * four times the pixels of a 1x one and would crawl for four times as long,
 * which is exactly the sort of thing that reads fine on the machine it was
 * tuned on and wrong on a television.
 *
 * The whole pass is budgeted to finish inside the dwell — see
 * [OVERVIEW_MAX_SCROLL_MS] — so the swap lands on a synopsis that has been
 * read to the end rather than cutting one off mid-travel. The loop past that
 * point is not dead code: a spotlight with a single film in it never swaps,
 * and without the return trip its synopsis would sit at the bottom forever.
 */
@Composable
private fun AutoScrollOverview(text: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    LaunchedEffect(text) {
        scroll.scrollTo(0)
        if (text.isBlank()) return@LaunchedEffect
        delay(OVERVIEW_READ_HOLD_MS)

        while (isActive) {
            val overflowPx = scroll.maxValue
            // Zero while the text still fits — three lines or fewer — and
            // also for the frame before layout has run, which is why this
            // waits and re-reads rather than giving up.
            if (overflowPx <= 0) {
                delay(OVERVIEW_READ_HOLD_MS)
                continue
            }

            val overflowDp = with(density) { overflowPx.toDp().value }
            val travel = (overflowDp * OVERVIEW_MS_PER_DP)
                .toInt()
                .coerceAtMost(OVERVIEW_MAX_SCROLL_MS)

            scroll.animateScrollTo(overflowPx, tween(travel, easing = LinearEasing))
            delay(OVERVIEW_BOTTOM_HOLD_MS)
            scroll.animateScrollTo(0, tween(OVERVIEW_RETURN_MS))
            delay(OVERVIEW_READ_HOLD_MS)
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = HubColors.TextDim,
        modifier = modifier.verticalScroll(scroll),
    )
}

/** The web hero's three placeholder lines, for the beat before a pick lands. */
@Composable
private fun HeroSkeleton() {
    @Composable
    fun line(width: Float, height: androidx.compose.ui.unit.Dp) {
        Box(
            Modifier
                .fillMaxWidth(width)
                .height(height)
                .clip(RoundedCornerShape(10.dp))
                .background(HubColors.Surface.copy(alpha = 0.7f)),
        )
    }

    line(0.42f, 46.dp)
    Spacer(Modifier.height(18.dp))
    line(0.52f, 14.dp)
    Spacer(Modifier.height(10.dp))
    line(0.30f, 14.dp)
}

/** A colour mixed towards white — how each palette produces its own highlight. */
private fun Color.lighten(amount: Float): Color = lerp(this, Color.White, amount)

/**
 * Ink that will actually read on [fill].
 *
 * The six palettes disagree about how bright their accents are — Cyberpunk's
 * cyan and Primefly's secondary are light enough that white text disappears
 * into them, while Netflixy's red and the site's violet need white — so no
 * single hard-coded ink is right for a control that every theme paints in its
 * own colour. The dark side of the choice is the theme's own background
 * rather than black, so the chip reads as cut out of the page.
 *
 * Gamma-encoded channels, not linearised ones: this is picking between two
 * inks, and the extra precision would not change a single answer.
 */
private fun inkOn(fill: Color): Color =
    if (0.2126f * fill.red + 0.7152f * fill.green + 0.0722f * fill.blue > INK_FLIP_LUMINANCE) {
        HubColors.Background
    } else {
        Color.White
    }

/**
 * Where the three-point path is at [progress].
 *
 * The web keyframes name a start, a midpoint and an end rather than just two
 * ends, and that midpoint is the whole difference between a camera move and a
 * zoom: the path bends at half way, so the pan changes direction while the
 * scale keeps growing.
 */
private fun pathAt(progress: Float, from: Float, mid: Float, to: Float): Float =
    if (progress < 0.5f) {
        from + (mid - from) * (progress * 2f)
    } else {
        mid + (to - mid) * ((progress - 0.5f) * 2f)
    }

/** `brightness(1.34) saturate(1.16)` as a colour matrix. */
private fun heroArtworkFilter(): ColorFilter {
    val s = ARTWORK_SATURATION
    val b = ARTWORK_BRIGHTNESS
    // Rec. 709 luma, the same weights the CSS filter uses.
    val lr = 0.2126f
    val lg = 0.7152f
    val lb = 0.0722f

    val matrix = ColorMatrix(
        floatArrayOf(
            ((1 - s) * lr + s) * b, ((1 - s) * lg) * b, ((1 - s) * lb) * b, 0f, 0f,
            ((1 - s) * lr) * b, ((1 - s) * lg + s) * b, ((1 - s) * lb) * b, 0f, 0f,
            ((1 - s) * lr) * b, ((1 - s) * lg) * b, ((1 - s) * lb + s) * b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    return ColorFilter.colorMatrix(matrix)
}

/**
 * Share of the screen the hero occupies — the web build's `84svh` for TV.
 *
 * Not the whole screen, and the remainder is not slack: it is where the top of
 * the first row shows through the veil's lower fade, which is what tells the
 * viewer there is anything below the hero at all.
 */
/**
 * Leaves the first shelf visible at rest so artwork and cards read as a single
 * composition instead of two consecutive pages. The backdrop remains the
 * dominant element, but its bottom gradient now lands immediately above the
 * row the viewer is about to browse.
 */
private const val HERO_HEIGHT_FRACTION = 0.68f

/**
 * How long one destaque holds the screen before the next takes over.
 *
 * Raised from sixteen seconds together with the synopsis pace, not
 * independently of it. The hero has two clocks and they are not free of each
 * other: the reader has to reach the end of the text before the text is taken
 * away, so slowing the reading without lengthening the stay would not have
 * made the synopsis more readable — it would have made more of it unread.
 *
 * The cost is the full rotation, which at 53 destaques goes from about
 * fourteen minutes to about nineteen.
 */
private const val SPOTLIGHT_DWELL_MS = 21_000L

/**
 * One complete camera move. Longer than the dwell on purpose: the crossfade
 * away happens while the pan is still travelling, so the artwork is never
 * seen parked at the end of its path. Moved up with the dwell to keep that
 * true — left at twenty it would now finish five seconds before the swap and
 * the backdrop would sit still at the end of every destaque.
 */
private const val KEN_BURNS_MS = 26_000

/** The web hero's `hero-reveal`, also used for the swap between destaques. */
private const val REVEAL_MS = 300

/** `cubic-bezier(0.37, 0, 0.28, 1)` — the television keyframes' own easing. */
private val KenBurnsEasing = CubicBezierEasing(0.37f, 0f, 0.28f, 1f)

// The television path from `@keyframes ken-burns-tv`: half the travel of the
// desktop pan, because a set smears motion far more than a phone panel does
// and anything faster reads as judder on a good number of them.
private const val SCALE_FROM = 1.015f
private const val SCALE_MID = 1.045f
private const val SCALE_TO = 1.075f
private const val PAN_X_FROM = 0.003f
private const val PAN_X_MID = -0.005f
private const val PAN_X_TO = 0.0055f
private const val PAN_Y_FROM = -0.002f
private const val PAN_Y_MID = 0.0015f
private const val PAN_Y_TO = -0.0035f

/** `0 6px 40px rgba(0, 0, 0, 0.7)`, straight off the web hero's `h1`. */
private val TITLE_SHADOW = Shadow(
    color = Color.Black.copy(alpha = 0.7f),
    offset = Offset(0f, 6f),
    blurRadius = 40f,
)

/** Above this the fill is light enough that white text stops reading on it. */
private const val INK_FLIP_LUMINANCE = 0.55f

/** How far the chip and pill gradients travel towards white across their fill. */
private const val CHIP_HIGHLIGHT = 0.42f
private const val PILL_HIGHLIGHT = 0.28f

private const val ARTWORK_BRIGHTNESS = 1.34f
private const val ARTWORK_SATURATION = 1.16f

/** `h1 { max-width: 16ch }`, expressed against the panel rather than the glyphs. */
private const val TITLE_WIDTH_FRACTION = 0.62f

/** `.overview { max-width: 52ch }` in the television block. */
private const val OVERVIEW_WIDTH_FRACTION = 0.5f

/**
 * Five lines of `bodyLarge` (24 sp each), held whether the synopsis fills
 * them or not — and the window the rest of it scrolls through.
 *
 * The 2 dp over 120 is slack, not a sixth line: it absorbs the rounding
 * between a 24 sp line box and its dp height so the fifth line cannot be
 * shaved to a row of clipped descenders.
 */
private val OVERVIEW_HEIGHT = 122.dp

/**
 * Time to read what is already on screen, before and after each pass.
 *
 * Five lines is a lot to take in, and the scroll starting under the eye
 * before it has reached the bottom of them is what makes an auto-scroller
 * feel like it is racing the reader.
 */
private const val OVERVIEW_READ_HOLD_MS = 5_500L

/** Reading pace, expressed against the screen rather than against its pixels. */
private const val OVERVIEW_MS_PER_DP = 140f

/**
 * Ceiling on one downward pass.
 *
 * Chosen against the dwell, not by feel: [OVERVIEW_READ_HOLD_MS] plus this
 * has to land inside [SPOTLIGHT_DWELL_MS], so even the longest synopsis in
 * the rotation reaches its last line before the next destaque replaces it.
 *
 * 5.5 s + 14 s = 19.5 s inside a 21 s dwell. This is the point the earlier
 * note warned about: the reading pace could no longer be slowed on its own,
 * because the sixteen seconds a destaque used to hold the screen were already
 * spent. So the dwell moved with it — see [SPOTLIGHT_DWELL_MS] — rather than
 * the synopsis being cut off mid-travel, which is what "slower" would
 * otherwise have quietly meant.
 *
 * What still gives first, on the very longest entries, is
 * [OVERVIEW_BOTTOM_HOLD_MS]: the swap arrives about when the last line does,
 * so the pause at the bottom and the trip back up do not happen. That is the
 * right thing to lose — the text has been read by then, and the return exists
 * for the single-destaque case where nothing ever swaps.
 */
private const val OVERVIEW_MAX_SCROLL_MS = 14_000

private const val OVERVIEW_BOTTOM_HOLD_MS = 2_500L

/** The way back up is a reset, not a read — fast on purpose. */
private const val OVERVIEW_RETURN_MS = 700

private val HERO_COPY_BOTTOM_PADDING = 34.dp

private const val MAX_GENRE_CHIPS = 3
