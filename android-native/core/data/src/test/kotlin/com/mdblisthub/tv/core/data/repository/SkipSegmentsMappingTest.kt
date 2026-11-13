package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.model.SkipSegment
import com.mdblisthub.tv.core.model.SkipSegmentKind
import com.mdblisthub.tv.core.network.HttpClients
import com.mdblisthub.tv.core.network.dto.IntroDbSegmentDto
import com.mdblisthub.tv.core.network.dto.IntroDbSegmentsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentsMappingTest {

    @Test
    fun `parses a real IntroDB answer into ordered segments`() {
        // Verbatim from api.introdb.app/segments?imdb_id=tt0386676&season=3&episode=5.
        val body = """
            {"imdb_id":"tt0386676","media_type":"tv","is_movie":false,"season":3,"episode":5,
            "intro":{"start_sec":37,"end_sec":68,"start_ms":37000,"end_ms":68000,"confidence":1,
            "submission_count":1,"updated_at":"2026-04-08T18:29:18.312Z"},"recap":null,
            "outro":{"start_sec":1242,"end_sec":1292,"start_ms":1242000,"end_ms":1292000,
            "confidence":1,"submission_count":1,"updated_at":"2026-07-26T23:31:50.025Z"},
            "post_credits":null,"no_intro":false,"no_recap":false,"no_outro":false}
        """.trimIndent()

        val dto = HttpClients.json.decodeFromString(IntroDbSegmentsDto.serializer(), body)

        assertEquals(
            listOf(
                SkipSegment(SkipSegmentKind.INTRO, 37_000, 68_000),
                SkipSegment(SkipSegmentKind.OUTRO, 1_242_000, 1_292_000),
            ),
            dto.toSkipSegments(),
        )
    }

    @Test
    fun `an episode nobody has timed has no segments`() {
        assertTrue(IntroDbSegmentsDto().toSkipSegments().isEmpty())
    }

    @Test
    fun `recap before intro keeps playback order`() {
        val dto = IntroDbSegmentsDto(
            intro = segment(60_000, 90_000),
            recap = segment(0, 45_000),
        )

        assertEquals(
            listOf(SkipSegmentKind.RECAP, SkipSegmentKind.INTRO),
            dto.toSkipSegments().map { it.kind },
        )
    }

    @Test
    fun `drops segments the community does not agree on`() {
        val dto = IntroDbSegmentsDto(intro = segment(10_000, 40_000, confidence = 0.3))

        assertTrue(dto.toSkipSegments().isEmpty())
    }

    @Test
    fun `drops slips, inverted ranges and missing ends`() {
        val dto = IntroDbSegmentsDto(
            intro = segment(10_000, 12_000),
            recap = segment(50_000, 40_000),
            outro = IntroDbSegmentDto(startMs = 1_000_000, endMs = null),
        )

        assertTrue(dto.toSkipSegments().isEmpty())
    }

    @Test
    fun `caps an intro at five minutes but never the credits`() {
        val dto = IntroDbSegmentsDto(
            intro = segment(0, 6 * 60_000),
            outro = segment(2_400_000, 2_400_000 + 10 * 60_000),
        )

        assertEquals(listOf(SkipSegmentKind.OUTRO), dto.toSkipSegments().map { it.kind })
    }

    private fun segment(start: Long, end: Long, confidence: Double? = 1.0) =
        IntroDbSegmentDto(startMs = start, endMs = end, confidence = confidence)
}
