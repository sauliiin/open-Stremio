package com.mdblisthub.tv.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Downloads ahead of the player, onto disk: the deep cushion the Java heap
 * cannot afford on a TV.
 *
 * **The only writer of the cache.** The player reads it without ever writing to
 * it, and that is not an optimisation. A read-through `CacheDataSource` holds a
 * lock on the whole hole it is filling, up to the next cached span or the end of
 * the file, so a second writer can never get a lock anywhere ahead of the player.
 * This class used to run alongside one: every chunk it fetched was read and thrown
 * away, the cursor stayed where it was, and after three chunks it gave up, having
 * spent 48MB of the link and stored nothing.
 *
 * With no lock in the way, the player simply finds these spans when its loader
 * reaches them, and moves its read from the network onto the disk. From then on
 * this is the only thing using the link.
 *
 * **It yields the link whenever the player's next bytes are not on disk.** A player
 * reading from the cache is not competing for anything, so it is not a reason to
 * stop. A player reading from the network is, because splitting a bottleneck two
 * ways makes the stall longer.
 *
 * Every field read across the thread boundary is `@Volatile` and pushed in from the
 * main thread: ExoPlayer is single-threaded by contract and this loop runs on IO.
 *
 * See `player/PLAYBACK_STALLS.md` before changing who writes the cache.
 */
