package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.model.SubtitleOption

/**
 * Picks the subtitle worth turning on without being asked.
 *
 * Portuguese is tried first, English second, and neither is forced if
 * nothing in that language exists — the point is to save a trip to the
 * picker on the common case, not to guarantee captions appear.
 *
 * Within a language, candidates are ranked by how many meaningful words
 * their own release text shares with the release actually playing —
 * `1080p WEB-DL x265` matching a subtitle that says the same is a much
 * safer bet than one pulled off a CAM rip of the same film. Ties — most
 * often because [SubtitleOption.releaseHint] is null on both sides, since
 * it is not part of the Stremio subtitle protocol and most addons send
 * nothing for it — fall to whichever option is more downloaded where that
 * is known, and to the source's own ordering where it isn't.
 */
object SubtitleMatcher {

    fun bestMatch(options: List<SubtitleOption>, playingRelease: String?, preferredLang: String? = null): SubtitleOption? {
        val releaseTokens = playingRelease?.let(::tokens) ?: emptySet()

        if (preferredLang != null) {
            val preferred = bestOf(options.filter { it.lang.lowercase().startsWith(preferredLang) }, releaseTokens)
            if (preferred != null) return preferred
        }

        return bestOf(options.filter { it.lang.looksPortuguese() }, releaseTokens)
            ?: bestOf(options.filter { it.lang.looksEnglish() }, releaseTokens)
    }

    /**
     * Two keys, most-significant first. `compareBy` and `maxWithOrNull` both
     * keep the first element on a full tie, which is what turns "no release
     * text on either side" into "no downloads on either side" into "whichever
     * the source listed first" — each falls through to the next rather than
     * needing its own branch, because an empty [releaseTokens] set makes
     * every intersection score 0 regardless of candidate.
     */
    private fun bestOf(candidates: List<SubtitleOption>, releaseTokens: Set<String>): SubtitleOption? =
        candidates.maxWithOrNull(
            compareBy(
                { tokens(it.releaseHint.orEmpty()).intersect(releaseTokens).size },
                { it.popularity },
            ),
        )

    /**
     * Splits a release string into the words worth comparing: lowercase,
     * alphanumeric runs of three or more characters, with known synonyms
     * folded to one spelling first — `x264`/`h264`/`h.264` all mean the same
     * codec, but as separate tokens they would share nothing — and the ones
     * too generic to mean anything (container/language boilerplate that
     * shows up in nearly every file name) filtered out so they cannot
     * inflate an otherwise-unrelated match.
     */
    private fun tokens(text: String): Set<String> {
        val normalized = ALIASES.fold(text.lowercase()) { acc, (pattern, canonical) ->
            pattern.replace(acc, canonical)
        }
        return WORD.findAll(normalized)
            .map { it.value }
            .filter { it.length >= 3 && it !in NOISE }
            .toSet()
    }

    private fun String?.looksPortuguese(): Boolean {
        val key = orEmpty().lowercase()
        return key.startsWith("po") || key.startsWith("pt")
    }

    private fun String?.looksEnglish(): Boolean = orEmpty().lowercase().startsWith("en")

    private val WORD = Regex("[a-z0-9]+")
    private val NOISE = setOf(
        "the", "and", "www", "com", "org", "net", "http", "https",
        "srt", "sub", "subs", "subtitle", "subtitles",
        "por", "pob", "pt", "br", "eng", "en",
    )

    /**
     * Release-tag spellings that mean the same thing, collapsed to one
     * canonical token before the general splitter runs. Ordered before the
     * plain word split specifically to catch the punctuated forms (`web-dl`,
     * `blu-ray`) that the splitter would otherwise cut into pieces too short
     * to survive the length filter — `"web-dl"` becomes `{"web"}`, not
     * `{"web", "dl"}`, since `"dl"` alone is under three characters.
     */
    private val ALIASES: List<Pair<Regex, String>> = listOf(
        Regex("""x\.?264|h\.?264|avc""") to "h264",
        Regex("""x\.?265|h\.?265|hevc""") to "hevc",
        Regex("""web[.\-\s]?dl""") to "webdl",
        Regex("""web[.\-\s]?rip""") to "webrip",
        Regex("""blu[.\-\s]?ray""") to "bluray",
        Regex("""dvd[.\-\s]?rip""") to "dvdrip",
        Regex("""bd[.\-\s]?rip""") to "bdrip",
        Regex("""hd[.\-\s]?rip""") to "hdrip",
        Regex("""hd[.\-\s]?tv""") to "hdtv",
        Regex("""\b4k\b|2160p|uhd""") to "2160p",
    )
}
