package com.mdblisthub.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The old stall rule against the new one, on the same inputs.
 *
 * The old rule is reproduced exactly by `minProgressMs = 1`: any movement at
 * all clears the suspicion, which is what "has `bufferedPosition` changed?"
 * meant. That equivalence is what makes this a comparison rather than a
 * re-implementation — there is only one [StallTracker] here, configured two
 * ways.
 *
 * Written because the claim being made for the change needed checking rather
 * than asserting, and checking it narrowed it. The new rule is not better on
 * every stall; it is better on one regime, identical on the rest, and never
 * worse anywhere. Which of those applies to a given viewer's mirror is an
 * empirical question, not something the diff can answer.
 */
class StallRuleComparisonTest {

    private fun oldRule() = tracker(minProgressMs = 1L)
    private fun newRule() = tracker(minProgressMs = 2_000L)

    private fun tracker(minProgressMs: Long) = StallTracker(
        pollMs = 1_000L,
        frozenMs = 10_000L,
        suspectMs = 3_000L,
        minProgressMs = minProgressMs,
        softReconnectLimit = 1,
    )

    /** Poll index at which the rule first asks for a repair, or null if it never does. */
    private fun firstAction(tracker: StallTracker, series: List<Long>): Int? {
        series.forEachIndexed { index, buffered ->
            val action = tracker.onPoll(
                waiting = true,
                bufferedMs = buffered,
                suspect = false,
                settling = false,
            )
            if (action != StallTracker.Action.WAIT) return index
        }
        return null
    }

    /**
     * Captured from the emulator at 160kbit/s, on a source the player could
     * not keep up with: `bufferedPosition` gained **10ms of media roughly
     * every seventeen seconds**. Bytes were arriving the whole time.
     *
     * And on this trace the two rules are indistinguishable — both repair at
     * the eleventh poll. The bumps are spaced further apart than the freeze
     * threshold, so the old rule's counter reaches ten seconds between them
     * just as the new one does.
     *
     * This is the measurement that corrected the claim. A trickle alone is not
     * enough to defeat the old rule; it has to be a trickle that touches the
     * buffer *often*.
     */
    @Test
    fun `on the captured trickle the two rules agree`() {
        val captured = List(16) { 3_070_651L } + List(19) { 3_070_661L } + List(15) { 3_070_671L }

        assertEquals(firstAction(oldRule(), captured), firstAction(newRule(), captured))
        assertEquals(10, firstAction(newRule(), captured))
    }

    /**
     * The regime where they differ, and the one the change exists for: a
     * mirror that touches the buffer on *every* poll while delivering far
     * less than playback consumes.
     *
     * 100ms of media per second is a twentieth of real time. Playback will not
     * resume until `bufferForPlaybackAfterRebufferMs` is buffered, which from
     * empty is a hundred seconds away at this rate — and the old rule never
     * repairs it, because something moved every single time it looked.
     */
    @Test
    fun `a buffer touched every poll defeats the old rule and not the new one`() {
        val trickle = (1..120).map { 60_000L + it * 100L }

        assertEquals("the old rule never repairs this", null, firstAction(oldRule(), trickle))
        assertEquals(10, firstAction(newRule(), trickle))
    }

    /**
     * The safety property, which matters more than either case above: across
     * every shape of series, the new rule can only ever act *sooner*. A change
     * to a repair that tears down a working connection has to be shown not to
     * fire earlier than warranted on sources the old rule was happy with —
     * this is the other half of that, that it never fires later either.
     */
    @Test
    fun `the new rule never acts later than the old one`() {
        val shapes = buildList {
            // Frozen solid.
            add(List(60) { 50_000L })
            // Healthy: half of real time.
            add((1..60).map { 50_000L + it * 500L })
            // Faster than real time — a source catching up.
            add((1..60).map { 50_000L + it * 3_000L })
            // Trickles of every cadence, from every-poll to once a minute.
            for (period in 1..20) {
                add((1..60).map { 50_000L + (it / period) * 10L })
            }
            // Bursty: quiet, then a jump, repeatedly.
            add((1..60).map { 50_000L + (it / 7) * 2_500L })
            // Backward steps, as a seek produces.
            add((1..60).map { if (it < 30) 50_000L + it * 100L else 10_000L + it * 100L })
        }

        for (series in shapes) {
            val old = firstAction(oldRule(), series) ?: Int.MAX_VALUE
            val new = firstAction(newRule(), series) ?: Int.MAX_VALUE
            assertTrue(
                "new rule acted later ($new) than old ($old) on $series",
                new <= old,
            )
        }
    }
}
