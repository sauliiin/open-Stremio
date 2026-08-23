package com.mdblisthub.tv.core.data.repository.source

import com.mdblisthub.tv.core.model.CoreText
import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.TraktTokenStore
import com.mdblisthub.tv.core.data.SimklTokenStore
import com.mdblisthub.tv.core.model.MdblistHomeFeedItem
import com.mdblisthub.tv.core.model.MdblistHomeFeedKeys
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.TmdbImages
import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.TraktApi
import com.mdblisthub.tv.core.network.SimklApi
import com.mdblisthub.tv.core.network.dto.BucketEntryDto
import com.mdblisthub.tv.core.network.dto.BucketTitleDto
import com.mdblisthub.tv.core.network.dto.MdbItemDto
import com.mdblisthub.tv.core.network.dto.TraktTitleDto
import com.mdblisthub.tv.core.network.dto.TraktUpNextItemDto
import com.mdblisthub.tv.core.network.dto.UpNextItemDto
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where the four account-owned home rows come from.
 *
 * Network only, and deliberately artwork-free: both implementations hand back
 * cards carrying ids and a title, and `HomeFeedsRepository` runs the one
 * artwork pass over them. That is what keeps a poster looking the same
 * whichever provider filled the row.
 */
interface HomeFeedSource {

    /**
     * Loads all four feeds, keyed by [MdblistHomeFeedKeys]. A feed the source
     * could not fetch is simply absent from the map rather than empty, so the
     * repository leaves the previous contents of that row alone instead of
     * blanking it over one failed request.
     */
    suspend fun load(limit: Int): Map<String, List<MdblistHomeFeedItem>>
}

class MdblistHomeFeedSource(
    private val api: MdblistApi,
    private val session: SessionStore,
) : HomeFeedSource {

    override suspend fun load(limit: Int): Map<String, List<MdblistHomeFeedItem>> {
        val apiKey = session.currentKey()
        if (apiKey.isBlank()) return emptyMap()

        // Twice the row length for the two feeds that collapse duplicates: a
        // collection lists a series once per collected episode and the watched
        // sync once per play, so asking for exactly [limit] rows there yields
        // far fewer than [limit] distinct cards.
        val wide = limit * 2

        return coroutineScope {
            listOf(
                async {
                    runCatching {
                        MdblistHomeFeedKeys.UP_NEXT to
                            api.upNext(apiKey, limit = limit).items
                                .mapNotNull(UpNextItemDto::toFeedItem)
                    }.getOrNull()
                },
                async {
                    runCatching {
                        MdblistHomeFeedKeys.RECENTLY_ADDED to api.recentlyAdded(apiKey, limit = wide)
                            .sortedByDescending { it.collectedAt.orEmpty() }
                            .mapNotNull { it.toFeedItem() }
                            .distinctBy { it.media.key }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        MdblistHomeFeedKeys.WATCHLIST to api.watchlist(apiKey, limit = limit)
                            .sortedByDescending { it.watchlistAt.orEmpty() }
                            .mapNotNull { it.toFeedItem() }
                            .distinctBy { it.media.key }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        val watched = api.recentlyWatched(apiKey, limit = wide)
                        MdblistHomeFeedKeys.RECENTLY_WATCHED to
                            (
                                watched.movies.map { it to MediaType.MOVIE } +
                                    watched.shows.map { it to MediaType.SHOW }
                                )
                                .sortedByDescending { (entry, _) -> entry.lastWatchedAt.orEmpty() }
                                .mapNotNull { (entry, type) -> entry.toFeedItem(type) }
                                .distinctBy { it.media.key }
                    }.getOrNull()
                },
            ).awaitAll().filterNotNull().toMap()
        }
    }
}

/**
 * Trakt.
 *
 * Every row here is two calls merged, because Trakt keeps films and series on
 * separate endpoints and offers nothing like mdblist's `unified=true`. The
 * merge is by timestamp — `listed_at`, `collected_at`, `watched_at` — which is
 * the same ordering the mdblist rows already impose locally, so the two
 * providers produce rows in the same order for the same account.
 */
