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

    private fun recommendation(tmdbId: Int) = TmdbSearchResultDto(
        id = tmdbId,
        title = "Film $tmdbId",
        posterPath = "/poster.jpg",
        backdropPath = "/backdrop.jpg",
        voteAverage = 8.0,
        voteCount = 500,
    )

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

    @Test
    fun `a history of series alone still finds films, by reaching past the five`() = runBlocking {
        // Five series watched more recently than the one film. TMDB answers
        // `tv/{id}` with series, which the films-only rule drops — so without
        // the second pass over the most recent *films* the hero would be
        // empty for this viewer despite having something to show.
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

        assertEquals(listOf(unseen), spotlight.map { it.tmdbId })
    }
}
