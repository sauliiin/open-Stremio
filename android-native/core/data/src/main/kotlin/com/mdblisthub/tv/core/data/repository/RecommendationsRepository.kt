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
        val pool = candidates(seeds)

        seeds
            .map { seed -> async { rowFor(seed, pool[seed].orEmpty(), alreadyWatched) } }
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
     * Two filters, in the order they cost: unwatched, and worth featuring.
     * The second is the reason [TmdbSearchResultDto.voteCount] exists:
     * [MIN_SPOTLIGHT_SCORE] is a threshold on an *average*, and an average of
     * three votes is not one. Without a floor under it a 9.0 that four people
     * agreed on is indistinguishable here from a 9.0 that four thousand did,
     * and the hero would present the first as a destaque.
     *
     * There used to be a third, ahead of both: films only. It cost more than
     * it looked like it did — every series TMDB suggested was discarded, so a
     * viewer who mostly watches series was shown a hero built from whatever
     * films happened to sit further back in their history. The hero has no
     * trouble presenting a série — it reads its type from the item like every
     * other screen — so the rule was removing the recommendation rather than
     * the wrong artwork.
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
        if (key.isNotBlank()) {
            val watched = runCatching { watched(key) }.getOrNull().orEmpty()
            val pool = candidates(watched.take(SEED_ROWS))
            val personalized = buildSpotlight(watched) { type, tmdbId ->
                pool[type to tmdbId].orEmpty()
            }
            if (personalized.isNotEmpty()) return personalized
        }

        val popular = runCatching {
            tmdbApi.popularMovies(ApiConfig.TMDB_KEY, ApiConfig.LANGUAGE).results
        }.getOrNull().orEmpty()
        return buildTmdbFallbackSpotlight(popular)
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
     *
     * Handed its [candidates] rather than fetching them: the hero is asking
     * for the same seeds at the same moment, and see [candidates] for why
     * that has to be one fetch and not two.
     */
    private suspend fun rowFor(
        seed: Pair<MediaType, Int>,
        candidates: List<TmdbSearchResultDto>,
        alreadyWatched: Set<String>,
    ): RecommendationRow? {
        val (type, tmdbId) = seed
        media.ensureDetail(type, tmdbId)
        val seedTitle = media.observeDetail(type, tmdbId).first()?.title ?: return null

        val items = candidates
            .filter { !it.posterPath.isNullOrBlank() }
            .map { it.toMediaItem(type) }
            .filter { it.key() !in alreadyWatched }
            .take(PER_ROW)

        if (items.isEmpty()) return null
        return RecommendationRow(seedTitle = seedTitle, items = items)
    }

    /**
     * Every seed's neighbours, fetched once per Home and shared.
     *
     * Memoised for the same reason [watched] is, and against a bill that got
     * four times larger the moment both halves of this class grew:
     * [becauseYouWatched] and [spotlight] run concurrently from `HomeViewModel`'s
     * `init`, over the same [SEED_ROWS] seeds, and each seed now costs two
     * TMDB calls rather than one. Left alone that is forty requests on a
     * launch, twenty of them asking a second time for an answer already on
     * its way — on a television box, over a connection that is frequently
     * the worst part of the room.
     *
     * The lock is held across the fetch, which is what makes the second
     * caller wait for the first rather than start its own. That costs it one
     * round trip and no more: the seeds inside are fetched in parallel, so
     * what it waits out is the slowest seed, not the sum of them.
     *
     * Keyed on nothing but the seed set's own contents, and expiring on the
     * same clock as the history it came from — a pool outliving the watch
     * list that chose its seeds would recommend against a history the viewer
     * has since added to.
     */
    private suspend fun candidates(
        seeds: List<Pair<MediaType, Int>>,
    ): Map<Pair<MediaType, Int>, List<TmdbSearchResultDto>> = candidatesMutex.withLock {
        val cached = candidatesCache
        if (cached != null &&
            System.currentTimeMillis() - candidatesFetchedAt < WATCHED_TTL_MS &&
            cached.keys.containsAll(seeds)
        ) {
            return@withLock cached
        }

        val fetched = coroutineScope {
            seeds
                .map { seed -> async { seed to neighboursOf(seed) } }
                .map { it.await() }
                .toMap()
        }

        candidatesCache = fetched
        candidatesFetchedAt = System.currentTimeMillis()
        fetched
    }

    private val candidatesMutex = Mutex()
    private var candidatesCache: Map<Pair<MediaType, Int>, List<TmdbSearchResultDto>>? = null
    private var candidatesFetchedAt = 0L

    /**
     * One seed's pool: both TMDB neighbourhoods, merged.
     *
     * The two endpoints are asked concurrently and each swallows its own
     * failure, so `/similar` being down costs its half of one seed's
     * candidates and nothing else — not the seed, and not the other nine.
     *
     * `distinctBy` keeps the first of a duplicate, and the order is not
     * incidental: a title both endpoints name is kept as the
     * recommendation, which is the stronger of the two signals.
     */
    private suspend fun neighboursOf(
        seed: Pair<MediaType, Int>,
    ): List<TmdbSearchResultDto> = coroutineScope {
        val (type, tmdbId) = seed
        val recommended = async {
            runCatching {
                tmdbApi.recommendations(type.tmdb, tmdbId, ApiConfig.TMDB_KEY, ApiConfig.LANGUAGE).results
            }.getOrNull().orEmpty()
        }
        val similar = async {
            runCatching {
                tmdbApi.similar(type.tmdb, tmdbId, ApiConfig.TMDB_KEY, ApiConfig.LANGUAGE).results
            }.getOrNull().orEmpty()
        }
        (recommended.await() + similar.await()).distinctBy { it.id }
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
    return spotlightFrom(watched.take(SEED_ROWS), alreadyWatched, recommendationsFor)
}

