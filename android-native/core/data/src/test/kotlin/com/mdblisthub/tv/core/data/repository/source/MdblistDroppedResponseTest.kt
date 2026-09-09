package com.mdblisthub.tv.core.data.repository.source

import com.mdblisthub.tv.core.network.dto.MdblistDroppedResponseDto
import com.mdblisthub.tv.core.network.dto.MdblistDroppedIdsDto
import com.mdblisthub.tv.core.network.dto.MdblistDroppedShowDto
import com.mdblisthub.tv.core.network.dto.MdblistDroppedWriteDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdblistDroppedResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `updated show is accepted`() {
        val response = json.decodeFromString<MdblistDroppedResponseDto>(
            """{"updated":{"shows":1},"existing":{"shows":0}}""",
        )

        assertTrue(response.acceptedShow())
    }

    @Test
    fun `already dropped show is accepted`() {
        val response = json.decodeFromString<MdblistDroppedResponseDto>(
            """{"updated":{"shows":0},"existing":{"shows":1}}""",
        )

        assertTrue(response.acceptedShow())
    }

    @Test
    fun `http success with unresolved show is rejected`() {
        val response = json.decodeFromString<MdblistDroppedResponseDto>(
            """{"updated":{"shows":0},"existing":{"shows":0},"not_found":{"shows":1}}""",
        )

        assertFalse(response.acceptedShow())
    }

    @Test
    fun `request keeps nested ids and dropped timestamp expected by mdblist`() {
        val request = MdblistDroppedWriteDto(
            shows = listOf(
                MdblistDroppedShowDto(
                    ids = MdblistDroppedIdsDto(imdb = "tt0944947", tmdb = 1399),
                    droppedAt = "2026-09-09T12:34:56.789Z",
                ),
            ),
        )

        assertEquals(
            """{"shows":[{"ids":{"imdb":"tt0944947","tmdb":1399},"dropped_at":"2026-09-09T12:34:56.789Z"}]}""",
            json.encodeToString(request),
        )
    }
}
