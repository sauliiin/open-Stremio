package com.mdblisthub.tv.ui.player

import com.mdblisthub.tv.core.model.SkipSegment
import com.mdblisthub.tv.core.model.SkipSegmentKind
import com.mdblisthub.tv.player.PlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentTest {

    private val intro = SkipSegment(SkipSegmentKind.INTRO, startMs = 37_000, endMs = 68_000)
    private val outro = SkipSegment(SkipSegmentKind.OUTRO, startMs = 1_242_000, endMs = 1_292_000)
    private val segments = listOf(intro, outro)
    private val duration = 1_300_000L

    @Test
    fun `button is up for the whole segment and nowhere else`() {
        assertNull(activeSkipSegment(segments, 36_999, duration))
        assertEquals(intro, activeSkipSegment(segments, 37_000, duration))
        assertEquals(intro, activeSkipSegment(segments, 60_000, duration))
        assertNull(activeSkipSegment(segments, 100_000, duration))
        assertEquals(outro, activeSkipSegment(segments, 1_250_000, duration))
    }

    @Test
    fun `button leaves just before the segment ends on its own`() {
        assertEquals(intro, activeSkipSegment(segments, 66_499, duration))
        assertNull(activeSkipSegment(segments, 66_500, duration))
    }

    @Test
    fun `a segment past the end of this file is ignored`() {
        val shortCut = 1_200_000L
        assertNull(activeSkipSegment(segments, 1_250_000, shortCut))
    }

    @Test
    fun `position is watched closely just ahead of a segment`() {
        assertFalse(nearSkipSegment(segments, 30_000))
        assertTrue(nearSkipSegment(segments, 31_000))
        assertTrue(nearSkipSegment(segments, 50_000))
        assertFalse(nearSkipSegment(segments, 68_000))
        assertFalse(nearSkipSegment(emptyList(), 50_000))
    }

    @Test
    fun `credits start comes from the outro, when it is plausible`() {
        assertEquals(1_242_000L, creditsStartMs(segments, duration))
        assertNull(creditsStartMs(listOf(intro), duration))
        assertNull(creditsStartMs(segments, durationMs = 0))
        // An outro timed in the first half of the file is from another cut.
        assertNull(creditsStartMs(segments, durationMs = 3_000_000))
    }

    @Test
    fun `next episode card waits for the credits when they are known`() {
        assertFalse(offer(positionMs = 1_241_999, progress = 0.955f, creditsStartMs = 1_242_000))
        assertTrue(offer(positionMs = 1_242_000, progress = 0.955f, creditsStartMs = 1_242_000))
    }

    @Test
    fun `next episode card can arrive before ninety three percent at early credits`() {
        assertTrue(offer(positionMs = 1_000_000, progress = 0.80f, creditsStartMs = 1_000_000))
    }

    private fun offer(positionMs: Long, progress: Float, creditsStartMs: Long?) =
        shouldOfferNextEpisode(
            enabled = true,
            hasNextEpisode = true,
            phase = PlaybackPhase.PLAYING,
            progress = progress,
            alreadyTriggered = false,
            positionMs = positionMs,
            creditsStartMs = creditsStartMs,
        )
}
