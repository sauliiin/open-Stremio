package com.mdblisthub.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets the link, and when the worker has to let go of a download already
 * in flight.
 *
 * The tests this file replaces asserted the opposite of most of what is below:
 * that a chunk in flight survives while the player is loading, provided the
 * player's buffer is not shrinking. That was a hypothesis about chunk thrash
 * that nothing ever measured, and it cost a 2.5GB television on 5GHz Wi-Fi its
 * uninterrupted playback — because "loading with a growing buffer" is not an
 * edge case, it is most of a film.
 *
 * Which is the point of writing them down as a series: the rule now has one
 * lever, and a future relaxation of it has to argue with these.
 */
class PrefetchPolicyTest {

    private val none = PrefetchPolicy.Effect.NONE
    private val cancel = PrefetchPolicy.Effect.CANCEL_IN_FLIGHT

    // ------------------------------------------------------------ the rule

    @Test
    fun `nothing is fetched while the player's own loader wants bytes`() {
        val policy = PrefetchPolicy()
        assertEquals(cancel, policy.onSample(loading = true))
        assertFalse(policy.mayStartChunk)
    }

    @Test
    fun `the spare link is used once the player has reached its target`() {
        val policy = PrefetchPolicy()
        assertEquals(none, policy.onSample(loading = false))
        assertTrue(policy.mayStartChunk)
    }

    /** Nothing may start before anything is known — the safe direction. */
    @Test
    fun `a fresh policy does not assume the link is free`() {
        assertFalse(PrefetchPolicy().mayStartChunk)
    }

    // ------------------------------------------- the regression, written down

    /**
     * The case that broke a real television. A player filling a byte budget
     * worth well under `minBufferMs` of film is loading almost continuously,
     * with its buffer growing the whole time — so a rule that spared the chunk
     * whenever the buffer was not shrinking spared it nearly always, and the
     * second connection ran alongside the player for most of the film.
     */
    @Test
    fun `a growing buffer does not entitle the worker to keep its chunk`() {
        val policy = PrefetchPolicy()
        policy.onSample(loading = false)
        assertEquals(
            "loading is loading, whatever the buffer is doing",
            cancel,
            policy.onSample(loading = true),
        )
    }

    /**
     * And it must hold for a whole run of them, not just the first. This is
     * the shape the old rule turned into a permanent exception.
     */
    @Test
    fun `every poll of a loading player takes the link back`() {
        val policy = PrefetchPolicy()
        val effects = (1..10).map { policy.onSample(loading = true) }
        assertEquals(List(10) { cancel }, effects)
        assertFalse(policy.mayStartChunk)
    }

    /**
     * The thrash this costs is real and was the reason for the old exception.
     * It is also cheap: `SimpleCache` keeps completed spans, so an abandoned
     * chunk costs the bytes already written and nothing else. That trade is
     * the decision, and it belongs in a test rather than in a comment.
     */
    @Test
    fun `an oscillating loader hands the link back on every flicker`() {
        val policy = PrefetchPolicy()
        val effects = (1..10).map { policy.onSample(loading = it % 2 == 0) }
        assertEquals(
            listOf(none, cancel, none, cancel, none, cancel, none, cancel, none, cancel),
            effects,
        )
    }

    // ----------------------------------------------------------- the events

    /**
     * The position ticker runs every four seconds while a film is watched,
     * which is four seconds of sharing a link the player has already claimed.
     * The event carries the same verdict immediately.
     */
    @Test
    fun `a loading event claims the link without waiting for a sample`() {
        val policy = PrefetchPolicy()
        policy.onSample(loading = false)
        assertTrue(policy.mayStartChunk)

        assertEquals(cancel, policy.onLoadingChanged(true))
        assertFalse(policy.mayStartChunk)
    }

    @Test
    fun `a loading event releasing the link frees the worker`() {
        val policy = PrefetchPolicy()
        policy.onSample(loading = true)
        assertEquals(none, policy.onLoadingChanged(false))
        assertTrue(policy.mayStartChunk)
    }

    @Test
    fun `a stopped picture takes the link immediately`() {
        val policy = PrefetchPolicy()
        policy.onSample(loading = false)
        assertEquals(cancel, policy.onPlayerStalled())
        assertFalse(policy.mayStartChunk)
    }

    @Test
    fun `a seek drops the chunk fetched for where the viewer left`() {
        assertEquals(cancel, PrefetchPolicy().onSeek())
    }

    /**
     * A seek is about the bytes in flight, not about who owns the link: the
     * player may well be idle and entitled to go on prefetching from the new
     * position on the very next pass.
     */
    @Test
    fun `a seek does not by itself take the link away`() {
        val policy = PrefetchPolicy()
        policy.onSample(loading = false)
        policy.onSeek()
        assertTrue(policy.mayStartChunk)
    }

    @Test
    fun `a new source starts with no claim on the link`() {
        val policy = PrefetchPolicy()
        policy.onSample(loading = false)
        policy.reset()
        assertFalse(policy.mayStartChunk)
    }
}
