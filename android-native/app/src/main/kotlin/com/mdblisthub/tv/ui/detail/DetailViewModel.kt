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

/**
 * Why a watchlist/collection/watched write did not stick.
 *
 * A repository message travels as text; anything this ViewModel raises itself
 * travels as a resource id, because only the screen has a context that knows
 * the interface language.
 */
sealed interface LibraryError {
    data class Message(val text: String) : LibraryError
    data object SaveFailed : LibraryError
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

    private val _libraryError = MutableStateFlow<LibraryError?>(null)
    val libraryError: StateFlow<LibraryError?> = _libraryError.asStateFlow()

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
                .onFailure { failure ->
                    // The repository's own message when it has one, otherwise a
                    // resource id — resolving a default here would use the
                    // application context, which never sees the in-app locale.
                    _libraryError.value = failure.message
                        ?.takeIf { it.isNotBlank() }
                        ?.let(LibraryError::Message)
                        ?: LibraryError.SaveFailed
                }
            _pending.update { it - bucket }
        }
    }

    /**
     * Opens the popup immediately, in a loading state, then fills it in once
     * Wikipedia (or its TMDB fallback) answers — the credit already carries
     * both the name and the TMDB person id, so there is nothing faster to
     * show first.
     */
    fun openCast(member: CastMember) {
        _castBio.value = CastBioState(member = member, loading = true)
        viewModelScope.launch {
            val result = graph.wikipedia.summaryFor(member.id, member.name)
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
                        // Reached only once both Wikipedia and its TMDB
                        // fallback have nothing — the reason itself is not
                        // shown, just logged (see WikipediaRepository), since
                        // a real failure (network, parsing) and an actor who
                        // genuinely has no bio anywhere both read the same
                        // to the person looking at the popup.
                        error = "WIKIPEDIA_NOT_FOUND",
                    )
                }
            }
        }
    }

    fun closeCast() {
        _castBio.value = CastBioState()
    }
}
