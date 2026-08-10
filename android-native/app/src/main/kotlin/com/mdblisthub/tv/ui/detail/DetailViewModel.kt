package com.mdblisthub.tv.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.CastMember
import com.mdblisthub.tv.core.model.Episode
import com.mdblisthub.tv.core.model.LibraryBucket
import com.mdblisthub.tv.core.model.MediaDetail
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.PersonSummary
import com.mdblisthub.tv.core.model.WikipediaLookup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which of the three mdblist buckets this title sits in, right now. */
data class LibraryState(
    val watchlist: Boolean = false,
    val collection: Boolean = false,
    val watched: Boolean = false,
) {
    operator fun get(bucket: LibraryBucket): Boolean = when (bucket) {
        LibraryBucket.WATCHLIST -> watchlist
        LibraryBucket.COLLECTION -> collection
        LibraryBucket.WATCHED -> watched
    }
}

/** What the cast popup shows — `member` doubles as "is it open at all". */
data class CastBioState(
    val member: CastMember? = null,
    val loading: Boolean = false,
    val summary: PersonSummary? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(
    private val graph: DataGraph,
    private val type: MediaType,
    private val tmdbId: Int,
) : ViewModel() {

    val detail: StateFlow<MediaDetail?> = graph.media.observeDetail(type, tmdbId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _season = MutableStateFlow(1)
    val season: StateFlow<Int> = _season.asStateFlow()

    val episodes: StateFlow<List<Episode>> = _season
        .flatMapLatest { graph.media.observeEpisodes(tmdbId, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whole-title membership across all three buckets. A show's "watched"
     * marks the series, the same as the web build — mdblist has no
     * per-episode bucket, only scrobbling, which is what actually playing an
     * episode already reports.
     */
    val library: StateFlow<LibraryState> = combine(
        graph.library.observeMembership(LibraryBucket.WATCHLIST, tmdbId),
        graph.library.observeMembership(LibraryBucket.COLLECTION, tmdbId),
        graph.library.observeMembership(LibraryBucket.WATCHED, tmdbId),
    ) { watchlist, collection, watched -> LibraryState(watchlist, collection, watched) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    /** Which bucket has a write in flight, so its own button disables. */
    private val _pending = MutableStateFlow<Set<LibraryBucket>>(emptySet())
    val pending: StateFlow<Set<LibraryBucket>> = _pending.asStateFlow()

    private val _libraryError = MutableStateFlow<String?>(null)
    val libraryError: StateFlow<String?> = _libraryError.asStateFlow()

    private val _castBio = MutableStateFlow(CastBioState())
    val castBio: StateFlow<CastBioState> = _castBio.asStateFlow()

    init {
        viewModelScope.launch {
            // Usually a no-op: the card's focus already warmed this before the
            // user pressed OK, so the screen paints from Room straight away.
            graph.media.ensureCompleteDetail(type, tmdbId)
            if (type == MediaType.SHOW) graph.media.ensureEpisodes(tmdbId, 1)
            graph.library.refresh(LibraryBucket.WATCHLIST)
            graph.library.refresh(LibraryBucket.COLLECTION)
            graph.library.refresh(LibraryBucket.WATCHED)
        }
    }

    fun selectSeason(number: Int) {
        _season.value = number
        viewModelScope.launch { graph.media.ensureEpisodes(tmdbId, number) }
    }

    fun toggleWatchlist() = toggleBucket(LibraryBucket.WATCHLIST)
    fun toggleCollection() = toggleBucket(LibraryBucket.COLLECTION)
    fun toggleWatched() = toggleBucket(LibraryBucket.WATCHED)

    private fun toggleBucket(bucket: LibraryBucket) {
        val current = detail.value ?: return
        if (bucket in _pending.value) return

        val add = !library.value[bucket]
        _libraryError.value = null
        _pending.update { it + bucket }

        viewModelScope.launch {
            graph.library.toggle(bucket, type, tmdbId, current.imdbId, add)
                .onFailure {
                    _libraryError.value = it.message ?: "Não consegui salvar essa mudança."
                }
            _pending.update { it - bucket }
        }
    }

    /**
     * Opens the popup immediately, in a loading state, then fills it in once
     * Wikipedia answers — the name is all a cast credit carries, so there is
     * nothing faster to show first.
     */
    fun openCast(member: CastMember) {
        _castBio.value = CastBioState(member = member, loading = true)
        viewModelScope.launch {
            val result = graph.wikipedia.summaryFor(member.name)
            _castBio.update {
                // The popup may have been closed, or another member opened,
                // while this was in flight — a stale answer should not
                // reopen it or overwrite what is showing now.
                if (it.member?.id != member.id) return@update it
                when (result) {
                    is WikipediaLookup.Found -> it.copy(loading = false, summary = result.summary, error = null)
                    is WikipediaLookup.NotFound -> it.copy(
                        loading = false,
                        summary = null,
                        // The reason is shown, not just swallowed, so a real
                        // failure (network, parsing) reads differently from
                        // an actor who genuinely has no Wikipedia article.
                        error = "Não foi encontrada biografia para ${member.name} na Wikipedia",
                    )
                }
            }
        }
    }

    fun closeCast() {
        _castBio.value = CastBioState()
    }
}
