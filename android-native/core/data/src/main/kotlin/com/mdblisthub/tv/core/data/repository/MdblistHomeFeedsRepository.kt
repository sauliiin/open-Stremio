package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.model.MdblistHomeFeed
import com.mdblisthub.tv.core.model.MdblistHomeFeedItem
import com.mdblisthub.tv.core.model.MdblistHomeFeedKeys
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.TmdbImages
import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.dto.BucketEntryDto
import com.mdblisthub.tv.core.network.dto.BucketTitleDto
import com.mdblisthub.tv.core.network.dto.FirebaseCatalogPreferenceDto
import com.mdblisthub.tv.core.network.dto.MdbItemDto
import com.mdblisthub.tv.core.network.dto.UpNextItemDto
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** Account-native MDBList rows that are not user-created lists. */
class MdblistHomeFeedsRepository(
    private val api: MdblistApi,
    private val session: SessionStore,
) {
    /** Content is account-scoped; a previous API key's rows must never bleed into a new session. */
    private val content = MutableStateFlow(OwnedFeedContent())

    /**
     * Feed customizations deliberately share the catalog-preference envelope.
     * Both are stable string-keyed home rows, so this gives these feeds Google
     * cloud sync without introducing a second, incompatible Firebase shape.
     */
    fun observeFeeds(): Flow<List<MdblistHomeFeed>> =
        combine(session.apiKey, session.catalogPreferences, content) { apiKey, preferences, content ->
            if (apiKey.isBlank()) return@combine emptyList()
            val items = content.items.takeIf { content.ownerKey == apiKey }.orEmpty()
            val byKey = preferences.associateBy { it.key }
            DEFAULTS.mapNotNull { default ->
                val preference = byKey[default.key]
                if (preference?.deleted == true) return@mapNotNull null
                default.copy(
                    name = preference?.name ?: default.originalName,
                    position = preference?.position,
                    hidden = preference?.hidden ?: false,
                    items = items[default.key].orEmpty(),
                )
            }
        }

    suspend fun refresh() {
        val apiKey = session.currentKey()
        if (apiKey.isBlank()) return

        coroutineScope {
            listOf(
                async {
                    runCatching {
                        MdblistHomeFeedKeys.UP_NEXT to
                            api.upNext(apiKey).items.mapNotNull(UpNextItemDto::toFeedItem)
                    }.getOrNull()
                },
                async {
                    runCatching {
                        MdblistHomeFeedKeys.RECENTLY_ADDED to api.recentlyAdded(apiKey)
                            .sortedByDescending { it.collectedAt.orEmpty() }
                            .mapNotNull { it.toFeedItem() }
                            .distinctBy { it.media.key }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        MdblistHomeFeedKeys.WATCHLIST to api.watchlist(apiKey)
                            .sortedByDescending { it.watchlistAt.orEmpty() }
                            .mapNotNull { it.toFeedItem() }
                            .distinctBy { it.media.key }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        val watched = api.recentlyWatched(apiKey)
                        MdblistHomeFeedKeys.RECENTLY_WATCHED to
                            (watched.movies.map { it to MediaType.MOVIE } +
                                watched.shows.map { it to MediaType.SHOW })
                                .sortedByDescending { (entry, _) -> entry.lastWatchedAt.orEmpty() }
                                .mapNotNull { (entry, type) -> entry.toFeedItem(type) }
                                .distinctBy { it.media.key }
                                .take(FEED_LIMIT)
                    }.getOrNull()
                },
            ).awaitAll().filterNotNull().forEach { (key, items) ->
                // The account may have changed while four network requests
                // were in flight. Discard those late answers instead of ever
                // publishing them under the new account.
                if (session.currentKey() != apiKey) return@forEach
                val previous = content.value
                    .takeIf { it.ownerKey == apiKey }
                    ?.items
                    .orEmpty()
                content.value = OwnedFeedContent(
                    ownerKey = apiKey,
                    items = previous + (key to items.take(FEED_LIMIT)),
                )
            }
        }
    }

    suspend fun toggleVisibility(feed: MdblistHomeFeed, hidden: Boolean) = runCatching {
        updatePreference(feed) { it.copy(hidden = hidden) }
    }

    suspend fun rename(feed: MdblistHomeFeed, rawName: String) = runCatching {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "O nome não pode ficar vazio." }
        updatePreference(feed) {
            it.copy(name = name.takeUnless { value -> value == feed.originalName })
        }
    }

    suspend fun delete(feed: MdblistHomeFeed) = runCatching {
        updatePreference(feed) { it.copy(deleted = true) }
    }

    private suspend fun updatePreference(
        feed: MdblistHomeFeed,
        transform: (FirebaseCatalogPreferenceDto) -> FirebaseCatalogPreferenceDto,
    ) {
        val current = session.currentCatalogPreferences()
        val previous = current.firstOrNull { it.key == feed.key }
            ?: FirebaseCatalogPreferenceDto(
                key = feed.key,
                position = feed.position,
                hidden = feed.hidden,
            )
        session.saveCatalogPreferences(
            current.filterNot { it.key == feed.key } + transform(previous),
        )
    }

    private companion object {
        const val FEED_LIMIT = 50

        val DEFAULTS = listOf(
            MdblistHomeFeed(
                key = MdblistHomeFeedKeys.UP_NEXT,
                name = "Up Next",
                originalName = "Up Next",
            ),
            MdblistHomeFeed(
                key = MdblistHomeFeedKeys.RECENTLY_ADDED,
                name = "Recently Added",
                originalName = "Recently Added",
            ),
            MdblistHomeFeed(
                key = MdblistHomeFeedKeys.WATCHLIST,
                name = "Watchlist",
                originalName = "Watchlist",
            ),
            MdblistHomeFeed(
                key = MdblistHomeFeedKeys.RECENTLY_WATCHED,
                name = "Recently Watched",
                originalName = "Recently Watched",
            ),
        )
    }
}

