package com.mdblisthub.tv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Downloads ahead of the playback position, onto disk.
 *
 * **Why this exists.** The cache wired into `PlaybackController` is a
 * `CacheDataSource`, and a `CacheDataSource` is read-through: it stores the
 * bytes the player has *already asked for* and never a byte more. So it makes a
 * rewind free and a retry cheap, but it does nothing for the thing that
 * actually decides whether the picture stops — how far ahead of the current
 * frame the app is holding media.
 *
 * That cushion lived entirely in `DefaultAllocator`, which takes its `byte[]`
 * from the **Java heap**. On a box with 3GB of RAM there is room for it. On a
 * Fire TV Stick there is not, and the failure is not a clean one: the buffer
 * competes with Compose, Coil and the decoders for a heap ceiling around a
 * quarter of a gigabyte, garbage collection turns into the stutter, and a
 * rebuffer that cannot refill `bufferForPlaybackAfterRebufferMs` fast enough
 * leaves the picture stopped until the user seeks.
 *
 * Disk has none of those limits. eMMC on the cheapest stick is still orders of
 * magnitude faster than the Wi-Fi radio next to it, and a gigabyte of it costs
 * the heap nothing. So the deep cushion moves here — minutes of film, on disk,
 * ahead of the playhead — and the RAM buffer shrinks back to what a constrained
 * device can actually afford (see [HeapBudget]). When the link hiccups, the
 * player reads out of the cache instead of waiting on the network.
 *
 * **What it deliberately does not do:**
 *
 * - **Compete with the player for bandwidth.** [onPosition] reports how much
 *   the player itself has buffered, and while that is thin the loop stands
 *   down completely. A starving player means the network is the bottleneck,
 *   and splitting a bottleneck two ways makes the stall longer, not shorter.
 *   Prefetching only ever uses the bandwidth left over once the player's own
 *   `LoadControl` has stopped loading.
 * - **Touch the player.** Every field it reads across the thread boundary is
 *   `@Volatile` and pushed in from the main thread. ExoPlayer is
 *   single-threaded by contract and this loop runs on IO.
 * - **Run on anything but a progressive file.** See [canPrefetch]: byte offsets
 *   into an HLS playlist or a DASH manifest are meaningless, and a local file
 *   is already on disk.
 */
