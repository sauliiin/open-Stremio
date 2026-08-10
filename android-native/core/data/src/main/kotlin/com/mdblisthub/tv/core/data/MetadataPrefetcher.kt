package com.mdblisthub.tv.core.data

import com.mdblisthub.tv.core.data.repository.MediaRepository
import com.mdblisthub.tv.core.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Warms one title's detail the moment focus *settles* on a card.
 *
 * Deliberately *not* a WorkManager job. WorkManager is for work that must
 * survive the process and may be deferred by hours; this is the opposite — it
 * is only useful within the next second or two, and if the user moves on it
 * should simply be dropped.
 *
 * The settle delay is the important part. Holding a direction key sweeps
 * focus across a whole row in a second, and warming is three API calls per
 * title — so firing on every focus meant a sweep past twenty cards queued
 * sixty requests for titles the user never stopped on. Those are what
 * rate-limited mdblist, which is how titles ended up cached with no ratings.
 * Cancelling the pending warm on each new focus means only the card actually
 * landed on is ever fetched.
 */
class MetadataPrefetcher(
    private val media: MediaRepository,
    private val scope: CoroutineScope,
) {
    private val warmed = ConcurrentHashMap<String, Long>()
    private val permits = Semaphore(MAX_IN_FLIGHT)

    /** Only the most recent focus is ever waiting; the rest are cancelled. */
    private var pending: Job? = null

    fun prefetch(type: MediaType, tmdbId: Int): Job? {
        val key = "${type.mdblist}:$tmdbId"
        val last = warmed[key]
        if (last != null && System.currentTimeMillis() - last < REWARM_MS) return null

        pending?.cancel()
        val job = scope.launch {
            delay(SETTLE_MS)

            // Claimed only once the focus actually held, so a card swept past
            // is not recorded as warmed and can still be fetched later.
            warmed[key] = System.currentTimeMillis()
            pruneIfCrowded()

            permits.withPermit {
                // A failure here is not worth reporting: the detail screen
                // will ask again and surface its own error if it matters.
                media.ensureDetail(type, tmdbId)
            }
        }
        pending = job
        return job
    }

    /**
     * Drops entries past their re-warm window once the map has grown enough
     * to be worth walking — an evening of browsing otherwise leaves one entry
     * per title ever focused, none of which is read again.
     */
    private fun pruneIfCrowded() {
        if (warmed.size < PRUNE_ABOVE) return
        val cutoff = System.currentTimeMillis() - REWARM_MS
        warmed.entries.removeAll { it.value < cutoff }
    }

    private companion object {
        const val MAX_IN_FLIGHT = 3
        const val REWARM_MS = 10 * 60 * 1000L

        /**
         * Long enough that a held direction key never triggers one, short
         * enough to still be warm by the time OK is pressed.
         */
        const val SETTLE_MS = 350L
        const val PRUNE_ABOVE = 512
    }
}