private data class OwnedFeedContent(
    val ownerKey: String = "",
    val items: Map<String, List<MdblistHomeFeedItem>> = emptyMap(),
)

private fun UpNextItemDto.toFeedItem(): MdblistHomeFeedItem? {
    val show = show ?: return null
    val next = nextEpisode ?: return null
    val tmdbId = show.ids?.tmdb ?: return null
    val season = next.season ?: return null
    val episode = next.episode ?: return null
    val episodeLabel = buildString {
        append(show.title)
        append(" • T")
        append(season)
        append(" E")
        append(episode)
        next.title?.takeIf { it.isNotBlank() }?.let {
            append(" • ")
            append(it)
        }
    }
    return MdblistHomeFeedItem(
        media = MediaItem(
            tmdbId = tmdbId,
            type = MediaType.SHOW,
            title = episodeLabel,
            imdbId = show.ids?.imdb,
            year = show.year,
            posterUrl = TmdbImages.url(show.poster, TmdbImages.POSTER_CARD),
            runtimeMinutes = next.runtime,
        ),
        season = season,
        episode = episode,
    )
}

private fun MdbItemDto.toFeedItem(): MdblistHomeFeedItem? {
    val tmdbId = ids?.tmdb ?: id
    val imdb = imdbId ?: ids?.imdb
    if (tmdbId <= 0 && imdb == null) return null
    return MdblistHomeFeedItem(
        MediaItem(
            tmdbId = tmdbId,
            type = MediaType.fromMdblist(mediatype),
            title = title,
            imdbId = imdb,
            year = releaseYear,
            posterUrl = TmdbImages.upscale(poster),
            genres = genre.orEmpty(),
            runtimeMinutes = runtime?.takeIf { it > 0 },
            score = ratings?.firstOrNull { it.source == "imdb" }?.value
                ?.let { (it * 10).roundToInt() },
        ),
    )
}

private fun BucketEntryDto.toFeedItem(type: MediaType): MdblistHomeFeedItem? {
    val title = if (type == MediaType.MOVIE) movie else show
    return title?.toFeedItem(type)
}

private fun BucketTitleDto.toFeedItem(type: MediaType): MdblistHomeFeedItem? {
    val tmdbId = ids?.tmdb ?: return null
    return MdblistHomeFeedItem(
        MediaItem(
            tmdbId = tmdbId,
            type = type,
            title = title.orEmpty().ifBlank { "Sem título" },
            imdbId = ids?.imdb,
            year = year,
            posterUrl = TmdbImages.upscale(poster),
            runtimeMinutes = runtime?.takeIf { it > 0 },
        ),
    )
}
