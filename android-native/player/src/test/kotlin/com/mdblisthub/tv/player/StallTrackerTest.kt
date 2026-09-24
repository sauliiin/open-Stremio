package com.mdblisthub.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported failure, written down: "às vezes quando começa a fazer buffer,
 * ele não retoma até eu apertar avançar."
 *
 * Every case below is a series of polls, because that is the only shape in
 * which the bug exists — no single poll of a trickling source looks any
 * different from a healthy one.
 */
class StallTrackerTest {

    private fun tracker() = StallTracker(
        pollMs = 1_000L,
        frozenMs = 10_000L,
        suspectMs = 3_000L,
        minProgressMs = 2_000L,
        softReconnectLimit = 1,
    )

    /**
     * Feeds [polls] seconds of waiting during which the buffer grows by
     * [gainPerPollMs] each second, and returns everything the tracker decided.
     */
    private fun StallTracker.waitWith(
        polls: Int,
        gainPerPollMs: Long,
        buffered: Long = 60_000L,
        suspect: Boolean = false,
        settling: Boolean = false,
    ): List<StallTracker.Action> {
        var position = buffered
        return (1..polls).map {
            position += gainPerPollMs
            onPoll(waiting = true, bufferedMs = position, suspect = suspect, settling = settling)
        }
    }

    // ------------------------------------------------------------ the bug

    /**
     * The regression itself. A mirror delivering 100ms of media per second of
     * wall clock — a twentieth of real time — moves `bufferedPosition` on
     * every single poll, so the old rule ("has it moved?") read it as alive
     * and never repaired it. Playback meanwhile will not resume until ten
     * seconds are buffered, which at this rate is a hundred seconds away.
     *
     * That is the film that sits frozen until the viewer presses skip.
     */
    @Test
    fun `a source trickling below real time is repaired, not waited on`() {
        val actions = tracker().waitWith(polls = 30, gainPerPollMs = 100L)
        assertTrue(
            "a trickling source must eventually be reconnected, got $actions",
            actions.contains(StallTracker.Action.SOFT_RECONNECT),
        )
    }

    /**
     * The other half of the same rule, and the reason the bar is a fifth of
     * real time rather than something stricter: a source delivering half a
     * second of media per second refills a ten-second cushion inside twenty
     * seconds and is worth waiting for. Touching it would replace a working
     * connection for no reason.
     */
    @Test
    fun `a source above the progress bar is left alone`() {
        val actions = tracker().waitWith(polls = 60, gainPerPollMs = 500L)
        assertEquals(List(60) { StallTracker.Action.WAIT }, actions)
    }

    /** A dead socket delivers nothing at all, and is the case that always worked. */
    @Test
    fun `a frozen source is reconnected after the freeze threshold`() {
        val actions = tracker().waitWith(polls = 12, gainPerPollMs = 0L)
        // Eleven, not ten: the first poll has no reference to measure against
        // and only establishes one, so the ten seconds of evidence start from
        // the second.
        assertEquals(
            "reconnect on the eleventh poll, not before",
            10,
            actions.indexOf(StallTracker.Action.SOFT_RECONNECT),
        )
    }

    /**
     * Playing for half a second between ten-second waits is the oscillation a
     * viewer describes as "it keeps stopping". The old rule zeroed the counter
     * on each of those half-seconds, so the ladder never reached its first
     * rung however long the stuttering went on.
     */
    @Test
    fun `stuttering that never recovers still reaches the ladder`() {
        val tracker = tracker()
        val actions = mutableListOf<StallTracker.Action>()
        var buffered = 60_000L
        repeat(8) {
            // Nine seconds frozen, then a single poll of playback.
            repeat(9) { actions += tracker.onPoll(true, buffered, suspect = false, settling = false) }
            buffered += 500L
            actions += tracker.onPoll(waiting = false, bufferedMs = buffered, suspect = false, settling = false)
        }
        assertTrue(
            "a stutter that never recovers must reach the ladder, got $actions",
            actions.contains(StallTracker.Action.SOFT_RECONNECT),
        )
    }

    /** A source that genuinely recovers pays its debt off and keeps its fresh attempt. */
    @Test
    fun `sustained playback clears the freeze counter`() {
        val tracker = tracker()
        tracker.waitWith(polls = 9, gainPerPollMs = 0L)
        // Nine polls frozen, then nine of real playback: the debt decays at
        // the rate it accrued, so the tenth frozen poll is not the trigger.
        repeat(9) { tracker.onPoll(waiting = false, bufferedMs = 60_000L, suspect = false, settling = false) }
        val actions = tracker.waitWith(polls = 9, gainPerPollMs = 0L)
        assertEquals(List(9) { StallTracker.Action.WAIT }, actions)
    }

    // ------------------------------------------------------- the ladder

    @Test
    fun `a link that ignores the reconnection is reopened cold`() {
        val tracker = tracker()
        assertTrue(tracker.waitWith(30, 0L).contains(StallTracker.Action.SOFT_RECONNECT))
        assertTrue(
            "the second stall of one run escalates",
            tracker.waitWith(30, 0L).contains(StallTracker.Action.REOPEN),
        )
    }

    @Test
    fun `recovering between stalls earns a fresh soft attempt`() {
        val tracker = tracker()
        assertTrue(tracker.waitWith(11, 0L).contains(StallTracker.Action.SOFT_RECONNECT))
        repeat(20) { tracker.onPoll(waiting = false, bufferedMs = 90_000L, suspect = false, settling = false) }
        assertTrue(
            "a source that played on is owed another soft reconnect",
            tracker.waitWith(11, 0L).contains(StallTracker.Action.SOFT_RECONNECT),
        )
    }

    // ------------------------------------------------------ the guards

    /** A socket presumed dead after a long pause is not worth ten seconds of proof. */
    @Test
    fun `a suspect connection is repaired sooner`() {
        val actions = tracker().waitWith(polls = 6, gainPerPollMs = 0L, suspect = true)
        assertEquals(3, actions.indexOf(StallTracker.Action.SOFT_RECONNECT))
    }

    /** Silence right after a seek is the expected state, not a dead host. */
    @Test
    fun `nothing is repaired while a seek is still settling`() {
        val actions = tracker().waitWith(polls = 30, gainPerPollMs = 0L, settling = true)
        assertEquals(List(30) { StallTracker.Action.WAIT }, actions)
    }

    /** But the counter kept running underneath, so the repair lands immediately after. */
    @Test
    fun `a seek postpones the repair without forgiving it`() {
        val tracker = tracker()
        tracker.waitWith(polls = 30, gainPerPollMs = 0L, settling = true)
        assertEquals(
            StallTracker.Action.SOFT_RECONNECT,
            tracker.onPoll(waiting = true, bufferedMs = 60_000L, suspect = false, settling = false),
        )
    }

    /**
     * A backward seek leaves the buffer behind a reference taken in front of
     * it. Comparing across that produces a difference that can never turn
     * positive — every poll would look like a stall, and the film would be
     * reconnected for the crime of being rewound.
     */
    @Test
    fun `a backward seek re-establishes the reference instead of counting as a stall`() {
        val tracker = tracker()
        tracker.onPoll(waiting = true, bufferedMs = 600_000L, suspect = false, settling = false)
        val actions = (1..30).map {
            // Rewound to the start of the film, now filling normally from there.
            tracker.onPoll(true, 10_000L + it * 500L, suspect = false, settling = false)
        }
        assertEquals(List(30) { StallTracker.Action.WAIT }, actions)
    }
}
