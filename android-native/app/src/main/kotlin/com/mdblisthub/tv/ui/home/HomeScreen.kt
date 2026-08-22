package com.mdblisthub.tv.ui.home

import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.verticalScroll

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaList
import com.mdblisthub.tv.core.model.MdblistHomeFeed
import com.mdblisthub.tv.core.model.AddonCatalog
import com.mdblisthub.tv.core.model.ResumePoint
import com.mdblisthub.tv.core.ui.component.FanartBackdrop
import com.mdblisthub.tv.core.ui.component.LoadingScreen
import com.mdblisthub.tv.core.ui.component.MediaRow
import com.mdblisthub.tv.core.ui.component.RailItem
import com.mdblisthub.tv.core.ui.component.SideRail
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens
import com.mdblisthub.tv.core.model.MediaDetail
import com.mdblisthub.tv.core.model.HubThemeVariant
import coil3.compose.AsyncImage
import com.mdblisthub.tv.ui.component.AnimatedOpenStreamTitle
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.hubViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where a focused row parks: 30% down the viewport, the same pivot Compose's own (internal) TV spec uses. */
private const val ROW_PIVOT = 0.3f

/** Rounding tolerance on the hero's bounds — not a widening of them. */
private const val SUB_PIXEL_SLACK = 1f

private sealed interface EditableListTarget {
    val displayName: String

    data class Mdblist(val list: MediaList) : EditableListTarget {
        override val displayName: String get() = list.name
    }

    data class Stremio(val catalog: AddonCatalog) : EditableListTarget {
        override val displayName: String get() = catalog.name
    }

    data class Feed(val feed: MdblistHomeFeed) : EditableListTarget {
        override val displayName: String get() = feed.name
    }
}

/**
 * How the column scrolls when focus moves between rows.
 *
 * Compose's default already animates — it is a `spring` — so the jerkiness
 * was never a missing animation. It is that the default scrolls the *minimum*
 * distance needed to reveal the row, so every row settles at whatever height
 * happens to work out and each press travels a different amount. The eye
 * reads that as stumbling rather than gliding.
 *
 * The fix is a fixed landing point. Compose ships exactly this as
 * `PivotBringIntoViewSpec`, in its Android source set because it exists for
 * televisions — but it is `internal`, so the geometry is restated here.
 *
 * There used to be a `scrollAnimationSpec` override here — a stiff, non-bouncy
 * spring, chosen because the pivot makes *every* press scroll and a held
 * direction key needs an animation that retargets from wherever it is rather
 * than restarting. Compose has since deprecated that member outright
 * ("Animation spec customization is no longer supported") and no longer reads
 * it anywhere, so the override was doing nothing but emitting a warning. The
 * scroll it now uses is the framework's own, which is already a spring with
 * exactly that retargeting behaviour; only the landing point below was ever
 * the part Compose could not supply.
 */
