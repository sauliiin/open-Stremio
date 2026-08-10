package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.WyzieItemDto
import retrofit2.http.GET
import retrofit2.http.Query

/** Wyzie's own subtitle search. See `ApiConfig.WYZIE_BASE`. */
interface WyzieApi {

    @GET("search")
    suspend fun search(
        @Query("id") imdbId: String,
        @Query("language") language: String,
        @Query("key") apiKey: String,
        @Query("season") season: Int? = null,
        @Query("episode") episode: Int? = null,
    ): List<WyzieItemDto>
}
