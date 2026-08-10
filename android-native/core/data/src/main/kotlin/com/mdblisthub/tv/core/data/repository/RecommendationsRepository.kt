package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.RecommendationRow
import com.mdblisthub.tv.core.model.TmdbImages
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.TmdbApi
import com.mdblisthub.tv.core.network.dto.BucketEntryDto
import com.mdblisthub.tv.core.network.dto.TmdbSearchResultDto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * "Porque você assistiu" — mirrors the web build's `RecommendationsService`,
 * one difference from it: seeded off mdblist's native `sync/watched` bucket
 * (the same source the "Marcar assistido" button reads and writes) instead
 * of a list named "Last Watched". That bucket exists for every mdblist
 * account — a curated list with that exact name does not — so this works
 * the same for any user, not just one with Trakt/Simkl history synced into
 * a specifically-named list.
 */
class RecommendationsRepository(
    private val mdblistApi: MdblistApi,
    private val tmdbApi: TmdbApi,
    private val media: MediaRepository,
    private val session: SessionStore,
) {
    suspend fun becauseYouWatched(): List<RecommendationRow> = coroutineScope {
        val key = session.currentKey()
        if (key.isBlank()) return@coroutineScope emptyList()

        val watched = runCatching { watched(key) }.getOrNull().orEmpty()
        if (watched.isEmpty()) return@coroutineScope emptyList()

        val alreadyWatched = watched.mapTo(HashSet()) { it.key() }
        val seeds = watched.take(SEED_ROWS)

        seeds
            .map { (type, tmdbId) -> async { rowFor(type, tmdbId, alreadyWatched) } }
            .mapNotNull { it.await() }
            .filter { it.items.size >= MIN_ROW_SIZE }
    }

    /**
     * Every watched title, most recent first.
     *
     * `sync/watched` hands back the whole set in one call, not a page — good
     * for the exclusion list (every one of these has to be filtered out of
     * every row, not just the five seeds), but it also means the ordering is
     * entirely on `last_watched_at`; see the field's own doc comment for how
     * sure that guess is.
     */
    private suspend fun watched(apiKey: String): List<Pair<MediaType, Int>> {
        val response = mdblistApi.bucket("${MDBLIST_ROOT}sync/watched", apiKey)

        val movies = response.movies.map { it to MediaType.MOVIE }
        val shows = response.shows.map { it to MediaType.SHOW }

        return (movies + shows)
            .sortedByDescending { (entry, _) -> entry.lastWatchedAt.orEmpty() }
            .mapNotNull { (entry, type) -> entry.tmdbId()?.let { type to it } }
    }

    private fun BucketEntryDto.tmdbId(): Int? = movie?.ids?.tmdb ?: show?.ids?.tmdb ?: ids?.tmdb ?: id

    /**
     * The bucket carries ids only, not a title — borrows the same detail
     * cache the "continuar assistindo" artwork fix reads, rather than a
     * second network call of its own.
     */
    private suspend fun rowFor(
        type: MediaType,
        tmdbId: Int,
        alreadyWatched: Set<String>,
    ): RecommendationRow? {
        media.ensureDetail(type, tmdbId)
        val seedTitle = media.observeDetail(type, tmdbId).first()?.title ?: return null

        val results = runCatching {
            tmdbApi.recommendations(type.tmdb, tmdbId, ApiConfig.TMDB_KEY, ApiConfig.LANGUAGE).results
        }.getOrNull() ?: return null

        val items = results
            .filter { !it.posterPath.isNullOrBlank() }
            .map { it.toMediaItem(type) }
            .filter { it.key() !in alreadyWatched }
            .take(PER_ROW)

        if (items.isEmpty()) return null
        return RecommendationRow(seedTitle = seedTitle, items = items)
    }

    private fun MediaItem.key() = "${type.mdblist}:$tmdbId"
    private fun Pair<MediaType, Int>.key() = "${first.mdblist}:$second"

    private fun TmdbSearchResultDto.toMediaItem(fallbackType: MediaType): MediaItem {
        val type = mediaType?.let { MediaType.fromTmdb(it) } ?: fallbackType
        val date = releaseDate?.takeIf { it.isNotBlank() } ?: firstAirDate?.takeIf { it.isNotBlank() }

        return MediaItem(
            tmdbId = id,
            type = type,
            title = title ?: name ?: "Sem título",
            year = date?.take(4)?.toIntOrNull(),
            posterUrl = TmdbImages.url(posterPath, TmdbImages.POSTER_CARD),
            backdropUrl = TmdbImages.url(backdropPath, TmdbImages.BACKDROP_FANART),
            score = voteAverage.takeIf { it > 0 }?.let { (it * 10).roundToInt() },
        )
    }

    private companion object {
        const val MDBLIST_ROOT = "https://api.mdblist.com/"
        const val SEED_ROWS = 5
        const val PER_ROW = 20
        const val MIN_ROW_SIZE = 4
    }
}
