package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.repository.source.readAllBucketPages
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.network.dto.BucketEntryDto
import com.mdblisthub.tv.core.network.dto.BucketPaginationDto
import com.mdblisthub.tv.core.network.dto.BucketResponseDto
import com.mdblisthub.tv.core.network.dto.BucketTitleDto
import com.mdblisthub.tv.core.network.dto.MdbIdsDto
import com.mdblisthub.tv.core.network.dto.TmdbSearchResultDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spotlight's exclusion rule, across a paginated watch history.
 *
 * `BucketPagingTest` proves the loop fetches every page. This proves the
 * pages are then *used* — that a film sitting on page two is as excluded as
 * one on page one — which is the thing the viewer actually notices and the
 * one no amount of correct paging guarantees on its own.
 */
class RecommendationsSpotlightTest {

    /** Watched a while ago, so it lands on the second page of the bucket. */
    private val oldFavourite = 550

    /** The most recent watch, and therefore the seed. */
    private val lastNight = 680

    /** Never watched. The only thing the hero should end up with. */
    private val unseen = 27205

    private fun watchedMovie(tmdbId: Int, at: String) = BucketEntryDto(
        lastWatchedAt = at,
        movie = BucketTitleDto(ids = MdbIdsDto(tmdb = tmdbId)),
    )

    /**
     * A candidate that clears every bar except the one under test.
     *
     * The release date is not decoration: the hero only features titles from
     * [minFeaturedYear] on, so a recommendation built without one is filtered
     * before it reaches whatever the test was actually about.
     */
    private fun recommendation(tmdbId: Int, releaseDate: String = "2020-06-01") =
        TmdbSearchResultDto(
            id = tmdbId,
            title = "Film $tmdbId",
            posterPath = "/poster.jpg",
            backdropPath = "/backdrop.jpg",
            releaseDate = releaseDate,
            voteAverage = 8.0,
            voteCount = 500,
        )

    /** Mirrors `MIN_SPOTLIGHT_YEAR`, which is private to the repository. */
    private val minFeaturedYear = 2016

    /** Page one holds the recent watch; page two holds the older one. */
    private val bucketPages = mapOf<String?, BucketResponseDto>(
        null to BucketResponseDto(
            movies = listOf(watchedMovie(lastNight, at = "2026-08-21T03:58:32.000Z")),
            pagination = BucketPaginationDto(hasMore = true, nextCursor = "page-2"),
        ),
        "page-2" to BucketResponseDto(
            movies = listOf(watchedMovie(oldFavourite, at = "2019-01-04T20:11:00.000Z")),
        ),
    )

    /** Both seeds suggest the old favourite alongside something unseen. */
    private val recommendations: suspend (MediaType, Int) -> List<TmdbSearchResultDto> =
        { _, _ -> listOf(recommendation(oldFavourite), recommendation(unseen)) }

    @Test
    fun `a film watched only on the second page is still excluded`() = runBlocking {
        val watched = readAllBucketPages { cursor -> bucketPages.getValue(cursor) }.watchedTitles()

        val spotlight = buildSpotlight(watched, recommendations)

        assertEquals(listOf(unseen), spotlight.map { it.tmdbId })
    }

    /**
     * The bug, kept as a test.
     *
     * This is the old behaviour written down: shape only the first page and
     * the old favourite comes back as a destaque. It is here so that anyone
     * who "simplifies" the paging away sees, in a name, exactly what they
     * have reintroduced — a film the viewer finished years ago, recommended
     * to them as something new.
     */
    @Test
    fun `reading only the first page is what used to let a watched film through`() = runBlocking {
        val firstPageOnly = bucketPages.getValue(null).watchedTitles()

        val spotlight = buildSpotlight(firstPageOnly, recommendations)

        assertEquals(setOf(oldFavourite, unseen), spotlight.map { it.tmdbId }.toSet())
    }

    @Test
    fun `the seed itself is never recommended back`() = runBlocking {
        val watched = readAllBucketPages { cursor -> bucketPages.getValue(cursor) }.watchedTitles()

        val spotlight = buildSpotlight(watched) { _, _ ->
            listOf(recommendation(lastNight), recommendation(unseen))
        }

        assertEquals(listOf(unseen), spotlight.map { it.tmdbId })
    }

    @Test
    fun `most recently watched comes first, whichever page it arrived on`() {
        val watched = runBlocking {
            readAllBucketPages { cursor -> bucketPages.getValue(cursor) }.watchedTitles()
        }

        // Page order is not recency order — the seeds are chosen by
        // `last_watched_at`, so a page-two entry watched yesterday would have
        // to outrank a page-one entry watched last year.
        assertEquals(
            listOf(MediaType.MOVIE to lastNight, MediaType.MOVIE to oldFavourite),
            watched,
        )
    }