@OptIn(UnstableApi::class)
internal class MediaPrefetcher(
    private val cache: Cache,
    private val upstreamFactory: DataSource.Factory,
    private val scope: CoroutineScope,
    /**
     * Ceiling on how far ahead this may work, derived from the cache the spans
     * have to fit in.
     *
     * Overrunning it is not merely wasteful. `LeastRecentlyUsedCacheEvictor`
     * would start evicting to make room, and the only thing older than what
     * this just wrote is the film the viewer is *currently watching* — so an
     * oversized window turns the prefetcher into something that deletes the
     * back buffer to make room for itself.
     */
    private val maxWindowBytes: Long,
) {

    @Volatile
    private var positionMs = 0L

    /** True while the player's own buffer is too thin to share bandwidth. */
    @Volatile
    private var starving = true

    /**
     * Cancelled from the main thread on a seek, so a chunk fetched for a
     * position the viewer has just left does not have to finish first.
     */
    @Volatile
    private var writer: CacheWriter? = null

    private var job: Job? = null
    private var activeKey: String? = null

    /**
     * Starts working ahead of [uri], or does nothing if one is already running
     * for the same file.
     *
     * The idempotence matters: this is called from every `STATE_READY` and
     * from the position ticker, both of which fire repeatedly through a film.
     * Without the guard, each rebuffer would tear down the loop and restart it
     * from an empty window.
     */
    fun start(uri: Uri, cacheKey: String?, durationMs: Long) {
        val key = cacheKey?.takeIf { it.isNotBlank() } ?: uri.toString()
        if (activeKey == key && job?.isActive == true) return
        stop()
        if (durationMs <= 0 || !canPrefetch(uri)) return
        activeKey = key
        job = scope.launch(Dispatchers.IO) { run(uri, key, durationMs) }
    }

    fun onPosition(positionMs: Long, playerBufferedMs: Long, playerLoading: Boolean) {
        this.positionMs = positionMs
        // Both halves are load-bearing; see [STARVING_BUFFER_MS] for why the
        // threshold on its own was not enough to tell a starving player from a
        // satisfied one.
        starving = playerLoading && playerBufferedMs < STARVING_BUFFER_MS
    }

    /**
     * Abandons the chunk in flight without stopping the loop — the next pass
     * recomputes the window from wherever the viewer just landed.
     */
    fun invalidate() {
        writer?.cancel()
    }

    fun stop() {
        writer?.cancel()
        writer = null
        job?.cancel()
        job = null
        activeKey = null
        starving = true
    }

    private suspend fun run(uri: Uri, key: String, durationMs: Long) {
        // One `CacheDataSource` for the whole run: it is opened and closed per
        // chunk by `CacheWriter`, and rebuilding the factory each time would
        // discard OkHttp's warm connection to the host along with it.
        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            // Same reasoning as the player's own source: a half-written span
            // from a mirror that died must never be why a working one fails.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()

        val copyBuffer = ByteArray(COPY_BUFFER_BYTES)
        var backoffMs = MIN_BACKOFF_MS
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            // The player is the only thing that can establish this, on its
            // first read, so early in playback there is simply nothing to work
            // from — and guessing a length would place every range request
            // wrong for the rest of the film.
            val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (contentLength <= 0 || starving) {
                delay(IDLE_POLL_MS)
                continue
            }

            // Bytes per millisecond, averaged over the whole file. This is an
            // approximation — a variable-bitrate encode is denser in some
            // places than others — and it does not need to be better than one.
            // Being a few seconds off only means the window starts slightly
            // before or after where the playhead really is, and the window is
            // minutes wide.
            val bytesPerMs = contentLength.toDouble() / durationMs
            val from = (positionMs * bytesPerMs).toLong().coerceIn(0L, contentLength)
            val windowBytes = minOf((WINDOW_MS * bytesPerMs).toLong(), maxWindowBytes)
            val target = minOf(from + windowBytes, contentLength)

            // The playhead is at the end of the file — the last minutes of a
            // film, or a position past it while a seek settles. Checked before
            // the index lookup below rather than after, so `getCachedLength` is
            // never asked about a zero-length range.
            if (from >= target) {
                delay(IDLE_POLL_MS)
                continue
            }

            // Skip whatever is already on disk in one contiguous run from the
            // playhead. `CacheWriter` would skip it too, but doing it here
            // means the common case — window already full — costs one index
            // lookup rather than opening a data source to discover the same.
            val cached = cache.getCachedLength(key, from, target - from)
            val cursor = if (cached > 0) from + cached else from
            if (cursor >= target) {
                delay(IDLE_POLL_MS)
                continue
            }

            val spec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(cursor)
                .setLength(minOf(CHUNK_BYTES, target - cursor))
                // Mirrors what `DefaultMediaSourceFactory` puts on the player's
                // own reads from `MediaItem.customCacheKey`. If these two
                // disagree the prefetcher fills a cache the player never looks
                // in, and every byte here is wasted twice — once downloading
                // it, once downloading it again.
                .setKey(key)
                .build()

            val chunk = CacheWriter(dataSource, spec, copyBuffer, /* progressListener = */ null)
            writer = chunk
            // `cache()` blocks until the chunk is on disk, the writer is
            // cancelled, or the read fails. None of those is a coroutine
            // cancellation, so `runCatching` here cannot swallow one.
            val failed = runCatching { chunk.cache() }.isFailure
            writer = null

            if (failed) {
                // Nothing here is worth reporting. A prefetch that fails costs
                // the viewer nothing — the player will fetch those bytes
                // itself when it reaches them — so the loop backs off and
                // tries further along rather than surfacing an error for
                // something that is not a playback problem.
                consecutiveFailures++
                // Giving up entirely, rather than retrying at a slower rate,
                // because of what a *run* of failures most likely means: a host
                // that allows one connection per link and is refusing this
                // second one. That is not a transient condition and it will not
                // improve over the film — meanwhile every attempt is another
                // request against a host that has already said no, on a link
                // the player still needs. Nothing is lost by stopping: the
                // player's own connection is untouched and playback continues
                // exactly as it did before this class existed.
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            } else {
                consecutiveFailures = 0
                backoffMs = MIN_BACKOFF_MS
            }
        }
    }

    private companion object {

        /**
         * Only progressive files over the network.
         *
         * HLS and DASH address media by segment through a manifest, so a byte
         * offset into the URI names part of a playlist rather than part of the
         * film; `file://` and `content://` are already on local storage, and
         * copying them into the cache would double the space for no gain.
         */
        fun canPrefetch(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false
            return Util.inferContentType(uri) == C.CONTENT_TYPE_OTHER
        }

        /**
         * How far ahead of the playhead to keep the cache filled.
         *
         * Ten minutes is far past any outage worth waiting through — which is
         * the point: on a high-bitrate remux this limit is not what binds.
         * `maxWindowBytes` is, and it is the honest constraint, because what
         * the disk can hold is a real quantity while a number of seconds is
         * only a wish. At ~25Mbps ten minutes is about 1.9GB, so on most boxes
         * the byte budget cuts the window well before this does, and this
         * exists mainly so a *low*-bitrate encode does not have the prefetcher
         * racing hours ahead of a viewer who is about to change episode.
         *
         * The cost of overshooting is bandwidth spent on film nobody watches,
         * which is why this is not simply unbounded.
         */
        const val WINDOW_MS = 600_000L

        /**
         * How much is fetched before the loop looks at the playhead again.
         *
         * The trade is between HTTP requests and wasted bytes. Smaller chunks
         * mean a new range request every few seconds of film; larger ones mean
         * a seek throws away more of what is in flight. 16MB is roughly five
         * seconds of a 25Mbps remux — frequent enough that the window tracks a
         * moving playhead, large enough that the request overhead disappears
         * against the transfer.
         */
        const val CHUNK_BYTES = 16L * 1024 * 1024

        /** `CacheWriter`'s own default; there is no reason to differ. */
        const val COPY_BUFFER_BYTES = 128 * 1024

        /**
         * Below this much buffered in the player itself, prefetching stops.
         *
         * This is the whole bandwidth-sharing policy. Above the line the
         * player's `LoadControl` has already stopped loading and the link is
         * idle, so this loop is using capacity nobody else wants. Below it the
         * player is fighting for every byte, and the correct amount of help to
         * offer is none.
         *
         * Paired with `isLoading` rather than applied alone, because as a lone
         * threshold it switched this class off on exactly the files it was
         * written for. The player's buffer is capped in **bytes** by
         * [HeapBudget], so what those bytes are worth in *seconds* falls as the
         * bitrate rises: the ~56MB a Fire TV Stick is allowed is around 18
         * seconds of a 25Mbps remux but only 11 of a 40Mbps one. Under the old
         * rule that second file sat permanently below this line — on a
         * completely full buffer, with the link completely idle — and the
         * prefetcher stood down for the entire film, on the one release whose
         * bitrate made it necessary.
         *
         * `isLoading` is what tells the two apart. A player that has stopped
         * loading is satisfied, however few seconds that turned out to buy, and
         * the bandwidth left over is genuinely spare.
         */
        const val STARVING_BUFFER_MS = 15_000L

        /** Long enough that a full window costs almost nothing to re-check. */
        const val IDLE_POLL_MS = 2_000L

        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 20_000L

        /**
         * Failed chunks in a row before the prefetcher stands down for good on
         * this source. Five is past anything a flaky link explains.
         */
        const val MAX_CONSECUTIVE_FAILURES = 5
    }
}
