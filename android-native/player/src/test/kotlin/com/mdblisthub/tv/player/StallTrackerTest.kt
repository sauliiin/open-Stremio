package com.mdblisthub.tv.player

import com.mdblisthub.tv.player.StallTracker.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallTrackerTest {

    private fun tracker() = StallTracker(
        pollMs = 1_000L,
        windowMs = 8_000L,
        waitingWindowMs = 5_000L,
        maxWindowMs = 60_000L,
        healthyRate = 0.6,
        deadRate = 0.2,
        reconnectsBeforeReopen = 2,
    )

    /** Feeds [seconds] polls with the buffer gaining [rate] of real time. */
    private fun StallTracker.run(
        seconds: Int,
        rate: Double,
        waiting: Boolean = false,
        loaderActive: Boolean = true,
        settling: Boolean = false,
        startMs: Long = 0L,
    ): List<Action> = (0..seconds).map { second ->
        onPoll(loaderActive, waiting, startMs + (second * 1_000 * rate).toLong(), settling)
    }

    @Test
    fun `a connection keeping up with the film is never touched`() {
        assertEquals(List(301) { Action.WAIT }, tracker().run(300, rate = 1.0))
    }

    @Test
    fun `a full buffer gaining nothing is not a stall`() {
        assertEquals(List(301) { Action.WAIT }, tracker().run(300, rate = 0.0, loaderActive = false))
    }

    @Test
    fun `a quiet socket is replaced while the film is still playing`() {
        val actions = tracker().run(10, rate = 0.0)
        assertEquals(Action.RECONNECT, actions[8])
        assertFalse(actions.contains(Action.REOPEN))
    }

    @Test
    fun `a connection at a third of real time is replaced, however steadily it delivers`() {
        assertEquals(Action.RECONNECT, tracker().run(10, rate = 0.36)[8])
    }

    @Test
    fun `a playing film is never reopened, whatever the link does`() {
        assertFalse(tracker().run(600, rate = 0.0, waiting = false).contains(Action.REOPEN))
    }

    @Test
    fun `reconnecting backs off on a link that is simply slower than the film`() {
        val actions = tracker().run(200, rate = 0.5)
        val reconnects = actions.withIndex().filter { it.value == Action.RECONNECT }.map { it.index }
        assertEquals(listOf(16, 32, 60), reconnects.zipWithNext { a, b -> b - a }.take(3))
        assertTrue(reconnects.size < 8)
    }

    /** Measured at 18 reconnects in one film, each costing throughput the link did not have. */
    @Test
    fun `reconnecting backs off with the picture stopped too, while the link delivers`() {
        val actions = tracker().run(150, rate = 0.5, waiting = true)
        val reconnects = actions.withIndex().filter { it.value == Action.RECONNECT }.map { it.index }
        assertEquals(listOf(10, 20, 40), reconnects.zipWithNext { a, b -> b - a }.take(3))
    }

    @Test
    fun `a slow link with the picture stopped is never reopened`() {
        assertFalse(tracker().run(300, rate = 0.5, waiting = true).contains(Action.REOPEN))
    }

    @Test
    fun `a dead link with the picture stopped escalates after its reconnects`() {
        val actions = tracker().run(40, rate = 0.0, waiting = true).filter { it != Action.WAIT }
        assertEquals(listOf(Action.RECONNECT, Action.RECONNECT, Action.REOPEN), actions.take(3))
    }

    @Test
    fun `silence right after a seek is not held against the link`() {
        assertEquals(List(61) { Action.WAIT }, tracker().run(60, rate = 0.0, waiting = true, settling = true))
    }

    @Test
    fun `recovering clears the record`() {
        val tracker = tracker()
        tracker.run(6, rate = 0.0, waiting = true)
        tracker.run(6, rate = 2.0, waiting = true, startMs = 10_000L)
        val after = tracker.run(16, rate = 0.0, waiting = true, startMs = 100_000L)
        assertEquals(listOf(Action.RECONNECT, Action.RECONNECT, Action.REOPEN), after.filter { it != Action.WAIT })
    }

    /** Feeds one poll per reading of the buffer's edge, and says on which it acted. */
    private fun StallTracker.feed(edges: List<Long>, waiting: Boolean = false): List<Int> =
        edges.withIndex().filter { (_, edge) ->
            onPoll(loaderActive = true, waiting = waiting, bufferedMs = edge, settling = false) != Action.WAIT
        }.map { it.index }

    /** Measured: a mirror dying every 40MB froze the picture for 29s in three minutes. */
    @Test
    fun `a burst does not hide the silence after it`() {
        val burstThenSilence = listOf(0L, 10_000L) + List(10) { 10_000L }
        assertEquals(listOf(9), tracker().feed(burstThenSilence))
    }

    @Test
    fun `a burst does not hide a trickle after it`() {
        val burstThenTrickle = listOf(0L, 10_000L) + List(10) { 10_000L + it * 100L }
        assertEquals(listOf(9), tracker().feed(burstThenTrickle))
    }

    @Test
    fun `backing off from a slow link does not delay noticing it has gone silent`() {
        val tracker = tracker()
        val slow = tracker.run(60, rate = 0.5)
        assertTrue(slow.count { it == Action.RECONNECT } >= 2)
        val silent = List(10) { 30_000L }
        assertTrue(tracker.feed(silent).first() <= 8)
    }

    @Test
    fun `an outage does not leave the next slow connection backed off`() {
        val tracker = tracker()
        tracker.run(80, rate = 0.0)
        val slow = tracker.run(10, rate = 0.36)
        assertTrue(slow.contains(Action.RECONNECT))
    }

    @Test
    fun `stopping for lack of media acts at once on a link already silent for that long`() {
        val tracker = tracker()
        tracker.run(4, rate = 0.0)
        assertEquals(Action.RECONNECT, tracker.onPoll(true, waiting = true, bufferedMs = 0L, settling = false))
    }

    @Test
    fun `a backward seek re-establishes the reference instead of reading as a stall`() {
        val tracker = tracker()
        tracker.run(5, rate = 1.0, startMs = 100_000L)
        assertEquals(List(6) { Action.WAIT }, tracker.run(5, rate = 1.0, startMs = 10_000L))
    }
}
