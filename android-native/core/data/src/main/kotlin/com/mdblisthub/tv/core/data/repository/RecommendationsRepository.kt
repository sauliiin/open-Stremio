package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.model.CoreText
import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.RecommendationRow
import com.mdblisthub.tv.core.model.TmdbImages
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.data.repository.source.wholeBucket
import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.TmdbApi
import com.mdblisthub.tv.core.network.dto.BucketEntryDto
import com.mdblisthub.tv.core.network.dto.BucketResponseDto
import com.mdblisthub.tv.core.network.dto.TmdbSearchResultDto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * "Destaques" — what the home hero rotates through.
     *
     * The same seeds as [becauseYouWatched], collapsed into one pool instead
     * of one row per seed, because a hero shows *titles*, not shelves: which
     * of the five watches produced a given pick is not information the hero
     * has anywhere to put, and grouping by it would only make the rotation
     * lopsided towards whichever seed happened to return the most results.
     *
     * Three filters, in the order they cost: films only, unwatched, and worth
     * featuring. The last one is the reason [TmdbSearchResultDto.voteCount]
     * exists: [MIN_SPOTLIGHT_SCORE] is a threshold on an *average*, and an
     * average of three votes is not one. Without a floor under it a 9.0 that
     * four people agreed on is indistinguishable here from a 9.0 that four
     * thousand did, and the hero would present the first as a destaque.
     *
     * A backdrop is required, not preferred. The hero is a full-bleed
     * landscape panel under a Ken Burns pan; a portrait poster stretched to
     * cover it does not read as artwork, it reads as a bug.
     *
     * Everything that survives is returned. There was a cut here — the
     * twelve best by score — and it was the wrong shape for what the hero
     * is: a rotation, not a chart. A cut only earns its place when showing
     * the rest would cost something, and the rest arrived in the same five
     * responses, cost nothing more to keep, and are the same recommendations
     * by the same rules. What the cut actually did was decide that thirty of
     * them were never worth seeing.
     */
    suspend fun spotlight(): List<MediaItem> {
        val key = session.currentKey()
        if (key.isBlank()) return emptyList()

        val watched = runCatching { watched(key) }.getOrNull().orEmpty()

        return buildSpotlight(watched) { type, tmdbId ->
            runCatching {
                tmdbApi.recommendations(type.tmdb, tmdbId, ApiConfig.TMDB_KEY, ApiConfig.LANGUAGE).results
            }.getOrNull().orEmpty()
        }
    }

    /**
     * Every watched title, most recent first.
     *
     * This used to read one page and a comment here used to claim that was
     * the whole set. It is not — see [wholeBucket] — and the difference was
     * visible: the hero recommended films the viewer had already seen,
     * because anything watched further back than the last hundred *entries*
     * was simply not in the set being filtered against.
     *
     * Memoised, and that is not premature: [becauseYouWatched] and
     * [spotlight] both need it and both run on the same Home, so without this
     * the bucket was fetched twice on every launch. One fetch behind a mutex
     * means the second caller waits for the first rather than starting its
     * own, which is what keeps paging from doubling the cost of a launch.
     */
    private suspend fun watched(apiKey: String): List<Pair<MediaType, Int>> = watchedMutex.withLock {
        val cached = watchedCache
        if (cached != null && System.currentTimeMillis() - watchedFetchedAt < WATCHED_TTL_MS) {
            return@withLock cached
        }

        val resolved = mdblistApi
            .wholeBucket("${MDBLIST_ROOT}sync/watched", apiKey)
            .watchedTitles()

        watchedCache = resolved
        watchedFetchedAt = System.currentTimeMillis()
        resolved
    }

    private val watchedMutex = Mutex()
    private var watchedCache: List<Pair<MediaType, Int>>? = null
    private var watchedFetchedAt = 0L

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

}

// --------------------------------------------------------------------------
// The part that has no account, no database and no network in it.
//
// `spotlight()` above is three things at once: a session key, a bucket read
// and a set of rules about what deserves to be featured. Only the last is
// worth pinning down in a test, and it was the one locked away — reaching it
// meant standing up a `SessionStore` (which needs a `Context`) and a
// `MediaRepository` (which needs Room), neither of which the rules touch.
// Splitting them out costs an indirection and buys the exclusion being
// provable; see `RecommendationsSpotlightTest`.
// --------------------------------------------------------------------------

/**
 * The watched bucket as ids, most recent first.
 *
 * Deliberately takes a whole [BucketResponseDto] rather than a page: the
 * caller is expected to have concatenated the pages already — see
 * `wholeBucket` — and the bug this pairing exists to prevent is precisely
 * shaping *one* page and calling it the history.
 *
 * The ordering is entirely on `last_watched_at`; see that field's own doc
 * comment for how sure that guess is.
 */
