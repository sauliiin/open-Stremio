package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.IntroDbSegmentsDto
import retrofit2.http.GET
import retrofit2.http.Query

/** IntroDB's segment lookup. See `ApiConfig.INTRODB_BASE`. */
interface IntroDbApi {

    @GET("segments")
    suspend fun segments(
        @Query("imdb_id") imdbId: String,
        @Query("season") season: Int,
        @Query("episode") episode: Int,
    ): IntroDbSegmentsDto
}
