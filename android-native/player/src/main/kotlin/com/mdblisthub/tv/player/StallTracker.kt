package com.mdblisthub.tv.player

/**
 * Decides, poll by poll, whether the connection feeding the player has stopped
 * being worth keeping.
 *
 * It measures whether the forward edge of the buffer keeps up with real time
 * while the loader is asking for bytes, and it starts measuring long before the
 * buffer runs out. Waiting for `STATE_BUFFERING` meant every bad connection
 * became a frozen picture first, when replacing it costs nothing as long as it
 * happens while there is still film in hand.
 *
 * Everything the decision needs is a parameter, and nothing here reads a
 * player, a clock or a socket, so a freeze can be written down as a test.
 *
 * The thresholds come from measurements: see `player/PLAYBACK_STALLS.md`.
 */
internal class StallTracker(
    private val pollMs: Long,
    private val windowMs: Long,
    private val waitingWindowMs: Long,
    private val maxWindowMs: Long,
    /** Share of real time the buffer must gain for a connection to count as healthy. */
    private val healthyRate: Double,
    /** Below this share, a stopped picture is not waiting on a slow link but on a dead one. */
    private val deadRate: Double,
    private val reconnectsBeforeReopen: Int,
) {

    /** What the watch should do about this poll. */
    enum class Action {
        /** Nothing yet — the source is fine, or the evidence is still accumulating. */
        WAIT,

        /** Replace the socket and resume in place, which nobody can see. */
        RECONNECT,

        /** The link ignored new connections too; re-prepare the source cold. */
        REOPEN,
    }

    /** The buffer's edge at each poll, newest last, as far back as the longest window. */
    private val edges = ArrayDeque<Long>()
    private var poorWindows = 0
    private var deadWindows = 0

    /**
     * Judged over the trailing window on every poll, not over windows laid end to end.
     * A connection that sends a burst and dies would otherwise be saved by the burst,
     * and be noticed a whole window after it mattered.
     *
     * @param loaderActive whether the loader is asking for bytes. A full buffer
     *   gains nothing by design, and that is not a measurement.
     * @param waiting whether the picture has stopped for lack of media.
     * @param bufferedMs the forward edge of the buffer, in film time.
     * @param settling whether a seek is recent enough that silence is still the
     *   expected state.
     */
    fun onPoll(loaderActive: Boolean, waiting: Boolean, bufferedMs: Long, settling: Boolean): Action {
        if (!loaderActive || settling) {
            edges.clear()
            return Action.WAIT
        }
        // A buffer behind the last reading means a backward seek moved it.
        if (edges.isNotEmpty() && bufferedMs < edges.last()) edges.clear()
        edges.addLast(bufferedMs)
        if (edges.size > maxWindowMs / pollMs + 1) edges.removeFirst()

        val base = if (waiting) waitingWindowMs else windowMs

        // Next to nothing arriving is never backed off from, because there is no
        // throughput left to protect.
        val recent = rateOver(base)
        if (recent != null && recent < deadRate) {
            restartFrom(bufferedMs)
            if (!waiting) {
                deadWindows = 0
                return Action.RECONNECT
            }
            deadWindows++
            if (deadWindows > reconnectsBeforeReopen) {
                deadWindows = 0
                return Action.REOPEN
            }
            return Action.RECONNECT
        }

        // A link that delivers does get backed off from: when it is simply slower than
        // the film, every connection is as poor as the last, and a reconnect every few
        // seconds costs a fifth of the little it has.
        val backedOff = (base shl poorWindows.coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(maxWindowMs)
        val rate = rateOver(backedOff) ?: return Action.WAIT
        if (rate >= healthyRate) {
            poorWindows = 0
            deadWindows = 0
            return Action.WAIT
        }
        restartFrom(bufferedMs)
        poorWindows++
        deadWindows = 0
        return Action.RECONNECT
    }

    /** What the buffer gained per unit of real time, or null with less history than that. */
    private fun rateOver(windowMs: Long): Double? {
        val polls = (windowMs / pollMs).toInt()
        if (edges.size <= polls) return null
        return (edges.last() - edges[edges.size - 1 - polls]).toDouble() / windowMs
    }

    private fun restartFrom(bufferedMs: Long) {
        edges.clear()
        edges.addLast(bufferedMs)
    }

    private companion object {
        const val MAX_BACKOFF_SHIFT = 6
    }
}
