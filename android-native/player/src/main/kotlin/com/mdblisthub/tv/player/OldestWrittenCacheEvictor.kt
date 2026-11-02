package com.mdblisthub.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Evicts in the order the spans were written.
 *
 * Least-recently-used is backwards for read-ahead. A span the player has just read
 * counts as recently used, and one written minutes ago and waiting to be played does
 * not, so a full cache throws away the film that is coming up and keeps the film
 * already watched. The hole that leaves is filled again, by evicting the next one.
 *
 * [MediaPrefetcher] writes in file order, so oldest written is furthest behind the
 * viewer, and a previous film goes before any of this one. Not asking for span touches
 * is what keeps each span's timestamp at the moment it was written.
 */
@OptIn(UnstableApi::class)
internal class OldestWrittenCacheEvictor(private val maxBytes: Long) : CacheEvictor {

    private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it })
    private var currentBytes = 0L

    override fun requiresCacheSpanTouches(): Boolean = false

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        currentBytes += span.length
        evict(cache, 0L)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        currentBytes -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(cache: Cache, requiredBytes: Long) {
        while (currentBytes + requiredBytes > maxBytes && spans.isNotEmpty()) {
            cache.removeSpan(spans.first())
        }
    }
}
