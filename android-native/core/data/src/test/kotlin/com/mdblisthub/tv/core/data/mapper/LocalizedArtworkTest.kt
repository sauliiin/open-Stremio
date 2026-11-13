package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.network.FanartTvImage
import com.mdblisthub.tv.core.network.dto.TmdbDetailDto
import com.mdblisthub.tv.core.network.dto.TmdbImageDto
import com.mdblisthub.tv.core.network.dto.TmdbImagesDto
import com.mdblisthub.tv.core.network.dto.TmdbTranslationDataDto
import com.mdblisthub.tv.core.network.dto.TmdbTranslationDto
import com.mdblisthub.tv.core.network.dto.TmdbTranslationsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalizedArtworkTest {
    private val es = listOf("es", "en")
    private val pt = listOf("pt", "en")

    private fun image(path: String, language: String?, votes: Double = 5.0) =
        TmdbImageDto(filePath = path, language = language, voteAverage = votes)

    @Test
    fun `logo prefers the interface language over a better-voted English one`() {
        val images = TmdbImagesDto(
            logos = listOf(
                image("/en.png", "en", votes = 9.0),
                image("/es.png", "es", votes = 2.0),
                image("/none.png", null, votes = 10.0),
            ),
        )
        assertEquals("/es.png", localizedLogoPath(images, es))
    }

    @Test
    fun `logo falls back to English, then to a textless one`() {
        val english = TmdbImagesDto(logos = listOf(image("/pt.png", "pt"), image("/en.png", "en")))
        assertEquals("/en.png", localizedLogoPath(english, listOf("fr", "en")))

        val textless = TmdbImagesDto(logos = listOf(image("/ja.png", "ja"), image("/none.png", null)))
        assertEquals("/none.png", localizedLogoPath(textless, es))
    }

    @Test
    fun `poster follows the interface language, then English, then TMDB's own pick`() {
        val tmdb = TmdbDetailDto(
            posterPath = "/default.jpg",
            images = TmdbImagesDto(posters = listOf(image("/en.jpg", "en"), image("/pt.jpg", "pt"))),
        )
        assertEquals("/pt.jpg", localizedPosterPath(tmdb, pt))
        assertEquals("/en.jpg", localizedPosterPath(tmdb, listOf("fr", "en")))
        assertEquals("/default.jpg", localizedPosterPath(tmdb.copy(images = null), pt))
    }

    @Test
    fun `landscape never takes an untagged backdrop`() {
        val images = TmdbImagesDto(backdrops = listOf(image("/scene.jpg", null, votes = 10.0)))
        assertNull(localizedTmdbLandscapeUrl(images, pt))

        val tagged = TmdbImagesDto(
            backdrops = listOf(image("/en.jpg", "en", votes = 9.0), image("/es.jpg", "es", votes = 1.0)),
        )
        assertEquals(true, localizedTmdbLandscapeUrl(tagged, es)?.endsWith("/es.jpg"))
    }

    @Test
    fun `fanart prefers the interface language, then English, then the best-liked`() {
        val thumbs = listOf(
            FanartTvImage(url = "https://f/en", likes = "9", lang = "en"),
            FanartTvImage(url = "https://f/fr", likes = "1", lang = "fr"),
            FanartTvImage(url = "https://f/de", likes = "20", lang = "de"),
        )
        assertEquals("https://f/fr", localizedFanartUrl(thumbs, listOf("fr", "en")))
        assertEquals("https://f/en", localizedFanartUrl(thumbs, es))
        assertEquals("https://f/de", localizedFanartUrl(thumbs.filter { it.lang != "en" }, es))
    }

    private fun translation(language: String, country: String, title: String) =
        TmdbTranslationDto(language = language, country = country, data = TmdbTranslationDataDto(title = title))

    @Test
    fun `title uses the interface translation, preferring its own region`() {
        val tmdb = TmdbDetailDto(
            title = "Spirited Away",
            originalLanguage = "ja",
            originalTitle = "千と千尋の神隠し",
            translations = TmdbTranslationsDto(
                listOf(
                    translation("pt", "PT", "A Viagem de Chihiro (PT)"),
                    translation("pt", "BR", "A Viagem de Chihiro"),
                    translation("en", "US", "Spirited Away"),
                ),
            ),
        )
        assertEquals("A Viagem de Chihiro", localizedTitle(tmdb, pt, region = "BR"))
    }

    @Test
    fun `untranslated title falls back to English rather than the original script`() {
        val tmdb = TmdbDetailDto(
            name = "進撃の巨人",
            originalLanguage = "ja",
            originalName = "進撃の巨人",
            translations = TmdbTranslationsDto(
                listOf(
                    translation("es", "ES", ""),
                    TmdbTranslationDto("en", "US", TmdbTranslationDataDto(name = "Attack on Titan")),
                ),
            ),
        )
        assertEquals("Attack on Titan", localizedTitle(tmdb, es, region = "419"))
    }

    @Test
    fun `a title in the interface's own language keeps its original name`() {
        val tmdb = TmdbDetailDto(
            title = "City of God",
            originalLanguage = "pt",
            originalTitle = "Cidade de Deus",
            translations = TmdbTranslationsDto(
                listOf(translation("pt", "BR", ""), translation("en", "US", "City of God")),
            ),
        )
        assertEquals("Cidade de Deus", localizedTitle(tmdb, pt, region = "BR"))
    }

    @Test
    fun `without translations the title is what TMDB answered`() {
        assertEquals("Title", localizedTitle(TmdbDetailDto(title = "Title"), pt, region = "BR"))
    }
}
