package com.mdblisthub.tv.player

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * On-disk cache for the video itself.
 *
 * What [MediaPrefetcher] works ahead into, and what the player then reads instead of
 * the network. It also serves any rewind over ground fetched that way.
 *
 * **One instance per process, always.** `SimpleCache` throws if a second
 * instance is pointed at a directory an existing one already holds, so this is
 * an object rather than something the controller builds per playback.
 */
@OptIn(UnstableApi::class)
object MediaCache {

    private const val DIRECTORY = "media"

    /** What to ask for when the volume will not say how much room it has. */
    private const val FALLBACK_BYTES = 512L * 1024 * 1024

    private const val MIN_BYTES = 64L * 1024 * 1024

    /**
     * The ceiling on read-ahead, in the only currency that is actually cheap.
     *
     * [HeapBudget]'s equivalent has to be small: that one is `byte[]` on the Java heap.
     * This is a file. It costs no heap, produces no garbage, and competes only for
     * storage the evictor manages anyway. At the ~65Mbps of a 4K remux, three quarters
     * of this is five minutes of film ahead of the buffer, past any outage worth waiting
     * through. Raising it reserves nothing.
     */
    private const val MAX_BYTES = 4096L * 1024 * 1024

    /**
     * Share of the storage that is free right now the cache may occupy.
     *
     * This is the limit that binds, on purpose, and not `getCacheQuotaBytes`. The quota
     * measured a couple of hundred megabytes on the devices tested, about what the heap
     * already holds, which left read-ahead nothing to add. It is not a limit either: it
     * only says whose files go first if the volume fills up, and `SimpleCache` tolerates
     * losing a span. A quarter of the free space leaves the box usable.
     */
    private const val FREE_SPACE_SHARE = 0.25

    @Volatile
    private var instance: SimpleCache? = null

    /**
     * The budget [get] actually resolved to, or zero before the cache exists.
     *
     * Published because [MediaPrefetcher] has to bound its read-ahead window by
     * the size of the cache it is filling — a window wider than the budget
     * makes the evictor delete the part of the film being watched to make room
     * for the part that has not been reached yet.
     */
    @Volatile
    var budgetBytes: Long = 0L
        private set

    /**
     * Opens the cache without handing it back.
     *
     * Exists so the application can pay [get]'s cost — a storage-quota binder
     * call, a `stat` of the volume and opening the SQLite span index — on a
     * background thread at launch, instead of leaving it on the main thread
     * inside `PlaybackController`'s constructor, between the OK keypress and
     * the first addon request. Returning `Unit` rather than the `Cache` keeps
     * the unstable Media3 type from leaking into `:app`, which has no business
     * holding one.
     */
    fun warm(context: Context) {
        get(context)
    }

    fun get(context: Context): Cache? {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: runCatching {
                val app = context.applicationContext
                val directory = File(app.cacheDir, DIRECTORY)
                // Resolved once and reused for the evictor and for the
                // published figure: it reads the free space, so calling it twice
                // can hand out two different numbers and leave the prefetcher
                // sizing its window against a budget the evictor is not enforcing.
                val budget = resolveBudgetBytes(app)
                SimpleCache(
                    directory,
                    OldestWrittenCacheEvictor(budget),
                    // Keeps the span index in SQLite instead of rebuilding it
                    // by walking every cached file at startup, which on a
                    // gigabyte of cache is the difference between opening the
                    // player instantly and stalling on it.
                    StandaloneDatabaseProvider(app),
                ).also { budgetBytes = budget }
            }.getOrNull()?.also { instance = it }
            // A cache that cannot be created is not worth failing playback
            // over: the caller treats null as "stream straight through".
        }
    }

    private fun resolveBudgetBytes(context: Context): Long {
        // Skipped rather than trusted when it reads zero, since that is what an
        // unreadable volume returns and it would collapse every cache to MIN_BYTES.
        val free = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val storage = context.getSystemService(StorageManager::class.java)
                storage.getAllocatableBytes(storage.getUuidForPath(context.cacheDir))
            } else {
                context.cacheDir.usableSpace
            }
        }.getOrDefault(0L)
        val budget = if (free > 0) (free * FREE_SPACE_SHARE).toLong() else FALLBACK_BYTES
        return budget.coerceIn(MIN_BYTES, MAX_BYTES)
    }
}
