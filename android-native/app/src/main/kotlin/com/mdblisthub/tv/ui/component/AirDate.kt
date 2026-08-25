package com.mdblisthub.tv.ui.component

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A TMDB air date, written the way each of this app's languages writes one.
 *
 * pt: "sex., 18/12/2026"   en: "Fri, Dec 18, 2026"   es: "vie., 18 dic 2026"
 *
 * Takes the raw "pt"/"en"/"es" code rather than a `Locale` — `Locale.forLanguageTag`
 * on a bare "pt" resolves to European Portuguese, whose CLDR weekday
 * abbreviations don't reliably match Brazil's; building the locale explicitly
 * for each branch pins the exact one this format was written against.
 *
 * `minSdk` is 24 without core library desugaring — see the note on
 * `SyncMappers.formatter()` — so this reaches for `SimpleDateFormat` rather
 * than `java.time`, the same tradeoff the rest of the app already made.
 *
 * Shared rather than private to the detail screen because the home hero now
 * writes the same date for the episode it is resuming, and two copies of a
 * format string are two chances for the two screens to disagree about what a
 * date looks like.
 */
internal fun formatAirDate(airDate: String, language: String): String? {
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(airDate)
    }.getOrNull() ?: return null
    return when (language) {
        "pt" -> SimpleDateFormat("EEE, dd/MM/yyyy", Locale.forLanguageTag("pt-BR")).format(parsed)
        "es" -> SimpleDateFormat("EEE, d MMM yyyy", Locale.forLanguageTag("es")).format(parsed)
        else -> SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(parsed)
    }
}
