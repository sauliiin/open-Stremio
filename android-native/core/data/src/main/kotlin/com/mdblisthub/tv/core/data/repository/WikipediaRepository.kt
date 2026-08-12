package com.mdblisthub.tv.core.data.repository

import android.net.Uri
import android.util.Log
import com.mdblisthub.tv.core.model.PersonSummary
import com.mdblisthub.tv.core.model.WikipediaLookup
import com.mdblisthub.tv.core.network.WikipediaApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * A cast member's bio, looked up by name alone.
 *
 * Goes straight at the REST summary endpoint with the credited name as the
 * title — the same endpoint Wikipedia's own official apps use for wide
 * public traffic, and normalises near-exact titles (case, spaces vs
 * underscores) server-side on its own. An earlier version resolved the
 * title through the older `/w/api.php?action=query` search first, but that
 * endpoint enforces much stricter anti-abuse limits on anonymous traffic —
 * shared IPs (carrier CGNAT, a TV box's ISP) were getting a flat HTTP 403
 * from it long before the summary endpoint itself would ever be a problem.
 * The edition follows the current interface language. Portuguese uses the
 * Portuguese edition first and falls back to English when no local article
 * exists; English stays on the English edition so changing the app language
 * cannot still produce a Portuguese biography.
 */
class WikipediaRepository(
    private val api: WikipediaApi,
    private val interfaceLanguage: Flow<String>,
) {

    suspend fun summaryFor(name: String): WikipediaLookup {
        var failure: Throwable? = null
        for (edition in wikipediaEditionsFor(interfaceLanguage.first())) {
            val result = runCatching { fetch(edition, name) }
            result.getOrNull()?.let { return WikipediaLookup.Found(it) }
            failure = result.exceptionOrNull() ?: failure
        }

        // No edition hit — surface *why*, not just that it failed. A page that
        // genuinely does not exist and a network/parse failure both end up
        // here, and only the reason tells them apart.
        if (failure != null) Log.w(TAG, "lookup failed for \"$name\"", failure)
        return WikipediaLookup.NotFound(failure?.let { "${it::class.simpleName}: ${it.message}" } ?: "sem artigo")
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

internal fun wikipediaEditionsFor(languageTag: String): List<String> =
    if (languageTag.equals("pt", ignoreCase = true) || languageTag.startsWith("pt-", ignoreCase = true)) {
        listOf("pt", "en")
    } else {
        listOf("en")
    }
