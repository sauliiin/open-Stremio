package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.TraktCollectionItemDto
import com.mdblisthub.tv.core.network.dto.TraktCommentDto
import com.mdblisthub.tv.core.network.dto.TraktHistoryItemDto
import com.mdblisthub.tv.core.network.dto.TraktPlaybackDto
import com.mdblisthub.tv.core.network.dto.TraktScrobbleDto
import com.mdblisthub.tv.core.network.dto.TraktSyncResponseDto
import com.mdblisthub.tv.core.network.dto.TraktSyncWriteDto
import com.mdblisthub.tv.core.network.dto.TraktUpNextItemDto
import com.mdblisthub.tv.core.network.dto.TraktUserSettingsDto
import com.mdblisthub.tv.core.network.dto.TraktWatchedItemDto
import com.mdblisthub.tv.core.network.dto.TraktWatchlistItemDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Trakt, as the alternative to [MdblistApi] for the five account-owned rows.
 *
 * Two things about this API shape the calls below. **Every paginated read
 * defaults to ten items**, so `limit` is passed explicitly everywhere rather
 * than left out — omitting it does not mean "everything", it means a tenth of
 * a screen. And the auth token is not a query parameter the way mdblist's key
 * is; it rides in headers attached by [TraktHeadersInterceptor], which is why
 * no method here takes a credential.
 *
 * `type` is a path segment: `movies`, `shows` or `episodes` depending on the
 * endpoint. Trakt has no unified variant of these the way mdblist's
 * `unified=true` gives one, so a feed that mixes films and series is two calls
 * merged locally.
 */
interface TraktApi {

    /** Who the stored token belongs to — shown next to "Disconnect". */
    @GET("users/settings")
    suspend fun settings(): TraktUserSettingsDto

    // ---------------------------------------------------------------- reads

    /**
     * `sort_by`/`sort_how` are path segments, not query parameters. `added`
     * descending is what the Watchlist row wants: newest first, the same
     * order mdblist's `sort=added&order=desc` produces.
     */
    @GET("sync/watchlist/{type}/{sort_by}/{sort_how}")
    suspend fun watchlist(
        @Path("type") type: String,
        @Path("sort_by") sortBy: String = "added",
        @Path("sort_how") sortHow: String = "desc",
        @Query("limit") limit: Int,
        @Query("page") page: Int = 1,
    ): List<TraktWatchlistItemDto>

    @GET("sync/collection/{type}")
    suspend fun collection(
        @Path("type") type: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int = 1,
    ): List<TraktCollectionItemDto>

    /** Newest first, one row per play — the source of "Recently Watched". */
    @GET("sync/history/{type}")
    suspend fun history(
        @Path("type") type: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int = 1,
    ): List<TraktHistoryItemDto>

    /**
     * The whole watched set rather than a recent slice, which is what the
     * detail screen's "watched" button reads.
     *
     * Paginated since July 2026: without `limit` this answers 100 items, not
     * the account's full set, and caps `limit` at 250. `extended=full` is now
     * a no-op; per-episode `seasons` only come with `extended=progress`, which
     * in turn caps pages at 100. A `Response` so the caller can walk pages by
     * `X-Pagination-Page-Count` instead of guessing from a short page.
     */
    @GET("sync/watched/{type}")
    suspend fun watched(
        @Path("type") type: String,
        @Query("extended") extended: String? = null,
        @Query("limit") limit: Int,
        @Query("page") page: Int = 1,
    ): Response<List<TraktWatchedItemDto>>

    @GET("sync/progress/up_next")
    suspend fun upNext(
        @Query("limit") limit: Int,
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String = "last_watched_at",
        @Query("sort_how") sortHow: String = "desc",
    ): List<TraktUpNextItemDto>

    @GET("sync/playback/{type}")
    suspend fun playback(
        @Path("type") type: String,
        @Query("limit") limit: Int,
    ): List<TraktPlaybackDto>

    /**
     * Public comments on a title — the detail screen's Trakt reviews.
     *
     * OAuth optional: with no linked account this still answers, and with one
     * Trakt drops comments from users that account has blocked. `id` takes a
     * Trakt id, slug or IMDb id (not a TMDB id). `sort` is `likes`, `newest`,
     * `oldest`, `replies`, `highest`, `lowest` or `plays`.
     */
    @GET("{type}/{id}/comments/{sort}")
    suspend fun comments(
        @Path("type") type: String,
        @Path("id") id: String,
        @Path("sort") sort: String = "likes",
        @Query("limit") limit: Int,
        @Query("page") page: Int = 1,
    ): List<TraktCommentDto>

    // --------------------------------------------------------------- writes

    @POST("sync/watchlist")
    suspend fun addToWatchlist(@Body body: TraktSyncWriteDto): TraktSyncResponseDto

    @POST("sync/watchlist/remove")
    suspend fun removeFromWatchlist(@Body body: TraktSyncWriteDto): TraktSyncResponseDto

    @POST("sync/collection")
    suspend fun addToCollection(@Body body: TraktSyncWriteDto): TraktSyncResponseDto

    @POST("sync/collection/remove")
    suspend fun removeFromCollection(@Body body: TraktSyncWriteDto): TraktSyncResponseDto

    /**
     * "Watched" on Trakt is the history, not a bucket: adding a title records
     * a play, removing it erases those plays. Sending a show adds every
     * episode of it, which is the same whole-title meaning the mdblist path
     * has and what the detail screen's button promises.
     */
    @POST("sync/history")
    suspend fun addToHistory(@Body body: TraktSyncWriteDto): TraktSyncResponseDto

    @POST("sync/history/remove")
    suspend fun removeFromHistory(@Body body: TraktSyncWriteDto): TraktSyncResponseDto

    /** Hides a show from watched progress without deleting its play history. */
    @POST("users/hidden/progress_watched")
    suspend fun hideFromWatchedProgress(@Body body: TraktSyncWriteDto): TraktSyncResponseDto

    // ----------------------------------------------------------- scrobbling

    /** `action` is `start`, `pause` or `stop`. Past 80% a stop marks it watched. */
    @POST("scrobble/{action}")
    suspend fun scrobble(
        @Path("action") action: String,
        @Body body: TraktScrobbleDto,
    ): Response<ResponseBody>

    /**
     * Drops a paused session — "remove from continue watching".
     *
     * Addressed by the playback item's own id, not by the title's, which is
     * why [TraktPlaybackDto.id] is carried all the way into the resume row.
     */
    @DELETE("sync/playback/{id}")
    suspend fun deletePlayback(@Path("id") id: Long): Response<ResponseBody>
}