@OptIn(UnstableApi::class)
internal class MediaPrefetcher(
    private val cache: Cache,
    private val upstreamFactory: DataSource.Factory,
    private val calls: AbortableCallFactory,
    private val loader: LoaderTap,
    private val scope: CoroutineScope,
    /**
     * Ceiling on how far ahead this may work, derived from the cache the spans have
     * to fit in. Wider than the cache, the evictor would delete the film being
     * watched to make room for the part not yet reached.
     */
    private val maxWindowBytes: Long,
) {

    @Volatile
    private var positionMs = 0L

    @Volatile
    private var playerLoading = true

    @Volatile
    private var writer: CacheWriter? = null

    /** Set when this class ended a chunk itself, so that is not read as the host failing. */
    @Volatile
    private var chunkCancelled = false

    private var job: Job? = null

    @Volatile
    private var activeKey: String? = null

    /**
     * A file given up on, not to be restarted for. [start] runs on every position
     * tick, so the verdict has to outlive the coroutine that reached it. Cleared by
     * [stop], which is what a genuinely new source goes through.
     */
    private var surrenderedKey: String? = null

    /** Starts working ahead of [uri], or does nothing if already doing so. */
    fun start(uri: Uri, cacheKey: String?, durationMs: Long) {
        val key = cacheKey?.takeIf { it.isNotBlank() } ?: uri.toString()
        if (key == surrenderedKey) return
        if (activeKey == key && job?.isActive == true) return
        stop()
        if (durationMs <= 0 || !canPrefetch(uri)) return
        activeKey = key
        job = scope.launch(Dispatchers.IO) { run(uri, key, durationMs) }
    }

    /** A sample of the playhead and the loader, in case an event was missed. */
    fun onPosition(positionMs: Long, playerLoading: Boolean) {
        this.positionMs = positionMs
        this.playerLoading = playerLoading
    }

    /**
     * The player's `LoadControl` started or stopped wanting bytes. Pushed rather than
     * read off the position tick, which runs every four seconds once the OSD is
     * hidden — long enough to empty a small buffer on a shared link.
     */
    fun onLoadingChanged(loading: Boolean) {
        playerLoading = loading
        val key = activeKey ?: return
        if (playerNeedsLink(key)) cancelChunk()
    }

    /** The viewer moved, so the chunk in flight was fetched for somewhere they left. */
    fun invalidate() = cancelChunk()

    fun stop() {
        cancelChunk()
        job?.cancel()
        job = null
        activeKey = null
        surrenderedKey = null
        playerLoading = true
    }

    private fun playerNeedsLink(key: String): Boolean =
        playerLoading && !cache.isCached(key, loader.readPosition, COVERED_BYTES)

    /** Aborts the call too: a chunk stuck on a quiet socket would not see the flag. */
    private fun cancelChunk() {
        chunkCancelled = true
        writer?.cancel()
        calls.abortLatest()
    }

    private suspend fun run(uri: Uri, key: String, durationMs: Long) {
        // One data source for the whole run: `CacheWriter` opens and closes it per
        // chunk, and rebuilding it would discard the warm connection to the host.
        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()

        val copyBuffer = ByteArray(COPY_BUFFER_BYTES)
        var backoffMs = MIN_BACKOFF_MS
        var refusals = 0
        var lastCompletedCursor = -1L
        var unproductiveChunks = 0
        var loggedAhead = -1L

        while (currentCoroutineContext().isActive) {
            val contentLength = contentLengthOf(key)
            if (contentLength <= 0 || playerNeedsLink(key)) {
                delay(IDLE_POLL_MS)
                continue
            }

            // Averaged over the whole file. A variable bitrate makes this a few
            // seconds off, against a window minutes wide.
            val bytesPerMs = contentLength.toDouble() / durationMs
            val playhead = (positionMs * bytesPerMs).toLong()
            val from = maxOf(playhead, loader.readPosition).coerceIn(0L, contentLength)
            // Measured from the loader, not the playhead: what sits between the two is
            // already in the heap, and a window counting it could end before the
            // buffer does.
            val windowBytes = minOf((WINDOW_MS * bytesPerMs).toLong(), maxWindowBytes)
            val target = minOf(from + windowBytes, contentLength)
            if (from >= target) {
                delay(IDLE_POLL_MS)
                continue
            }

            // Skips what is already on disk in one contiguous run past the loader.
            val cached = cache.getCachedLength(key, from, target - from)
            val cursor = if (cached > 0) from + cached else from
            if (cursor >= target) {
                delay(IDLE_POLL_MS)
                continue
            }

            if (cursor == lastCompletedCursor) {
                unproductiveChunks++
                if (unproductiveChunks >= MAX_UNPRODUCTIVE_CHUNKS) {
                    Log.i(TAG, "read-ahead standing down: $cursor fetched $unproductiveChunks times, not retained")
                    surrenderedKey = key
                    return
                }
            } else {
                unproductiveChunks = 0
            }

            val spec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(cursor)
                .setLength(minOf(CHUNK_BYTES, target - cursor))
                // The key the player reads under. If these disagree, this fills a
                // cache the player never looks in.
                .setKey(key)
                .build()

            chunkCancelled = false
            val chunk = CacheWriter(dataSource, spec, copyBuffer) { _, _, _ ->
                if (playerNeedsLink(key)) cancelChunk()
            }
            writer = chunk
            // Blocks until the chunk is on disk, the writer is cancelled, or the read
            // fails. None of those is a coroutine cancellation.
            val error = runCatching { chunk.cache() }.exceptionOrNull()
            writer = null

            when {
                chunkCancelled -> Unit
                error == null -> {
                    lastCompletedCursor = cursor
                    refusals = 0
                    backoffMs = MIN_BACKOFF_MS
                    val ahead = (cursor + spec.length - loader.readPosition) / LOG_STEP_BYTES
                    if (ahead != loggedAhead) {
                        loggedAhead = ahead
                        Log.d(TAG, "read-ahead ${ahead * LOG_STEP_BYTES / MB}MB on disk past the loader")
                    }
                }
                else -> {
                    // A host answering 4xx or 5xx most likely allows one connection per
                    // link and is refusing this second one, which will not improve over
                    // the film. Nothing is lost by stopping: the player's own connection
                    // is untouched. A read that timed out says nothing of the kind, and
                    // giving up on it would cost the rest of the film its read-ahead
                    // over one network outage.
                    if (error.isRefusal()) refusals++
                    if (refusals >= MAX_REFUSALS) {
                        Log.i(TAG, "read-ahead standing down: refused $refusals times in a row")
                        surrenderedKey = key
                        return
                    }
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    /**
     * Nothing writes the length for this class any more: `CacheDataSource` only stores
     * it while writing, and the player no longer does. So it is taken from the player's
     * own response and stored, which also lets a later session find it when every byte
     * it needs at first is already on disk.
     */
    private fun contentLengthOf(key: String): Long {
        val stored = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (stored > 0) return stored
        val reported = loader.contentLength
        if (reported > 0) {
            val mutations = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(mutations, reported)
            runCatching { cache.applyContentMetadataMutations(key, mutations) }
        }
        return reported
    }

    private companion object {

        const val TAG = "Playback"

        const val MB = 1024L * 1024L

        const val LOG_STEP_BYTES = 64L * MB

        /**
         * Only progressive files over the network. HLS and DASH address media by
         * segment, so a byte offset would name part of a playlist, and a local file is
         * already on disk.
         */
        fun canPrefetch(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false
            return Util.inferContentType(uri) == C.CONTENT_TYPE_OTHER
        }

        /**
         * How far ahead of the loader to keep the cache filled. On a high-bitrate
         * remux `maxWindowBytes` binds first; this keeps a low-bitrate encode from
         * racing hours ahead of a viewer who is about to change episode.
         */
        const val WINDOW_MS = 600_000L

        /** How much is fetched before the loop looks at the playhead again. */
        const val CHUNK_BYTES = 16L * 1024 * 1024

        /** `CacheWriter`'s own default. */
        const val COPY_BUFFER_BYTES = 128 * 1024

        /**
         * How much must be on disk past the loader for it not to need the link. Well
         * above `CacheDataSource`'s 100KB between looks at the cache, so the player is
         * sure to find it there.
         */
        const val COVERED_BYTES = 1024L * 1024

        const val IDLE_POLL_MS = 2_000L

        /**
         * Chunks fetched at the same offset before giving up. A span that is written and
         * then evicted leaves the cursor where it was, and left alone this would fetch
         * the same bytes forever on the link the player is using.
         */
        const val MAX_UNPRODUCTIVE_CHUNKS = 3

        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 20_000L

        /** Refused chunks in a row before standing down for good on this source. */
        const val MAX_REFUSALS = 5
    }
}

private fun Throwable.isRefusal(): Boolean = causes().any {
    it is HttpDataSource.InvalidResponseCodeException
}
