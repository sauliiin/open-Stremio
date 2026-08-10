package com.mdblisthub.tv.core.data.repository

import android.net.Uri
import android.util.Log
import com.mdblisthub.tv.core.model.PersonSummary
import com.mdblisthub.tv.core.model.WikipediaLookup
import com.mdblisthub.tv.core.network.WikipediaApi

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
 * pt.wikipedia is tried first — this is a Portuguese-language app — and
 * en.wikipedia backs it up, since plenty of actors have a fuller (or their
 * only) article there.
 */
class WikipediaRepository(private val api: WikipediaApi) {

    suspend fun summaryFor(name: String): WikipediaLookup {
        val pt = runCatching { fetch("pt", name) }
        pt.getOrNull()?.let { return WikipediaLookup.Found(it) }

        val en = runCatching { fetch("en", name) }
        en.getOrNull()?.let { return WikipediaLookup.Found(it) }

        // Neither hit — surface *why*, not just that it failed. A page that
        // genuinely does not exist and a network/parse failure both end up
        // here, and only the reason tells them apart.
        val failure = en.exceptionOrNull() ?: pt.exceptionOrNull()
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
