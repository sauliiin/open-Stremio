package com.mdblisthub.tv.core.data.repository

import android.net.Uri
import android.util.Log
import com.mdblisthub.tv.core.model.PersonSummary
import com.mdblisthub.tv.core.model.TmdbImages
import com.mdblisthub.tv.core.model.WikipediaLookup
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.TmdbApi
import com.mdblisthub.tv.core.network.WikipediaApi
import java.util.Calendar
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * A cast member's bio, looked up by name and TMDB person id.
 *
 * Wikipedia is queried first, straight at the REST summary endpoint with the
 * credited name as the title — the same endpoint Wikipedia's own official
 * apps use for wide public traffic, and normalises near-exact titles (case,
 * spaces vs underscores) server-side on its own. An earlier version resolved
 * the title through the older `/w/api.php?action=query` search first, but
 * that endpoint enforces much stricter anti-abuse limits on anonymous
 * traffic — shared IPs (carrier CGNAT, a TV box's ISP) were getting a flat
 * HTTP 403 from it long before the summary endpoint itself would ever be a
 * problem. The edition follows the current interface language. Portuguese
 * uses the Portuguese edition first and falls back to English when no local
 * article exists; English stays on the English edition so changing the app
 * language cannot still produce a Portuguese biography.
 *
 * TMDB's own `/person` biography is the fallback when no Wikipedia article
 * exists at all — dubbing actors, minor crew and many non-English-market
 * names never get one, and TMDB has an entry for practically anyone with a
 * credit. It is fetched alongside Wikipedia rather than only after Wikipedia
 * fails: every bio, Wikipedia's included, opens with a one-line age sentence
 * (see [ageSentence]) built from TMDB's `birthday`/`deathday`, which
 * Wikipedia's free-text extract does not expose in a parseable form.
 */
class WikipediaRepository(
    private val api: WikipediaApi,
    private val tmdbApi: TmdbApi,
    private val interfaceLanguage: Flow<String>,
) {

    suspend fun summaryFor(personId: Int, name: String): WikipediaLookup = coroutineScope {
        val tmdbDeferred = async {
            runCatching { tmdbApi.person(personId, ApiConfig.TMDB_KEY, ApiConfig.LANGUAGE) }.getOrNull()
        }

        val language = interfaceLanguage.first()

        var failure: Throwable? = null
        var wiki: PersonSummary? = null
        for (edition in wikipediaEditionsFor(language)) {
            val result = runCatching { fetch(edition, name) }
            val found = result.getOrNull()
            if (found != null) {
                wiki = found
                break
            }
            failure = result.exceptionOrNull() ?: failure
        }

        val tmdb = tmdbDeferred.await()
        val bioText = wiki?.extract ?: tmdb?.biography?.takeIf { it.isNotBlank() }

        if (bioText == null) {
            // No edition hit and TMDB had nothing either — surface *why* the
            // Wikipedia side failed, not just that it did. A name with
            // genuinely no article and a network/parse failure both end up
            // here, and only the reason tells them apart.
            if (failure != null) Log.w(TAG, "lookup failed for \"$name\"", failure)
            return@coroutineScope WikipediaLookup.NotFound(
                failure?.let { "${it::class.simpleName}: ${it.message}" } ?: "sem artigo",
            )
        }

        val bioName = wiki?.name ?: tmdb?.name?.takeIf { it.isNotBlank() } ?: name
        // A single space, not a blank line: this reads as one continuous
        // paragraph opening with the age, not a caption sitting above the
        // biography.
        val extract = ageSentence(bioName, tmdb?.birthday, tmdb?.deathday, language)
            ?.let { "$it $bioText" }
            ?: bioText

        WikipediaLookup.Found(
            PersonSummary(
                name = bioName,
                extract = extract,
                thumbnailUrl = wiki?.thumbnailUrl ?: TmdbImages.url(tmdb?.profilePath, TmdbImages.PROFILE),
                pageUrl = wiki?.pageUrl,
            ),
        )
    }

    private suspend fun fetch(lang: String, name: String): PersonSummary? {
        val summaryUrl = "https://$lang.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(name)}"
        val dto = api.summary(summaryUrl)
        val extract = dto.extract?.takeIf { it.isNotBlank() } ?: return null

        return PersonSummary(
            name = dto.title?.takeIf { it.isNotBlank() } ?: name,
            extract = extract,
            thumbnailUrl = dto.thumbnail?.source,
            pageUrl = dto.contentUrls?.desktop?.page,
        )
    }

    private companion object {
        const val TAG = "WikipediaRepository"
    }
}

/** Same "pt" / "pt-*" check the interface language setting itself uses. */
internal fun isPortuguese(languageTag: String): Boolean =
    languageTag.equals("pt", ignoreCase = true) || languageTag.startsWith("pt-", ignoreCase = true)

internal fun wikipediaEditionsFor(languageTag: String): List<String> =
    if (isPortuguese(languageTag)) listOf("pt", "en") else listOf("en")

/**
 * "<Nome> tem <N> anos." for the common case, "<Nome> morreu aos <N> anos."
 * when TMDB's record carries a death date — "tem N anos" read over someone
 * already dead would be wrong in the present tense. Null when there is no
 * birthday to compute from, which silently drops the sentence rather than
 * guessing an age.
 *
 * Follows [languageTag] independently of which Wikipedia edition answered:
 * an English interface reading a Portuguese-edition extract (the edition
 * list above falls back across languages, the interface setting does not)
 * must still get this one sentence in English.
 */
internal fun ageSentence(name: String, birthday: String?, deathday: String?, languageTag: String): String? {
    if (birthday.isNullOrBlank()) return null
    val portuguese = isPortuguese(languageTag)
    return if (!deathday.isNullOrBlank()) {
        ageAtDate(birthday, deathday)?.let {
            if (portuguese) "$name morreu aos $it anos." else "$name died at $it."
        }
    } else {
        ageToday(birthday)?.let {
            if (portuguese) "$name tem $it anos." else "$name is $it years old."
        }
    }
}

private fun ageToday(birthday: String): Int? {
    val now = Calendar.getInstance()
    return ageAsOf(birthday, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
}

private fun ageAtDate(birthday: String, isoDate: String): Int? {
    val parts = isoDate.split("-")
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    return ageAsOf(birthday, y, m, d)
}

/** Whole years between a `YYYY-MM-DD` birthday and the given date, or null if either fails to parse. */
private fun ageAsOf(birthday: String, targetYear: Int, targetMonth: Int, targetDay: Int): Int? {
    val parts = birthday.split("-")
    if (parts.size != 3) return null
    val by = parts[0].toIntOrNull() ?: return null
    val bm = parts[1].toIntOrNull() ?: return null
    val bd = parts[2].toIntOrNull() ?: return null
    var age = targetYear - by
    if (targetMonth < bm || (targetMonth == bm && targetDay < bd)) age--
    return age.takeIf { it >= 0 }
}