internal fun BucketResponseDto.watchedTitles(): List<Pair<MediaType, Int>> {
    val movieEntries = movies.map { it to MediaType.MOVIE }
    val showEntries = shows.map { it to MediaType.SHOW }

    return (movieEntries + showEntries)
        .sortedByDescending { (entry, _) -> entry.lastWatchedAt.orEmpty() }
        .mapNotNull { (entry, type) -> entry.tmdbId()?.let { type to it } }
}

/**
 * The destaques, given a watch history and a way to ask TMDB for neighbours.
 *
 * [recommendationsFor] is expected to swallow its own failures and answer
 * with an empty list: one seed's endpoint being down should cost that seed's
 * suggestions and nothing else.
 */
internal suspend fun buildSpotlight(
    watched: List<Pair<MediaType, Int>>,
    recommendationsFor: suspend (MediaType, Int) -> List<TmdbSearchResultDto>,
): List<MediaItem> {
    if (watched.isEmpty()) return emptyList()

    val alreadyWatched = watched.mapTo(HashSet()) { it.key() }

    val fromRecent = spotlightFrom(watched.take(SEED_ROWS), alreadyWatched, recommendationsFor)
    if (fromRecent.isNotEmpty()) return fromRecent

    // TMDB's recommendation endpoints are same-type: `tv/{id}` answers with
    // series and nothing else. So a viewer whose last five watches were all
    // episodes has just had every candidate filtered out by the films-only
    // rule below, and the hero would sit empty for someone with a watch
    // history full of films slightly further back. Reaching past the five for
    // the five most recent *films* is what the request means in that case,
    // not "show nothing".
    val movieSeeds = watched.filter { (type, _) -> type == MediaType.MOVIE }.take(SEED_ROWS)
    return spotlightFrom(movieSeeds, alreadyWatched, recommendationsFor)
}

private suspend fun spotlightFrom(
    seeds: List<Pair<MediaType, Int>>,
    alreadyWatched: Set<String>,
    recommendationsFor: suspend (MediaType, Int) -> List<TmdbSearchResultDto>,
): List<MediaItem> = coroutineScope {
    seeds
        .map { (type, tmdbId) -> async { recommendationsFor(type, tmdbId) } }
        .flatMap { it.await() }
        .filter { it.voteAverage > MIN_SPOTLIGHT_SCORE }
        .filter { it.voteCount >= MIN_SPOTLIGHT_VOTES }
        .filter { !it.backdropPath.isNullOrBlank() && !it.posterPath.isNullOrBlank() }
        .map { it.toMediaItem(MediaType.MOVIE) }
        .filter { it.type == MediaType.MOVIE }
        .filter { it.key() !in alreadyWatched }
        .distinctBy { it.tmdbId }
        // Shuffled, and no longer ranked first. The sort was there to decide
        // who made the cut; with everyone in, ordering by score and then
        // shuffling is two passes that cancel out. What is worth keeping is
        // the shuffle — it is what stops the hero opening on the same film
        // every launch, and what makes "Surpreenda-me" walk a different path
        // each session instead of the same countdown.
        .shuffled()
}

private fun BucketEntryDto.tmdbId(): Int? = movie?.ids?.tmdb ?: show?.ids?.tmdb ?: ids?.tmdb ?: id

private fun MediaItem.key() = "${type.mdblist}:$tmdbId"

private fun Pair<MediaType, Int>.key() = "${first.mdblist}:$second"

private fun TmdbSearchResultDto.toMediaItem(fallbackType: MediaType): MediaItem {
    val type = mediaType?.let { MediaType.fromTmdb(it) } ?: fallbackType
    val date = releaseDate?.takeIf { it.isNotBlank() } ?: firstAirDate?.takeIf { it.isNotBlank() }

    return MediaItem(
        tmdbId = id,
        type = type,
        title = title ?: name ?: CoreText.untitled,
        year = date?.take(4)?.toIntOrNull(),
        posterUrl = TmdbImages.url(posterPath, TmdbImages.POSTER_CARD),
        backdropUrl = TmdbImages.url(backdropPath, TmdbImages.BACKDROP_FANART),
        score = voteAverage.takeIf { it > 0 }?.let { (it * 10).roundToInt() },
    )
}

private const val MDBLIST_ROOT = "https://api.mdblist.com/"

/**
 * How long the watched set is reused for.
 *
 * Long enough to cover a Home being rebuilt — a theme change, a rotation,
 * coming back from a title — and far short of a sitting, so marking something
 * watched still takes effect without a restart.
 */
private const val WATCHED_TTL_MS = 5 * 60 * 1_000L
private const val SEED_ROWS = 5
private const val PER_ROW = 20
private const val MIN_ROW_SIZE = 4

/** "Nota superior a 6", on TMDB's own 0-10 scale. */
private const val MIN_SPOTLIGHT_SCORE = 6.0

/**
 * Votes a title needs before its average is allowed to mean anything here.
 * Low on purpose — this is a floor under noise, not a popularity gate, and a
 * genuinely obscure film with a real audience clears it comfortably.
 */
private const val MIN_SPOTLIGHT_VOTES = 50
