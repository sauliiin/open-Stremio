package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.BucketPaginationDto
import com.mdblisthub.tv.core.network.dto.BucketResponseDto
import com.mdblisthub.tv.core.network.dto.LibraryWriteDto
import com.mdblisthub.tv.core.network.dto.MdbInfoDto
import com.mdblisthub.tv.core.network.dto.MdbItemDto
import com.mdblisthub.tv.core.network.dto.MdbListDto
import com.mdblisthub.tv.core.network.dto.MdbUserDto
import com.mdblisthub.tv.core.network.dto.PlaybackSessionDto
import com.mdblisthub.tv.core.network.dto.UpNextResponseDto
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * mdblist.
 *
 * Worth noting against the web build: the library writes here are plain JSON
 * POSTs. In a browser they need a proxy, because a cross-origin JSON POST
 * triggers a CORS preflight and mdblist answers OPTIONS with 405. A native
 * client has no such rule, so the proxy the web app needs simply does not
 * exist in this one.
 */
interface MdblistApi {

    @GET("user")
    suspend fun user(@Query("apikey") apiKey: String): MdbUserDto

    @GET("lists/user")
    suspend fun lists(
        @Query("apikey") apiKey: String,
        /** `no-store` makes an explicit refresh bypass the six-hour HTTP cache too. */
        @Header("Cache-Control") cacheControl: String? = null,
    ): List<MdbListDto>

    /** `unified=true` flattens films and shows, which the mixed lists need. */
    @GET("lists/{id}/items")
    suspend fun listItems(
        @Path("id") listId: Long,
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("unified") unified: Boolean,
        @Query("append_to_response") append: String,
    ): List<MdbItemDto>

    /** Aggregated ratings and mirrored reviews for one title, keyed by TMDB id. */
    @GET("tmdb/{type}/{id}")
    suspend fun info(
        @Path("type") type: String,
        @Path("id") tmdbId: Int,
        @Query("apikey") apiKey: String,
        @Query("append_to_response") append: String,
    ): MdbInfoDto

    /**
     * One page of a library bucket.
     *
     * [cursor] is null for the first page and [BucketPaginationDto.nextCursor]
     * thereafter. Callers want `wholeBucket`, not this — a single page is
     * almost never the answer to "what has this account watched".
     */
    @GET
    suspend fun bucket(
        @Url url: String,
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int,
        @Query("cursor") cursor: String?,
    ): BucketResponseDto

    @POST
    suspend fun bucketWrite(
        @Url url: String,
        @Query("apikey") apiKey: String,
        @Body body: LibraryWriteDto,
    ): Response<ResponseBody>

    /**
     * Scrobbling goes out as nested JSON — `{"progress":..,"movie":{"ids":{..}}}`.
     *
     * It used to be form-encoded with the target in bracket notation, on the
     * reading that mdblist's schema documented that shape. It does not accept
     * it: every such call answered `400 {"non_field_errors":["Either 'movie'
     * or 'show' must be provided"]}`, because the bracket keys are never
     * decoded back into a nested object, so the field it demands is simply
     * absent. Verified against the live API — the same target as JSON answers
     * 200 and stores the point.
     */
    @POST("scrobble/{action}")
    suspend fun scrobble(
        @Path("action") action: String,
        @Query("apikey") apiKey: String,
        @Body body: JsonObject,
    ): Response<ResponseBody>

    /** Paused sessions, which is what makes a title resumable across devices. */
    @GET("sync/playback")
    suspend fun playback(@Query("apikey") apiKey: String): List<PlaybackSessionDto>

    /** In-progress shows paired with the next unwatched episode. */
    @GET("upnext")
    suspend fun upNext(
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int = 50,
        @Query("hide_unreleased") hideUnreleased: Boolean = true,
        @Header("Cache-Control") cacheControl: String? = "no-store",
    ): UpNextResponseDto

    /** The account watchlist ordered by when each title was added to it. */
    @GET("watchlist/items")
    suspend fun watchlist(
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int = 50,
        @Query("unified") unified: Boolean = true,
        @Query("sort") sort: String = "added",
        @Query("order") order: String = "desc",
        @Query("append_to_response") append: String = "poster,ratings",
        @Header("Cache-Control") cacheControl: String? = "no-store",
    ): List<MdbItemDto>

    /** Titles added to the user's collection, newest first after local normalization. */
    @GET("sync/collection")
    suspend fun recentlyAdded(
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int = 100,
        @Query("unified") unified: Boolean = true,
        @Query("append_to_response") append: String = "poster,ratings",
        @Header("Cache-Control") cacheControl: String? = "no-store",
    ): List<MdbItemDto>

    /** Latest watched movie/show snapshots, including artwork. */
    @GET("sync/watched")
    suspend fun recentlyWatched(
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int = 100,
        @Query("append_to_response") append: String = "poster,ratings",
        @Header("Cache-Control") cacheControl: String? = "no-store",
    ): BucketResponseDto
}
