package com.mdblisthub.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.AddonCatalog
import com.mdblisthub.tv.core.model.AddonCatalogItem
import com.mdblisthub.tv.core.model.ClockPosition
import com.mdblisthub.tv.core.model.LibraryBucket
import com.mdblisthub.tv.core.model.MediaList
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.MdblistHomeFeed
import com.mdblisthub.tv.core.model.RecommendationRow
import com.mdblisthub.tv.core.model.ResumePoint
import com.mdblisthub.tv.core.model.ScrobbleTarget
import com.mdblisthub.tv.core.ui.theme.HubColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

internal sealed interface HomeMediaRow {
    val position: Int

    data class Mdblist(val list: MediaList, override val position: Int) : HomeMediaRow
    data class Feed(val feed: MdblistHomeFeed, override val position: Int) : HomeMediaRow
    data class Stremio(val catalog: AddonCatalog, override val position: Int) : HomeMediaRow
}

/**
 * A request to keep a just-moved row in view, at the index it moved to.
 *
 * The index is the row's position within `homeRows`; the screen adds whatever
 * fixed items sit above the row list before scrolling.
 *
 * [id] exists only so that moving the *same* row twice in a row still counts
 * as a new request: a `StateFlow` drops a value equal to the one it already
 * holds, and without the counter the second press of "move down" would be
 * published, deduplicated, and never acted on.
 */
internal data class HomeRowReveal(val rowIndex: Int, val id: Long)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(private val graph: DataGraph) : ViewModel() {

    /**
     * Rows are lazy and routinely leave composition while the user scrolls.
     * Keeping one StateFlow per list preserves its last Room emission, so a
     * row returning from above is full-sized immediately instead of briefly
     * reappearing as an empty, zero-height item during focus search.
     */
    private val itemFlows = mutableMapOf<Long, StateFlow<List<MediaItem>>>()
    private val catalogItemFlows = mutableMapOf<String, MutableStateFlow<List<AddonCatalogItem>>>()
    private val catalogLoadJobs = mutableMapOf<String, Job>()
    private val loadedCatalogs = mutableSetOf<String>()
    private val loadingMoreLists = mutableSetOf<Long>()
    private val exhaustedLists = mutableSetOf<Long>()
    private val moveMutex = Mutex()
    private var dynamicRefreshJob: Job? = null

    /**
     * The row a reorder just moved, for the screen to scroll back into view.
     *
     * Reordering writes through Room/DataStore and comes back as a new
     * `homeRows`, and a `LazyColumn` keeps its scroll anchored to whatever was
     * at the top rather than to the item that moved — so with rows this tall
     * the moved one routinely lands outside the viewport and the reorder looks
     * like it did nothing. Naming it here is what lets the screen follow it.
     */
    private val _rowToReveal = MutableStateFlow<HomeRowReveal?>(null)
    internal val rowToReveal: StateFlow<HomeRowReveal?> = _rowToReveal.asStateFlow()
    private var rowRevealCounter = 0L

    /** Called by the screen once it has scrolled to [rowToReveal]. */
    internal fun onRowRevealed() {
        _rowToReveal.value = null
    }

    private val _initialSyncComplete = MutableStateFlow(false)
    val initialSyncComplete: StateFlow<Boolean> = _initialSyncComplete.asStateFlow()

