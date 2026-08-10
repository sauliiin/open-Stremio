package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.OpenSubtitlesDownloadDto
import com.mdblisthub.tv.core.network.dto.OpenSubtitlesDownloadRequestDto
import com.mdblisthub.tv.core.network.dto.OpenSubtitlesSearchDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** OpenSubtitles.com's own API — credentials attached by [OpenSubtitlesHeadersInterceptor]. */
interface OpenSubtitlesApi {

    @GET("subtitles")
    suspend fun search(
        @Query("imdb_id") imdbId: String,
        @Query("languages") languages: String,
        @Query("season_number") season: Int? = null,
        @Query("episode_number") episode: Int? = null,
        @Query("type") type: String? = null,
        @Query("order_by") orderBy: String = "download_count",
        @Query("order_direction") orderDirection: String = "desc",
    ): OpenSubtitlesSearchDto

    /** Mints a one-time download link for a file named in a [search] result. */
    @POST("download")
    suspend fun download(@Body body: OpenSubtitlesDownloadRequestDto): OpenSubtitlesDownloadDto
}
