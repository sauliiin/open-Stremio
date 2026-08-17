package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.model.PlayableStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayableStreamSizeTest {
    @Test
    fun `parses megabytes and decimal gigabytes`() {
        assertEquals(606L * 1024 * 1024, stream("606 MB").sizeBytes)
        assertEquals((4.5 * 1024 * 1024 * 1024).toLong(), stream("4.5 GB").sizeBytes)
    }

    @Test
    fun `accepts decimal comma and leaves unknown sizes manual`() {
        assertEquals((1.25 * 1024 * 1024 * 1024).toLong(), stream("1,25 GB").sizeBytes)
        assertNull(stream(null).sizeBytes)
        assertNull(stream("unknown").sizeBytes)
    }

    /**
     * The case the probe's size check exists for: an episode inside a season
     * pack, where the label advertises the pack and `behaviorHints.videoSize`
     * describes the file the link actually serves.
     */
    @Test
    fun `the addon's own byte count wins over the scraped label`() {
        val episodeBytes = 3L * 1024 * 1024 * 1024
        assertEquals(episodeBytes, stream("58.4 GB", sizeBytesHint = episodeBytes).sizeBytes)
    }

    @Test
    fun `falls back to the label when the hint is absent or nonsense`() {
        assertEquals(606L * 1024 * 1024, stream("606 MB", sizeBytesHint = null).sizeBytes)
        assertEquals(606L * 1024 * 1024, stream("606 MB", sizeBytesHint = 0L).sizeBytes)
    }

    private fun stream(size: String?, sizeBytesHint: Long? = null) = PlayableStream(
        key = "test",
        addon = "test",
        title = "test",
        size = size,
        sizeBytesHint = sizeBytesHint,
        url = "https://example.com/movie.mkv",
    )
}
