package com.mdblisthub.tv.navigation

import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.ResumePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {

    @Test
    fun resumePreservesEpisodeAndRequestsManualSourceSelection() {
        val point = ResumePoint(
            type = MediaType.SHOW,
            tmdbId = 1399,
            imdbId = "tt0944947",
            title = "Example",
            season = 4,
            episode = 7,
            progress = 42f,
        )

        assertEquals(
            "player/show/1399?season=4&episode=7&select=true",
            Routes.resume(point),
        )
    }

    @Test
    fun movieResumeRequestsManualSourceSelectionWithoutEpisodeCoordinates() {
        val point = ResumePoint(
            type = MediaType.MOVIE,
            tmdbId = 550,
            imdbId = "tt0137523",
            title = "Example",
            progress = 58f,
        )

        assertEquals(
            "player/movie/550?season=-1&episode=-1&select=true",
            Routes.resume(point),
        )
    }
}
