package com.mdblisthub.tv.core.ui.component

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mdblisthub.tv.core.ui.R
import androidx.tv.material3.IconButton
import androidx.tv.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens
import com.mdblisthub.tv.core.ui.theme.HubMotion
import com.mdblisthub.tv.core.ui.theme.HubShapes
import kotlin.math.abs

/**
 * Keeps the focused card inside the same horizontal safe area as the row
 * title.
 *
 * LazyRow's default bring-into-view calculation considers the physical edge
 * of the viewport visible. After browsing right and returning, it therefore
 * stops as soon as the first card reaches x=0, consuming the content padding
 * and leaving that card flush against the bezel. This is the default minimal
 * scroll calculation with both viewport edges inset, so focus restores to the
 * visual margin instead. It also overrides the home screen's vertical pivot
 * spec, which must never be applied horizontally to individual cards.
 */
@OptIn(ExperimentalFoundationApi::class)
private class SafeHorizontalScroll(private val insetPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val leading = offset
        val trailing = offset + size
        val safeLeading = insetPx
        val safeTrailing = containerSize - insetPx

        if (leading >= safeLeading && trailing <= safeTrailing) return 0f
        if (leading < safeLeading && trailing > safeTrailing) return 0f

        val leadingDelta = leading - safeLeading
        val trailingDelta = trailing - safeTrailing
        return if (abs(leadingDelta) < abs(trailingDelta)) leadingDelta else trailingDelta
    }
}

/**
 * Netflixy and Primefly: pins the focused card to the leading inset instead
 * of the minimal "safe area" scroll [SafeHorizontalScroll] does for every
 * other theme. Every D-pad move scrolls the row so the newly focused card
 * lands at that same x position — the first (leftmost) column — matching the
 * Netflix TV behaviour the theme is named for. Mirrors its vertical
 * counterpart, `RowPivotScroll`, in `HomeScreen`, which keeps the focused
 * shelf as the top one.
 *
 * [slide] replaces the default spring with a timed glide, so the whole row
 * visibly slides one card over on each press rather than snapping into place.
 */
@OptIn(ExperimentalFoundationApi::class)
private class LeadingColumnScroll(
    private val insetPx: Float,
    private val slide: AnimationSpec<Float>? = null,
) : BringIntoViewSpec {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = slide ?: super.scrollAnimationSpec

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        return offset - insetPx
    }
}

/**
 * Told which cards sit either side of the one just focused in any row, so a
 * screen can ready what it will need for them before the next press. The home
 * screen uses it to warm its hero; the default does nothing.
 */
val LocalRowFocusNeighbors = staticCompositionLocalOf<(List<MediaItem>) -> Unit> { {} }

/**
 * The focus style of the themes that pin focus to the first column (Primefly,
 * Netflixy and their trailer twins), from the interface settings: true paints one ring
 * fixed over the first column; false leaves the ring on the focused card, which
 * then slides into that column with it. The slide itself is the same either way.
 */
val LocalFixedFocusFrame = staticCompositionLocalOf { true }

