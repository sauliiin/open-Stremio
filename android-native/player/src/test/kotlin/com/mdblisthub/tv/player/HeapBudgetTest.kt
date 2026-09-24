package com.mdblisthub.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class HeapBudgetTest {

    @Test
    fun backBufferNeverExceedsTenSeconds() {
        assertEquals(
            HeapBudget.MAX_BACK_BUFFER_MS,
            HeapBudget.backBufferMs(
                targetBytes = 512 * 1024 * 1024,
                bytesPerSecond = 512 * 1024,
            ),
        )
    }

    /**
     * A budget worth one second of film has no surplus to share, so the back
     * buffer is not merely reduced — it is refused. This is the case that used
     * to be handed a two-second floor it had no way to pay for.
     */
    @Test
    fun backBufferIsRefusedWhenTheBudgetCannotCoverTheForwardReserve() {
        assertEquals(
            0L,
            HeapBudget.backBufferMs(
                targetBytes = 64 * 1024 * 1024,
                bytesPerSecond = 64L * 1024 * 1024,
            ),
        )
    }

    /**
     * 100MB at 4MB/s is 25 seconds of film. Twenty of those are reserved for
     * the forward buffer, leaving a five-second surplus — of which the back
     * buffer takes a fifth.
     */
    @Test
    fun backBufferTracksTwentyPercentOfTheSurplus() {
        assertEquals(
            1_000L,
            HeapBudget.backBufferMs(
                targetBytes = 100 * 1024 * 1024,
                bytesPerSecond = 4L * 1024 * 1024,
            ),
        )
    }

    /**
     * The same throughput on a budget worth a minute rather than 25 seconds.
     * The surplus grows with the pot, so the back buffer does too — which is
     * the proportionality a share of the *whole* budget could not give at both
     * ends.
     */
    @Test
    fun backBufferGrowsWithTheBudgetOnceTheReserveIsCovered() {
        assertEquals(
            8_000L,
            HeapBudget.backBufferMs(
                targetBytes = 240 * 1024 * 1024,
                bytesPerSecond = 4L * 1024 * 1024,
            ),
        )
    }

    /**
     * 448MB at 4MB/s is nearly two minutes of film, so a third of it is far
     * past the ceiling and the device is asked for the same ten seconds
     * `DefaultLoadControl` would have asked for on its own.
     */
    @Test
    fun aRoomyBudgetStillResumesOnTheFullCushion() {
        assertEquals(
            HeapBudget.bufferForPlaybackAfterRebufferMs().toLong(),
            HeapBudget.rebufferStartMs(
                targetBytes = 448 * 1024 * 1024,
                bytesPerSecond = 4L * 1024 * 1024,
            ),
        )
    }

    /**
     * 64MB at 4MB/s is sixteen seconds of film. Asking for ten of them back
     * before showing a frame is asking a link that has just faltered for most
     * of the budget — the freeze that only the skip button cleared.
     */
    @Test
    fun aThinBudgetResumesOnWhatItCanActuallyRebuild() {
        assertEquals(
            5_280L,
            HeapBudget.rebufferStartMs(
                targetBytes = 64 * 1024 * 1024,
                bytesPerSecond = 4L * 1024 * 1024,
            ),
        )
    }

    /** However thin the budget, resuming on nothing lands straight back in the stall. */
    @Test
    fun theResumeThresholdNeverDropsBelowItsFloor() {
        assertEquals(
            HeapBudget.MIN_REBUFFER_START_MS,
            HeapBudget.rebufferStartMs(
                targetBytes = 64 * 1024 * 1024,
                bytesPerSecond = 64L * 1024 * 1024,
            ),
        )
    }

    @Test
    fun playbackTimingCushionDoesNotShrinkOnHigherRamProfiles() {
        assertEquals(5_000, HeapBudget.bufferForPlaybackMs())
        assertEquals(10_000, HeapBudget.bufferForPlaybackAfterRebufferMs())
    }

    @Test
    fun scaleCycleMatchesTheOsdOrder() {
        assertEquals(
            listOf(VideoScaleType.FIT, VideoScaleType.STRETCH, VideoScaleType.ZOOM),
            SCALE_CYCLE,
        )
    }
}
