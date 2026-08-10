package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.OmdbDto
import retrofit2.http.GET
import retrofit2.http.Query

interface OmdbApi {
    /**
     * OMDb serves everything off the root, so the relative path is `.` — it
     * resolves against the base URL without appending a segment.
     */
    @GET(".")
    suspend fun byImdb(
        @Query("apikey") apiKey: String,
        @Query("i") imdbId: String,
        @Query("plot") plot: String,
    ): OmdbDto
}
