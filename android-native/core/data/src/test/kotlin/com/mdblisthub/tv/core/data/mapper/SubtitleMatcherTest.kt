package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.model.SubtitleOption
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleMatcherTest {

    private fun option(key: String, lang: String, releaseHint: String?, popularity: Int = 0) =
        SubtitleOption(
            key = key,
            addon = "test",
            label = lang,
            lang = lang,
            url = "https://example.com/$key.srt",
            releaseHint = releaseHint,
            popularity = popularity,
        )

    @Test
    fun `x264 and h264 are recognised as the same codec`() {
        val playing = "John.Wick.Chapter.3.Parabellum.2019.1080p.BluRay.x264-SPARKS"
        val wrongCodec = option("a", "pt", "John.Wick.Chapter.3.Parabellum.2019.1080p.BluRay.x265-OTHER")
        val sameCodecDifferentSpelling =
            option("b", "pt", "John.Wick.Chapter.3.Parabellum.2019.1080p.BluRay.h264-SPARKS")

        val best = SubtitleMatcher.bestMatch(listOf(wrongCodec, sameCodecDifferentSpelling), playing)

        assertEquals("b", best?.key)
    }

    @Test
    fun `hyphenated and concatenated source tags match`() {
        val playing = "Some.Movie.2020.1080p.WEB-DL.DDP5.1"
        val concatenated = option("a", "en", "Some.Movie.2020.1080p.WEBDL.DDP5.1")
        val unrelated = option("b", "en", "Some.Movie.2020.CAM.XVID")

        val best = SubtitleMatcher.bestMatch(listOf(unrelated, concatenated), playing)

        assertEquals("a", best?.key)
    }

    @Test
    fun `4k, 2160p and uhd are the same resolution token`() {
        val playing = "Movie.2021.2160p.HDR.DV"
        val taggedUhd = option("a", "en", "Movie.2021.UHD.HDR10")
        val taggedLow = option("b", "en", "Movie.2021.720p")

        val best = SubtitleMatcher.bestMatch(listOf(taggedLow, taggedUhd), playing)

        assertEquals("a", best?.key)
    }

    @Test
    fun `popularity breaks a tie when no release text is available`() {
        val lessDownloaded = option("a", "pt", releaseHint = null, popularity = 10)
        val moreDownloaded = option("b", "pt", releaseHint = null, popularity = 500)

        val best = SubtitleMatcher.bestMatch(listOf(lessDownloaded, moreDownloaded), "Some.Movie.2020.1080p")

        assertEquals("b", best?.key)
    }

    @Test
    fun `popularity does not override a real token match`() {
        val playing = "Some.Movie.2020.1080p.BluRay.x264-SPARKS"
        val matchingButLessPopular =
            option("a", "pt", "Some.Movie.2020.1080p.BluRay.x264-SPARKS", popularity = 5)
        val popularButUnrelated = option("b", "pt", "Some.Other.Movie.CAM", popularity = 9_000)

        val best = SubtitleMatcher.bestMatch(listOf(popularButUnrelated, matchingButLessPopular), playing)

        assertEquals("a", best?.key)
    }

    @Test
    fun `falls back to portuguese-first then english when nothing matches by tokens`() {
        val english = option("a", "en", releaseHint = null)
        val portuguese = option("b", "pt", releaseHint = null)

        assertEquals("b", SubtitleMatcher.bestMatch(listOf(english, portuguese), null)?.key)
        assertEquals("a", SubtitleMatcher.bestMatch(listOf(english), null)?.key)
        assertEquals(null, SubtitleMatcher.bestMatch(emptyList(), null))
    }

    @Test
    fun `preferred Balkan languages match addon ISO 639-2 codes`() {
        val options = listOf(
            option("croatian", "hrv", releaseHint = null),
            option("serbian", "srp", releaseHint = null),
            option("bosnian", "bos", releaseHint = null),
        )

        assertEquals("croatian", SubtitleMatcher.bestMatch(options, null, "hr")?.key)
        assertEquals("serbian", SubtitleMatcher.bestMatch(options, null, "sr")?.key)
        assertEquals("bosnian", SubtitleMatcher.bestMatch(options, null, "bs")?.key)
    }
}
