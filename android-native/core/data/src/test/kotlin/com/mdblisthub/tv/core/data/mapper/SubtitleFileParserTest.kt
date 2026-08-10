package com.mdblisthub.tv.core.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real files, not synthetic ones.
 *
 * The resource here was downloaded from the OpenSubtitles v3 addon for
 * John Wick 3 — the exact file that produced "a legenda foi selecionada e não
 * aparece" on a television. Subtitle bugs are almost always about a specific
 * file's quirks (its line endings, its declared-versus-actual encoding), so a
 * hand-written fixture would have proven nothing.
 */
class SubtitleFileParserTest {

    private fun load(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing resource $name" }
            .use { it.readBytes() }

    /**
     * The addon declares `SubEncoding: CP1252` for this file and the bytes are
     * in fact UTF-8 — a mismatch that is entirely normal for scraped subtitles.
     */
    @Test
    fun `parses the pob file the addon actually serves`() {
        val track = SubtitleFileParser.parse(load(POB_FILE), "CP1252")

        assertFalse("no cues parsed at all", track.isEmpty)
        assertTrue("suspiciously few cues: ${track.cues.size}", track.cues.size > 500)
    }

    /** The window the on-device test was watching when nothing appeared. */
    @Test
    fun `has a cue at four minutes in`() {
        val track = SubtitleFileParser.parse(load(POB_FILE), "CP1252")
        val cue = track.cueAt(3 * 60_000 + 6_000)

        assertNotNull("expected dialogue around 3:06", cue)
    }

    /**
     * The declared charset is wrong, so trusting it renders every accent as
     * mojibake. Portuguese without accents is the whole language broken.
     */
    @Test
    fun `decodes accents despite the wrong declared charset`() {
        val track = SubtitleFileParser.parse(load(POB_FILE), "CP1252")
        val joined = track.cues.joinToString("\n") { it.text }

        assertTrue("expected 'segurança' intact", joined.contains("segurança"))
        assertFalse("mojibake present", joined.contains("Ã§"))
    }

    /** CRLF is what OpenSubtitles serves; blocks must still split on it. */
    @Test
    fun `handles crlf line endings`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nOlá\r\n\r\n" +
            "2\r\n00:00:03,000 --> 00:00:04,000\r\nMundo\r\n"
        val track = SubtitleFileParser.parse(srt.toByteArray(Charsets.UTF_8), null)

        assertEquals(2, track.cues.size)
        assertEquals("Olá", track.cues[0].text)
    }

    private companion object {
        const val POB_FILE = "opensubtitles-pob-cp1252.srt"
    }
}
