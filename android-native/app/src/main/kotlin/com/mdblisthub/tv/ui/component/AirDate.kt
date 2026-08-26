package com.mdblisthub.tv.ui.component

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A TMDB air date, written the way each of this app's languages writes one.
 *
 * pt: "sex., 18/12/2026"   en: "Fri, Dec 18, 2026"   es: "vie., 18 dic 2026"
 * fr: "ven. 18 déc. 2026"
 *
 * Takes the raw "pt"/"en"/"es"/"fr" code rather than a `Locale` — `Locale.forLanguageTag`
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
    val parsed = runCatching { parser.get()!!.parse(airDate) }.getOrNull() ?: return null
    return formatters.getValue(language.takeIf(formatters::containsKey) ?: "en").get()!!.format(parsed)
}

/**
 * One [SimpleDateFormat] per thread per pattern, built on first use.
 *
 * Constructing one is not cheap — it compiles the pattern and pulls the
 * locale's CLDR symbols — and the season list calls this once per episode, so
 * opening a 24-episode season built 48 of them. They cannot simply be shared
 * `val`s because `SimpleDateFormat` is famously not thread-safe; a
 * [ThreadLocal] is the standard way to keep both facts true at once.
 */
private fun formatter(pattern: String, locale: Locale) =
    // `object :` rather than `ThreadLocal.withInitial`, which needs
    // `java.util.function.Supplier` and so is only guaranteed from API 24 —
    // exactly this app's floor, with no margin. This form has existed since
    // API 1 and needs no desugaring.
    object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat(pattern, locale)
    }

private val parser = formatter("yyyy-MM-dd", Locale.US)

private val formatters = mapOf(
    "pt" to formatter("EEE, dd/MM/yyyy", Locale.forLanguageTag("pt-BR")),
    "es" to formatter("EEE, d MMM yyyy", Locale.forLanguageTag("es")),
    "fr" to formatter("EEE d MMM yyyy", Locale.FRENCH),
    "en" to formatter("EEE, MMM d, yyyy", Locale.US),
)
