package com.mdblisthub.tv.player

/**
 * Decides, poll by poll, whether a committed source has stopped being worth
 * waiting for.
 *
 * Pulled out of `PlaybackController.startStallWatch` rather than left inline,
 * because the bug this class was extracted to fix was not visible from the
 * outside. A source trickling a few milliseconds of media per second and a
 * source delivering normally produce the same sequence of player states, the
 * same logs, and — under the old rule, which asked only whether
 * `bufferedPosition` had *moved* — the same verdict. The difference is
 * arithmetic over a series of polls, and arithmetic over a series is exactly
 * what cannot be checked by watching a film play.
 *
 * Everything the decision needs is a parameter, and nothing here reads a
 * player, a clock or a socket. That is the point: [onPoll] is a pure function
 * of the inputs and the counters below, so the freeze a viewer reports as
 * "it only comes back when I press skip" can be written down as a test.
 */
internal class StallTracker(
    private val pollMs: Long,
    private val frozenMs: Long,
    private val suspectMs: Long,
    private val minProgressMs: Long,
    private val softReconnectLimit: Int,
) {

    /** What the watch should do about this poll. */
    enum class Action {
        /** Nothing yet — either the source is fine or the evidence is still accumulating. */
        WAIT,

        /** Reopen the socket at the same moment of the film, keeping the picture. */
        SOFT_RECONNECT,

        /** The link ignored a reconnection too; re-prepare the source cold. */
        REOPEN,
    }

    private var frozenForMs = 0L
    private var reference = UNSET
    private var softReconnects = 0

    /**
     * @param waiting whether the player is stopped, wants to play, and has a
     *   committed source — anything else is not a stall to repair.
     * @param bufferedMs the forward edge of the buffer, in film time.
     * @param suspect whether the connection is already presumed dead, which
     *   shortens the proof required from [frozenMs] to [suspectMs].
     * @param settling whether a seek is recent enough that silence is still
     *   the expected state. The freeze counter keeps running underneath — this
     *   postpones the response, it does not forgive it.
     */
    fun onPoll(
        waiting: Boolean,
        bufferedMs: Long,
        suspect: Boolean,
        settling: Boolean,
    ): Action {
        if (!waiting) {
            // Decays rather than resets. A link that delivers half a second of
            // film between ten-second waits is not recovering, but under the
            // old rule each of those half-seconds zeroed the counter, so the
            // ladder below never reached its first rung. Decaying at the rate
            // it accumulates means a source that really is playing clears its
            // debt in seconds while one that is merely twitching does not.
            frozenForMs = (frozenForMs - pollMs).coerceAtLeast(0L)
            if (frozenForMs == 0L) softReconnects = 0
            reference = UNSET
            return Action.WAIT
        }

        // Progress is measured against a reference several polls back, and it
        // has to be *meaningful*. Any movement at all used to count as proof
        // of life, which made a mirror trickling a few milliseconds per second
        // indistinguishable from a healthy one — while playback, which will
        // not resume until `bufferForPlaybackAfterRebufferMs` is buffered,
        // stayed stopped for as long as the trickle lasted.
        //
        // A buffer *behind* the reference means a backward seek moved it, and
        // a difference that can never turn positive is not a measurement.
        // No reference to measure against, or a buffer behind one — the
        // latter meaning a backward seek moved it, and a difference that can
        // never turn positive is not a measurement. Establish and wait.
        //
        // The debt is deliberately *kept* across this. Re-establishing a
        // reference is not evidence that anything was delivered, and zeroing
        // here reopened the hole the decay above was closing: a stutter that
        // ends each frozen run with one poll of playback comes back with the
        // reference cleared, and if that cleared the counter too the ladder
        // could never be reached however long the stuttering lasted.
        if (reference == UNSET || bufferedMs < reference) {
            reference = bufferedMs
            return Action.WAIT
        }
        if (bufferedMs - reference >= minProgressMs) {
            reference = bufferedMs
            frozenForMs = 0L
            return Action.WAIT
        }

        frozenForMs += pollMs
        if (frozenForMs < (if (suspect) suspectMs else frozenMs)) return Action.WAIT
        if (settling) return Action.WAIT

        // Cleared before the repair rather than after: both rungs return long
        // before anything is delivered, and the next poll must not measure
        // staleness against a buffer position belonging to the connection just
        // abandoned.
        frozenForMs = 0L
        reference = UNSET

        if (softReconnects < softReconnectLimit) {
            softReconnects++
            return Action.SOFT_RECONNECT
        }
        softReconnects = 0
        return Action.REOPEN
    }

    private companion object {
        /**
         * "No reference yet", as a value no buffer position can take.
         * Deliberately not `C.TIME_UNSET`: nothing else here knows what a
         * media library is, and keeping it that way is what makes this
         * testable on the JVM.
         */
        const val UNSET = Long.MIN_VALUE
    }
}
