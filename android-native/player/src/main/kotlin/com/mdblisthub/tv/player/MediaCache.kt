package com.mdblisthub.tv.player

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * On-disk cache for the video itself.
 *
 * Without it the only thing standing between a rewind and a re-download is the
 * in-memory back buffer, which is measured in seconds — so skipping back past
 * it re-fetches bytes the box already had, and a failover that retries the
 * same URL starts from zero.
 *
 * **One instance per process, always.** `SimpleCache` throws if a second
 * instance is pointed at a directory an existing one already holds, so this is
 * an object rather than something the controller builds per playback.
 */
@OptIn(UnstableApi::class)
object MediaCache {

    private const val DIRECTORY = "media"

    /** What to ask for when the system will not say what it allows. */
    private const val FALLBACK_BYTES = 512L * 1024 * 1024

    private const val MIN_BYTES = 64L * 1024 * 1024

    /**
     * The ceiling on read-ahead, in the only currency that is actually cheap.
     *
     * This is the number that decides how deep the forward buffer can get, and
     * it is worth being clear about why it may be this large when
     * [HeapBudget]'s equivalent must be small. That one is `byte[]` on the Java
     * heap, where every extra megabyte is collector work and the ceiling is a
     * couple of hundred megabytes on a stick. This is a file. It costs no heap,
     * produces no garbage, and the only thing it competes for is storage the
     * evictor manages anyway.
     *
     * So the useful size is set by what it has to survive: a mirror or a home
     * link going quiet. At the ~25Mbps of a high-bitrate remux, two gigabytes
     * is roughly ten minutes of film held ahead of the playhead — past any
     * outage worth waiting through, and the quota below almost always binds
     * first on a real device. Raising it does not *reserve* anything; it only
     * stops an arbitrary constant from being the reason a box with room to
     * spare buffers less than it could.
     */
    private const val MAX_BYTES = 2048L * 1024 * 1024

    /**
     * Share of the app's cache quota this may take.
     *
     * The quota covers everything under `cacheDir` — the 96MB HTTP cache and
     * the artwork cache live there too — so claiming all of it would simply
     * push the system into evicting one of the three, and the one it evicts is
     * not ours to choose.
     *
     * Raised from half now that the media cache carries the forward buffer
     * rather than only serving rewinds. The three consumers are not equal any
     * more: a cold artwork thumbnail costs one fast request, a cold metadata
     * response costs one more, and a cold *film* costs the picture stopping.
     * The remaining 30% is still comfortably more than the other two use
     * together.
     */
    private const val QUOTA_SHARE = 0.7

    /**
     * Share of the storage that is *actually free right now* the cache may
     * occupy, regardless of what the quota allows.
     *
     * A second, independent limit, for the same reason [HeapBudget] takes the
     * smaller of a heap reading and a RAM reading. `getCacheQuotaBytes` is a
     * policy number: it says what the platform will let this app keep, and on
     * some devices it answers generously without much reference to the disk it
     * is describing. Free space is a physical number. On an 8GB stick with a
     * couple of gigabytes left, filling that with film is antisocial even where
     * the quota permits it — the user notices a device that can no longer
     * install an app long before they notice a deeper buffer.
     *
     * A quarter leaves the box obviously usable while still being, on any
     * normal device, far more than the read-ahead window can consume.
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
                // published figure: it reads a live storage quota, so calling
                // it twice can hand out two different numbers and leave the
                // prefetcher sizing its window against a budget the evictor
                // is not actually enforcing.
                val budget = resolveBudgetBytes(app)
                SimpleCache(
                    directory,
                    LeastRecentlyUsedCacheEvictor(budget),
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

    /**
     * Sized from what the system says it will let this app keep, rather than
     * from a constant.
     *
     * `getCacheQuotaBytes` is the number that actually decides whether the
     * cache survives: write past it and the platform deletes from `cacheDir`
     * under storage pressure, so an oversized cache does not just fail to
     * help, it silently discards work — and the miss rate that produces looks
     * exactly like a broken cache key.
     */
    private fun resolveBudgetBytes(context: Context): Long {
        val quota = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val storage = context.getSystemService(StorageManager::class.java)
                val uuid = storage.getUuidForPath(context.cacheDir)
                storage.getCacheQuotaBytes(uuid)
            }.getOrNull()
        } else {
            null
        }

        val budget = quota
            ?.takeIf { it > 0 }
            ?.let { (it * QUOTA_SHARE).toLong() }
            ?: FALLBACK_BYTES

        // Whichever of the two limits is tighter, then the constants. The free
        // space check is skipped rather than trusted when it reads zero, since
        // that is what an unreadable volume returns and it would otherwise
        // collapse every cache to MIN_BYTES.
        val free = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val storage = context.getSystemService(StorageManager::class.java)
                val uuid = storage.getUuidForPath(context.cacheDir)
                storage.getAllocatableBytes(uuid)
            } else {
                context.cacheDir.usableSpace
            }
        }.getOrDefault(0L)
        val affordable = if (free > 0) (free * FREE_SPACE_SHARE).toLong() else Long.MAX_VALUE

        return minOf(budget, affordable).coerceIn(MIN_BYTES, MAX_BYTES)
    }
}