    val allAddonCatalogs: StateFlow<List<AddonCatalog>> = graph.addons.observeCatalogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    /**
     * Straight out of Room. The home paints on the first frame from whatever
     * the last sync left behind; the refresh below writes over it whenever it
     * finishes, and nothing on screen ever waits for the network.
     */
    val allLists: StateFlow<List<MediaList>> = graph.lists.observeLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allFeeds: StateFlow<List<MdblistHomeFeed>> = graph.homeFeeds.observeFeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lists: StateFlow<List<MediaList>> = kotlinx.coroutines.flow.combine(allLists, _isEditMode) { lists, editMode ->
        if (editMode) lists else lists.filter { !it.hidden }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val addonCatalogs: StateFlow<List<AddonCatalog>> =
        kotlinx.coroutines.flow.combine(allAddonCatalogs, _isEditMode) { catalogs, editMode ->
            if (editMode) catalogs else catalogs.filter { !it.hidden }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val feeds: StateFlow<List<MdblistHomeFeed>> =
        kotlinx.coroutines.flow.combine(allFeeds, _isEditMode) { feeds, editMode ->
            if (editMode) feeds else feeds.filter { !it.hidden }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val resumePoints: StateFlow<List<ResumePoint>> = graph.playback.resumePoints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val watchedIds: StateFlow<Set<Int>> = graph.library.observeBucket(com.mdblisthub.tv.core.model.LibraryBucket.WATCHED)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val watchedEpisodes: StateFlow<Set<String>> = graph.library.observeWatchedEpisodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleEditMode() {
        _isEditMode.value = !_isEditMode.value
    }

    val autotrailer: StateFlow<Boolean> = graph.uiPreferences.autotrailer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val posterLandscapeTransformation: StateFlow<Boolean> =
        graph.uiPreferences.posterLandscapeTransformation
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * The clock overlay's three settings, read here rather than inside the
     * clock itself so the home can decide whether to compose it at all —
     * turned off, it costs nothing but a `false`.
     */
    val clockEnabled: StateFlow<Boolean> = graph.uiPreferences.clockScope
        .map { it.onHome }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val clockPosition: StateFlow<ClockPosition> = graph.uiPreferences.clockPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClockPosition.RIGHT)

    val clockHomeAutoHide: StateFlow<Boolean> = graph.uiPreferences.clockHomeAutoHide
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Non-null only after the focused title's trailer has rendered its first
     * frame. Cards observe this separately from the URL so buffering never
     * makes the poster expand before motion is actually visible.
     */
    private val _trailerPlayingItemKey = MutableStateFlow<String?>(null)
    val trailerPlayingItemKey: StateFlow<String?> = _trailerPlayingItemKey.asStateFlow()

    /**
     * Advances the palette and writes the choice down, so the box comes back
     * in it. The repaint is immediate and the write is not waited on: the
     * palette is in-memory state, and the store only has to agree with it by
     * the next cold start.
     */
    fun cycleTheme() {
        val next = HubColors.toggleTheme(autotrailer.value)
        viewModelScope.launch { graph.uiPreferences.saveTheme(next) }
    }

    fun toggleListVisibility(list: MediaList) {
        viewModelScope.launch {
            graph.lists.toggleVisibility(list.id, !list.hidden)
        }
    }

    fun moveList(list: MediaList, direction: Int) {
        viewModelScope.launch { graph.lists.move(list.id, direction) }
    }

    fun renameList(list: MediaList, name: String) {
        viewModelScope.launch { graph.lists.rename(list.id, name) }
    }

    fun deleteList(list: MediaList) {
        itemFlows.remove(list.id)
        loadingMoreLists.remove(list.id)
        exhaustedLists.remove(list.id)
        viewModelScope.launch { graph.lists.delete(list.id) }
    }

    fun toggleCatalogVisibility(catalog: AddonCatalog) {
        viewModelScope.launch {
            graph.addons.toggleCatalogVisibility(catalog, !catalog.hidden)
        }
    }

    fun renameCatalog(catalog: AddonCatalog, name: String) {
        viewModelScope.launch { graph.addons.renameCatalog(catalog, name) }
    }

    fun deleteCatalog(catalog: AddonCatalog) {
        val cacheKey = catalogCacheKey(catalog)
        catalogLoadJobs.remove(cacheKey)?.cancel()
        catalogItemFlows.remove(cacheKey)
        loadedCatalogs.remove(cacheKey)
        viewModelScope.launch { graph.addons.deleteCatalog(catalog) }
    }

    fun toggleFeedVisibility(feed: MdblistHomeFeed) {
        viewModelScope.launch { graph.homeFeeds.toggleVisibility(feed, !feed.hidden) }
    }

    fun renameFeed(feed: MdblistHomeFeed, name: String) {
        viewModelScope.launch { graph.homeFeeds.rename(feed, name) }
    }

    fun deleteFeed(feed: MdblistHomeFeed) {
        viewModelScope.launch { graph.homeFeeds.delete(feed) }
    }

    /** Reorders the combined MDBList + Stremio rows, including across their boundary. */
    internal fun moveRow(rows: List<HomeMediaRow>, index: Int, direction: Int) {
        val target = index + direction
        if (target !in rows.indices) return
        val reordered = rows.toMutableList().apply {
            val row = removeAt(index)
            add(target, row)
        }
        viewModelScope.launch {
            // A held lock means the UI has not observed the previous order yet.
            // Dropping key-repeat events here is safer than queueing snapshots
            // built from stale positions and applying them after the first move.
            if (!moveMutex.tryLock()) return@launch

            // Announced here — after the lock, so a dropped repeat never moves
            // the viewport for a reorder that did not happen, but *before* the
            // write, deliberately. The new order has to travel through
            // DataStore/Room and come back as a fresh `homeRows`, and waiting
            // for that would race the emission: the screen would run its scroll
            // against the old order and follow the row to where it used to be.
            // The destination index is already known, and scrolling a
            // `LazyColumn` to an index does not require the item to be there
            // yet — the viewport moves at once and the rows settle into it.
            rowRevealCounter++
            _rowToReveal.value = HomeRowReveal(target, rowRevealCounter)

            try {
                runCatching {
                    graph.lists.setPositions(
                        reordered.mapIndexedNotNull { position, row ->
                            (row as? HomeMediaRow.Mdblist)?.list?.id?.let { it to position }
                        }.toMap(),
                    ).getOrThrow()
                    graph.addons.setCatalogPositions(
                        reordered.mapIndexedNotNull { position, row ->
                            when (row) {
                                is HomeMediaRow.Stremio -> row.catalog.key to position
                                is HomeMediaRow.Feed -> row.feed.key to position
                                is HomeMediaRow.Mdblist -> null
                            }
                        }.toMap(),
                    ).getOrThrow()
                }
            } finally {
                moveMutex.unlock()
            }
        }
    }

    fun itemsFor(listId: Long): StateFlow<List<MediaItem>> =
        itemFlows.getOrPut(listId) {
            graph.lists.observeItems(listId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    /** Drives the panel above the rows. */
    private val _focused = MutableStateFlow<MediaItem?>(null)
    val focused: StateFlow<MediaItem?> = _focused.asStateFlow()

    /**
     * The "Continuar assistindo" entry behind [focused], when there is one.
     *
     * Kept beside [_focused] rather than folded into it because a [MediaItem]
     * has no season or episode — `ResumePoint.toCardItem()` drops both — and
     * the hero writes the resumed episode's own air date and number. Null for
     * every other row, which is what puts the hero back on the title-level
     * line those rows want.
     */
    private val _focusedResume = MutableStateFlow<ResumePoint?>(null)

    /** The app's language code, for writing the episode's air date. */
    val language: StateFlow<String> = graph.uiPreferences.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "en")

    /**
     * The episode row for whatever "Continuar assistindo" card holds focus.
     *
     * Settled on the same clock as the rest of the hero, so sweeping the row
     * does not queue a season fetch per card passed over.
     */
    val focusedEpisode: StateFlow<com.mdblisthub.tv.core.model.Episode?> = _focusedResume
        .debounce { if (it == null) 0L else FANART_SETTLE_MS }
        .flatMapLatest { point ->
            val showId = point?.tmdbId
            val season = point?.season
            val number = point?.episode
            if (point == null || showId == null || showId <= 0 || season == null || number == null) {
                flowOf(null)
            } else {
                graph.media.observeEpisodes(showId, season)
                    .map { episodes -> episodes.firstOrNull { it.episodeNumber == number } }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * [_focused], held still.
     *
     * Everything the hero draws hangs off this rather than off [focused]
     * directly, and [focused] deliberately is not settled: the panel's title
     * and year are a text swap and should track the remote exactly, but each
     * distinct backdrop below is a full-screen `w1280` decode. Sweeping a row
     * used to queue one per card passed over, which is the single heaviest
     * thing the home screen did.
     *
     * The wait is per-move rather than fixed. Settling exists to protect a
     * picture that is *already on screen* from being replaced by one the
     * viewer is only passing through — so on the one move where nothing is up
     * yet, it is protecting nothing and costs an empty hero for its trouble.
     */
    private val settledFocus: Flow<MediaItem?> = _focused
        // Carries the previous card alongside the current one, which is the
        // only thing `debounce` cannot see for itself.
        .runningFold<MediaItem?, Pair<MediaItem?, MediaItem?>>(null to null) { step, item ->
            step.second to item
        }
        .drop(1)
        .debounce { (previous, current) ->
            when {
                current == null -> 0L
                // Nothing is on screen to protect: this is the first card
                // focused after the opening spotlight, and the hero behind it
                // is empty. Waiting the full settle here buys nothing and
                // lands the artwork after the hero has finished arriving
                // rather than while it still is — see `HERO_ENTER_MS`.
                previous == null -> FANART_FIRST_SETTLE_MS
                else -> FANART_SETTLE_MS
            }
        }
        .map { (_, current) -> current }

    /**
     * Drives the fanart specifically — separate from [focused] because a
     * list card only ever carries a poster, never a backdrop (mdblist's list
     * endpoint doesn't return one), so the raw item's own `backdropUrl` is
     * always null off every row except "Continuar assistindo". Falling back
     * to the poster there means a portrait image stretched to cover a
     * full-bleed landscape panel — soft to the point of looking broken.
     *
     * This instead watches the *cached detail* for whatever is focused right
     * now, which [MetadataPrefetcher] is already warming on the same focus
     * event for the "open feels instant" reason. The poster fallback still
     * covers the gap before that detail lands; once it does, the fanart
     * upgrades to the real backdrop in place.
     */
    val focusedBackdropUrl: StateFlow<String?> = settledFocus
        .flatMapLatest { item ->
            if (item == null) {
                flowOf(null)
            } else if (item.tmdbId <= 0) {
                flowOf(item.backdropUrl)
            } else {
                graph.media.observeDetail(item.type, item.tmdbId)
                    .flatMapLatest { detail ->
                        val url = detail?.backdropUrl ?: item.backdropUrl
                        if (url != null) {
                            flowOf<String?>(url)
                        } else {
                            kotlinx.coroutines.flow.flow {
                                emit(graph.media.getFanartBackdropUrl(item.type, item.tmdbId))
                            }
                        }
                    }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val focusedDetail: StateFlow<com.mdblisthub.tv.core.model.MediaDetail?> = settledFocus
        .flatMapLatest { item ->
            if (item == null || item.tmdbId <= 0) {
                flowOf(null)
            } else {
                graph.media.observeDetail(item.type, item.tmdbId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The trailer to autoplay behind the hero, once a card has been sat on
     * long enough to mean it — see `HeroArt`, which is the only consumer.
     *
     * The dwell is the whole feature. Sweeping a row past a dozen posters must
     * cost nothing: `debounce` restarts on every focus move, so no lookup is
     * even attempted until the remote has been still for
     * [TRAILER_DWELL_MS], and `flatMapLatest` cancels the one in flight the
     * instant focus moves again. What survives that is a viewer who stopped —
     * which is the only case a trailer is wanted for.
     *
     * IMDb, not TMDB, because [com.mdblisthub.tv.core.data.repository.TrailerRepository]
     * hands back a plain MP4 that Media3 can play inline; the alternative tiers
     * behind the detail screen's overlay are a `WebView` and an intent, and
     * neither belongs on a home screen that is meant to stay quiet.
     *
     * Emits null — not "keep the last one" — the moment focus moves, so the
     * art block can drop straight back to the backdrop rather than leaving a
     * trailer for the wrong title playing under a new one's title card.
     */
    val focusedTrailerUrl: StateFlow<String?> = _focused
        .flatMapLatest { item ->
            if (item == null) {
                flowOf(null)
            } else {
                kotlinx.coroutines.flow.flow<String?> {
                    // Null *before* the wait, not after it. This is what makes
                    // moving the remote cut the current trailer dead: the
                    // `flatMapLatest` above cancels this flow the instant focus
                    // changes, and its replacement's first act is to clear the
                    // URL — which drops the `AndroidView` holding the player
                    // and lets the backdrop back in.
                    //
                    // Sequencing it the other way — debouncing upstream and
                    // clearing here — looked equivalent and was not: `debounce`
                    // only delays the *new* value, so the old one stayed the
                    // current state and the previous title's trailer went on
                    // playing for the whole dwell, over the new title's card.
                    emit(null)

                    // The dwell, restarted from zero for whatever is focused
                    // now. Cancellation is the mechanism, so no timer has to be
                    // tracked or reset by hand.
                    delay(TRAILER_DWELL_MS)

                    val imdbId = item.imdbId?.takeIf { it.isNotBlank() }
                        ?: graph.media.observeDetail(item.type, item.tmdbId)
                            .first()?.imdbId?.takeIf { it.isNotBlank() }
                    emit(imdbId?.let { runCatching { graph.trailers.mp4For(it) }.getOrNull() })
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * "Porque você assistiu" — built once per visit, not persisted: unlike
     * the mdblist rows above, TMDB's recommendations have nothing worth
     * caching in Room for, and the seeds are cheap to re-derive from
     * whatever "Last Watched" looks like right now. Cheap because the
     * repository shares one fetch with the hero below, which is asking about
     * the same seeds at the same moment — see `RecommendationsRepository`.
     */
    private val _becauseYouWatched = MutableStateFlow<List<RecommendationRow>>(emptyList())
    val becauseYouWatched: StateFlow<List<RecommendationRow>> = _becauseYouWatched.asStateFlow()

    /**
     * "Destaques" — the titles the home hero pans across.
     *
     * Same lifetime and same reasoning as [becauseYouWatched]: derived from
     * the watch history as it stands on this visit, nothing worth persisting.
     */
    private val _spotlight = MutableStateFlow<List<MediaItem>>(emptyList())
    val spotlight: StateFlow<List<MediaItem>> = _spotlight.asStateFlow()

    /**
     * Whether the spotlight query has come back — however it came back.
     *
     * The screen needs the empty *result* told apart from the empty *initial
     * value*, and a `List` cannot carry that distinction. While this is false
     * the hero holds its place as a skeleton; once it is true an empty
     * [spotlight] means the account genuinely has nothing to feature — no
     * watch history, or nothing recommended off it that clears the bar — and
     * the hero gives its space back to the rows instead of sitting empty.
     */
    private val _spotlightLoaded = MutableStateFlow(false)
    val spotlightLoaded: StateFlow<Boolean> = _spotlightLoaded.asStateFlow()

    /**
     * Whether the viewer wants the hero at all — the Settings toggle.
     *
     * Seeded from the synchronous mirror rather than from a hard-coded
     * default, so the very first composition already knows the answer. Seeded
     * with `true` instead, a viewer who had turned the hero off would get a
     * screen of skeleton on every cold start before the flow corrected it.
     */
    val spotlightEnabled: StateFlow<Boolean> = graph.uiPreferences.spotlightHero
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            graph.uiPreferences.startupSpotlightHero(),
        )

    private val _spotlightIndex = MutableStateFlow(0)

    /**
     * Which destaque is on screen.
     *
     * Held here rather than in the hero itself because the hero is an item in
     * a `LazyColumn`: scrolling down to the third row disposes it, and state
     * kept inside it would put the rotation back to the first title — and
     * back to a `KenBurns` starting from zero — every time the viewer came
     * back up to the top.
     */
    val spotlightItem: StateFlow<MediaItem?> =
        kotlinx.coroutines.flow.combine(_spotlight, _spotlightIndex) { items, index ->
            items.getOrNull(index)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Advances the spotlight, wrapping. Drives both the timer and
     * "Surpreenda-me" — and drops anything the IMDb bar rejects on the way.
     *
     * The bar cannot be applied where the pool is built. That pool comes from
     * TMDB's neighbour endpoints, whose payloads carry `vote_average` and no
     * IMDb figure whatsoever; the IMDb number arrives from mdblist, one
     * request per title, and there are a hundred and sixty of them. Paying
     * that on every Home is not a trade worth making for a hero that shows
     * three titles a minute.
     *
     * So the test is applied here, to the one title about to take the screen,
     * against a rating that is already in Room — `MetadataPrefetcher` warms
     * the *next* destaque during the current one's dwell, precisely so that
     * by the time this runs the answer is usually sitting there. Nothing is
     * fetched to answer it.
     *
     * Two consequences worth stating plainly rather than discovering:
     *
     * A title whose rating has not arrived is **shown**, not held. Unknown is
     * not the same as bad, and blocking on it would mean a hero that stalls
     * on a slow connection rather than one that occasionally features
     * something it would rather not.
     *
     * And after a skip, the title landed on is one the prefetch never warmed
     * — it was two ahead, not one — so it is judged only if something else
     * happened to cache it. A skip therefore costs the next slot its check.
     */
    fun nextSpotlight() {
        val items = _spotlight.value
        if (items.isEmpty()) return
        viewModelScope.launch {
            var index = _spotlightIndex.value
            // Bounded by the pool: a rotation where every rating is known and
            // every one of them fails must still land somewhere rather than
            // walk the ring forever.
            repeat(items.size) {
                index = (index + 1) % items.size
                if (meetsRatingBar(items[index])) {
                    _spotlightIndex.value = index
                    return@launch
                }
            }
            _spotlightIndex.value = (_spotlightIndex.value + 1) % items.size
        }
    }

    /**
     * Whether a title clears the IMDb bar for its own kind — 6 for a film, 7
     * for a série — reading only what is already cached.
     *
     * Answers `true` for a title with no rating in Room yet and for one
     * mdblist has no IMDb entry for at all. Both are "not known to be below
     * the bar", and this is a filter against titles that are known bad, not a
     * gate that demands proof of quality before anything may be shown.
     */
    private suspend fun meetsRatingBar(item: MediaItem): Boolean {
        val cached = graph.media.observeDetail(item.type, item.tmdbId).first()
            ?: return true
        val imdb = cached.ratings.firstOrNull { it.key == IMDB_RATING_KEY }?.score
            ?: return true
        return imdb >= item.type.imdbBar
    }

    /**
     * The bar, on `RatingBadge.score`'s own 0–100 scale rather than IMDb's
     * 0–10 — that is what is stored, and converting the stored value back
     * every time would be arithmetic in the hot path to make a constant look
     * familiar.
     */
    private val MediaType.imdbBar: Int
        get() = if (this == MediaType.SHOW) SHOW_IMDB_BAR else MOVIE_IMDB_BAR

    /**
     * The synopsis, genres and clearlogo behind the hero copy.
     *
     * Undebounced, unlike [focusedDetail]: the spotlight changes on a timer
     * measured in seconds, never on a remote sweep, so there is no burst of
     * intermediate values to settle. [MediaRepository.ensureDetail] is called
     * first because nothing else warms these — they are TMDB recommendations
     * the viewer has not focused, so Room has no row for them until asked.
     */
    val spotlightDetail: StateFlow<com.mdblisthub.tv.core.model.MediaDetail?> = spotlightItem
        .flatMapLatest { item ->
            if (item == null || item.tmdbId <= 0) {
                flowOf(null)
            } else {
                kotlinx.coroutines.flow.flow<com.mdblisthub.tv.core.model.MediaDetail?> {
                    // Null first, and it is not a formality. A `StateFlow`
                    // holds its last value until the next one arrives, and
                    // everything below this line is slower than the title
                    // above it — so without this the hero spent the first
                    // moment of every swap showing the new film's name over
                    // the previous film's synopsis and genres, which reads as
                    // the app having the wrong data rather than as loading.
                    emit(null)

                    coroutineScope {
                        // Concurrently, not before: `observeDetail` answers
                        // from Room, so a destaque that has already been
                        // warmed — which, thanks to the prefetch below, is
                        // nearly all of them — repaints on the next frame
                        // instead of waiting out a network round trip that
                        // is only there to refresh it.
                        launch { graph.media.ensureDetail(item.type, item.tmdbId) }
                        emitAll(graph.media.observeDetail(item.type, item.tmdbId))
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        refreshDynamicRows()
        viewModelScope.launch {
            _becauseYouWatched.value = graph.recommendations.becauseYouWatched()
        }
        viewModelScope.launch {
            // Warms the destaque *after* the one on screen.
            //
            // The rotation is the rare case where what comes next is known
            // well in advance — sixteen seconds of it — and spending that on
            // the fetch is what closes the gap the `emit(null)` above would
            // otherwise leave visible. Keyed on the item rather than the
            // index so that "Surpreenda-me" jumping the queue re-aims it.
            kotlinx.coroutines.flow.combine(_spotlight, _spotlightIndex) { items, index ->
                if (items.isEmpty()) null else items[(index + 1) % items.size]
            }
                .distinctUntilChanged()
                .collect { next ->
                    if (next != null && next.tmdbId > 0) {
                        graph.prefetcher.prefetch(next.type, next.tmdbId)
                    }
                }
        }
        viewModelScope.launch {
            // Driven by the preference rather than fired once: turning the
            // hero off has to stop costing a `sync/watched` plus a round of
            // TMDB calls per launch, and turning it back on has to fill it
            // without waiting for a restart. `collectLatest` cancels a fetch
            // still in flight when the switch is flipped back the other way.
            spotlightEnabled.collectLatest { enabled ->
                if (!enabled) {
                    _spotlight.value = emptyList()
                    _spotlightLoaded.value = true
                    return@collectLatest
                }
                _spotlightLoaded.value = false
                try {
                    val pool = graph.recommendations.spotlight()
                    _spotlight.value = pool
                    _spotlightIndex.value = openingIndex(pool)
                } finally {
                    _spotlightLoaded.value = true
                }
            }
        }
    }

    /**
     * Which destaque the hero opens on.
     *
     * The one slot [nextSpotlight]'s cheap test cannot cover: at this point
     * the pool is seconds old and nothing in it has been warmed, so every
     * rating reads as unknown and the bar would wave the first title through
     * whatever it is. That slot is also the most seen in the app — it is what
     * the Home *is*, for the first twenty seconds of every session.
     *
     * So this one is resolved rather than guessed, and the fetch is not an
     * extra: `spotlightDetail` calls `ensureDetail` on whatever ends up here
     * the moment it is shown, so what this changes is the order — the rating
     * is asked for before the reveal instead of just after it. The cost is a
     * round trip added to a wait that already spans a paginated watch history
     * and twenty TMDB calls.
     *
     * [OPENING_CANDIDATES] caps it. Almost always the first title clears the
     * bar and this is one fetch; the cap is there so that a pool whose head
     * happens to be a run of weak titles cannot turn the opening into a
     * sequence of round trips with the viewer watching a skeleton.
     */
    private suspend fun openingIndex(pool: List<MediaItem>): Int {
        for (index in 0 until minOf(OPENING_CANDIDATES, pool.size)) {
            val item = pool[index]
            runCatching { graph.media.ensureDetail(item.type, item.tmdbId) }
            if (meetsRatingBar(item)) return index
        }
        return 0
    }

    /** Refreshes account activity whenever Home becomes visible again. */
    fun refreshDynamicRows() {
        // A previously exhausted page may have gained items while Home was in
        // the background. It is allowed one new end-of-row probe on return.
        exhaustedLists.clear()
        refreshLoadedCatalogs(allAddonCatalogs.value)
        if (dynamicRefreshJob?.isActive == true) return
        dynamicRefreshJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.coroutineScope {
                    // The index is a small no-store request. Keeping it in the
                    // same single-flight refresh as the activity feeds makes a
                    // list created on MDBList appear as soon as Home resumes,
                    // without racing an identical startup request.
                    launch { graph.lists.refreshLists(force = true) }
                    launch { graph.homeFeeds.refresh() }
                    launch { graph.playback.refreshResumePoints() }
                    launch { graph.library.refresh(com.mdblisthub.tv.core.model.LibraryBucket.WATCHED) }
                    // Addons installed on another device land here too, not
                    // only on a cold start: this is the same "account activity
                    // changed elsewhere" case as the rows above, and without
                    // it a television left open never sees what the phone just
                    // installed.
                    launch { graph.firebaseSync.restore() }
                }
                graph.scheduler.hydrateSoon()
            } finally {
                _initialSyncComplete.value = true
            }
        }
    }

    fun onFocused(item: MediaItem, resumePoint: ResumePoint? = null) {
        if (_focused.value?.key != item.key) {
            _trailerPlayingItemKey.value = null
        }
        _focusedResume.value = resumePoint
        // The season is fetched once and cached; `ensureEpisodes` is a no-op
        // afterwards, the same way `prefetch` below is for the detail row.
        val showId = resumePoint?.tmdbId
        val season = resumePoint?.season
        if (showId != null && showId > 0 && season != null) {
            viewModelScope.launch { graph.media.ensureEpisodes(showId, season) }
        }
        _focused.value = item
        // Warming the detail on focus is what makes opening a title feel
        // instant: by the time the user presses OK, it is already in Room.
        if (item.tmdbId > 0) {
            graph.prefetcher.prefetch(item.type, item.tmdbId)
        } else {
            // Guest home rows commonly come straight from Stremio add-ons.
            // Those catalogues identify titles by IMDb ID, whereas the hero's
            // clearlogo and synopsis cache is TMDB-keyed. Previously IMDb was
            // resolved only after pressing OK, leaving the focused home panel
            // permanently without either piece of metadata for guests.
            val imdbId = item.imdbId ?: return
            viewModelScope.launch {
                graph.media.resolveImdb(item.type, imdbId).onSuccess { tmdbId ->
                    // Focus may have moved while the lookup was in flight;
                    // never replace the currently displayed title with a
                    // late result for an old card.
                    if (_focused.value?.key != item.key) return@onSuccess
                    _focused.value = item.copy(tmdbId = tmdbId)
                    graph.prefetcher.prefetch(item.type, tmdbId)
                }
            }
        }
    }

    /**
     * Focus went back up to the spotlight, so nothing below it is focused.
     *
     * Worth saying out loud rather than leaving [_focused] pointing at the
     * card the viewer last passed over: while the spotlight owns the viewport
     * the hero that reads [_focused] is not in composition at all, so the
     * value is describing something nobody can see. Clearing it is what lets
     * the next move down be recognised as an arrival onto an empty hero and
     * take [FANART_FIRST_SETTLE_MS] rather than the full sweep settle.
     */
    fun onSpotlightFocused() {
        _trailerPlayingItemKey.value = null
        _focusedResume.value = null
        _focused.value = null
    }

    fun onTrailerPlaybackChanged(itemKey: String?, playing: Boolean) {
        if (playing) {
            // A focus move can dispose an old player just as it reports READY.
            // Never let that stale frame expand the newly focused card.
            if (itemKey != null && _focused.value?.key == itemKey) {
                _trailerPlayingItemKey.value = itemKey
            }
        } else if (_trailerPlayingItemKey.value == itemKey) {
            _trailerPlayingItemKey.value = null
        }
    }

    fun itemsForCatalog(catalog: AddonCatalog): StateFlow<List<AddonCatalogItem>> =
        catalogItemFlows.getOrPut(catalogCacheKey(catalog)) {
            MutableStateFlow(emptyList())
        }.asStateFlow()

    fun ensureCatalog(catalog: AddonCatalog, force: Boolean = false) {
        val cacheKey = catalogCacheKey(catalog)
        val target = catalogItemFlows.getOrPut(cacheKey) { MutableStateFlow(emptyList()) }
        if (!force && cacheKey in loadedCatalogs) return
        if (catalogLoadJobs[cacheKey]?.isActive == true) return

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                graph.addons.catalogItems(catalog).onSuccess { items ->
                    // The flow itself is identity-scoped, so a response from an
                    // old configured base can never overwrite the new one.
                    target.value = items
                    loadedCatalogs += cacheKey
                }
            } finally {
                if (catalogLoadJobs[cacheKey] === coroutineContext[Job]) {
                    catalogLoadJobs.remove(cacheKey)
                }
            }
        }
        catalogLoadJobs[cacheKey] = job
        job.start()
    }

    /** Revalidates only catalogs that have actually been visited on this Home. */
    private fun refreshLoadedCatalogs(catalogs: List<AddonCatalog>) {
        val currentByKey = catalogs.associateBy(::catalogCacheKey)
        val activeKeys = currentByKey.keys

        catalogLoadJobs.keys.filterNot { it in activeKeys }.forEach { staleKey ->
            catalogLoadJobs.remove(staleKey)?.cancel()
        }
        catalogItemFlows.keys.retainAll(activeKeys)
        loadedCatalogs.retainAll(activeKeys)

        currentByKey.forEach { (cacheKey, catalog) ->
            if (cacheKey in catalogItemFlows) ensureCatalog(catalog, force = true)
        }
    }

    fun ensureItems(listId: Long) {
        viewModelScope.launch { graph.lists.refreshItems(listId) }
    }

    fun loadMore(listId: Long) {
        if (listId in loadingMoreLists || listId in exhaustedLists) return
        loadingMoreLists += listId
        viewModelScope.launch {
            try {
                graph.lists.loadMore(listId).onSuccess { loaded ->
                    if (loaded == 0) exhaustedLists += listId
                }
            } finally {
                loadingMoreLists -= listId
            }
        }
    }

    /**
     * Drops a title from "Continuar assistindo", here and on the account.
     *
     * `PlaybackRepository.clear` has existed since the row did — it posts
     * `scrobble/clear` and deletes the Room row — but nothing ever called it,
     * so a half-watched title someone had abandoned stayed on the home screen
     * with no way to remove it short of finishing it.
     */
    fun removeResumePoint(point: ResumePoint) {
        viewModelScope.launch { graph.playback.clear(point.toTarget()) }
    }

    /**
     * Returns a continue-watching entry to a never-played state.
     *
     * Playback and watched history are separate records at MDBList, Trakt and
     * Simkl. Deleting only the paused session removes the Home card but can
     * leave the title/episode marked as watched, so Reset deliberately clears
     * both records and updates their Room mirrors as well.
     */
    fun resetResumePoint(point: ResumePoint) {
        viewModelScope.launch {
            graph.playback.clear(point.toTarget())

            val season = point.season
            val episode = point.episode
            if (point.type == MediaType.SHOW && season != null && episode != null) {
                graph.library.setEpisodeWatched(
                    showTmdbId = point.tmdbId ?: 0,
                    showImdbId = point.imdbId,
                    season = season,
                    episode = episode,
                    watched = false,
                )
            } else if (point.tmdbId != null || point.imdbId != null) {
                graph.library.toggle(
                    bucket = LibraryBucket.WATCHED,
                    type = point.type,
                    tmdbId = point.tmdbId ?: 0,
                    imdbId = point.imdbId,
                    add = false,
                )
            }
        }
    }

    /** Sets the watched state of either a title or one concrete episode. */
    fun setWatched(
        item: MediaItem,
        season: Int? = null,
        episode: Int? = null,
        watched: Boolean,
    ) {
        viewModelScope.launch {
            val target = ScrobbleTarget(
                item.type,
                item.tmdbId.takeIf { it > 0 },
                item.imdbId,
                season,
                episode,
            )
            val result = if (item.type == MediaType.SHOW && season != null && episode != null) {
                graph.library.setEpisodeWatched(
                    item.tmdbId,
                    item.imdbId,
                    season,
                    episode,
                    watched,
                )
            } else if (item.tmdbId > 0 || item.imdbId != null) {
                graph.library.toggle(
                    LibraryBucket.WATCHED,
                    item.type,
                    item.tmdbId,
                    item.imdbId,
                    add = watched,
                )
            } else {
                return@launch
            }
            if (result.isSuccess && watched) {
                graph.playback.clear(target)
            }
        }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            graph.scheduler.onSignedOut()
            graph.auth.signOut()
            onDone()
        }
    }

    private companion object {
        /**
         * Matched to the prefetcher's own settle delay: the fanart wants the
         * detail row that focus-warming is fetching, so waiting the same
         * beat means the backdrop usually arrives instead of the poster
         * fallback flashing first.
         */
        const val FANART_SETTLE_MS = 350L

        /**
         * The settle for the first card focused after the spotlight.
         *
         * Short enough that the backdrop is already crossfading in while the
         * hero block is still travelling into place, and long enough to still
         * absorb the case where the viewer presses down and immediately
         * carries on sideways along the row.
         */
        const val FANART_FIRST_SETTLE_MS = 180L

        /**
         * How still the remote has to be before a trailer is even looked up.
         *
         * Deliberately far longer than [FANART_SETTLE_MS]: settling the fanart
         * is one image decode and wants to feel immediate, while this starts
         * *video with sound*. Four seconds is long enough that nobody sweeping
         * a row ever triggers one, and short enough that stopping to read a
         * synopsis rolls into the trailer without feeling like a wait.
         */
        const val TRAILER_DWELL_MS = 3_000L
    }

    private fun catalogCacheKey(catalog: AddonCatalog): String =
        "${catalog.addonBase.trimEnd('/')}\u0000${catalog.key}"
}

/** The key `RatingsMapper` files mdblist's IMDb figure under. */
private const val IMDB_RATING_KEY = "imdb"

/**
 * The IMDb floor a destaque has to clear, on `RatingBadge.score`'s 0–100
 * scale: 6,0 for a film and 7,0 for a série.
 *
 * Two numbers rather than one because the scales are not comparable. IMDb
 * grades a série on its whole run, and a run that stayed on the air long
 * enough to be graded at all has already survived a filter no single film
 * passes through — so 6,5 is a middling série and a perfectly good film.
 * One shared floor would either fill the hero with unremarkable television
 * or throw away films worth featuring.
 */
private const val MOVIE_IMDB_BAR = 60
private const val SHOW_IMDB_BAR = 70

/**
 * How many titles the opening may resolve before giving up and taking the
 * first. See `openingIndex`.
 */
private const val OPENING_CANDIDATES = 3
