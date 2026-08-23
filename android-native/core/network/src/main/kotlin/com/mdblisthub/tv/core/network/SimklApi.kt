package com.mdblisthub.tv.core.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Minimal, TV-oriented surface of Simkl's PIN, sync and scrobble APIs. */
interface SimklApi {
    @GET("oauth/pin") suspend fun requestPin(): JsonObject
    @GET("oauth/pin/{code}") suspend fun pollPin(@Path("code") code: String): JsonObject

    @GET("sync/all-items/{type}/{status}") suspend fun items(
        @Path("type") type: String,
        @Path("status") status: String,
        @Query("extended") extended: String = "ids_only",
        @Query("episode_watched_at") episodeWatchedAt: String? = null,
        @Query("include_all_episodes") includeAllEpisodes: String? = null,
        @Query("next_watch_info") nextWatchInfo: String? = null,
    ): JsonObject

    @POST("sync/history") suspend fun addHistory(@Body body: JsonObject): JsonObject
    @POST("sync/history/remove") suspend fun removeHistory(@Body body: JsonObject): JsonObject
    @POST("sync/add-to-list") suspend fun addToList(@Body body: JsonObject): JsonObject
    @POST("scrobble/{action}") suspend fun scrobble(
        @Path("action") action: String,
        @Body body: JsonObject,
    ): Response<JsonObject>
    @GET("sync/playback") suspend fun playback(): JsonArray
    @DELETE("sync/playback/{id}") suspend fun deletePlayback(@Path("id") id: Long): Response<Unit>
}
