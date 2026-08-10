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
    private const val FALLBACK_BYTES = 256L * 1024 * 1024

    private const val MIN_BYTES = 64L * 1024 * 1024
    private const val MAX_BYTES = 1024L * 1024 * 1024

    /**
     * Share of the app's cache quota this may take.
     *
     * The quota covers everything under `cacheDir` — the 96MB HTTP cache and
     * the artwork cache live there too — so claiming all of it would simply
     * push the system into evicting one of the three, and the one it evicts is
     * not ours to choose.
     */
    private const val QUOTA_SHARE = 0.5

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): Cache? {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: runCatching {
                val app = context.applicationContext
                val directory = File(app.cacheDir, DIRECTORY)
                SimpleCache(
                    directory,
                    LeastRecentlyUsedCacheEvictor(budgetBytes(app)),
                    // Keeps the span index in SQLite instead of rebuilding it
                    // by walking every cached file at startup, which on a
                    // gigabyte of cache is the difference between opening the
                    // player instantly and stalling on it.
                    StandaloneDatabaseProvider(app),
                )
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
    private fun budgetBytes(context: Context): Long {
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

        return budget.coerceIn(MIN_BYTES, MAX_BYTES)
    }
}
