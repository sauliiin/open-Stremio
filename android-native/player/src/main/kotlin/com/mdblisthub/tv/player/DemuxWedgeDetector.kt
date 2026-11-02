package com.mdblisthub.tv.player

/**
 * Recognises a demuxer reading a file without getting any film out of it.
 *
 * This is the worst freeze there is, because nothing about it looks wrong. Inside a
 * damaged stretch the demuxer can read garbage as an element claiming to be gigabytes
 * long, and then skip through it at full speed: no error, no samples, the link busy,
 * for as long as the rest of the file takes to arrive. New connections change nothing,
 * since they deliver the same bytes to the same parser state.
 *
 * The signature is bytes going in and nothing coming out. Allocated memory is what
 * tells it apart from a badly interleaved file, which also delivers bytes for a long
 * time without moving the buffer edge, but whose bytes all become samples queued for
 * the other tracks.
 *
 * Measured in bytes rather than seconds, so a slow link takes longer to be sure instead
 * of being judged on less evidence.
 */
internal class DemuxWedgeDetector(private val minUnusedBytes: Long) {

    private var measuring = false
    private var readAtStart = 0L
    private var allocatedAtStart = 0L
    private var bufferedAtStart = 0L
    private var lastAllocated = 0L

    /**
     * @param waiting whether the picture has stopped for lack of media.
     * @param bytesRead everything the loader has read so far, from any source.
     * @param allocatedBytes what the samples in memory take up.
     * @param bufferedMs the forward edge of the buffer, in film time.
     * @return true once enough has been read with nothing to show for it.
     */
    fun onPoll(waiting: Boolean, bytesRead: Long, allocatedBytes: Long, bufferedMs: Long): Boolean {
        // Memory going down means a seek released the buffer, which is not a measurement.
        val invalidated = !waiting || bufferedMs != bufferedAtStart || allocatedBytes < lastAllocated
        lastAllocated = allocatedBytes
        if (!measuring || invalidated) {
            restart(waiting, bytesRead, allocatedBytes, bufferedMs)
            return false
        }

        val read = bytesRead - readAtStart
        if (read < minUnusedBytes) return false

        val kept = allocatedBytes - allocatedAtStart
        restart(waiting, bytesRead, allocatedBytes, bufferedMs)
        return kept < read / KEPT_DIVISOR
    }

    private fun restart(waiting: Boolean, bytesRead: Long, allocatedBytes: Long, bufferedMs: Long) {
        measuring = waiting
        readAtStart = bytesRead
        allocatedAtStart = allocatedBytes
        bufferedAtStart = bufferedMs
    }

    private companion object {
        /** Keeping under a tenth of what was read is not demuxing. Headers cost a lot less. */
        const val KEPT_DIVISOR = 10L
    }
}
