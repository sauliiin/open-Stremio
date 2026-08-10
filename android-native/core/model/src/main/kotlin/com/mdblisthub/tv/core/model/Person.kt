package com.mdblisthub.tv.core.model

/** A cast member's bio, pulled from Wikipedia by name — see the cast popup. */
data class PersonSummary(
    val name: String,
    val extract: String,
    val thumbnailUrl: String? = null,
    val pageUrl: String? = null,
)

/**
 * Whether the lookup found a bio, carrying *why* when it did not — a name
 * with genuinely no article reads identically to a network failure
 * otherwise, and the two need different fixes.
 */
sealed interface WikipediaLookup {
    data class Found(val summary: PersonSummary) : WikipediaLookup
    data class NotFound(val reason: String) : WikipediaLookup
}