/**
 * A list, rendered as a row of posters.
 *
 * Each shelf remembers its own last focused card. This is important for D-pad
 * navigation: leaving shelf two from item four and returning from shelf one
 * must restore item four, rather than using shelf one's horizontal position or
 * advancing to the next card through geometric focus search.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    title: String,
    items: List<MediaItem>,
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false,
    hidden: Boolean = false,
    onToggleVisibility: () -> Unit = {},
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
    onItemClick: (MediaItem) -> Unit = {},
    onItemFocused: (MediaItem) -> Unit = {},
    onReachedEnd: () -> Unit = {},
    /**
     * Overridable and index-aware because [MediaItem.key] — `type:tmdbId` —
     * is right for every catalog row, where a title appears at most once,
     * but not for "Continuar assistindo": two different in-progress episodes
     * of the same show carry the same tmdbId, and `toCardItem()` drops
     * season/episode entirely, so the two cards are not just same-keyed but
     * structurally *equal* `MediaItem`s. No function of the item alone can
     * tell them apart — only their position in the list the caller built
     * can, which is why this takes the index too, and why the crash this
     * exists to fix ("Key ... was already used") could not be solved from
     * inside this component.
     */
    key: (Int, MediaItem) -> Any = { _, item -> item.key },
    /**
     * Takes priority over [onItemClick] when set. The same ambiguity the
     * custom [key] exists for — two cards that are equal `MediaItem`s —
     * means `onItemClick(item)` alone cannot tell "Continuar assistindo"
     * which of two identical-looking cards was actually pressed either;
     * only the position can, so that row supplies this instead of widening
     * every other row's simpler, already-correct `(MediaItem) -> Unit`.
     */
    onItemClickIndexed: ((Int, MediaItem) -> Unit)? = null,
    /** Held OK on a card — see [PosterCard]. Indexed for the same reason as above. */
    onItemLongClickIndexed: ((Int, MediaItem) -> Unit)? = null,
    /**
     * Takes priority over [onItemFocused] when set, and exists for the same
     * ambiguity as the two above: "Continuar assistindo" builds cards whose
     * `MediaItem`s are structurally equal across two episodes of one show, so
     * the item handed to a focus callback cannot say *which* episode the
     * viewer is now looking at. The hero above the rows needs exactly that —
     * it writes the episode's own air date and number — and only the position
     * carries it.
     */
    onItemFocusedIndexed: ((Int, MediaItem) -> Unit)? = null,
    progressPercent: ((Int, MediaItem) -> Float?)? = null,
    isWatched: ((Int, MediaItem) -> Boolean)? = null,
    requestInitialFocus: Boolean = false,
    onInitialFocusHandled: () -> Unit = {},
    /**
     * Names this row as a focus target its screen can aim at. The home screen
     * hands the one for the row you were last on to its list's `enter`, so
     * coming back from the side rail returns here rather than wherever the
     * default geometric search happens to land — which, with a sliver of the
     * row above showing at the pivot, is the row above.
     */
    rowFocusRequester: FocusRequester? = null,
    /**
     * Asks this row to take focus back on the card it last held it on.
     *
     * For the screen coming back into composition — returning from a title's
     * details or the player — where nothing holds focus any more and the
     * `FocusRequester`s the screen kept have all been rebuilt. The card index
     * and the row's scroll both survive that trip as saved state, so the row
     * itself is the only thing that still knows which card it was.
     */
    restoreFocus: Boolean = false,
    onFocusRestored: () -> Unit = {},
    /** Enables the modern poster-to-backdrop preview while keeping this row's data and focus model unchanged. */
    expandCardsOnFocus: Boolean = true,
    /** The card whose inline trailer has rendered its first frame. */
    expandedItemKey: State<String?>? = null,
    /** True only while autoplay owns expansion timing; false uses the three-second dwell. */
    synchronizeCardExpansion: Boolean = false,
) {
    if (items.isEmpty() && !isEditMode) return

    // Keep a requester for every position, not just for the first card. The
    // focus system's geometry can otherwise select the next card when a
    // different shelf is left and re-entered.
    val itemFocusRequesters = remember(items.size) {
        List(items.size) { FocusRequester() }
    }
    // Saveable, not just remembered: leaving for another screen disposes this
    // row, and the index is what `restoreFocus` aims at on the way back.
    val lastFocusedIndex = rememberSaveable(items.size) {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    val rowListState = rememberLazyListState()
    val firstItemFocusRequester = itemFocusRequesters.firstOrNull()
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus && firstItemFocusRequester?.requestFocus() == true) {
            onInitialFocusHandled()
        }
    }
    LaunchedEffect(restoreFocus, items.size) {
        if (!restoreFocus) return@LaunchedEffect
        val index = lastFocusedIndex.intValue.coerceIn(0, items.lastIndex)
        val requester = itemFocusRequesters.getOrNull(index) ?: return@LaunchedEffect
        // A frame first: before the row's first layout nothing is "visible",
        // and scrolling then would throw away the restored scroll offset.
        withFrameNanos { }
        if (rowListState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            rowListState.scrollToItem(index)
        }
        repeat(RESTORE_FOCUS_ATTEMPTS) {
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) {
                onFocusRestored()
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    val focusNeighbors = LocalRowFocusNeighbors.current
    val horizontalInsetPx = with(LocalDensity.current) {
        HubDimens.ScreenPaddingHorizontal.toPx()
    }
    val fixedFrameChosen = LocalFixedFocusFrame.current
    val horizontalScrollSpec = remember(horizontalInsetPx, HubColors.variant, fixedFrameChosen) {
        fun glide(stiffness: Float) = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = stiffness,
        )
        when {
            // Under the fixed frame the slide *is* the focus change, so it is
            // paced — a little brisker than Primefly's. With the ring riding
            // on the card it is quicker still, but not the stock snap.
            HubColors.isNetflixLayout -> LeadingColumnScroll(
                horizontalInsetPx,
                slide = glide(
                    if (fixedFrameChosen) NETFLIX_FRAME_SLIDE_STIFFNESS else NETFLIX_MOVING_SLIDE_STIFFNESS,
                ),
            )
            // Slower, so the card is still settling into the fixed frame as
            // the hero above begins to dissolve to it (the hero follows
            // ~0.5 s after the press) — one movement, not two.
            //
            // A spring, not a longer tween: bring-into-view re-aims its
            // animation every frame as the target moves, and a tween restarts
            // its easing on each re-aim. With an ease-in start that restart
            // never gets past the slow part — the row crawled for well over a
            // second. A spring carries its velocity through the re-aim.
            HubColors.isPrimefly -> LeadingColumnScroll(
                horizontalInsetPx,
                slide = glide(PRIMEFLY_SLIDE_STIFFNESS),
            )
            else -> SafeHorizontalScroll(horizontalInsetPx)
        }
    }

    // Hoisted rather than read inline: `stringResource` is only callable from a
    // composable scope, and the `Icon` calls below sit inside `IconButton`
    // content lambdas where reading them per icon would also re-resolve on
    // every recomposition of the row.
    val moveUpLabel = stringResource(R.string.row_move_up)
    val moveDownLabel = stringResource(R.string.row_move_down)
    val renameLabel = stringResource(R.string.row_rename)
    val showLabel = stringResource(R.string.row_show)
    val hideLabel = stringResource(R.string.row_hide)
    val deleteLabel = stringResource(R.string.row_delete)

    Column(
        modifier.fillMaxWidth().alpha(if (isEditMode && hidden) 0.5f else 1f),
        verticalArrangement = Arrangement.spacedBy(
            if (HubColors.isPrimefly || HubColors.isCyberpunk) 6.dp else 10.dp,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = HubDimens.ScreenPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = if (HubColors.isCyberpunk) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                color = HubColors.Text,
            )
            
            if (isEditMode) {
                Spacer(modifier = Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(end = HubDimens.ScreenPaddingHorizontal),
                ) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        colors = editIconColors(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = moveUpLabel,
                            tint = if (canMoveUp) HubColors.Text else HubColors.TextFaint,
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        colors = editIconColors(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = moveDownLabel,
                            tint = if (canMoveDown) HubColors.Text else HubColors.TextFaint,
                        )
                    }
                    IconButton(
                        onClick = onRename,
                        colors = editIconColors(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = renameLabel,
                            tint = HubColors.Text,
                        )
                    }
                    IconButton(
                        onClick = onToggleVisibility,
                        colors = editIconColors(),
                    ) {
                        Icon(
                            imageVector = if (hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (hidden) showLabel else hideLabel,
                            tint = HubColors.Text
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        colors = editIconColors(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = deleteLabel,
                            tint = HubColors.Rotten,
                        )
                    }
                }
            }
        }

        // Prime Video's fixed focus frame. The ring is not part of
        // any card — it is painted once, over the first column, and stays put
        // while LeadingColumnScroll slides each newly focused card in under it.
        // A per-card border would visibly hop one card to the right on every
        // press and then ride the row back, which is the opposite of the look.
        //
        // Netflixy and CyberFlix pin the focused card to the first column the
        // same way, so the same ring works there; it only has to follow the
        // poster's width as it widens into its landscape preview.
        val fixedFocusFrame = (HubColors.isPrimefly || HubColors.isNetflixLayout) &&
            fixedFrameChosen && !isEditMode
        val focusedArtworkWidth = remember { mutableStateOf(HubDimens.PosterWidth) }
        var rowHasFocus by remember { mutableStateOf(false) }
        val frameAlpha by animateFloatAsState(
            targetValue = if (fixedFocusFrame && rowHasFocus) 1f else 0f,
            animationSpec = tween(HubMotion.Focus, easing = HubMotion.StandardEasing),
            label = "fixed-focus-frame-alpha",
        )

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Without room past the last card the list stops scrolling before the
            // final few can reach the first column, and focus would walk out of
            // the frame. Enough trailing space for the last card to sit in it.
            val trailingPadding = if (fixedFocusFrame) {
                (maxWidth - HubDimens.ScreenPaddingHorizontal - HubDimens.PosterWidth)
                    .coerceAtLeast(HubDimens.ScreenPaddingHorizontal)
            } else {
                HubDimens.ScreenPaddingHorizontal
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalScrollSpec) {
                LazyRow(
                    state = rowListState,
                    horizontalArrangement = Arrangement.spacedBy(HubDimens.CardSpacing),
                    contentPadding = PaddingValues(
                        start = HubDimens.ScreenPaddingHorizontal,
                        end = trailingPadding,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { rowHasFocus = it.hasFocus }
                        .let { if (rowFocusRequester != null) it.focusRequester(rowFocusRequester) else it }
                        .focusProperties {
                            onEnter = {
                                itemFocusRequesters
                                    .getOrNull(lastFocusedIndex.intValue)
                                    ?.requestFocus() == true
                            }
                        },
                ) {
                    itemsIndexed(items, key = key) { index, item ->
                        PosterCard(
                            item = item,
                            // The first item is the initial screen entry target;
                            // later returns are handled by this row's restorer.
                            initialFocusRequester = firstItemFocusRequester.takeIf { index == 0 },
                            onClick = {
                                onItemClickIndexed?.invoke(index, item) ?: onItemClick(item)
                            },
                            onFocused = { focused ->
                                lastFocusedIndex.intValue = index
                                onItemFocusedIndexed?.invoke(index, focused) ?: onItemFocused(focused)
                                // Paging off focus rather than off scroll position:
                                // on a remote the two are the same thing, and focus
                                // is the one that fires exactly once per card.
                                if (focused.key == items.lastOrNull()?.key) onReachedEnd()
                                focusNeighbors(
                                    listOfNotNull(items.getOrNull(index + 1), items.getOrNull(index - 1)),
                                )
                            },
                            progressPercent = progressPercent?.invoke(index, item),
                            isWatched = isWatched?.invoke(index, item) ?: false,
                            onLongClick = onItemLongClickIndexed?.let { handler ->
                                { handler(index, item) }
                            },
                            expandOnFocus = expandCardsOnFocus && !isEditMode,
                            drawFocusBorder = !fixedFocusFrame,
                            onFocusedArtworkWidth = if (fixedFocusFrame) {
                                { width -> focusedArtworkWidth.value = width }
                            } else {
                                null
                            },
                            expansionTrigger = if (synchronizeCardExpansion) {
                                expandedItemKey?.value == item.key
                            } else {
                                null
                            },
                            modifier = Modifier
                                .focusRequester(itemFocusRequesters[index])
                                .focusProperties { canFocus = !isEditMode },
                        )
                    }
                }
            }
            if (fixedFocusFrame) {
                FixedFocusFrame(width = focusedArtworkWidth, alpha = { frameAlpha })
            }
        }
    }
}

