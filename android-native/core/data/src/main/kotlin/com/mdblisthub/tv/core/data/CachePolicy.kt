package com.mdblisthub.tv.core.data

import java.util.concurrent.TimeUnit

/**
 * How long each kind of cached thing stays good.
 *
 * These are the numbers that decide whether the app opens instantly or waits
 * on the network, so they are stated in one place rather than sprinkled
 * through the repositories. The rule behind them: a row is refreshed on the
 * timescale its upstream actually changes, and a title's facts barely change
 * at all.
 */
object CachePolicy {

    /** mdblist rebuilds dynamic lists a few times a day. */
    val LISTS_MS: Long = TimeUnit.HOURS.toMillis(6)

    /** The contents of one list. Same reasoning, same window. */
    val LIST_ITEMS_MS: Long = TimeUnit.HOURS.toMillis(6)

    /** Cast, artwork and ratings. Ratings drift; nothing else does. */
    val DETAIL_MS: Long = TimeUnit.DAYS.toMillis(7)

    /**
     * A detail row built while mdblist or OMDb was failing — see
     * `MediaDetailEntity.metadataComplete`. Short, because the row is wrong
     * rather than merely old; long enough that a card the remote sweeps past
     * repeatedly does not retry a rate-limited API on every focus.
     */
    val PARTIAL_DETAIL_MS: Long = TimeUnit.MINUTES.toMillis(10)

    /** Episode lists only change when a season is airing. */
    val EPISODES_MS: Long = TimeUnit.DAYS.toMillis(2)

    /** Watchlist membership, which the user changes from other devices. */
    val LIBRARY_MS: Long = TimeUnit.MINUTES.toMillis(30)

    /** Detail rows for titles no longer in any list are dropped after this. */
    val ORPHAN_DETAIL_MS: Long = TimeUnit.DAYS.toMillis(30)

    fun isStale(fetchedAt: Long?, maxAge: Long, now: Long = System.currentTimeMillis()): Boolean =
        fetchedAt == null || fetchedAt <= 0L || now - fetchedAt > maxAge
}
