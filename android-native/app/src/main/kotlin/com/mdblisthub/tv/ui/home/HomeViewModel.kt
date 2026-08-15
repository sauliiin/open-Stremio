package com.mdblisthub.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.AddonCatalog
import com.mdblisthub.tv.core.model.MediaList
import com.mdblisthub.tv.core.model.MdblistHomeFeed
import com.mdblisthub.tv.core.model.RecommendationRow
import com.mdblisthub.tv.core.model.ResumePoint
import com.mdblisthub.tv.core.ui.theme.HubColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

internal sealed interface HomeMediaRow {
    val position: Int

    data class Mdblist(val list: MediaList, override val position: Int) : HomeMediaRow
    data class Feed(val feed: MdblistHomeFeed, override val position: Int) : HomeMediaRow
    data class Stremio(val catalog: AddonCatalog, override val position: Int) : HomeMediaRow
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(private val graph: DataGraph) : ViewModel() {

    /**
     * Rows are lazy and routinely leave composition while the user scrolls.
     * Keeping one StateFlow per list preserves its last Room emission, so a
     * row returning from above is full-sized immediately instead of briefly
     * reappearing as an empty, zero-height item during focus search.
     */
    private val itemFlows = mutableMapOf<Long, StateFlow<List<MediaItem>>>()
    private val catalogItemFlows = mutableMapOf<String, MutableStateFlow<List<MediaItem>>>()
    private val catalogLoadJobs = mutableMapOf<String, Job>()
    private val loadedCatalogs = mutableSetOf<String>()
    private val loadingMoreLists = mutableSetOf<Long>()
    private val exhaustedLists = mutableSetOf<Long>()
    private val moveMutex = Mutex()
    private var dynamicRefreshJob: Job? = null

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

    fun toggleEditMode() {
        _isEditMode.value = !_isEditMode.value
    }

    /**
     * Advances the palette and writes the choice down, so the box comes back
     * in it. The repaint is immediate and the write is not waited on: the
     * palette is in-memory state, and the store only has to agree with it by
     * the next cold start.
     */
    fun cycleTheme() {
        val next = HubColors.toggleTheme()
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
     *
     * Debounced, and [focused] deliberately is not: the panel's title and
     * year are a text swap and should track the remote exactly, but each
     * distinct URL here is a full-screen `w1280` decode. Sweeping a row used
     * to queue one per card passed over, which is the single heaviest thing
     * the home screen did.
     */
    val focusedBackdropUrl: StateFlow<String?> = _focused
        .debounce { if (it == null) 0L else FANART_SETTLE_MS }
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

    val focusedDetail: StateFlow<com.mdblisthub.tv.core.model.MediaDetail?> = _focused
        .debounce { if (it == null) 0L else FANART_SETTLE_MS }
        .flatMapLatest { item ->
            if (item == null || item.tmdbId <= 0) {
                flowOf(null)
            } else {
                graph.media.observeDetail(item.type, item.tmdbId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * "Porque você assistiu" — built once per visit, not persisted: unlike
     * the mdblist rows above, TMDB's recommendations have nothing worth
     * caching in Room for, and the five seeds are cheap to re-derive from
     * whatever "Last Watched" looks like right now.
     */
    private val _becauseYouWatched = MutableStateFlow<List<RecommendationRow>>(emptyList())
    val becauseYouWatched: StateFlow<List<RecommendationRow>> = _becauseYouWatched.asStateFlow()

    init {
        refreshDynamicRows()
        viewModelScope.launch {
            _becauseYouWatched.value = graph.recommendations.becauseYouWatched()
        }
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

    fun onFocused(item: MediaItem) {
        _focused.value = item
        // Warming the detail on focus is what makes opening a title feel
        // instant: by the time the user presses OK, it is already in Room.
        if (item.tmdbId > 0) graph.prefetcher.prefetch(item.type, item.tmdbId)
    }

    fun itemsForCatalog(catalog: AddonCatalog): StateFlow<List<MediaItem>> =
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
    }

    private fun catalogCacheKey(catalog: AddonCatalog): String =
        "${catalog.addonBase.trimEnd('/')}\u0000${catalog.key}"
}
