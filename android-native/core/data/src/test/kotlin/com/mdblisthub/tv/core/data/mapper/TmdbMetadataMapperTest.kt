package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.ReviewProvider
import com.mdblisthub.tv.core.network.dto.MdbInfoDto
import com.mdblisthub.tv.core.network.dto.MdbRatingDto
import com.mdblisthub.tv.core.network.dto.MdbReviewDto
import com.mdblisthub.tv.core.network.dto.TmdbDetailDto
import com.mdblisthub.tv.core.network.dto.TmdbReviewAuthorDto
import com.mdblisthub.tv.core.network.dto.TmdbReviewDto
import com.mdblisthub.tv.core.network.dto.TmdbReviewsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbMetadataMapperTest {
    @Test
    fun `TMDB owns duplicated title metadata and its score`() {
        val detail = buildDetailEntity(
            type = MediaType.MOVIE,
            tmdbId = 1,
            tmdb = TmdbDetailDto(
                title = "TMDB title",
                releaseDate = "2024-05-01",
                runtime = 120,
                voteAverage = 8.4,
                voteCount = 2_000,
            ),
            info = MdbInfoDto(
                title = "MDBList title",
                year = 1999,
                runtime = 90,
                ratings = listOf(MdbRatingDto(source = "tmdb", value = 10.0)),
            ),
            omdb = null,
            now = 0,
        )

        assertEquals("TMDB title", detail.title)
        assertEquals(2024, detail.year)
        assertEquals(120, detail.runtimeMinutes)
        assertEquals(84, detail.ratings.single { it.key == "tmdb" }.score)
        assertEquals(2_000L, detail.ratings.single { it.key == "tmdb" }.votes)
    }

    @Test
    fun `uses direct TMDB reviews and only Trakt reviews from MDBList`() {
        val detail = buildDetailEntity(
            type = MediaType.MOVIE,
            tmdbId = 1,
            tmdb = TmdbDetailDto(
                title = "Title",
                reviews = TmdbReviewsDto(
                    listOf(
                        TmdbReviewDto(
                            author = "TMDB author",
                            content = "TMDB review",
                            authorDetails = TmdbReviewAuthorDto(rating = 9.0),
                        ),
                    ),
                ),
            ),
            info = MdbInfoDto(
                reviews = listOf(
                    MdbReviewDto(author = "Mirrored", content = "Do not use", providerId = 2),
                    MdbReviewDto(author = "Trakt author", content = "Trakt review", providerId = 1),
                ),
            ),
            omdb = null,
            now = 0,
        )

        assertEquals(2, detail.reviews.size)
        assertEquals(ReviewProvider.TMDB, detail.reviews[0].provider)
        assertEquals(ReviewProvider.TRAKT, detail.reviews[1].provider)
        assertEquals("TMDB review", detail.reviews[0].content)
        assertEquals("Trakt review", detail.reviews[1].content)
        assertNull(detail.reviews.find { it.content == "Do not use" })
    }
}