class TraktHomeFeedSource(
    private val api: TraktApi,
    private val tokens: TraktTokenStore,
) : HomeFeedSource {

    override suspend fun load(limit: Int): Map<String, List<MdblistHomeFeedItem>> {
        if (!tokens.isLinked()) return emptyMap()

        // Same reasoning as the mdblist source: a collection and a play
        // history both repeat a series, so those two ask for more rows than
        // the feed shows and let the de-duplication below do the trimming.
        val wide = limit * 2

        return coroutineScope {
            listOf(
                async {
                    runCatching {
                        MdblistHomeFeedKeys.UP_NEXT to
                            api.upNext(limit = limit).mapNotNull(TraktUpNextItemDto::toFeedItem)
                    }.getOrNull()
                },
                async {
                    runCatching {
                        val movies = api.collection("movies", limit = wide)
                        val shows = api.collection("shows", limit = wide)
                        MdblistHomeFeedKeys.RECENTLY_ADDED to (movies + shows)
                            .sortedByDescending { it.addedAt.orEmpty() }
                            .mapNotNull {
                                it.movie?.toFeedItem(MediaType.MOVIE)
                                    ?: it.show?.toFeedItem(MediaType.SHOW)
                            }
                            .distinctBy { it.media.key }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        val movies = api.watchlist("movies", limit = limit)
                        val shows = api.watchlist("shows", limit = limit)
                        MdblistHomeFeedKeys.WATCHLIST to (movies + shows)
                            .sortedByDescending { it.listedAt.orEmpty() }
                            .mapNotNull {
                                it.movie?.toFeedItem(MediaType.MOVIE)
                                    ?: it.show?.toFeedItem(MediaType.SHOW)
                            }
                            .distinctBy { it.media.key }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        // `episodes`, not `shows`: the history records one row
                        // per play, and a series' plays are its episodes. Each
                        // row carries the show it belongs to, which is the card
                        // this feed shows.
                        val movies = api.history("movies", limit = wide)
                        val episodes = api.history("episodes", limit = wide)
                        MdblistHomeFeedKeys.RECENTLY_WATCHED to (movies + episodes)
                            .sortedByDescending { it.watchedAt.orEmpty() }
                            .mapNotNull {
                                it.movie?.toFeedItem(MediaType.MOVIE)
                                    ?: it.show?.toFeedItem(MediaType.SHOW)
                            }
                            .distinctBy { it.media.key }
                    }.getOrNull()
                },
            ).awaitAll().filterNotNull().toMap()
        }
    }
}

