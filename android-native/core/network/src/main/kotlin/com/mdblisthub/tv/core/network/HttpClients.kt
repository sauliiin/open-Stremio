package com.mdblisthub.tv.core.network

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.ConnectionPool
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

    /**
     * How many warm sockets the whole app may keep, and this is load-bearing
     * rather than tuning.
     *
     * Every client below is derived from [metadata], so they all share this
     * one pool — which is the entire reason [playback] can reuse the TLS
     * handshake `StreamsRepository`'s probe just paid for. OkHttp's default
     * holds **five** idle connections in total, and this app routinely has
     * far more than five hosts in flight at once: a dozen addons fanning out,
     * up to `PROBE_CONCURRENCY` mirrors being range-probed, TMDB, mdblist,
     * OMDb and every poster off image.tmdb.org.
     *
     * At five, the winning candidate's connection is evicted by the losers
     * before the player ever opens it, so the documented reuse silently did
     * not happen and each start paid for a second handshake. Idle sockets are
     * cheap — a few kilobytes of kernel buffer each — and the keep-alive
     * still reaps them; the ceiling only has to be above the number of hosts
     * one playback touches.
     */
    private const val MAX_IDLE_CONNECTIONS = 32
    private const val KEEP_ALIVE_MINUTES = 5L

    /**
     * Concurrent requests allowed to one host.
     *
     * OkHttp defaults to five, which is right for an API and wrong for a CDN:
     * every poster on the home screen comes from the single host
     * `image.tmdb.org`, so five is the number of artwork downloads that may be
     * in flight while a row fills in, and the sixth waits. The APIs are not
     * affected in practice — the workers that walk them are sequential and
     * space themselves deliberately (see `Workers.kt`), so nothing here lifts
     * a limit they were relying on.
     */
    private const val MAX_REQUESTS_PER_HOST = 12

    fun metadata(context: Context): OkHttpClient {
        val cache = Cache(File(context.cacheDir, "http-metadata"), CACHE_BYTES)

        return OkHttpClient.Builder()
            .cache(cache)
            .connectionPool(
                ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES),
            )
            .dispatcher(
                Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST },
            )
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
        // Debrid addons routinely spend 10-15s validating tokens on their
        // first request of a session (cold start). The old 8s read timeout
        // silently discarded them — the most common cause of "no sources
        // found" when the same title played fine a moment later.
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        // Built on the *base's* executor rather than on one of its own. A bare
        // `Dispatcher()` lazily spins up a second unbounded thread pool, which
        // is precisely what the comment above says this client does not do —
        // the pool was shared, the threads were not. Passing the executor in
        // makes both true, and the limits below still apply only here.
        .dispatcher(
            Dispatcher(base.dispatcher.executorService).apply {
                maxRequests = 32
                maxRequestsPerHost = 8
            },
        )
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
     * The client the video itself is read over.
     *
     * Derived from [metadata] so it shares that connection pool — which is the
     * entire point. `StreamsRepository` probes each mirror with a two-byte
     * range request over a client from the same pool, so by the time the
     * player opens the winning candidate the TCP and TLS handshake is already
     * done and can simply be reused, instead of being paid for twice.
     *
     * **`callTimeout` must stay off.** It bounds the whole call including the
     * body, and here the body is a two-hour film — the addon client's twelve
     * seconds would cut playback off mid-scene. The read timeout is what
     * catches a host that goes quiet, and that is the correct tool.
     */
    fun playback(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .cache(null)
        // Generous for the same reason the data source used to be: a debrid
        // endpoint redirects instantly and then holds the connection open
        // while the file is prepared upstream, well past thirty seconds on a
        // cold link.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * What Coil reads posters and fanart over.
     *
     * Derived from [metadata] for its connection pool, but with the disk cache
     * **off**, and that is the whole point. Coil keeps its own 512MB artwork
     * cache; leaving OkHttp's cache attached meant every poster was written to
     * disk twice — once by each layer — and the second copy landed in the 96MB
     * metadata cache, where a few hundred posters are more than enough to
     * evict the JSON responses that cache exists to hold. So the app paid
     * double the disk writes to make its own metadata cache miss.
     *
     * Nothing is lost: the bytes still come off Coil's cache on the next
     * launch, which is the layer that was serving them anyway.
     */
    fun images(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .cache(null)
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

    /**
     * Trakt. Derived from [metadata] for its connection pool, but with the
     * disk cache off: everything this app reads from Trakt is live account
     * state — what is on the watchlist right now, how far into a film the
     * user got — and a cached answer there is simply a wrong one. The mdblist
     * equivalents reach the same place by passing `Cache-Control: no-store`
     * on each call; Trakt has no per-call credential to hang that off, so the
     * client says it once.
     *
     * [authenticator] is what makes an expired token invisible above this
     * layer — see [TraktAuthenticator]. Passed in rather than built here so
     * the token seam stays in one place.
     */
    fun trakt(base: OkHttpClient, tokens: () -> TraktTokens): OkHttpClient = base.newBuilder()
        .cache(null)
        .addInterceptor(TraktHeadersInterceptor(tokens))
        .authenticator(TraktAuthenticator(tokens))
        .build()

    /**
     * The OAuth host. Same headers, no authenticator: these are the calls that
     * *produce* tokens, and a `401` from one of them means the credentials are
     * wrong, not stale — retrying with a refreshed token would be nonsense.
     */
    fun traktAuth(base: OkHttpClient, tokens: () -> TraktTokens): OkHttpClient = base.newBuilder()
        .cache(null)
        .addInterceptor(TraktHeadersInterceptor(tokens))
        .build()

    /** Never serve a stale answer for something the user just asked to refresh. */
    val NO_CACHE: CacheControl = CacheControl.Builder().noCache().noStore().build()
}
