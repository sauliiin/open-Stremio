package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.ImdbGraphqlRequest
import com.mdblisthub.tv.core.network.dto.ImdbPlaybackResponse
import com.mdblisthub.tv.core.network.dto.ImdbTrailerIdResponse
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * IMDb's trailer files.
 *
 * The reason this exists at all: a YouTube trailer cannot be played inside
 * the app. Its embed is refused on a television — YouTube answers "error 152,
 * video unavailable" for videos that play anywhere else, because a TV is
 * expected to use their own app — and pulling the real stream URL out of a
 * watch page means scraping. IMDb, by contrast, simply hands out MP4 URLs,
 * which Media3 plays like any other file.
 *
 * The headers are not decoration. This endpoint answers for imdb.com, so a
 * request that does not look like it came from there is rejected.
 */
interface ImdbApi {

    @Headers(
        "Referer: https://www.imdb.com/",
        "Origin: https://www.imdb.com",
        "Accept-Language: en-US,en",
    )
    @POST("/")
    suspend fun latestTrailerId(@Body body: ImdbGraphqlRequest): ImdbTrailerIdResponse

    @Headers(
        "Referer: https://www.imdb.com/",
        "Origin: https://www.imdb.com",
        "Accept-Language: en-US,en",
    )
    @POST("/")
    suspend fun playbackUrls(@Body body: ImdbGraphqlRequest): ImdbPlaybackResponse

    companion object {
        const val LATEST_TRAILER_QUERY =
            "query (\$id: ID!) { title(id: \$id) { latestTrailer { id } } }"

        const val PLAYBACK_QUERY =
            "query VideoPlayback(\$viconst: ID!) { video(id: \$viconst) { " +
                "playbackURLs { displayName { value } videoMimeType url } } }"
    }
}
