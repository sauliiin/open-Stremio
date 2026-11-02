package com.mdblisthub.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.CoroutineScope
import okhttp3.Call

/**
 * How the bytes of a film reach the player: over the network, or from the disk the
 * prefetcher has been filling ahead of it.
 *
 * Two HTTP stacks over one client. They share its connection pool, and each has its
 * own [AbortableCallFactory], so ending one read leaves the other alone.
 */
@OptIn(UnstableApi::class)
internal class PlaybackDataPath(
    context: Context,
    callFactory: Call.Factory,
    scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val playerCalls = AbortableCallFactory(callFactory)
    private val prefetchCalls = AbortableCallFactory(callFactory)
    private val playerHttp = OkHttpDataSource.Factory(playerCalls)
    private val prefetchHttp = OkHttpDataSource.Factory(prefetchCalls)
    private val loaderTap = LoaderTap()

    /** Hoisted because `SimpleCache` throws if two of them open one directory. */
    private val cache = MediaCache.get(appContext)

    /**
     * What the player reads through. From disk before the network, and it never writes:
     * [prefetcher] is the cache's only writer, for the reason given on [MediaPrefetcher].
     *
     * `FLAG_IGNORE_CACHE_ON_ERROR` matters more here than in most apps: a damaged span
     * must never become the reason a working link cannot play.
     */
    val playerSource: DataSource.Factory =
        DefaultDataSource.Factory(appContext, playerHttp).let { upstream ->
            val source: DataSource.Factory = cache?.let {
                CacheDataSource.Factory()
                    .setCache(it)
                    .setUpstreamDataSourceFactory(upstream)
                    .setCacheWriteDataSinkFactory(null)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            } ?: upstream
            DataSource.Factory { source.createDataSource().apply { addTransferListener(loaderTap) } }
        }

    /** Null when no cache could be opened: with nowhere to write, there is no working ahead. */
    val prefetcher: MediaPrefetcher? = cache?.let {
        MediaPrefetcher(
            cache = it,
            upstreamFactory = DefaultDataSource.Factory(appContext, prefetchHttp),
            calls = prefetchCalls,
            loader = loaderTap,
            scope = scope,
            maxWindowBytes = (MediaCache.budgetBytes * PREFETCH_CACHE_SHARE).toLong(),
        )
    }

    /**
     * Points both stacks at a new candidate.
     *
     * The prefetcher has to be stopped first: it builds requests from the same headers,
     * so a chunk still in flight would finish under this candidate's and write whatever
     * that host returned into the previous file's cache key.
     *
     * Replaced wholesale rather than merged, so a header the previous mirror needed does
     * not leak into this one's request. Some debrid links 403 without the right one, and
     * the factories are re-read on every `prepare`.
     */
    fun switchTo(headers: Map<String, String>) {
        prefetcher?.stop()
        loaderTap.reset()
        playerHttp.setDefaultRequestProperties(headers)
        prefetchHttp.setDefaultRequestProperties(headers)
    }

    /** Everything the player's loader has read so far, from the network or the disk. */
    val bytesRead: Long get() = loaderTap.bytesRead

    /** Fails the player's read in flight, which resumes on a new socket. */
    fun abortPlayerRead() = playerCalls.abortLatest()

    private companion object {
        /**
         * Share of the disk cache the read-ahead window may occupy. The rest is what has
         * already been watched, kept for rewinds until the evictor needs the room.
         */
        const val PREFETCH_CACHE_SHARE = 0.75
    }
}
