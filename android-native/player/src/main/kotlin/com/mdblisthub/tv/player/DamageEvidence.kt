package com.mdblisthub.tv.player

/**
 * What the demuxer has said about the file, and whether that still holds.
 *
 * Damage is a claim about one place in the file. Once the buffer's edge has moved on
 * from there the load got through, and the claim is over. Left standing, one bad answer
 * from a host would keep the stall watch from ever replacing a connection again, and
 * would turn the next ordinary stall into a skip.
 *
 * Damage that really stands is reported again by every retry, so an edge that moves a
 * little inside it only makes the record start over from there.
 *
 * Recorded from the playback thread, polled from the stall watch.
 */
internal class DamageEvidence(private val passedAfterMs: Long) {

    @Volatile
    private var recordedAtMs = 0L
    private var seenAtMs = 0L
    private var edgeAtRecordMs = 0L

    fun record(nowMs: Long) {
        recordedAtMs = nowMs
    }

    fun clear() {
        recordedAtMs = 0L
    }

    fun standsSince(sinceMs: Long): Boolean = recordedAtMs > sinceMs

    /** @param bufferedMs the forward edge of the buffer, in film time. */
    fun onPoll(bufferedMs: Long) {
        val recorded = recordedAtMs
        if (recorded != seenAtMs) {
            seenAtMs = recorded
            edgeAtRecordMs = bufferedMs
        } else if (recorded != 0L && bufferedMs - edgeAtRecordMs >= passedAfterMs) {
            clear()
        }
    }
}
