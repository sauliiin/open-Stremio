package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.CachePolicy
import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.mapper.buildDetailEntity
import com.mdblisthub.tv.core.data.mapper.toDomain
import com.mdblisthub.tv.core.data.mapper.toEntity
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.model.Episode
import com.mdblisthub.tv.core.model.LandscapeArtwork
import com.mdblisthub.tv.core.model.MediaDetail
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.TmdbImages
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.FanartTvApi
import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.OmdbApi
import com.mdblisthub.tv.core.network.TmdbApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One title, assembled from three APIs and kept in Room.
 *
 * `observeDetail` never touches the network — it emits the cached row, or null
 * when there is none. `ensureDetail` is what fills it, and the workers call
 * the same path ahead of time so that by the time someone opens a title the
 * answer is usually already sitting in the database.
 */
class MediaRepository(
    private val tmdbApi: TmdbApi,
    private val mdblistApi: MdblistApi,
    private val omdbApi: OmdbApi,
    private val fanartTvApi: FanartTvApi,
    private val session: SessionStore,
    private val database: HubDatabase,
) {
    private val mediaDao = database.mediaDao()
    private val imdbToTmdb = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun observeDetail(type: MediaType, tmdbId: Int): Flow<MediaDetail?> =
        mediaDao.observeDetail(tmdbId, type.mdblist).map { it?.toDomain() }

    /**
     * The card row, straight from Room and without ever touching the network.
     *
     * This exists so playback does not have to wait on [ensureDetail]. The
     * addons are indexed by IMDb id and the decoy check needs a runtime —
     * both of which the list sync already wrote here, for free, while the
     * detail row costs three API calls. Null simply means the title arrived by
     * some path that never cached a card, and the caller falls back to detail.
     */
    suspend fun cachedCard(type: MediaType, tmdbId: Int): MediaItem? =
        mediaDao.card(tmdbId, type.mdblist)?.toDomain()

    /** The cached detail row without subscribing, for the same reason. */
    suspend fun cachedDetail(type: MediaType, tmdbId: Int): MediaDetail? =
        mediaDao.detail(tmdbId, type.mdblist)?.toDomain()

    /**
     * Resolves only what a visible Primefly card needs.
     *
     * This deliberately avoids MDBList, OMDb, cast, reviews and the rest of
     * full detail hydration. Every composed card can call it immediately
     * without focus turning a row of artwork into a serial metadata crawl.
     */
    suspend fun resolveLandscapeArtwork(item: MediaItem): LandscapeArtwork {
        item.landscapeUrl?.let { return LandscapeArtwork(it, item.backdropUrl) }

        val resolvedTmdbId = item.tmdbId.takeIf { it > 0 }
            ?: item.imdbId?.let { resolveImdb(item.type, it).getOrNull() }
            ?: return LandscapeArtwork(backdropUrl = item.backdropUrl)

        val cached = mediaDao.detail(resolvedTmdbId, item.type.mdblist)
        if (cached?.landscapeUrl != null ||
            cached != null && cached.fetchedAt >= LANDSCAPE_ART_SELECTION_V2_MS
        ) {
            return LandscapeArtwork(
                landscapeUrl = cached.landscapeUrl,
                backdropUrl = cached.backdropUrl ?: item.backdropUrl,
            )
        }

        return runCatching {
            val tmdb = tmdbApi.detail(
                type = item.type.tmdb,
                tmdbId = resolvedTmdbId,
                apiKey = ApiConfig.TMDB_KEY,
                language = ApiConfig.LANGUAGE,
                append = ARTWORK_APPEND,
                imageLanguage = TmdbApi.IMAGE_LANGUAGES,
                videoLanguage = TmdbApi.VIDEO_LANGUAGES,
            )
            val tmdbLandscape = bestTmdbLandscape(tmdb.images)
            val fanartId = if (item.type == MediaType.SHOW) tmdb.externalIds?.tvdbId else resolvedTmdbId
            val fanartLandscape = if (tmdbLandscape == null) {
                fanartId?.takeIf { it > 0 }?.let { getFanartLandscapeUrl(item.type, it) }
            } else {
                null
            }
            val artwork = LandscapeArtwork(
                landscapeUrl = tmdbLandscape ?: fanartLandscape,
                backdropUrl = TmdbImages.url(tmdb.backdropPath, TmdbImages.BACKDROP_FANART)
                    ?: cached?.backdropUrl
                    ?: item.backdropUrl,
            )
            mediaDao.updateArtwork(
                resolvedTmdbId,
                item.type.mdblist,
                artwork.landscapeUrl,
                artwork.backdropUrl,
            )
            mediaDao.updateDetailArtwork(
                resolvedTmdbId,
                item.type.mdblist,
                artwork.landscapeUrl,
                artwork.backdropUrl,
            )
            artwork
        }.getOrElse {
            LandscapeArtwork(
                landscapeUrl = cached?.landscapeUrl,
                backdropUrl = cached?.backdropUrl ?: item.backdropUrl,
            )
        }
    }

    fun observeEpisodes(showTmdbId: Int, seasonNumber: Int): Flow<List<Episode>> =
        mediaDao.observeEpisodes(showTmdbId, seasonNumber).map { rows -> rows.map { it.toDomain() } }

    suspend fun getFanartBackdropUrl(type: MediaType, tmdbId: Int): String? = runCatching {
        val mediaTypeStr = if (type == MediaType.SHOW) "tv" else "movies"
        val response = fanartTvApi.getArt(mediaTypeStr, tmdbId)
        val backgrounds = if (type == MediaType.SHOW) {
            response.showbackground
        } else {
            response.moviebackground
        }
        
        backgrounds
            ?.filter { it.url?.startsWith("http") == true }
            ?.maxByOrNull { it.likes?.toIntOrNull() ?: 0 }
            ?.url
    }.getOrNull()

    /**
     * Artwork for a 16:9 shelf card. Fanart's `thumb` collections are authored
     * for this exact shape. Generic Fanart backgrounds are deliberately not
     * accepted here — they are backdrops, not card art.
     */
    private suspend fun getFanartLandscapeUrl(type: MediaType, fanartId: Int): String? = runCatching {
        val mediaTypeStr = if (type == MediaType.SHOW) "tv" else "movies"
        val response = fanartTvApi.getArt(mediaTypeStr, fanartId)
        val thumbs = if (type == MediaType.SHOW) response.tvthumb else response.moviethumb
        bestFanartUrl(thumbs)
    }.getOrNull()

    /** Resolves Stremio catalog cards, which are commonly identified only by IMDb ID. */
    suspend fun resolveImdb(type: MediaType, imdbId: String): Result<Int> = runCatching {
        require(imdbId.startsWith("tt", ignoreCase = true)) { "O catálogo não forneceu um IMDb ID válido." }
        val cacheKey = "${type.mdblist}:$imdbId"
        imdbToTmdb[cacheKey]
            ?: mediaDao.tmdbIdForImdb(imdbId, type.mdblist)
            ?: tmdbApi.findByImdb(
                imdbId = imdbId,
                apiKey = ApiConfig.TMDB_KEY,
                language = ApiConfig.LANGUAGE,
            ).let { result ->
                val id = if (type == MediaType.SHOW) {
                    result.tvResults.firstOrNull()?.id
                } else {
                    result.movieResults.firstOrNull()?.id
                }
                requireNotNull(id?.takeIf { it > 0 }) { "Não encontrei este título no TMDB." }
            }.also { imdbToTmdb[cacheKey] = it }
    }

    /**
     * Background warming — the prefetcher on card focus, and the worker in
     * bulk. A degraded row is retried, but only after
     * [CachePolicy.PARTIAL_DETAIL_MS], so sweeping the remote across a row of
     * rate-limited titles does not turn every focus into another failed call.
     */
    suspend fun ensureDetail(
        type: MediaType,
        tmdbId: Int,
        force: Boolean = false,
    ): Result<Unit> = runCatching {
        val cached = mediaDao.detail(tmdbId, type.mdblist)
        val maxAge = if (cached?.metadataComplete == false) {
            CachePolicy.PARTIAL_DETAIL_MS
        } else {
            CachePolicy.DETAIL_MS
        }
        if (!force && cached != null &&
            cached.fetchedAt >= LANDSCAPE_ART_SELECTION_V2_MS &&
            !CachePolicy.isStale(cached.fetchedAt, maxAge)
        ) {
            return@runCatching
        }
        hydrate(type, tmdbId)
    }

    /**
     * What a screen the user is actually looking at asks for.
     *
     * Same cache rules for a row that came back whole, but a degraded one is
     * completed *now* rather than after the backoff above — the common way to
     * reach a detail screen is to focus a card and press OK a second later,
     * which is well inside that window, and waiting it out is exactly how a
     * title ends up on screen with no ratings or reviews.
     */
    suspend fun ensureCompleteDetail(type: MediaType, tmdbId: Int): Result<Unit> = runCatching {
        val cached = mediaDao.detail(tmdbId, type.mdblist)
        if (cached != null &&
            cached.metadataComplete &&
            cached.fetchedAt >= LANDSCAPE_ART_SELECTION_V2_MS &&
            !CachePolicy.isStale(cached.fetchedAt, CachePolicy.DETAIL_MS)
        ) {
            return@runCatching
        }
        hydrate(type, tmdbId)
    }

    /**
     * The expensive path, and the one the metadata worker runs in bulk.
     *
     * TMDB goes first because it resolves the IMDb id OMDb is keyed by;
     * mdblist and OMDb then run together. Both are allowed to fail — a title
     * with no aggregated ratings still has a detail screen worth showing —
     * but the row records that they did, so the failure is retried instead of
     * being cached as the truth for a week.
     */
    suspend fun hydrate(type: MediaType, tmdbId: Int) = coroutineScope {
        val tmdb = tmdbApi.detail(
            type = type.tmdb,
            tmdbId = tmdbId,
            apiKey = ApiConfig.TMDB_KEY,
            language = ApiConfig.LANGUAGE,
            append = TmdbApi.DETAIL_APPEND,
            imageLanguage = TmdbApi.IMAGE_LANGUAGES,
            videoLanguage = TmdbApi.VIDEO_LANGUAGES,
        )

        val imdbId = tmdb.externalIds?.imdbId
        val apiKey = session.currentKey()

        // `Result`, not `getOrNull()`: "the call failed" and "the call
        // answered with nothing" produce the same null but mean opposite
        // things to the cache — one is worth retrying, the other is the
        // title's actual truth.
        val info = async {
            if (apiKey.isBlank()) Result.success(null)
            // `review`, singular: mdblist answers with a `reviews` array only
            // for that exact spelling. `reviews` or `ratings,reviews` are
            // accepted with a 200 and simply no reviews in the body.
            else runCatching { mdblistApi.info(type.mdblist, tmdbId, apiKey, "review") }
        }
        val omdb = async {
            if (imdbId.isNullOrBlank()) Result.success(null)
            else runCatching { omdbApi.byImdb(ApiConfig.OMDB_KEY, imdbId, "full") }
        }
        // Fanart.tv keys films by TMDB id, but series by TVDB id.
        val fanartId = if (type == MediaType.SHOW) tmdb.externalIds?.tvdbId else tmdbId
        val fanartLandscape = async {
            fanartId?.takeIf { it > 0 }?.let { getFanartLandscapeUrl(type, it) }
        }

        val infoResult = info.await()
        val omdbResult = omdb.await()

        // A language-tagged TMDB backdrop generally carries the movie's title
        // or campaign treatment and works as card art. Untagged imagery is a
        // plain backdrop and therefore belongs only to the final fallback.
        val tmdbLandscape = bestTmdbLandscape(tmdb.images)

        val entity = buildDetailEntity(
            type = type,
            tmdbId = tmdbId,
            tmdb = tmdb,
            info = infoResult.getOrNull(),
            omdb = omdbResult.getOrNull(),
            now = System.currentTimeMillis(),
            metadataComplete = infoResult.isSuccess && omdbResult.isSuccess,
        ).let { detail ->
            // Explicit order requested by Primefly: TMDB landscape collection,
            // Fanart.tv landscape, then the title's generic backdrop field.
            detail.copy(landscapeUrl = tmdbLandscape ?: fanartLandscape.await())
        }
        mediaDao.upsertDetail(entity)
    }

    suspend fun ensureEpisodes(
        showTmdbId: Int,
        seasonNumber: Int,
        force: Boolean = false,
    ): Result<Unit> = runCatching {
        val fetchedAt = mediaDao.episodesFetchedAt(showTmdbId, seasonNumber)
        if (!force && !CachePolicy.isStale(fetchedAt, CachePolicy.EPISODES_MS)) return@runCatching

        val now = System.currentTimeMillis()
        val season = tmdbApi.season(showTmdbId, seasonNumber, ApiConfig.TMDB_KEY, ApiConfig.LANGUAGE)
        mediaDao.upsertEpisodes(season.episodes.map { it.toEntity(showTmdbId, now) })
    }
}

/** Forces pre-fix cached details to choose card artwork again exactly once. */
private const val LANDSCAPE_ART_SELECTION_V2_MS = 1_786_478_867_150L
private const val ARTWORK_APPEND = "external_ids,images"

private fun bestTmdbLandscape(
    images: com.mdblisthub.tv.core.network.dto.TmdbImagesDto?,
): String? = listOf("pt", "en").firstNotNullOfOrNull { language ->
    images?.backdrops
        ?.filter { it.filePath.isNotBlank() && it.language == language }
        ?.maxByOrNull { it.voteAverage }
        ?.let { TmdbImages.url(it.filePath, TmdbImages.BACKDROP_FANART) }
}

private fun bestFanartUrl(images: List<com.mdblisthub.tv.core.network.FanartTvImage>?): String? =
    images
        ?.filter { it.url?.startsWith("http") == true }
        ?.maxByOrNull { it.likes?.toIntOrNull() ?: 0 }
        ?.url