@OptIn(ExperimentalFoundationApi::class)
private class RowPivotScroll(
    private val variant: HubThemeVariant,
    private val normalFirstRowOffsetPx: Float,
    /**
     * True while focus is away on the side rail, and for a moment after it
     * comes back. See [HomeScreen]'s `pinnedForRail` for why the pivot has to
     * stand down for that window.
     */
    private val pinned: State<Boolean>,
    /** True while the spotlight hero owns the viewport above the rows. */
    private val spotlightHero: State<Boolean>,
    /**
     * Answers "this request is for something inside the spotlight hero, and
     * here is the scroll it actually wants" — or null when it is not, and the
     * pivot below should decide. See the guard in [calculateScrollDistance].
     */
    private val heroScrollDistance: (offset: Float, size: Float) -> Float?,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Returning to the rows must not move them. The list did not scroll
        // while the rail held focus, so the row focus is being restored to is
        // still exactly where the user left it — every pixel this would scroll
        // is a pixel of error, and it is what made the row look like it had
        // jumped by one. Same trick, same reason, as `pinWhileOnButtons` in
        // `DetailScreen`.
        if (pinned.value) return 0f

        // The spotlight hero has exactly one right resting place — the top
        // of the list, where the whole of it is on screen — and the pivot
        // cannot express that. The pivot knows only "park the focused child a
        // fixed distance from the top", and the child here is a button near
        // the hero's *bottom* edge; obeying it scrolled the artwork almost
        // entirely off the moment focus landed on "Ver detalhes", which on a
        // cold start is before the viewer has touched anything.
        //
        // Suppressing the scroll outright was the first fix and was wrong in
        // the other direction: coming back *up* from the rows, the button
        // being focused is above the viewport, so "don't scroll" left the
        // hero showing as a sliver with focus on something invisible. The
        // request has to be answered with the distance home, not with zero —
        // zero is merely what that distance comes to when the list is already
        // there.
        //
        // Geometry rather than a "hero has focus" flag on purpose: a flag has
        // to be lowered by a focus callback that races the bring-into-view
        // request it is meant to gate, and the first press of "down" out of
        // the hero is exactly the moment that race decides whether the rows
        // scroll at all.
        heroScrollDistance(offset, size)?.let { return it }

        val pivot = when {
            // Under the spotlight hero every theme has the same geometry —
            // one full-height list, hero first, rows after — so they all park
            // a focused row in the same place. The per-variant landing points
            // below were each measured against that theme's own fixed row
            // strip, and none of those strips is on screen while the hero is.
            spotlightHero.value -> normalFirstRowOffsetPx
            variant == HubThemeVariant.NORMAL -> normalFirstRowOffsetPx
            // The focused child is the card, not the whole shelf. This offset
            // equals the shelf heading plus its gap, so that heading lands at
            // the viewport top and every preceding shelf remains clipped.
            variant == HubThemeVariant.PRIMEFLY ||
                variant == HubThemeVariant.OPTIMUS_PRIME -> 0.11f * containerSize
            // CyberFlix rides with Netflixy: it shares the layout, so it has to
            // share the pivot too. Left on the generic `ROW_PIVOT` below it
            // parked rows lower, which in a viewport this short stopped the
            // outgoing shelf halfway — its posters clipped off the top while
            // its card labels stayed on screen, reading as a stray line of
            // titles under the synopsis with nothing above them.
            //
            variant == HubThemeVariant.NETFLIXY ||
                variant == HubThemeVariant.CYBERFLIX -> 0.18f * containerSize
            // Parks the focused shelf higher so it and the next two shelves
            // remain fully visible together on the TV viewport.
            variant == HubThemeVariant.CYBERPUNK -> 0.06f * containerSize
            else -> ROW_PIVOT * containerSize
        }

        // A row tall enough that parking it at the pivot would hang its
        // bottom off-screen is aligned to the bottom edge instead — parking
        // it would otherwise hide the very cards being brought into view.
        val target = if (size <= containerSize && containerSize - pivot < size) {
            containerSize - size
        } else {
            pivot
        }

        // The container clamps this at both ends, which is what keeps the
        // hero panel visible at the top of the list: the first row wants to
        // move *down* to reach the pivot, and there is nowhere to scroll.
        return offset - target
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    graph: DataGraph,
    onOpenTitle: (MediaItem) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAddons: () -> Unit,
    onOpenSettings: () -> Unit,
    onResume: (ResumePoint) -> Unit,
    onSignOut: () -> Unit,
) {
    val viewModel = hubViewModel { HomeViewModel(graph) }
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val allLists by viewModel.allLists.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val allFeeds by viewModel.allFeeds.collectAsStateWithLifecycle()
    val addonCatalogs by viewModel.addonCatalogs.collectAsStateWithLifecycle()
    val allAddonCatalogs by viewModel.allAddonCatalogs.collectAsStateWithLifecycle()
    val resumePoints by viewModel.resumePoints.collectAsStateWithLifecycle()
    // `focused`, `focusedBackdropUrl` and `focusedDetail` are deliberately NOT
    // collected here. Reading them at this level made this ~500-line composable
    // the recomposition scope for every single card the D-pad passes over.
    // `HeroPanel` and the backdrop collect them themselves, so the scope that
    // invalidates is the one that actually displays the value.
    val becauseYouWatched by viewModel.becauseYouWatched.collectAsStateWithLifecycle()
    // The *list* is collected here — it changes once, when the query lands —
    // while the item on screen and its detail are not: those turn over every
    // `SPOTLIGHT_DWELL_MS`, and read at this level they would make this whole
    // composable recompose on a timer. `SpotlightHeroBlock` collects them.
    val spotlight by viewModel.spotlight.collectAsStateWithLifecycle()
    val spotlightLoaded by viewModel.spotlightLoaded.collectAsStateWithLifecycle()
    val spotlightEnabled by viewModel.spotlightEnabled.collectAsStateWithLifecycle()
    val posterLandscapeTransformation by
        viewModel.posterLandscapeTransformation.collectAsStateWithLifecycle()
    // Keep the State object unwrapped here: only visible PosterCards read its
    // value, so a trailer frame does not invalidate this entire screen.
    val trailerPlayingItemKey = viewModel.trailerPlayingItemKey.collectAsStateWithLifecycle()
    val isEditMode by viewModel.isEditMode.collectAsStateWithLifecycle()
    val watchedIds by viewModel.watchedIds.collectAsStateWithLifecycle()
    val watchedEpisodes by viewModel.watchedEpisodes.collectAsStateWithLifecycle()
    val initialSyncComplete by viewModel.initialSyncComplete.collectAsStateWithLifecycle()
    val mdblistLinked by graph.auth.mdblistLinked.collectAsStateWithLifecycle(initialValue = false)
    val deletedListIds by graph.session.deletedListIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val isNormalTheme = HubColors.variant == HubThemeVariant.NORMAL
    var initialNormalFocusPending by remember { mutableStateOf(false) }

    // Hoisted so the reorder-reveal below can convert a row index into a
    // `LazyColumn` index. These two decide how many fixed items sit above the
    // row list, and stating them once is what keeps that arithmetic from
    // drifting out of step with the items themselves.
    /**
     * Whether the site's hero opens the page.
     *
     * Every palette, not just the one whose colours the web build happens to
     * use: the hero is the home screen's shape, and a theme is a set of
     * colours to paint that shape in — which is why nothing below asks which
     * variant is painted, and why the hero itself reads `HubColors` for every
     * fill it draws rather than carrying the site's violet around.
     *
     * Held up through the load rather than appearing when the query lands:
     * the block is most of the viewport, and letting the rows paint at the
     * top first would push them the height of a hero the moment it arrived.
     * Once loaded, an empty spotlight means the account has nothing to
     * feature — or the viewer turned it off in Settings — and the hero gives
     * the space back rather than sitting empty, which is what puts each
     * theme's own hero, `FocusedBackdrop` and the row strips back exactly as
     * they were.
     */
    val hasSpotlightHero = spotlightEnabled &&
        !isEditMode &&
        (!spotlightLoaded || spotlight.isNotEmpty())
    val hasHeroItem = !hasSpotlightHero &&
        !HubColors.isNetflixLayout &&
        !HubColors.isPrimefly &&
        !isNormalTheme
    val hasResumeItem = resumePoints.isNotEmpty() && !isEditMode
    val homeListState = rememberLazyListState()
    val heroPrimaryFocusRequester = remember { FocusRequester() }
    var lastContentFocusWasSpotlight by remember { mutableStateOf(false) }
    var browsingRowsWithFocusedHero by remember(HubColors.variant) { mutableStateOf(false) }
    val showFocusedHeroForRows = hasSpotlightHero &&
        HubColors.hasHeroTrailer &&
        browsingRowsWithFocusedHero
    val spotlightOwnsViewport = hasSpotlightHero && !showFocusedHeroForRows

    // Cyberflix and Optimus Prime have two deliberately different home
    // states: the rotating spotlight opens the page, then their original
    // clearlogo/synopsis/autotrailer hero follows the card focused below it.
    // Other themes keep the spotlight's flat, full-height scrolling layout.
    LaunchedEffect(hasSpotlightHero, HubColors.hasHeroTrailer) {
        if (!hasSpotlightHero || !HubColors.hasHeroTrailer) {
            browsingRowsWithFocusedHero = false
        }
    }

    val onShelfItemFocused = {
        lastContentFocusWasSpotlight = false
        if (hasSpotlightHero && HubColors.hasHeroTrailer) {
            browsingRowsWithFocusedHero = true
        }
    }
    val rowToReveal by viewModel.rowToReveal.collectAsStateWithLifecycle()

    /**
     * Follows a row that edit mode just moved.
     *
     * Without this the reorder appears to do nothing: the write lands, the
     * rows come back in the new order, but a `LazyColumn` anchors its scroll to
     * whatever was at the top — and these rows are a poster carousel tall, so
     * only one or two fit at 10-foot sizing. The moved row leaves the viewport,
     * and the only way to see where it went is to scroll after it, which is
     * exactly the work reordering was supposed to save. On the remote it costs
     * more than a scroll: a row that leaves the viewport is disposed, and the
     * D-pad focus sitting on its arrow button goes with it.
     *
     * Scrolls only when the destination is not already fully on screen, so a
     * move between two visible rows stays where it is instead of snapping.
     */
    LaunchedEffect(rowToReveal) {
        val reveal = rowToReveal ?: return@LaunchedEffect
        val leading = (if (hasSpotlightHero) 1 else 0) +
            (if (hasHeroItem) 1 else 0) +
            (if (hasResumeItem) 1 else 0)
        val targetIndex = leading + reveal.rowIndex
        val layout = homeListState.layoutInfo
        val visible = layout.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        val fullyVisible = visible != null &&
            visible.offset >= layout.viewportStartOffset &&
            visible.offset + visible.size <= layout.viewportEndOffset
        if (!fullyVisible) {
            homeListState.animateScrollToItem(targetIndex)
        }
        viewModel.onRowRevealed()
    }

    // Was NORMAL-only, because NORMAL was the only theme whose first
    // focusable was a row card. Now every theme opens on the hero, and the
    // hero is where focus should start on every one of them.
    LaunchedEffect(isNormalTheme, hasSpotlightHero) {
        initialNormalFocusPending = isNormalTheme || hasSpotlightHero
    }

    DisposableEffect(lifecycleOwner, viewModel, isNormalTheme, hasSpotlightHero) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDynamicRows()
                if (isNormalTheme || hasSpotlightHero) initialNormalFocusPending = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val extraCatalogs = remember(allLists, addonCatalogs, deletedListIds) {
        val representedListIds = allLists.mapTo(deletedListIds.toMutableSet()) { it.id }
        addonCatalogs.filterNot { catalog ->
            catalog.mdblistMirrorListId()?.let { it in representedListIds } == true
        }
    }
    val homeRows = remember(lists, allLists, feeds, allFeeds, extraCatalogs) {
        val feedsStillUseDefaultPlacement = allFeeds.none { it.position != null }
        val existingRowOffset = if (feedsStillUseDefaultPlacement) feeds.size else 0
        val lastStoredPosition = maxOf(
            allLists.maxOfOrNull { it.position } ?: -1,
            allFeeds.mapNotNull { it.position }.maxOrNull() ?: -1,
            extraCatalogs.mapNotNull { it.position }.maxOrNull() ?: -1,
        )
        var nextCatalogPosition = lastStoredPosition + 1
        val feedRows = feeds.mapIndexed { index, feed ->
            HomeMediaRow.Feed(
                feed,
                if (feedsStillUseDefaultPlacement) index else feed.position ?: index,
            )
        }
        val directRows = lists.map {
            HomeMediaRow.Mdblist(it, it.position + existingRowOffset)
        }
        val catalogRows = extraCatalogs.map { catalog ->
            HomeMediaRow.Stremio(
                catalog,
                (catalog.position ?: nextCatalogPosition++) + existingRowOffset,
            )
        }
        (feedRows + directRows + catalogRows)
            .sortedBy { it.position }
            .mapIndexed { index, row ->
                when (row) {
                    is HomeMediaRow.Mdblist -> row.copy(position = index)
                    is HomeMediaRow.Feed -> row.copy(position = index)
                    is HomeMediaRow.Stremio -> row.copy(position = index)
                }
            }
    }
    val openCatalogItem: (MediaItem) -> Unit = { item ->
        if (item.tmdbId > 0) {
            onOpenTitle(item)
        } else {
            item.imdbId?.let { imdbId ->
                scope.launch {
                    graph.media.resolveImdb(item.type, imdbId).onSuccess { tmdbId ->
                        onOpenTitle(item.copy(tmdbId = tmdbId))
                    }
                }
            }
        }
    }
    val resumePlayback: (ResumePoint) -> Unit = { point ->
        if ((point.tmdbId ?: 0) > 0) {
            onResume(point)
        } else {
            // The player route is TMDB-keyed. Resolve the uncommon IMDb-only
            // resume entry without throwing away its season, episode or progress.
            point.imdbId?.let { imdbId ->
                scope.launch {
                    graph.media.resolveImdb(point.type, imdbId).onSuccess { tmdbId ->
                        onResume(point.copy(tmdbId = tmdbId))
                    }
                }
            }
        }
    }

    var showExitDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<EditableListTarget?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<EditableListTarget?>(null) }
    var resumeRemovalTarget by remember { mutableStateOf<ResumePoint?>(null) }
    val emptyStateFocusRequester = remember { FocusRequester() }
    val railFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    /**
     * Whether anything at all on this screen holds focus.
     *
     * Read from the root, so it covers the rail and the content alike. It
     * exists for the one state that leaves a television unusable: every row
     * resolving empty — an expired MDBList key, a quota the account has
     * already spent, the network down — renders a screen with no focusable on
     * it, and a D-pad press then has no origin to search from. The key event
     * goes unhandled, the launcher takes it, and the app simply disappears
     * with no way back into Settings to fix the cause. See the effect below.
     */
    var screenHasFocus by remember { mutableStateOf(false) }
    /**
     * Holds the row list still across a trip to the side rail.
     *
     * `focusRestorer()` on the list below already brings focus back to the row
     * the user left, and that part was never the problem: the row it *landed*
     * on was right, but the list had scrolled a row's worth underneath it, so
     * a neighbour ended up parked at the pivot and it read as "it moved up a
     * line". The scroll comes from the pivot spec, which recomputes on the
     * restore's bring-into-view request the same as on any other focus move —
     * except this one is not a move. Nothing scrolled while the rail had
     * focus, so the correct scroll distance on the way back is zero, and this
     * flag is what says so.
     *
     * It stays raised for a beat past the hand-off because the restore's
     * requests arrive after the rail has already given up focus.
     */
    val pinnedForRail = remember { mutableStateOf(false) }
    var railFocused by remember { mutableStateOf(false) }
    var railReturnListPosition by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /**
     * The row focus should come back to, named for the list's `enter` below.
     *
     * Pinning the scroll alone was not enough: with the pivot leaving a sliver
     * of the previous row showing above the focused one, the default focus
     * search entering from the rail picks that sliver — it is geometrically
     * the nearer candidate — and `focusRestorer()` did not get consulted at
     * all. `enter` is consulted, and it is resolved by the focus system during
     * the search rather than after it, so unlike a `requestFocus()` fired from
     * a focus callback there is no transaction still in flight to overwrite it.
     */
    var lastFocusedRow by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(railFocused) {
        if (!railFocused && pinnedForRail.value) {
            // Long enough to cover the restore's bring-into-view, short enough
            // that a D-pad press the user makes after it is scrolled normally.
            kotlinx.coroutines.delay(200)
            pinnedForRail.value = false
        }
    }
    val normalFirstRowOffsetPx = with(LocalDensity.current) { 36.dp.toPx() }
    val spotlightHeroHeightPx = with(LocalDensity.current) { spotlightHeroHeight().toPx() }
    // Read through a `State` rather than captured: the spec outlives the
    // composition that built it, and a captured boolean would keep answering
    // for whichever spotlight the screen had when the theme was last changed.
    val heroPresent = rememberUpdatedState(spotlightOwnsViewport)
    val rowPivotScroll = remember(HubColors.variant, normalFirstRowOffsetPx, spotlightHeroHeightPx) {
        RowPivotScroll(
            HubColors.variant,
            normalFirstRowOffsetPx,
            pinnedForRail,
            heroPresent,
        ) { offset, size ->
            // How much of the hero has already gone off the top. The hero
            // spans `-scrolledOff .. heroHeight - scrolledOff` in the
            // viewport's own coordinates, which is what the two bounds below
            // compare against; the slack absorbs sub-pixel rounding rather
            // than widening the test.
            val scrolledOff = homeListState.firstVisibleItemScrollOffset.toFloat()
            when {
                !heroPresent.value -> null
                // Past the hero the list is back to ordinary rows, and so is
                // the pivot.
                homeListState.firstVisibleItemIndex != 0 -> null
                offset < -scrolledOff - SUB_PIXEL_SLACK -> null
                offset + size > spotlightHeroHeightPx - scrolledOff + SUB_PIXEL_SLACK -> null
                else -> -scrolledOff
            }
        }
    }
    val onInitialNormalFocusHandled = { initialNormalFocusPending = false }

    resumeRemovalTarget?.let { point ->
        Dialog(onDismissRequest = { resumeRemovalTarget = null }) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .background(HubColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.home_resume_remove_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = HubColors.Text,
                )
                Text(
                    stringResource(R.string.home_resume_remove_body, point.title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = HubColors.TextDim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            viewModel.removeResumePoint(point)
                            resumeRemovalTarget = null
                        },
                    ) { Text(stringResource(R.string.home_delete)) }
                    Button(onClick = { resumeRemovalTarget = null }) {
                        Text(stringResource(R.string.home_cancel))
                    }
                }
            }
        }
    }

    if (showExitDialog) {
        Dialog(onDismissRequest = { showExitDialog = false }) {
            Box(
                modifier = Modifier
                    .background(
                        HubColors.Surface, 
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(32.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally, 
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.home_exit_question),
                        style = MaterialTheme.typography.titleLarge, 
                        color = HubColors.Text
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { 
                                showExitDialog = false
                                viewModel.signOut(onSignOut) 
                            }
                        ) {
                            Text(stringResource(R.string.home_yes))
                        }
                        Button(onClick = { showExitDialog = false }) {
                            Text(stringResource(R.string.home_no))
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        Dialog(onDismissRequest = { renameTarget = null }) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .background(HubColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.home_rename_list), style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
                BasicTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = HubColors.Text),
                    cursorBrush = SolidColor(HubColors.Accent2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HubColors.Background, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = renameValue.isNotBlank(),
                        onClick = {
                            when (target) {
                                is EditableListTarget.Mdblist ->
                                    viewModel.renameList(target.list, renameValue)
                                is EditableListTarget.Stremio ->
                                    viewModel.renameCatalog(target.catalog, renameValue)
                                is EditableListTarget.Feed ->
                                    viewModel.renameFeed(target.feed, renameValue)
                            }
                            renameTarget = null
                        },
                    ) { Text(stringResource(R.string.home_save)) }
                    Button(onClick = { renameTarget = null }) { Text(stringResource(R.string.home_cancel)) }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        Dialog(onDismissRequest = { deleteTarget = null }) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .background(HubColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.home_delete_list_question), style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
                Text(
                    when (target) {
                        is EditableListTarget.Mdblist ->
                            stringResource(R.string.home_delete_mdblist_body, target.displayName)
                        is EditableListTarget.Stremio ->
                            stringResource(R.string.home_delete_addon_body, target.displayName)
                        is EditableListTarget.Feed ->
                            stringResource(R.string.home_delete_feed_body, target.displayName)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = HubColors.TextDim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            when (target) {
                                is EditableListTarget.Mdblist -> viewModel.deleteList(target.list)
                                is EditableListTarget.Stremio -> viewModel.deleteCatalog(target.catalog)
                                is EditableListTarget.Feed -> viewModel.deleteFeed(target.feed)
                            }
                            deleteTarget = null
                        },
                    ) { Text(stringResource(R.string.home_delete)) }
                    Button(onClick = { deleteTarget = null }) { Text(stringResource(R.string.home_cancel)) }
                }
            }
        }
    }

    // Remembered because this composable recomposes on every card focus (it
    // reads `focused` for the hero panel), and neither of these depends on
    // that — rebuilding them per focus is pure allocation.
    val menuHome = stringResource(R.string.menu_home)
    val menuSearch = stringResource(R.string.menu_search)
    val menuAddons = stringResource(R.string.menu_addons)
    val menuLists = stringResource(R.string.menu_lists)
    val menuListsDone = stringResource(R.string.menu_lists_done)
    val menuThemeNormal = stringResource(R.string.menu_theme_normal)
    val menuThemeCyberpunk = stringResource(R.string.menu_theme_cyberpunk)
    val menuThemeNetflixy = stringResource(R.string.menu_theme_netflixy)
    val menuThemePrimefly = stringResource(R.string.menu_theme_primefly)
    val menuThemeCyberflix = stringResource(R.string.menu_theme_cyberflix)
    val menuThemeOptimusPrime = stringResource(R.string.menu_theme_optimus_prime)
    val menuSettings = stringResource(R.string.menu_settings)
    val menuExit = stringResource(R.string.menu_exit)

    val currentThemeName = when (HubColors.variant) {
        HubThemeVariant.NORMAL -> menuThemeNormal
        HubThemeVariant.CYBERPUNK -> menuThemeCyberpunk
        HubThemeVariant.NETFLIXY -> menuThemeNetflixy
        HubThemeVariant.PRIMEFLY -> menuThemePrimefly
        HubThemeVariant.CYBERFLIX -> menuThemeCyberflix
        HubThemeVariant.OPTIMUS_PRIME -> menuThemeOptimusPrime
    }

    val rail = remember(isEditMode, HubColors.variant, menuHome, menuSearch, menuAddons, menuLists, menuListsDone, currentThemeName, menuSettings, menuExit) {
        listOf(
            RailItem("home", menuHome, Icons.Default.Home),
            RailItem("search", menuSearch, Icons.Default.Search),
            RailItem("addons", menuAddons, Icons.Default.Extension),
            RailItem("lists", if (isEditMode) menuListsDone else menuLists, if (isEditMode) Icons.Default.Check else Icons.AutoMirrored.Filled.ViewList),
            RailItem("theme", currentThemeName, Icons.Default.Palette),
            RailItem("settings", menuSettings, Icons.Default.Settings),
            RailItem("exit", menuExit, Icons.AutoMirrored.Filled.Logout),
        )
    }
    val resumeCards = remember(resumePoints) { resumePoints.map { it.toCardItem() } }

    /**
     * Raised only for the moment it takes to hand focus to the rail.
     *
     * The rail collapses to zero width when nothing there holds focus, and a
     * zero-width node cannot *take* focus — so the rescue below has to widen it
     * first, then let go. It is not a "something is wrong" flag and nothing on
     * screen reads it as one.
     */
    var railRescue by remember { mutableStateOf(false) }

    // The one thing this loop is for: a screen where *nothing at all* holds
    // focus leaves the D-pad with no origin to search from, the key event goes
    // unhandled, and the app drops to the launcher. Parking focus on the rail
    // costs nothing and makes that impossible.
    //
    // It deliberately draws no conclusion beyond that. An earlier version also
    // rendered a "nothing to show" notice from this same signal, and it was
    // wrong twice over: it fired during an ordinary cold start that was merely
    // slow, and — worse — it treated *the rail holding focus* as the symptom,
    // so simply opening the menu and reading it for a few seconds accused the
    // app of being broken. Whether the account actually has rows is a question
    // about data, and the empty states further down already answer it from the
    // data itself.
    LaunchedEffect(Unit) {
        while (isActive) {
            // Nothing to rescue while the first sync is still running: the
            // loading screen owns that state, and several sequential network
            // calls on a cold cache can easily outlast the window below.
            if (!initialSyncComplete || screenHasFocus) {
                delay(POLL_MS)
                continue
            }

            // Give the normal case room to resolve: rows land a beat after the
            // first composition, and whichever one takes focus ends this.
            delay(FOCUS_FALLBACK_MS)
            if (screenHasFocus) continue

            railRescue = true
            delay(RAIL_EXPAND_MS)
            runCatching { railFocusRequester.requestFocus() }
            // Dropped again immediately: once focus has landed the rail holds
            // itself open, and leaving the override raised would pin it open
            // after the viewer moves back into the rows.
            delay(RAIL_EXPAND_MS)
            railRescue = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onFocusChanged { screenHasFocus = it.hasFocus },
    ) {
        // The fanart follows focus, the way Estuary does it: whatever the
        // remote is pointing at fills the screen behind the rows.
        //
        // CyberFlix is the exception, and deliberately so: there the artwork is
        // not a full-bleed field behind everything but a bounded block in the
        // hero, sharing its rectangle with the trailer that replaces it (see
        // CyberFlix and OptimusPrime are the exceptions, and deliberately so:
        // there the artwork is not a full-bleed field behind everything but a
        // bounded block in the hero, sharing its rectangle with the trailer that
        // replaces it (see `HeroArtBlock`). Painting both would put the same
        // backdrop on screen twice at different sizes, and the trailer would then
        // be crossfading against a copy of the still it is supposed to be replacing.
        //
        // The spotlight hero is a third exception, and the most complete one:
        // it is the web home translated whole, and there the artwork belongs
        // to the hero and the rows below sit on flat page background. Painting
        // a focus-following fanart under them as well would be the one thing
        // that gives the port away.
        if (!HubColors.hasHeroTrailer && !hasSpotlightHero) {
            FocusedBackdrop(viewModel)
        }

        // The rail belongs on top of Home, not beside it. Giving it a share
        // of this layout's width remeasures the spotlight while it opens: a
        // long title can gain a line, pushing the action row below the
        // hero's clipped bottom, and the backdrop visibly recrops on every
        // trip to the menu. Keeping the content full-width makes both focus
        // states the same Hero; the rail merely covers its left edge.
        Box(Modifier.fillMaxSize()) content@{
            SideRail(
                items = rail,
                selectedKey = "home",
                focusRequester = railFocusRequester,
                onMoveFocusRight = {
                    when {
                        lastContentFocusWasSpotlight ->
                            heroPrimaryFocusRequester.requestFocus()
                        lastFocusedRow != null -> {
                            val rowFocus = lastFocusedRow
                            val listPosition = railReturnListPosition
                            if (listPosition == null) {
                                rowFocus?.requestFocus() == true
                            } else {
                                // A playing trailer adds an AndroidView to the
                                // fixed hero while focus is on the rail. That
                                // re-layout can make LazyColumn re-anchor to
                                // its first item (the Spotlight) before the
                                // card receives focus again, producing two
                                // heroes stacked on screen. Restore the exact
                                // position captured on entry before handing
                                // focus back to the row.
                                scope.launch {
                                    homeListState.scrollToItem(
                                        listPosition.first,
                                        listPosition.second,
                                    )
                                    if (rowFocus?.requestFocus() != true) {
                                        contentFocusRequester.requestFocus()
                                    }
                                }
                                true
                            }
                        }
                        else -> contentFocusRequester.requestFocus()
                    }
                },
                forceExpanded = railRescue,
                modifier = Modifier.zIndex(1f),
                onSelect = { item ->
                    when (item.key) {
                        "search" -> onOpenSearch()
                        "addons" -> onOpenAddons()
                        "lists" -> viewModel.toggleEditMode()
                        // Through the ViewModel, not HubColors directly: the
                        // choice has to be persisted as well as painted.
                        "theme" -> viewModel.cycleTheme()
                        "settings" -> onOpenSettings()
                        "exit" -> showExitDialog = true
                    }
                },
                onFocusChanged = { focused ->
                    railFocused = focused
                    if (focused) {
                        // This must be synchronous with the focus hand-off.
                        // A LaunchedEffect can start a frame later, after a
                        // fast Right press has already asked the list to bring
                        // its row back into view.
                        pinnedForRail.value = true
                        if (!lastContentFocusWasSpotlight && lastFocusedRow != null) {
                            railReturnListPosition =
                                homeListState.firstVisibleItemIndex to
                                    homeListState.firstVisibleItemScrollOffset
                        }
                    }
                },
            )

            if (!mdblistLinked && lists.isEmpty() && feeds.isEmpty() && resumePoints.isEmpty() && extraCatalogs.isEmpty()) {
                LaunchedEffect(Unit) {
                    emptyStateFocusRequester.requestFocus()
                }
                Column(
                    modifier = Modifier.fillMaxSize().padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        stringResource(R.string.home_empty_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = HubColors.Text,
                    )
                    Text(
                        stringResource(R.string.home_empty_desc),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                    )
                    HubButton(
                        text = stringResource(R.string.home_empty_button),
                        primary = true,
                        onClick = onOpenAddons,
                        modifier = Modifier.focusRequester(emptyStateFocusRequester),
                    )
                }
                return@content
            }

            // Gated on the sync flag, not on the row lists. The old condition
            // was `lists.isNotEmpty() && allLists.isEmpty()`, and `lists` is
            // `allLists` with the hidden ones filtered out — a subset can
            // never be non-empty while its superset is empty, so this branch
            // was unreachable and the message never appeared once.
            if (!initialSyncComplete && allLists.isEmpty()) {
                LoadingScreen(message = stringResource(R.string.home_syncing))
            }

            if (lists.isEmpty() && feeds.isEmpty() && resumePoints.isEmpty() && extraCatalogs.isEmpty()) {
                val hasHiddenRows = allLists.isNotEmpty() || allFeeds.isNotEmpty() || allAddonCatalogs.isNotEmpty()
                LaunchedEffect(hasHiddenRows) { emptyStateFocusRequester.requestFocus() }
                Column(
                    modifier = Modifier.fillMaxSize().padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        stringResource(
                            if (hasHiddenRows) R.string.home_no_visible_rows else R.string.home_no_lists,
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        color = HubColors.Text,
                    )
                    Text(
                        stringResource(
                            if (hasHiddenRows) {
                                R.string.home_no_visible_rows_desc
                            } else {
                                R.string.home_no_lists_desc
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                    )
                    HubButton(
                        text = stringResource(
                            if (hasHiddenRows) R.string.home_edit_lists else R.string.home_empty_button,
                        ),
                        primary = true,
                        onClick = if (hasHiddenRows) viewModel::toggleEditMode else onOpenAddons,
                        modifier = Modifier.focusRequester(emptyStateFocusRequester),
                    )
                }
                return@content
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocusRequester)
                    .focusGroup(),
            ) {
                // Cyberflix and Optimus Prime restore their focused-title hero
                // after focus leaves the spotlight for a shelf. That is where
                // their clearlogo, synopsis and autoplaying trailer live.
                // The other themes keep the spotlight's flat scrolling page.
                if (isNormalTheme && !hasSpotlightHero) {
                    Box(Modifier.fillMaxWidth().height(76.dp)) {
                        HeroPanel(viewModel)
                    }
                } else if (
                    (!hasSpotlightHero || showFocusedHeroForRows) &&
                    (HubColors.isNetflixLayout || HubColors.isPrimefly)
                ) {
                    Box(Modifier.weight(1f)) {
                        // Under the panel, not over it: the title, metadata and
                        // synopsis have to stay legible across the whole
                        // transition, and the block's left ramp is cut wide
                        // enough (see `HeroArt`) that the artwork has already
                        // faded out by the time it reaches them.
                        if (HubColors.hasHeroTrailer) {
                            HeroArtBlock(
                                viewModel = viewModel,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .fillMaxHeight()
                                    .fillMaxWidth(HERO_ART_WIDTH_FRACTION),
                            )
                        }
                        HeroPanel(viewModel)
                    }
                }

                CompositionLocalProvider(LocalBringIntoViewSpec provides rowPivotScroll) {
                    LazyColumn(
                        state = homeListState,
                        modifier = when {
                            // One shape for every theme once the hero is up:
                            // the fixed strip heights below exist to leave
                            // room for a hero pinned *above* the list, and
                            // this hero is the list's own first item, so the
                            // rows simply scroll it away like the web home
                            // does. Their measurements are untouched because
                            // they are still exactly right for the layouts
                            // they were taken for — the ones a theme falls
                            // back to when there is no spotlight to show.
                            spotlightOwnsViewport -> Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clipToBounds()
                            // OptimusPrime is Primefly's strip with the 15 dp
                            // it used to give away taken as height instead.
                            //
                            // Primefly lays out 312 dp and then parks it 15 dp
                            // lower, so the band between the hero's bottom
                            // edge and the first shelf is background nothing
                            // ever draws into. That costs Primefly nothing —
                            // its hero is a text panel — but here the hero
                            // holds artwork that stops dead at that edge, and
                            // 15 dp of empty navy is 15 dp of backdrop not
                            // being shown. Asking for 297 dp with no offset
                            // leaves the shelves pixel-for-pixel where they
                            // were (297 dp of visible strip either way, two
                            // complete landscape shelves inside 295.4 of it)
                            // and hands the difference to `HeroArt`: 228 → 243
                            // dp of block, which on a 16:9 backdrop is four
                            // points of crop recovered rather than four points
                            // of empty background.
                            HubColors.isOptimusPrime -> Modifier
                                .fillMaxWidth()
                                .height(297.dp)
                                .clipToBounds()
                            HubColors.isPrimefly -> Modifier
                                .fillMaxWidth()
                                // Two complete landscape shelves remain visible;
                                // the hero gives this space back from its bottom.
                                .height(312.dp)
                                .offset(y = 15.dp)
                                .clipToBounds()
                            // CyberFlix specifically, not Netflixy below: the
                            // next shelf's heading is meant to show here — see
                            // `hasHeroTrailer` — it is the one piece of the
                            // shelf below that stays legible while the rest of
                            // it is clipped, and it must come through whole.
                            //
                            // 257 dp is that heading's own lower edge, not a
                            // rounder number nearby: the heading starts 232.3
                            // dp into the list (top padding 12 + this shelf's
                            // 206.3 + the 14 dp gap between shelves) and is
                            // 24.6 dp tall, so 256.9 dp is the first line this
                            // strip can be shortened to without slicing the
                            // text itself — every dp below that starts eating
                            // into the glyphs rather than the whitespace under
                            // them. 264 had 7.1 dp of clipped-gap headroom
                            // going spare below that line; this claims it for
                            // the hero and stops exactly where the heading
                            // does.
                            HubColors.isCyberflix -> Modifier
                                .fillMaxWidth()
                                .height(257.dp)
                                .clipToBounds()
                            HubColors.isNetflixLayout -> Modifier
                                .fillMaxWidth()
                                .height(264.dp)
                                .clipToBounds()
                            isNormalTheme -> Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clipToBounds()
                            else -> Modifier.fillMaxSize()
                        }.focusProperties {
                            enter = { lastFocusedRow ?: FocusRequester.Default }
                        },
                        // Tighter than HubDimens.RowSpacing on purpose — with the
                        // smaller posters, this is what keeps two rows of a list on
                        // screen together instead of one full row plus a sliver of
                        // the next.
                        verticalArrangement = Arrangement.spacedBy(
                            when {
                                spotlightOwnsViewport -> 6.dp
                                HubColors.isPrimefly -> 6.dp
                                HubColors.isCyberpunk -> 8.dp
                                else -> 14.dp
                            },
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            // Primefly positions the whole viewport instead;
                            // padding here would be consumed when focus moves.
                            // The spotlight hero wants the same zero for a
                            // different reason: it is full-bleed artwork, and
                            // 12 dp of background above it would frame it.
                            top = if (HubColors.isPrimefly || spotlightOwnsViewport) 0.dp else 12.dp,
                            // Room to park the last row at the pivot instead of
                            // stopping short with it pinned to the bottom edge.
                            bottom = HubDimens.ScreenPaddingVertical * 8,
                        ),
                    ) {
                        if (hasSpotlightHero) {
                            item(key = "spotlight") {
                                SpotlightHeroBlock(
                                    viewModel = viewModel,
                                    onOpenTitle = onOpenTitle,
                                    requestInitialFocus = initialNormalFocusPending &&
                                        spotlight.isNotEmpty(),
                                    onInitialFocusHandled = onInitialNormalFocusHandled,
                                    primaryFocusRequester = heroPrimaryFocusRequester,
                                    modifier = Modifier.onFocusChanged { focus ->
                                        if (focus.hasFocus) {
                                            lastContentFocusWasSpotlight = true
                                            if (browsingRowsWithFocusedHero) {
                                                browsingRowsWithFocusedHero = false
                                                scope.launch { homeListState.animateScrollToItem(0) }
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        // Normal joins the themes whose hero is fixed above the shelves.
                        if (hasHeroItem) {
                            item(key = "hero") {
                                HeroPanel(viewModel)
                            }
                        }

                if (hasResumeItem) {
                    item(key = "resume") {
                        val resumeRowFocus = remember { FocusRequester() }
                        DisposableEffect(Unit) {
                            onDispose { if (lastFocusedRow === resumeRowFocus) lastFocusedRow = null }
                        }
                        MediaRow(
                            title = stringResource(R.string.home_resume_row),
                            items = resumeCards,
                            // `card` alone cannot say which episode this is —
                            // `toCardItem()` drops season/episode, so two
                            // in-progress episodes of the same show produce
                            // equal `MediaItem`s, and `indexOf` on those would
                            // always resolve to the *first* one regardless of
                            // which card was actually pressed. `resumeCards`
                            // and `resumePoints` are the same list at the same
                            // indices (see their construction above), so only
                            // the position — not the card's own equality —
                            // can say correctly which point this was.
                            onItemFocused = {
                                viewModel.onFocused(it)
                                lastFocusedRow = resumeRowFocus
                                onShelfItemFocused()
                            },
                            rowFocusRequester = resumeRowFocus,
                            key = { index, item -> resumePoints.getOrNull(index)?.key ?: item.key },
                            onItemClickIndexed = { index, _ ->
                                resumeCards.getOrNull(index)?.let(openCatalogItem)
                            },
                            onItemLongClickIndexed = { index, _ ->
                                resumeRemovalTarget = resumePoints.getOrNull(index)
                            },
                            progressPercent = { index, _ -> resumePoints.getOrNull(index)?.progress },
                            isWatched = { _, item -> watchedIds.contains(item.tmdbId) },
                            requestInitialFocus = isNormalTheme &&
                                initialNormalFocusPending &&
                                !isEditMode &&
                                !hasSpotlightHero,
                            onInitialFocusHandled = onInitialNormalFocusHandled,
                            expandCardsOnFocus = posterLandscapeTransformation,
                            expandedItemKey = trailerPlayingItemKey,
                            synchronizeCardExpansion = HubColors.hasHeroTrailer,
                        )
                    }
                }

                itemsIndexed(
                    homeRows,
                    key = { _, row ->
                        when (row) {
                            is HomeMediaRow.Mdblist -> "mdblist-${row.list.id}"
                            is HomeMediaRow.Feed -> row.feed.key
                            is HomeMediaRow.Stremio -> "addon-catalog-${catalogRowIdentity(row.catalog)}"
                        }
                    },
                ) { index, row ->
                    val requestInitialFocus = isNormalTheme &&
                        initialNormalFocusPending &&
                        !isEditMode &&
                        !hasSpotlightHero &&
                        resumePoints.isEmpty() &&
                        index == 0
                    val rowFocus = remember { FocusRequester() }
                    val trackFocus: (MediaItem) -> Unit = {
                        viewModel.onFocused(it)
                        lastFocusedRow = rowFocus
                        onShelfItemFocused()
                    }
                    // A requester whose row has left composition — edit mode
                    // rebuilding the list, a theme change, a row deleted — no
                    // longer points at anything, and `enter` handing that to
                    // the focus system is not a state it tolerates. Forget it
                    // on the way out and fall back to the default search.
                    DisposableEffect(Unit) {
                        onDispose { if (lastFocusedRow === rowFocus) lastFocusedRow = null }
                    }
                    when (row) {
                        is HomeMediaRow.Mdblist -> {
                            val list = row.list
                            val itemFlow = remember(list.id) { viewModel.itemsFor(list.id) }
                            ListRow(
                                list = list,
                                itemFlow = itemFlow,
                                isEditMode = isEditMode,
                                onToggleVisibility = { viewModel.toggleListVisibility(list) },
                                canMoveUp = index > 0,
                                canMoveDown = index < homeRows.lastIndex,
                                onMoveUp = { viewModel.moveRow(homeRows, index, -1) },
                                onMoveDown = { viewModel.moveRow(homeRows, index, 1) },
                                onRename = {
                                    renameTarget = EditableListTarget.Mdblist(list)
                                    renameValue = list.name
                                },
                                onDelete = {
                                    deleteTarget = EditableListTarget.Mdblist(list)
                                },
                                onEnsure = { viewModel.ensureItems(list.id) },
                                onItemClick = onOpenTitle,
                                onItemFocused = trackFocus,
                                onReachedEnd = { viewModel.loadMore(list.id) },
                                isWatched = { _, item -> watchedIds.contains(item.tmdbId) },
                                requestInitialFocus = requestInitialFocus,
                                onInitialFocusHandled = onInitialNormalFocusHandled,
                                rowFocusRequester = rowFocus,
                                expandCardsOnFocus = posterLandscapeTransformation,
                                expandedItemKey = trailerPlayingItemKey,
                                synchronizeCardExpansion = HubColors.hasHeroTrailer,
                            )
                        }
                        is HomeMediaRow.Stremio -> {
                            val catalog = row.catalog
                            val itemFlow = remember(catalog.addonBase, catalog.key) {
                                viewModel.itemsForCatalog(catalog)
                            }
                            AddonCatalogRow(
                                catalog = catalog,
                                itemFlow = itemFlow,
                                isEditMode = isEditMode,
                                onToggleVisibility = {
                                    viewModel.toggleCatalogVisibility(catalog)
                                },
                                canMoveUp = index > 0,
                                canMoveDown = index < homeRows.lastIndex,
                                onMoveUp = { viewModel.moveRow(homeRows, index, -1) },
                                onMoveDown = { viewModel.moveRow(homeRows, index, 1) },
                                onRename = {
                                    renameTarget = EditableListTarget.Stremio(catalog)
                                    renameValue = catalog.name
                                },
                                onDelete = {
                                    deleteTarget = EditableListTarget.Stremio(catalog)
                                },
                                onEnsure = { viewModel.ensureCatalog(catalog) },
                                onItemClick = openCatalogItem,
                                onItemFocused = trackFocus,
                                isWatched = { _, item -> watchedIds.contains(item.tmdbId) },
                                requestInitialFocus = requestInitialFocus,
                                onInitialFocusHandled = onInitialNormalFocusHandled,
                                rowFocusRequester = rowFocus,
                                expandCardsOnFocus = posterLandscapeTransformation,
                                expandedItemKey = trailerPlayingItemKey,
                                synchronizeCardExpansion = HubColors.hasHeroTrailer,
                            )
                        }
                        is HomeMediaRow.Feed -> {
                            val feed = row.feed
                            // Remembered rather than rebuilt: a fresh `List`
                            // instance every recomposition is a fresh instance
                            // as far as skipping is concerned, so `MediaRow`
                            // — which takes the list as an unstable parameter —
                            // re-ran for every row of every feed on each pass,
                            // however unchanged the items were.
                            val cards = remember(feed.items) { feed.items.map { it.media } }
                            MediaRow(
                                title = feed.name,
                                items = cards,
                                isEditMode = isEditMode,
                                hidden = feed.hidden,
                                onToggleVisibility = {
                                    viewModel.toggleFeedVisibility(feed)
                                },
                                canMoveUp = index > 0,
                                canMoveDown = index < homeRows.lastIndex,
                                onMoveUp = { viewModel.moveRow(homeRows, index, -1) },
                                onMoveDown = { viewModel.moveRow(homeRows, index, 1) },
                                onRename = {
                                    renameTarget = EditableListTarget.Feed(feed)
                                    renameValue = feed.name
                                },
                                onDelete = {
                                    deleteTarget = EditableListTarget.Feed(feed)
                                },
                                key = { itemIndex, item ->
                                    val feedItem = feed.items.getOrNull(itemIndex)
                                    "${item.key}:${feedItem?.season ?: 0}:${feedItem?.episode ?: 0}"
                                },
                                onItemClickIndexed = { _, item ->
                                    openCatalogItem(item)
                                },
                                isWatched = { itemIndex, item ->
                                    val feedItem = feed.items.getOrNull(itemIndex)
                                    if (feedItem?.season != null && feedItem.episode != null) {
                                        watchedEpisodes.contains("${item.tmdbId}:${feedItem.season}:${feedItem.episode}")
                                    } else {
                                        watchedIds.contains(item.tmdbId)
                                    }
                                },
                                onItemFocused = trackFocus,
                                requestInitialFocus = requestInitialFocus,
                                onInitialFocusHandled = onInitialNormalFocusHandled,
                                rowFocusRequester = rowFocus,
                                expandCardsOnFocus = posterLandscapeTransformation,
                                expandedItemKey = trailerPlayingItemKey,
                                synchronizeCardExpansion = HubColors.hasHeroTrailer,
                            )
                        }
                    }
                }

                // "Porque você assistiu" — always last, since it is built from
                // the five most recent watches rather than an MDBList row.
                if (!isEditMode) {
                    itemsIndexed(
                        becauseYouWatched,
                        key = { _, row -> "byw-${row.seedTitle}" },
                    ) { index, row ->
                        val bywRowFocus = remember { FocusRequester() }
                        DisposableEffect(Unit) {
                            onDispose { if (lastFocusedRow === bywRowFocus) lastFocusedRow = null }
                        }
                        MediaRow(
                            title = stringResource(R.string.home_because_you_watched, row.seedTitle),
                            items = row.items,
                            onItemClick = onOpenTitle,
                            onItemFocused = {
                                viewModel.onFocused(it)
                                lastFocusedRow = bywRowFocus
                                onShelfItemFocused()
                            },
                            rowFocusRequester = bywRowFocus,
                            isWatched = { _, item -> watchedIds.contains(item.tmdbId) },
                            requestInitialFocus = isNormalTheme &&
                                initialNormalFocusPending &&
                                !hasSpotlightHero &&
                                resumePoints.isEmpty() &&
                                homeRows.isEmpty() &&
                                index == 0,
                            onInitialFocusHandled = onInitialNormalFocusHandled,
                            expandCardsOnFocus = posterLandscapeTransformation,
                            expandedItemKey = trailerPlayingItemKey,
                            synchronizeCardExpansion = HubColors.hasHeroTrailer,
                        )
                    }
                }
                }
            }
            }
        }
    }
}

@Composable
private fun AddonCatalogRow(
    catalog: AddonCatalog,
    itemFlow: StateFlow<List<MediaItem>>,
    isEditMode: Boolean,
    onToggleVisibility: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onEnsure: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    isWatched: ((Int, MediaItem) -> Boolean)? = null,
    requestInitialFocus: Boolean = false,
    onInitialFocusHandled: () -> Unit = {},
    rowFocusRequester: FocusRequester? = null,
    expandCardsOnFocus: Boolean = true,
    expandedItemKey: State<String?>? = null,
    synchronizeCardExpansion: Boolean = false,
) {
    val items by itemFlow.collectAsStateWithLifecycle()
    LaunchedEffect(catalog.addonBase, catalog.key) { onEnsure() }
    MediaRow(
        title = catalog.name,
        items = items,
        isEditMode = isEditMode,
        hidden = catalog.hidden,
        onToggleVisibility = onToggleVisibility,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        onRename = onRename,
        onDelete = onDelete,
        onItemClick = onItemClick,
        onItemFocused = onItemFocused,
        isWatched = isWatched,
        requestInitialFocus = requestInitialFocus,
        onInitialFocusHandled = onInitialFocusHandled,
        rowFocusRequester = rowFocusRequester,
        expandCardsOnFocus = expandCardsOnFocus,
        expandedItemKey = expandedItemKey,
        synchronizeCardExpansion = synchronizeCardExpansion,
    )
}

private const val MDBLIST_CATALOG_HOST = "stremio-mdblist.baby-beamup.club"

private fun catalogRowIdentity(catalog: AddonCatalog): String =
    "${catalog.addonBase.trimEnd('/')}|${catalog.key}"

/** Returns the source MDBList id only for manifests generated by its catalog bridge. */
private fun AddonCatalog.mdblistMirrorListId(): Long? {
    val uri = runCatching { java.net.URI(addonBase) }.getOrNull() ?: return null
    if (!uri.host.orEmpty().contains(MDBLIST_CATALOG_HOST, ignoreCase = true)) return null

    val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
    val marker = segments.indexOfLast { it.equals("mdblist", ignoreCase = true) }
    if (marker < 2) return null
    return segments.getOrNull(marker - 2)?.toLongOrNull()
}

/**
 * One row, collecting its own items.
 *
 * `LazyColumn` only collects rows near the viewport. The ViewModel retains
 * each visited row's last Room emission, so returning upward restores its
 * geometry immediately instead of flashing through an empty 0dp item.
 */
@Composable
private fun ListRow(
    list: MediaList,
    itemFlow: StateFlow<List<MediaItem>>,
    isEditMode: Boolean = false,
    onToggleVisibility: () -> Unit = {},
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
    onEnsure: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    isWatched: ((Int, MediaItem) -> Boolean)? = null,
    onReachedEnd: () -> Unit,
    requestInitialFocus: Boolean = false,
    onInitialFocusHandled: () -> Unit = {},
    rowFocusRequester: FocusRequester? = null,
    expandCardsOnFocus: Boolean = true,
    expandedItemKey: State<String?>? = null,
    synchronizeCardExpansion: Boolean = false,
) {
    val items by itemFlow.collectAsStateWithLifecycle()

    LaunchedEffect(list.id) { onEnsure() }

    MediaRow(
        title = list.name,
        items = items,
        isEditMode = isEditMode,
        hidden = list.hidden,
        onToggleVisibility = onToggleVisibility,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        onRename = onRename,
        onDelete = onDelete,
        onItemClick = onItemClick,
        onItemFocused = onItemFocused,
        isWatched = isWatched,
        onReachedEnd = onReachedEnd,
        requestInitialFocus = requestInitialFocus,
        onInitialFocusHandled = onInitialFocusHandled,
        rowFocusRequester = rowFocusRequester,
        expandCardsOnFocus = expandCardsOnFocus,
        expandedItemKey = expandedItemKey,
        synchronizeCardExpansion = synchronizeCardExpansion,
    )
}

@Composable
private fun AutoScrollText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(text) {
        scrollState.scrollTo(0)
        kotlinx.coroutines.delay(7000)
        while (isActive) {
            val max = scrollState.maxValue
            if (max > 0) {
                // 190ms/px, from 40 by way of 80, 120 and 150. A synopsis
                // on a hero panel is glanced at, not studied, and every step
                // down has been towards the same thing: the eye has to be
                // able to leave a line and find it again on the way back.
                // Unlike the spotlight hero's copy of this, nothing here is
                // on a clock — the panel belongs to whatever card holds
                // focus and stays until the viewer moves — so the pace can
                // simply be chosen.
                scrollState.animateScrollTo(max, animationSpec = androidx.compose.animation.core.tween(durationMillis = max * 190, easing = androidx.compose.animation.core.LinearEasing))
                kotlinx.coroutines.delay(4000)
                scrollState.animateScrollTo(0, animationSpec = androidx.compose.animation.core.tween(durationMillis = 800))
                kotlinx.coroutines.delay(3000)
            } else {
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier.verticalScroll(scrollState)
    )
}

/**
 * The full-bleed artwork, collecting the focused item's backdrop itself.
 *
 * Split out for the recomposition scope, not for tidiness: the URL changes
 * every time focus settles on a different card, and read from `HomeScreen` it
 * invalidated the whole screen for what is one `AsyncImage`.
 */
@Composable
private fun FocusedBackdrop(viewModel: HomeViewModel) {
    val url by viewModel.focusedBackdropUrl.collectAsStateWithLifecycle()
    FanartBackdrop(url = url)
}

/**
 * CyberFlix's hero artwork: the backdrop and the trailer that takes it over.
 *
 * Split out for the same recomposition reason as [FocusedBackdrop] — both URLs
 * change on every settled focus, and read from `HomeScreen` they invalidated
 * the entire screen — but it matters more here, because an invalidation that
 * reaches [HeroArt] would take the `AndroidView` holding a *playing*
 * `ExoPlayer` with it.
 */
@Composable
private fun HeroArtBlock(viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val backdropUrl by viewModel.focusedBackdropUrl.collectAsStateWithLifecycle()
    val trailerUrl by viewModel.focusedTrailerUrl.collectAsStateWithLifecycle()
    val focused by viewModel.focused.collectAsStateWithLifecycle()

    HeroArt(
        backdropUrl = backdropUrl,
        trailerUrl = trailerUrl,
        trailerItemKey = focused?.key,
        onTrailerPlaybackChanged = viewModel::onTrailerPlaybackChanged,
        modifier = modifier,
        // Aloud, the way the streaming apps this theme is named after do it:
        // an auto-preview is meant to be a preview, and a silent one is just a
        // moving poster.
        muted = false,
    )
}

/**
 * The site's hero, collecting the rotating pick itself.
 *
 * Same reasoning as [FocusedBackdrop]: the item and its detail turn over on a
 * timer, and read from `HomeScreen` that timer would invalidate the entire
 * screen — including the row whose card currently holds focus — every sixteen
 * seconds.
 */
@Composable
private fun SpotlightHeroBlock(
    viewModel: HomeViewModel,
    onOpenTitle: (MediaItem) -> Unit,
    requestInitialFocus: Boolean,
    onInitialFocusHandled: () -> Unit,
    primaryFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val item by viewModel.spotlightItem.collectAsStateWithLifecycle()
    val detail by viewModel.spotlightDetail.collectAsStateWithLifecycle()

    SpotlightHero(
        item = item,
        detail = detail,
        onOpen = onOpenTitle,
        onNext = viewModel::nextSpotlight,
        requestInitialFocus = requestInitialFocus,
        onInitialFocusHandled = onInitialFocusHandled,
        primaryFocusRequester = primaryFocusRequester,
        modifier = modifier,
    )
}

/** Same reasoning as [FocusedBackdrop] — see the note in `HomeScreen`. */
@Composable
private fun HeroPanel(viewModel: HomeViewModel) {
    val item by viewModel.focused.collectAsStateWithLifecycle()
    val itemDetail by viewModel.focusedDetail.collectAsStateWithLifecycle()
    HeroPanelContent(item, itemDetail)
}

@Composable
private fun HeroPanelContent(item: MediaItem?, itemDetail: MediaDetail?) {
    if (HubColors.isNetflixLayout || HubColors.isPrimefly) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HubDimens.ScreenPaddingHorizontal, end = HubDimens.ScreenPaddingHorizontal, bottom = 4.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (item == null) {
                AnimatedOpenStreamTitle(
                    style = MaterialTheme.typography.headlineLarge,
                )
                return@Column
            }

            val logoUrl = itemDetail?.logoUrl
            val logoBottomPadding = if (HubColors.isPrimefly) 4.dp else 20.dp
            if (logoUrl != null) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = item.title,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .height(100.dp)
                        // Height alone left the width to the artwork, and a
                        // wide clearlogo — a long title set on one line — then
                        // ran the full width of the panel and out under the
                        // hero art. `Fit` means this only ever *caps* it: a
                        // logo narrower than half the screen keeps its natural
                        // size and only the offenders are scaled down.
                        .fillMaxWidth(LOGO_MAX_WIDTH_FRACTION)
                        .padding(bottom = logoBottomPadding),
                    alignment = Alignment.BottomStart,
                )
            } else {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = HubColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = logoBottomPadding)
                )
            }
            
            HeroMetadataRow(
                item = item,
                detail = itemDetail,
                modifier = Modifier.padding(bottom = if (HubColors.isPrimefly) 6.dp else 12.dp),
            )

            val overview = itemDetail?.overview
            if (overview != null) {
                if (HubColors.isPrimefly) {
                    AutoScrollText(
                        text = overview,
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.Text,
                        // Four visible lines, then the exact same timed
                        // vertical scroll used by the Netflixy synopsis.
                        modifier = Modifier.fillMaxWidth(0.55f).height(96.dp),
                    )
                } else {
                    AutoScrollText(
                        text = overview,
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.Text,
                        modifier = Modifier.fillMaxWidth(0.55f).weight(1f, fill = false)
                    )
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HubDimens.ScreenPaddingHorizontal)
                .height(76.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (item == null) {
                AnimatedOpenStreamTitle(
                    style = MaterialTheme.typography.headlineLarge,
                )
                return@Column
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineLarge,
                color = HubColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            HeroMetadataRow(item = item, detail = itemDetail)
        }
    }
}

/** Exact themed order: year • genre • duration, with theme-coloured separators. */
@Composable
private fun HeroMetadataRow(
    item: MediaItem,
    detail: MediaDetail?,
    modifier: Modifier = Modifier,
) {
    val values = listOfNotNull(
        (item.year ?: detail?.year)?.toString(),
        item.genres.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: detail?.genres?.firstOrNull()?.takeIf { it.isNotBlank() },
        (item.runtimeMinutes ?: detail?.runtimeMinutes)?.let {
            stringResource(R.string.home_minutes, it)
        },
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        values.forEachIndexed { index, value ->
            if (index > 0) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (HubColors.isPrimefly) HubColors.Accent else HubColors.NetflixRed,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = HubColors.TextDim,
            )
        }
    }
}

/**
 * How long the home screen may sit with no focus before the rail is handed it.
 *
 * A real Fire TV Stick is nowhere near an emulator on a development machine:
 * a weak CPU decoding the first frames of a season of posters, a radio that
 * has to actually reach mdblist over the internet rather than a loopback, and
 * `initialSyncComplete` covers only the list *definitions* — the items each
 * row needs still have to land after that, one `refreshItems` per row as it
 * scrolls into view. 1.2s was tuned against the emulator and fired on real
 * hardware on every ordinary cold start, not just the account-quota case it
 * was written for. An mdblist account actually running dry is the exception,
 * not the rule, so this errs long: fifteen seconds of patience for widgets
 * that are simply slow to arrive, rather than a few flashes of "nothing to
 * show" the viewer has to sit through before the real rows appear anyway.
 */
private const val FOCUS_FALLBACK_MS = 15_000L

/** Long enough for the rail's width animation to give focus something to land on. */
private const val RAIL_EXPAND_MS = 250L

/** Steady-state cadence once real content holds focus — cheap, two boolean reads. */
private const val POLL_MS = 1_000L

/**
 * Share of the hero's width CyberFlix gives the artwork block.
 *
 * The block is right-aligned and its own left edge is feathered away over the
 * first third of itself, so the artwork stops well before the title card
 * rather than at this line — which is why the number can be this generous
 * without the two ever colliding.
 *
 * A width-only increase was tried here to grow the block's area, on the
 * reasoning that the block already fills the hero's full height so width was
 * the only lever left. It was reverted: the height itself is already capped
 * by the hero `Box`'s share of the column (screen height minus the row
 * strip's fixed height below it), so widening alone could not deliver a real
 * area increase — it only stretched the same silhouette sideways. Growing
 * the area for real means growing what the hero `Box` is given, which means
 * taking height from the rows below; that call has since been made, in the
 * strip heights CyberFlix and OptimusPrime ask for above.
 *
 * Which leaves this constant where it already was: at its ceiling. The left
 * ramp has to have finished erasing the artwork by the time it reaches the
 * synopsis, and the synopsis ends 0.55 of the padded width in — 524 dp on the
 * 960 dp canvas a television gives. The block is right-aligned, so its opaque
 * edge sits at `width − W + LEFT_FEATHER · W`, and holding that at or past
 * 524 allows W ≤ 660 dp, i.e. 0.6875. Every dp past that is artwork printed
 * under the text; every dp of width bought by widening the ramp instead is
 * artwork drawn at partial alpha while the aspect — and with it the crop
 * taken out of a 16:9 backdrop — gets worse. Height was the only honest
 * lever, and it is the one that was pulled.
 */
private const val HERO_ART_WIDTH_FRACTION = 0.68f

/**
 * Ceiling on the clearlogo's width, as a share of the hero panel.
 *
 * A cap rather than a size: the image is drawn `Fit` and aligned to the
 * bottom-start, so anything narrower than this is untouched and only a logo
 * that would otherwise run under the artwork gets scaled back.
 */
private const val LOGO_MAX_WIDTH_FRACTION = 0.5f

private fun ResumePoint.toCardItem() = MediaItem(
    tmdbId = tmdbId ?: 0,
    type = type,
    title = title,
    imdbId = imdbId,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    score = score,
)
