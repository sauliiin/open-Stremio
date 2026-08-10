package com.mdblisthub.tv.core.network

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The HTTP layer.
 *
 * Two clients, because the two workloads want opposite settings. Metadata
 * calls go to four APIs that are up and fast, and their answers are worth
 * keeping on disk. Addon calls fan out to a dozen third-party hosts of which
 * one is usually down, so they need short timeouts, no caching, and enough
 * parallelism that the slow ones do not queue behind each other.
 */
object HttpClients {

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    /** Disk budget for the metadata cache. A TV box has room; a cold home does not. */
    private const val CACHE_BYTES = 96L * 1024 * 1024

    fun metadata(context: Context): OkHttpClient {
        val cache = Cache(File(context.cacheDir, "http-metadata"), CACHE_BYTES)

        return OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(UserAgentInterceptor)
            // Response-side, so it rewrites what gets *stored*: TMDB and
            // mdblist both answer `no-cache`, which would make the disk cache
            // above inert. Room is still the source of truth — this layer is
            // what stops a background refresh from re-downloading everything.
            .addNetworkInterceptor(CacheControlInterceptor)
            .build()
    }

    /**
     * Shares the connection pool and thread pool of the metadata client, which
     * is the whole reason to derive rather than build a second one.
     */
    fun addons(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .cache(null)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 8
        })
        .build()

    /**
     * Installing an addon, which is a different workload from using one.
     *
     * [addons] is tuned for fanning a title out over a dozen third-party hosts
     * where one is always down, so giving up in twelve seconds is correct
     * there. Installing is the opposite: one host, chosen deliberately, once.
     * Several popular addons cold-start on the first request to a freshly
     * configured URL — Torrentio validates the debrid key attached to it and
     * has been measured taking over twenty seconds to answer, then answering
     * in milliseconds forever after. Under the fan-out's timeout that first
     * request is aborted, and the addon simply cannot be added at all.
     */
    fun addonInstall(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * OpenSubtitles.com's own API. Its own client, not [addons], because the
     * headers [OpenSubtitlesHeadersInterceptor] attaches carry a personal
     * API key that must never ride along on a request to a third-party
     * addon host.
     */
    fun openSubtitles(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .cache(null)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(OpenSubtitlesHeadersInterceptor)
        .build()

    /** Never serve a stale answer for something the user just asked to refresh. */
    val NO_CACHE: CacheControl = CacheControl.Builder().noCache().noStore().build()
}
