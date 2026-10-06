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
import com.mdblisthub.tv.core.network.dto.TraktCommentDto
import com.mdblisthub.tv.core.network.dto.TraktCommentUserDto
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

    @Test
    fun `direct Trakt comments replace the MDBList mirror, minus spoilers and shouts`() {
        val long = "This one holds up on a rewatch and the ending still lands with the same weight it had the first time"
        val detail = buildDetailEntity(
            type = MediaType.MOVIE,
            tmdbId = 1,
            tmdb = TmdbDetailDto(title = "Title"),
            info = MdbInfoDto(
                reviews = listOf(MdbReviewDto(author = "Mirrored", content = "Mirror copy", providerId = 1)),
            ),
            omdb = null,
            now = 0,
            traktComments = listOf(
                TraktCommentDto(comment = long, userRating = 9, user = TraktCommentUserDto("jenn", "Jenn")),
                TraktCommentDto(comment = "11/10", user = TraktCommentUserDto("short")),
                TraktCommentDto(comment = long, spoiler = true, user = TraktCommentUserDto("flagged")),
                TraktCommentDto(comment = "$long [spoiler]he dies[/spoiler]", user = TraktCommentUserDto("inline")),
                TraktCommentDto(comment = long, user = TraktCommentUserDto("noname", name = "")),
            ),
        )

        assertEquals(listOf("Jenn", "noname"), detail.reviews.map { it.author })
        assertEquals(9.0, detail.reviews[0].rating)
        assertEquals(ReviewProvider.TRAKT, detail.reviews[0].provider)
        assertNull(detail.reviews.find { it.content == "Mirror copy" })
    }

    @Test
    fun `falls back to the MDBList mirror when Trakt leaves nothing to show`() {
        val mirror = MdbInfoDto(
            reviews = listOf(MdbReviewDto(author = "Mirrored", content = "Mirror copy", providerId = 1)),
        )
        listOf(null, emptyList(), listOf(TraktCommentDto(comment = "Great movie"))).forEach { comments ->
            val detail = buildDetailEntity(
                type = MediaType.MOVIE,
                tmdbId = 1,
                tmdb = TmdbDetailDto(title = "Title"),
                info = mirror,
                omdb = null,
                now = 0,
                traktComments = comments,
            )
            assertEquals(listOf("Mirror copy"), detail.reviews.map { it.content })
        }
    }
}
