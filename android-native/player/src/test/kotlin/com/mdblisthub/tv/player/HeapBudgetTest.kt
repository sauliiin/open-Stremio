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

    @Test
    fun backBufferNeverDropsBelowSafetyFloor() {
        assertEquals(
            HeapBudget.MIN_BACK_BUFFER_MS,
            HeapBudget.backBufferMs(
                targetBytes = 64 * 1024 * 1024,
                bytesPerSecond = 64L * 1024 * 1024,
            ),
        )
    }

    @Test
    fun backBufferTracksTwentyPercentOfByteBudget() {
        assertEquals(
            5_000L,
            HeapBudget.backBufferMs(
                targetBytes = 100 * 1024 * 1024,
                bytesPerSecond = 4L * 1024 * 1024,
            ),
        )
    }

    @Test
    fun scaleCycleMatchesTheOsdOrder() {
        assertEquals(
            listOf(VideoScaleType.FIT, VideoScaleType.STRETCH, VideoScaleType.ZOOM),
            SCALE_CYCLE,
        )
    }
}