    @Test
    fun `nothing is featured for an account with no history`() = runBlocking {
        assertTrue(buildSpotlight(emptyList(), recommendations).isEmpty())
    }

    /**
     * What replaced the films-only rule, and the test that used to guard it.
     *
     * The old assertion here was that this viewer — series recently, a film
     * further back — ends up with the *film*, because every series TMDB
     * suggested was dropped and a second pass over the most recent films was
     * what kept the hero from sitting empty. Both halves of that are gone.
     * The séries were always the recommendation; the workaround only existed
     * because they were being thrown away.
     */
    @Test
    fun `a history of series is featured as series`() = runBlocking {
        val watched = buildList {
            repeat(5) { add(MediaType.SHOW to 1000 + it) }
            add(MediaType.MOVIE to lastNight)
        }

        val spotlight = buildSpotlight(watched) { type, _ ->
            if (type == MediaType.SHOW) {
                listOf(recommendation(2000).copy(mediaType = "tv"))
            } else {
                listOf(recommendation(unseen))
            }
        }

        assertEquals(
            listOf(MediaType.SHOW to 2000, MediaType.MOVIE to unseen),
            spotlight.map { it.type to it.tmdbId }.sortedBy { (_, id) -> id },
        )
    }

    /**
     * The trap inside letting séries through.
     *
     * TMDB leaves `media_type` off these payloads often enough to matter, and
     * the fallback `toMediaItem` reaches for used to be the constant `MOVIE`
     * — invisible while the films-only rule meant nothing else survived. A
     * series arriving without a `media_type` would now be stamped as a film,
     * and a series id looked up against `movie/{id}` is a detail screen that
     * never loads. The fallback has to be the seed's own type.
     */
    @Test
    fun `a neighbour that omits its media type inherits the seed's`() = runBlocking {
        val spotlight = buildSpotlight(listOf(MediaType.SHOW to 1000)) { _, _ ->
            listOf(recommendation(2000))
        }

        assertEquals(listOf(MediaType.SHOW to 2000), spotlight.map { it.type to it.tmdbId })
    }

    /**
     * The hero is for what to watch now, and TMDB's neighbours have no sense
     * of time: the films around a 1984 film are its own decade. Only what
     * comes *out* of a seed is held to the year — the seed itself can be as
     * old as the viewer's taste happens to be.
     */
    @Test
    fun `a title older than the floor is not featured`() = runBlocking {
        val old = 1990
        val recent = 2024

        val spotlight = buildSpotlight(listOf(MediaType.MOVIE to lastNight)) { _, _ ->
            listOf(
                recommendation(old, releaseDate = "${minFeaturedYear - 1}-12-31"),
                recommendation(recent, releaseDate = "$minFeaturedYear-01-01"),
            )
        }

        // The floor is inclusive: the title released *in* the floor year stays.
        assertEquals(listOf(recent), spotlight.map { it.tmdbId })
    }

    /**
     * The one place this rule is stricter than the IMDb bar it sits beside.
     *
     * A missing rating is waved through there because learning it costs a
     * request. A missing year costs nothing to check — it simply is not in
     * the payload — so it is not an unknown worth the benefit of the doubt,
     * it is a title TMDB has no release date for.
     */
    @Test
    fun `a title with no release date at all is not featured`() = runBlocking {
        val undated = 4040

        val spotlight = buildSpotlight(listOf(MediaType.MOVIE to lastNight)) { _, _ ->
            listOf(recommendation(undated, releaseDate = ""))
        }

        assertTrue(spotlight.isEmpty())
    }

    /**
     * Films and séries are numbered separately by TMDB, so the two id spaces
     * overlap. While the hero was films-only that could never bite; now a
     * dedupe on the bare id would drop whichever of a colliding pair arrived
     * second, and the viewer would lose a destaque to a film they have never
     * been shown.
     */
    @Test
    fun `a film and a series sharing an id are both featured`() = runBlocking {
        val collidingId = 4242

        val spotlight = buildSpotlight(
            listOf(MediaType.SHOW to 1000, MediaType.MOVIE to lastNight),
        ) { type, _ ->
            if (type == MediaType.SHOW) {
                listOf(recommendation(collidingId).copy(mediaType = "tv"))
            } else {
                listOf(recommendation(collidingId))
            }
        }

        assertEquals(
            listOf(MediaType.MOVIE to collidingId, MediaType.SHOW to collidingId),
            spotlight.map { it.type to it.tmdbId }.sortedBy { (type, _) -> type.name },
        )
    }
}