/** Frames to keep trying while a returning row's card is still being composed. */
private const val RESTORE_FOCUS_ATTEMPTS = 30

/**
 * The fixed focus ring itself. Drawing only — never focusable, never measured
 * into the row — and its own composable so that following a widening poster
 * frame by frame recomposes this box alone rather than the whole row.
 */
@Composable
private fun FixedFocusFrame(width: State<Dp>, alpha: () -> Float) {
    Box(
        Modifier
            .padding(start = HubDimens.ScreenPaddingHorizontal)
            .size(width.value, HubDimens.PosterHeight)
            .graphicsLayer { this.alpha = alpha() }
            .border(
                width = 2.5.dp,
                color = HubColors.Accent,
                shape = RoundedCornerShape(HubShapes.ShelfCard),
            ),
    )
}

@Composable
private fun editIconColors() = androidx.tv.material3.IconButtonDefaults.colors(
    focusedContainerColor = HubColors.Accent,
    focusedContentColor = HubColors.Text,
    contentColor = HubColors.TextFaint,
)

/**
 * Primefly's row slide, timed to meet the hero's change of title: critically
 * damped at this stiffness it settles in a little over half a second.
 */
private const val PRIMEFLY_SLIDE_STIFFNESS = 110f

/**
 * Netflixy's slide under the fixed frame: the middle ground between its quick
 * default and Primefly's glide — about a third of a second. Settle time goes
 * with 1/√stiffness, so doubling it takes roughly 30% off.
 */
private const val NETFLIX_FRAME_SLIDE_STIFFNESS = 220f

/**
 * Netflixy's slide with the moving focus: a touch slower than the stock
 * bring-into-view spring (stiffness 1500, ~0.15 s) — about a quarter second —
 * and still quicker than the fixed frame's.
 */
private const val NETFLIX_MOVING_SLIDE_STIFFNESS = 450f
