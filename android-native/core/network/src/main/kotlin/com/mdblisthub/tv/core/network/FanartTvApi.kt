package com.mdblisthub.tv.core.network

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

@Serializable
data class FanartTvImage(
    val id: String? = null,
    val url: String? = null,
    val likes: String? = null,
)

@Serializable
data class FanartTvResponse(
    val name: String? = null,
    val tmdb_id: String? = null,
    val thetvdb_id: String? = null,
    val moviebackground: List<FanartTvImage>? = null,
    val showbackground: List<FanartTvImage>? = null,
    /** 16:9 promotional art made specifically for catalogue cards. */
    val moviethumb: List<FanartTvImage>? = null,
    val tvthumb: List<FanartTvImage>? = null,
)

interface FanartTvApi {
    @GET("{media_type}/{media_id}")
    suspend fun getArt(
        @Path("media_type") mediaType: String,
        @Path("media_id") mediaId: Int,
        @Header("api-key") apiKey: String = ApiConfig.FANART_TV_API_KEY
    ): FanartTvResponse
}
