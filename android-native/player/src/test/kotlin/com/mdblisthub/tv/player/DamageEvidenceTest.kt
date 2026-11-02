package com.mdblisthub.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DamageEvidenceTest {

    private val evidence = DamageEvidence(passedAfterMs = 2_000L)

    @Test
    fun `damage stands while the buffer's edge stays where it was`() {
        evidence.record(nowMs = 100L)
        repeat(60) { evidence.onPoll(bufferedMs = 50_000L) }
        assertTrue(evidence.standsSince(0L))
    }

    @Test
    fun `damage the load got past no longer stands`() {
        evidence.record(nowMs = 100L)
        evidence.onPoll(bufferedMs = 50_000L)
        evidence.onPoll(bufferedMs = 52_000L)
        assertFalse(evidence.standsSince(0L))
    }

    @Test
    fun `a few frames squeezed out of the damage do not end it`() {
        evidence.record(nowMs = 100L)
        evidence.onPoll(bufferedMs = 50_000L)
        evidence.onPoll(bufferedMs = 50_400L)
        assertTrue(evidence.standsSince(0L))
    }

    @Test
    fun `every new report starts the measure over`() {
        evidence.record(nowMs = 100L)
        evidence.onPoll(bufferedMs = 50_000L)
        evidence.onPoll(bufferedMs = 51_500L)
        evidence.record(nowMs = 200L)
        evidence.onPoll(bufferedMs = 51_600L)
        evidence.onPoll(bufferedMs = 53_000L)
        assertTrue(evidence.standsSince(0L))
    }

    @Test
    fun `damage older than a seek does not count`() {
        evidence.record(nowMs = 100L)
        assertFalse(evidence.standsSince(sinceMs = 150L))
    }

    @Test
    fun `a backward seek is not read as the load moving on`() {
        evidence.record(nowMs = 100L)
        evidence.onPoll(bufferedMs = 50_000L)
        evidence.onPoll(bufferedMs = 10_000L)
        assertTrue(evidence.standsSince(0L))
    }

    @Test
    fun `a new record after clearing is measured from where it happened`() {
        evidence.record(nowMs = 100L)
        evidence.onPoll(bufferedMs = 50_000L)
        evidence.clear()
        evidence.onPoll(bufferedMs = 90_000L)
        evidence.record(nowMs = 200L)
        evidence.onPoll(bufferedMs = 90_100L)
        evidence.onPoll(bufferedMs = 91_000L)
        assertTrue(evidence.standsSince(0L))
    }
}
