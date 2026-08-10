package com.mdblisthub.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.TmdbImages
import com.mdblisthub.tv.core.network.dto.TmdbSearchResultDto
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async

import com.mdblisthub.tv.core.network.ApiConfig

class SearchViewModel(private val graph: DataGraph) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<MediaItem>>(emptyList())
    val results: StateFlow<List<MediaItem>> = _results.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _query
                .debounce(500)
                .distinctUntilChanged()
                .collect { q ->
                    if (q.isBlank()) {
                        _results.value = emptyList()
                    } else {
                        performSearch(q)
                    }
                }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    private suspend fun performSearch(query: String) {
        _isLoading.value = true
        try {
            val preferredLang = graph.uiPreferences.language.first()
            kotlinx.coroutines.coroutineScope {
                val searchDeferred = async {
                    graph.network.tmdb.search(
                        apiKey = ApiConfig.TMDB_KEY,
                        language = preferredLang,
                        query = query,
                        includeAdult = false,
                        page = 1
                    ).results.filter { it.mediaType == "movie" || it.mediaType == "tv" }
                }

                val keywordDeferred = async {
                    // One request, then two reads of it. This used to call the
                    // endpoint again — same key, same query — purely to take
                    // the first result when no keyword matched the search text
                    // exactly, which is the common case.
                    val keywords = graph.network.tmdb.searchKeyword(
                        apiKey = ApiConfig.TMDB_KEY,
                        query = query,
                    ).results
                    keywords.firstOrNull { it.name.equals(query, ignoreCase = true) }
                        ?: keywords.firstOrNull()
                }

                val searchResults = searchDeferred.await()
                val keyword = keywordDeferred.await()

                val discoverMovieDeferred = async {
                    if (keyword != null) {
                        graph.network.tmdb.discoverMovie(
                            apiKey = ApiConfig.TMDB_KEY,
                            language = preferredLang,
                            keywords = keyword.id.toString(),
                            page = 1
                        ).results.map { it.copy(mediaType = "movie") }
                    } else emptyList()
                }

                val discoverTvDeferred = async {
                    if (keyword != null) {
                        graph.network.tmdb.discoverTv(
                            apiKey = ApiConfig.TMDB_KEY,
                            language = preferredLang,
                            keywords = keyword.id.toString(),
                            page = 1
                        ).results.map { it.copy(mediaType = "tv") }
                    } else emptyList()
                }

                val allResults = searchResults + discoverMovieDeferred.await() + discoverTvDeferred.await()
                
                _results.value = allResults
                    .distinctBy { "${it.mediaType}_${it.id}" }
                    .map { it.toMediaItem() }
            }
        } catch (e: Exception) {
            _results.value = emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    private fun TmdbSearchResultDto.toMediaItem(): MediaItem {
        val type = if (mediaType == "movie") MediaType.MOVIE else MediaType.SHOW
        return MediaItem(
            tmdbId = id,
            type = type,
            title = title ?: name ?: "",
            imdbId = null,
            year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull(),
            // Through `TmdbImages`, not hand-built. Search was the one screen
            // still asking for w342 — double the pixels a card can paint,
            // which is precisely the cost the constant exists to avoid.
            posterUrl = TmdbImages.url(posterPath, TmdbImages.POSTER_CARD),
            backdropUrl = TmdbImages.url(backdropPath, TmdbImages.BACKDROP_FANART),
            genres = emptyList(),
            runtimeMinutes = null,
            score = (voteAverage * 10).toInt().takeIf { it > 0 }
        )
    }
}