class SimklHomeFeedSource(private val api: SimklApi, private val tokens: SimklTokenStore) : HomeFeedSource {
    override suspend fun load(limit: Int): Map<String, List<MdblistHomeFeedItem>> {
        if (!tokens.isLinked()) return emptyMap()
        val plannedMovies = api.items("movies", "plantowatch", "full")
        val plannedShows = api.items("shows", "plantowatch", "full")
        val completedMovies = api.items("movies", "completed", "full")
        val completedShows = api.items("shows", "completed", "full")
        val watching = api.items("shows", "watching", "full", nextWatchInfo = "yes")
        fun entries(root: JsonObject) = listOf("movies", "shows", "anime")
            .flatMap { (root[it] as? JsonArray).orEmpty() }
        fun feed(roots: List<JsonObject>, sortField: String) = roots
            .flatMap(::entries)
            .mapNotNull { el ->
            val obj = el.jsonObject
            val movie = obj["movie"] as? JsonObject
            val show = obj["show"] as? JsonObject
            val title = show ?: movie ?: return@mapNotNull null
            val tmdb = title["ids"]?.jsonObject?.get("tmdb")?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            val item = MdblistHomeFeedItem(MediaItem(tmdbId = tmdb, type = if (show != null) MediaType.SHOW else MediaType.MOVIE,
                title = title["title"]?.jsonPrimitive?.content ?: CoreText.untitled,
                imdbId = title["ids"]?.jsonObject?.get("imdb")?.jsonPrimitive?.content,
                year = title["year"]?.jsonPrimitive?.content?.toIntOrNull()))
            item to obj[sortField]?.jsonPrimitive?.content.orEmpty()
        }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.media.key }
            .take(limit)
        val upNext = entries(watching).mapNotNull { el ->
            val obj = el.jsonObject
            val show = obj["show"] as? JsonObject ?: return@mapNotNull null
            val info = obj["next_to_watch_info"] as? JsonObject ?: return@mapNotNull null
            val season = info["season"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            val episode = info["episode"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            val tmdb = show["ids"]?.jsonObject?.get("tmdb")?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            val item = MdblistHomeFeedItem(MediaItem(tmdb, MediaType.SHOW, episodeLabel(show["title"]?.jsonPrimitive?.content.orEmpty(), season, episode, info["title"]?.jsonPrimitive?.content),
                imdbId = show["ids"]?.jsonObject?.get("imdb")?.jsonPrimitive?.content), season, episode)
            item to obj["last_watched_at"]?.jsonPrimitive?.content.orEmpty()
        }.sortedByDescending { it.second }.map { it.first }.take(limit)
        return mapOf(
            MdblistHomeFeedKeys.UP_NEXT to upNext,
            MdblistHomeFeedKeys.WATCHLIST to feed(
                listOf(plannedMovies, plannedShows),
                "added_to_watchlist_at",
            ),
            MdblistHomeFeedKeys.RECENTLY_WATCHED to feed(
                listOf(completedMovies, completedShows, watching),
                "last_watched_at",
            ),
        )
    }
}

// ------------------------------------------------------------ mdblist mappers

private fun UpNextItemDto.toFeedItem(): MdblistHomeFeedItem? {
    val show = show ?: return null
    val next = nextEpisode ?: return null
    val tmdbId = show.ids?.tmdb ?: return null
    val season = next.season ?: return null
    val episode = next.episode ?: return null
    return MdblistHomeFeedItem(
        media = MediaItem(
            tmdbId = tmdbId,
            type = MediaType.SHOW,
            title = episodeLabel(show.title, season, episode, next.title),
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
            title = title.orEmpty().ifBlank { CoreText.untitled },
            imdbId = ids?.imdb,
            year = year,
            posterUrl = TmdbImages.upscale(poster),
            runtimeMinutes = runtime?.takeIf { it > 0 },
        ),
    )
}

// -------------------------------------------------------------- trakt mappers

/**
 * No poster and no score, unlike the mdblist mappers above: Trakt's payload
 * carries neither in the shape this app reads. Both are filled in by the
 * artwork pass in `HomeFeedsRepository`, from the TMDB detail every card here
 * already has the id for.
 */
private fun TraktTitleDto.toFeedItem(type: MediaType): MdblistHomeFeedItem? {
    val tmdbId = ids?.tmdb ?: return null
    return MdblistHomeFeedItem(
        MediaItem(
            tmdbId = tmdbId,
            type = type,
            title = title.orEmpty().ifBlank { CoreText.untitled },
            imdbId = ids?.imdb,
            year = year,
            runtimeMinutes = runtime?.takeIf { it > 0 },
        ),
    )
}

private fun TraktUpNextItemDto.toFeedItem(): MdblistHomeFeedItem? {
    val show = show ?: return null
    val next = progress?.nextEpisode ?: return null
    val tmdbId = show.ids?.tmdb ?: return null
    val season = next.season ?: return null
    // Trakt's name for what mdblist calls `episode`.
    val episode = next.number ?: return null
    return MdblistHomeFeedItem(
        media = MediaItem(
            tmdbId = tmdbId,
            type = MediaType.SHOW,
            title = episodeLabel(show.title.orEmpty(), season, episode, next.title),
            imdbId = show.ids?.imdb,
            year = show.year,
            runtimeMinutes = next.runtime ?: show.runtime,
        ),
        season = season,
        episode = episode,
    )
}

/** `Série • T2 E5 • Título do episódio` — the Up Next card's one line. */
private fun episodeLabel(
    show: String,
    season: Int,
    episode: Int,
    episodeTitle: String?,
): String = buildString {
    append(show)
    append(" • T")
    append(season)
    append(" E")
    append(episode)
    episodeTitle?.takeIf { it.isNotBlank() }?.let {
        append(" • ")
        append(it)
    }
}
