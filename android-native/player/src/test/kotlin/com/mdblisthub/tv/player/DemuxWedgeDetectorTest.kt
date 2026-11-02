package com.mdblisthub.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemuxWedgeDetectorTest {

    private val mb = 1024L * 1024L

    private fun detector() = DemuxWedgeDetector(minUnusedBytes = 2 * mb)

    @Test
    fun `reading fast with nothing coming out is a wedge`() {
        val detector = detector()
        assertFalse(detector.onPoll(true, 0, 50 * mb, 20_000))
        assertTrue(detector.onPoll(true, 400 * mb, 50 * mb, 20_000))
    }

    @Test
    fun `a slow link takes longer to be sure but gets there`() {
        val detector = detector()
        val verdicts = (0..30).map { poll -> detector.onPoll(true, poll * 100 * 1024L, 50 * mb, 20_000) }
        assertTrue(verdicts.contains(true))
        assertFalse(verdicts.take(20).contains(true))
    }

    /** A badly interleaved file: minutes of audio queued before the next video frame. */
    @Test
    fun `bytes that become samples are not a wedge, however long the edge stands still`() {
        val detector = detector()
        for (poll in 0..120) {
            val read = poll * 4 * mb
            assertFalse(detector.onPoll(true, read, 10 * mb + read, 20_000))
        }
    }

    @Test
    fun `a stopped picture with nothing arriving is the link's problem, not the demuxer's`() {
        val detector = detector()
        for (poll in 0..120) assertFalse(detector.onPoll(true, 5 * mb, 50 * mb, 20_000))
    }

    @Test
    fun `nothing is measured while the film plays`() {
        val detector = detector()
        for (poll in 0..120) assertFalse(detector.onPoll(false, poll * 8 * mb, 50 * mb, 20_000))
    }

    /** A seek frees the whole buffer in one go, which must not read as bytes thrown away. */
    @Test
    fun `memory released by a seek is not a measurement`() {
        val detector = detector()
        assertFalse(detector.onPoll(true, 0, 190 * mb, 20_000))
        assertFalse(detector.onPoll(true, 8 * mb, 8 * mb, 20_000))
        assertFalse(detector.onPoll(true, 16 * mb, 16 * mb, 20_000))
    }

    @Test
    fun `the buffer edge moving proves the demuxer is producing`() {
        val detector = detector()
        for (poll in 0..120) {
            assertFalse(detector.onPoll(true, poll * 4 * mb, 50 * mb, 20_000 + poll * 500L))
        }
    }

    @Test
    fun `a second wedge after a skip is caught too`() {
        val detector = detector()
        detector.onPoll(true, 0, 50 * mb, 20_000)
        assertTrue(detector.onPoll(true, 100 * mb, 50 * mb, 20_000))
        detector.onPoll(true, 100 * mb, 50 * mb, 30_000)
        assertTrue(detector.onPoll(true, 200 * mb, 50 * mb, 30_000))
    }
}