/** Quality-filtered global TMDB picks for guests or accounts without usable history. */
internal fun buildTmdbFallbackSpotlight(results: List<TmdbSearchResultDto>): List<MediaItem> =
    results
        .filter { it.voteAverage > MIN_SPOTLIGHT_SCORE }
        .filter { it.voteCount >= MIN_SPOTLIGHT_VOTES }
        .filter { !it.backdropPath.isNullOrBlank() && !it.posterPath.isNullOrBlank() }
        .map { it.toMediaItem(MediaType.MOVIE) }
        .filter { it.type == MediaType.MOVIE }
        .filter { it.year.isFeaturableYear() }
        .distinctBy { it.tmdbId }
        .shuffled()

private suspend fun spotlightFrom(
    seeds: List<Pair<MediaType, Int>>,
    alreadyWatched: Set<String>,
    recommendationsFor: suspend (MediaType, Int) -> List<TmdbSearchResultDto>,
): List<MediaItem> = coroutineScope {
    seeds
        // Paired with the seed's own type, and that pairing is the whole
        // reason this is not a flat `flatMap` any more. TMDB's neighbour
        // endpoints are same-type but do not always say so — `media_type` is
        // absent often enough that `toMediaItem` needs a fallback — and the
        // fallback used to be the constant `MOVIE`, which was harmless only
        // while everything that survived was a film anyway. With séries
        // allowed through, that constant would stamp `MOVIE` on a series and
        // hand the detail screen a tv id to look up as a film.
        .map { (type, tmdbId) -> async { type to recommendationsFor(type, tmdbId) } }
        .flatMap { deferred ->
            val (seedType, results) = deferred.await()
            results.map { seedType to it }
        }
        .filter { (_, result) -> result.voteAverage > MIN_SPOTLIGHT_SCORE }
        .filter { (_, result) -> result.voteCount >= MIN_SPOTLIGHT_VOTES }
        .filter { (_, result) ->
            !result.backdropPath.isNullOrBlank() && !result.posterPath.isNullOrBlank()
        }
        .map { (seedType, result) -> result.toMediaItem(seedType) }
        .filter { it.year.isFeaturableYear() }
        .filter { it.key() !in alreadyWatched }
        // On the key, not the id: TMDB numbers films and séries separately,
        // so id 550 is both a film and a series. While this was films-only
        // that could not collide; now it can, and `distinctBy { tmdbId }`
        // would silently drop whichever of the two arrived second.
        .distinctBy { it.key() }
        // Shuffled, and no longer ranked first. The sort was there to decide
        // who made the cut; with everyone in, ordering by score and then
        // shuffling is two passes that cancel out. What is worth keeping is
        // the shuffle — it is what stops the hero opening on the same film
        // every launch, and what makes "Surpreenda-me" walk a different path
        // each session instead of the same countdown.
        .shuffled()
}

private fun BucketEntryDto.tmdbId(): Int? = movie?.ids?.tmdb ?: show?.ids?.tmdb ?: ids?.tmdb ?: id

/**
 * Whether a release year is recent enough to be featured.
 *
 * A null is *not* featurable, which is the one judgement call in here. It is
 * the opposite of how the IMDb bar in `HomeViewModel` treats a missing
 * rating, and deliberately: that rating costs a request to learn, so waving
 * an unknown through is the difference between a hero that shows something
 * and one that stalls. A year costs nothing — it is already in the payload —
 * so a title without one is not a title whose age is expensive to establish,
 * it is a title TMDB has no release date for, which in practice means
 * unreleased or barely catalogued. Neither is destaque material.
 */
private fun Int?.isFeaturableYear() = this != null && this >= MIN_SPOTLIGHT_YEAR

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
/**
 * How many of the most recent watches are asked for neighbours.
 *
 * Does two jobs, which is why it is one number: it is the count of
 * "porque você assistiu" fileiras, and it is the seed pool the hero rotates
 * out of. Ten rather than five because both of those wanted more and the
 * cost is flat — the seeds are fetched in parallel and shared between the
 * two callers (see `candidates`), so the extra five add requests but not
 * waiting.
 */
private const val SEED_ROWS = 10

/**
 * Cards kept per fileira.
 *
 * Worth knowing before raising it: one TMDB page is twenty results, and this
 * cut runs *after* the already-watched filter, so it is not what limits a
 * short fileira — it is a ceiling that a fileira only reaches when almost
 * nothing was filtered out of it. Since `neighboursOf` began merging two
 * endpoints there is genuinely more than twenty to choose from, which is the
 * first time this number has had anything to cut.
 */
private const val PER_ROW = 20
private const val MIN_ROW_SIZE = 4

/**
 * How far back the hero is allowed to reach.
 *
 * The pool is built from TMDB's neighbours of what the viewer last watched,
 * and neighbourhood has no sense of time in it: finish a Terminator and the
 * suggestions are its own decade, which is how a hero meant to open the app
 * on something to watch tonight fills with 1959 and 1981. The seeds stay as
 * they are — an old film is a fine thing to be recommended *from* — but what
 * comes back out of them is held to the present.
 */
private const val MIN_SPOTLIGHT_YEAR = 2016

/** "Nota superior a 6", on TMDB's own 0-10 scale. */
private const val MIN_SPOTLIGHT_SCORE = 6.0

/**
 * Votes a title needs before its average is allowed to mean anything here.
 * Low on purpose — this is a floor under noise, not a popularity gate, and a
 * genuinely obscure film with a real audience clears it comfortably.
 */
private const val MIN_SPOTLIGHT_VOTES = 50
