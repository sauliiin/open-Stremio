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
 * language cannot still produce a Portuguese biography. Spanish and French
 * work the same way as Portuguese.
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

    /**
     * @param releaseDate the `YYYY-MM-DD` of the title the viewer opened this
     *   credit from, when the caller has it. It is what lets the biography
     *   open with the person's age *at the time of this title* rather than
     *   only their age now — see [ageSentence].
     */
    suspend fun summaryFor(
        personId: Int,
        name: String,
        releaseDate: String? = null,
        isSeries: Boolean = false,
    ): WikipediaLookup = coroutineScope {
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
        val extract = ageSentence(
            name = bioName,
            birthday = tmdb?.birthday,
            deathday = tmdb?.deathday,
            languageTag = language,
            releaseDate = releaseDate,
            isSeries = isSeries,
        )
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

/** Same language-tag checks the interface language setting itself uses. */
internal fun isPortuguese(languageTag: String): Boolean =
    languageTag.equals("pt", ignoreCase = true) || languageTag.startsWith("pt-", ignoreCase = true)

internal fun isFrench(languageTag: String): Boolean =
    languageTag.equals("fr", ignoreCase = true) || languageTag.startsWith("fr-", ignoreCase = true)

internal fun isSpanish(languageTag: String): Boolean =
    languageTag.equals("es", ignoreCase = true) || languageTag.startsWith("es-", ignoreCase = true)

/**
 * The four languages the interface ships — see `values-es`, `values-fr` and
 * the Portuguese default.
 *
 * Named rather than left as a chain of `if`s because every sentence below has
 * to exist in all four: a `when` over this is exhaustive, so a fifth language
 * added to the app fails to compile here instead of silently falling through
 * to English, which is how Spanish came to be reading "is 49 years old" in a
 * Spanish interface.
 */
internal enum class BioLanguage { PORTUGUESE, SPANISH, FRENCH, ENGLISH }

internal fun bioLanguageOf(languageTag: String): BioLanguage = when {
    isPortuguese(languageTag) -> BioLanguage.PORTUGUESE
    isSpanish(languageTag) -> BioLanguage.SPANISH
    isFrench(languageTag) -> BioLanguage.FRENCH
    else -> BioLanguage.ENGLISH
}

/**
 * Which Wikipedia editions to try, in order, for the current interface
 * language.
 *
 * A `when` over [BioLanguage] rather than a chain of `if`s, for the reason
 * that enum exists: Spanish used to fall through the `else` here and read the
 * English edition, so a Spanish interface opened a biography in English under
 * a sentence that was — after the age line was fixed — correctly in Spanish.
 * Exhaustive means a fifth language fails to compile instead of inheriting
 * that.
 *
 * English stays on English alone: falling back *from* English would mean
 * changing the app language could still produce a foreign-language biography.
 */
internal fun wikipediaEditionsFor(languageTag: String): List<String> =
    when (bioLanguageOf(languageTag)) {
        BioLanguage.PORTUGUESE -> listOf("pt", "en")
        BioLanguage.SPANISH -> listOf("es", "en")
        BioLanguage.FRENCH -> listOf("fr", "en")
        BioLanguage.ENGLISH -> listOf("en")
    }

/**
 * The one-line sentence every biography opens with.
 *
 * Two clauses, and the second is the point of this being a sentence rather
 * than a label: how old the person is now (or was when they died), and how old
 * they were when the title the viewer is looking at came out. "Carlos tem 49
 * anos" answers a question nobody asked while watching a film from 2009;
 * "e possuía 32 anos quando o filme estreou" is the one that connects the face
 * on screen to the person in the popup.
 *
 * Null when there is no birthday to compute from — the sentence is dropped
 * rather than guessed at.
 *
 * Follows [languageTag] independently of which Wikipedia edition answered: an
 * English interface reading a Portuguese-edition extract (the edition list
 * falls back across languages, the interface setting does not) must still get
 * this one sentence in English.
 *
 * @param releaseDate the title's own `YYYY-MM-DD`, when known. Optional
 *   because the sentence has to keep working for callers that have a person
 *   but no title in hand.
 * @param isSeries chooses "the series" over "the film"; saying *film* about a
 *   television series is the kind of wrong that is only visible to the person
 *   least able to ignore it.
 */
internal fun ageSentence(
    name: String,
    birthday: String?,
    deathday: String?,
    languageTag: String,
    releaseDate: String? = null,
    isSeries: Boolean = false,
): String? {
    if (birthday.isNullOrBlank()) return null
    val language = bioLanguageOf(languageTag)

    val died = !deathday.isNullOrBlank()
    val currentAge = if (died) ageAtDate(birthday, deathday!!) else ageToday(birthday)
    val opening = currentAge?.let { openingClause(language, name, it, died) } ?: return null

    val premiereAge = releaseDate
        ?.takeIf { it.isNotBlank() }
        ?.let { ageAtDate(birthday, it) }
        // Deliberate, and not an off-by-one: see [FILMING_OFFSET_YEARS].
        ?.minus(FILMING_OFFSET_YEARS)
        // A release inside the person's first year, or the data error of a
        // credit on a title older than they are. Either way there is no age to
        // state rather than a negative one to render.
        ?.takeIf { it >= 0 }
        // Equal ages make the second clause say nothing twice — most often a
        // posthumous release, where the offset lands exactly on the age in the
        // first clause. Dropping it is not a failure case, so that clause
        // still stands on its own.
        ?.takeIf { it != currentAge }
        ?: return "$opening."

    return "$opening ${conjunction(language)} ${premiereClause(language, premiereAge, isSeries)}."
}

/**
 * Years taken off the age computed for the release date.
 *
 * The number this sentence is meant to answer is how old the person was *in
 * the scenes being watched*, and a film reaches an audience about a year after
 * it is shot. Computing from the release date alone therefore reads one year
 * older than the face on screen, which is the discrepancy this closes.
 *
 * So it is a deliberate offset, not an arithmetic slip — [ageAtDate] itself is
 * exact and tested as such. Anyone reading this later and reaching for the
 * obvious "fix" should change the product decision first, not the subtraction:
 * the tests below assert the offset, and will fail loudly rather than quietly
 * accept its removal.
 */
private const val FILMING_OFFSET_YEARS = 1

private fun openingClause(language: BioLanguage, name: String, age: Int, died: Boolean): String =
    if (died) {
        when (language) {
            BioLanguage.PORTUGUESE -> "$name morreu aos $age anos"
            BioLanguage.SPANISH -> "$name murió a los $age años"
            BioLanguage.FRENCH -> "$name est décédé à l’âge de $age ans"
            BioLanguage.ENGLISH -> "$name died at $age"
        }
    } else {
        when (language) {
            BioLanguage.PORTUGUESE -> "$name tem $age anos"
            BioLanguage.SPANISH -> "$name tiene $age años"
            BioLanguage.FRENCH -> "$name a $age ans"
            BioLanguage.ENGLISH -> "$name is $age years old"
        }
    }

private fun conjunction(language: BioLanguage): String = when (language) {
    BioLanguage.PORTUGUESE -> "e"
    BioLanguage.SPANISH -> "y"
    BioLanguage.FRENCH -> "et"
    BioLanguage.ENGLISH -> "and"
}

private fun premiereClause(language: BioLanguage, age: Int, isSeries: Boolean): String = when (language) {
    BioLanguage.PORTUGUESE ->
        if (isSeries) "possuía $age anos quando a série estreou" else "possuía $age anos quando o filme estreou"
    BioLanguage.SPANISH ->
        if (isSeries) "tenía $age años cuando se estrenó la serie" else "tenía $age años cuando se estrenó la película"
    BioLanguage.FRENCH ->
        if (isSeries) "avait $age ans à la sortie de la série" else "avait $age ans à la sortie du film"
    BioLanguage.ENGLISH ->
        if (isSeries) "was $age when the series premiered" else "was $age when the film premiered"
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
