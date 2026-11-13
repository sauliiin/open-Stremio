package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.model.TmdbImages
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.FanartTvImage
import com.mdblisthub.tv.core.network.dto.TmdbDetailDto
import com.mdblisthub.tv.core.network.dto.TmdbImageDto
import com.mdblisthub.tv.core.network.dto.TmdbImagesDto

/**
 * Every "which language should this title be shown in" decision, in one place.
 *
 * The rule is the same for the clearlogo, the landscape card, the poster and
 * the title: the interface language first, English second, and only then
 * whatever is left. `languages` is [ApiConfig.artworkLanguages] in the app and
 * a fixed list in tests.
 */

/**
 * The best-voted image in the first language of [languages] that has one.
 * [includeUntagged] adds images with no language (no text on them) as the last
 * resort, which suits a logo or poster but not campaign card art.
 */
internal fun List<TmdbImageDto>?.bestIn(
    languages: List<String>,
    includeUntagged: Boolean = false,
): TmdbImageDto? {
    val pool = this.orEmpty().filter { it.filePath.isNotBlank() }
    if (pool.isEmpty()) return null
    val order: List<String?> = if (includeUntagged) languages + null else languages
    return order.firstNotNullOfOrNull { language ->
        pool.filter { it.language == language }.maxByOrNull { it.voteAverage }
    }
}

/** Clearlogo: interface language, English, then a textless one. */
internal fun localizedLogoPath(
    images: TmdbImagesDto?,
    languages: List<String> = ApiConfig.artworkLanguages(),
): String? = images?.logos.bestIn(languages, includeUntagged = true)?.filePath

/**
 * Poster: interface language, English, then TMDB's own pick for the request
 * (usually the original-language one-sheet), then a textless one.
 */
internal fun localizedPosterPath(
    tmdb: TmdbDetailDto,
    languages: List<String> = ApiConfig.artworkLanguages(),
): String? = tmdb.images?.posters.bestIn(languages)?.filePath
    ?: tmdb.posterPath?.takeIf { it.isNotBlank() }
    ?: tmdb.images?.posters.bestIn(emptyList(), includeUntagged = true)?.filePath

/**
 * 16:9 card art from TMDB. Only language-tagged backdrops qualify: those carry
 * the title or campaign treatment, while untagged ones are plain scenes and
 * belong to the backdrop fallback instead.
 */
internal fun localizedTmdbLandscapeUrl(
    images: TmdbImagesDto?,
    languages: List<String> = ApiConfig.artworkLanguages(),
): String? = images?.backdrops.bestIn(languages)
    ?.let { TmdbImages.url(it.filePath, TmdbImages.BACKDROP_FANART) }

/**
 * Fanart.tv card art: interface language, English, then the best-liked of the
 * rest — the collection is purpose-made for cards, so any of it beats none.
 */
internal fun localizedFanartUrl(
    images: List<FanartTvImage>?,
    languages: List<String> = ApiConfig.artworkLanguages(),
): String? {
    val pool = images.orEmpty().filter { it.url?.startsWith("http") == true }
    if (pool.isEmpty()) return null
    fun List<FanartTvImage>.best() = maxByOrNull { it.likes?.toIntOrNull() ?: 0 }
    return (
        languages.firstNotNullOfOrNull { language ->
            pool.filter { it.lang.equals(language, ignoreCase = true) }.best()
        } ?: pool.best()
    )?.url
}

/**
 * Title: the name in the interface language (its own region first — `pt-BR`
 * over `pt-PT`), then the English one, then whatever TMDB answered.
 *
 * TMDB's `title` already is the requested translation when one exists; what
 * it does *not* do is fall back to English. An untranslated anime comes back
 * under its Japanese name, which this replaces with the English one.
 *
 * A title whose original language is the one being tried *is* its own
 * translation: TMDB usually leaves that entry blank, and without this a
 * Brazilian film in a Portuguese interface would skip to its English name.
 */
internal fun localizedTitle(
    tmdb: TmdbDetailDto,
    languages: List<String> = ApiConfig.artworkLanguages(),
    region: String? = ApiConfig.metadataRegion(),
): String {
    val translations = tmdb.translations?.translations.orEmpty()
    val chosen = languages.firstNotNullOfOrNull { language ->
        val matches = translations.filter {
            it.language.equals(language, ignoreCase = true) && it.data?.displayTitle != null
        }
        val preferredRegion = if (language == languages.firstOrNull()) region else "US"
        (matches.firstOrNull { it.country.equals(preferredRegion, ignoreCase = true) } ?: matches.firstOrNull())
            ?.data?.displayTitle
            ?: tmdb.displayOriginalTitle
                ?.takeIf { it.isNotBlank() && tmdb.originalLanguage.equals(language, ignoreCase = true) }
    }
    return chosen ?: tmdb.displayTitle
}
