package com.mdblisthub.tv.core.model

import java.util.Locale

/**
 * The handful of user-facing strings produced below the UI layer.
 *
 * These exist because `:core:model` and `:core:data` are where a title without
 * a name or a review without an author gets its placeholder — the value is
 * baked into the domain object and stored in Room, long before any composable
 * or `Context` is in reach, so `stringResource` is not available at the point
 * where the text is decided.
 *
 * Keyed off [Locale.getDefault] rather than a `Context`, and that is sound
 * here for the same reason it is in `Languages.label`: `MainActivity` mirrors
 * every interface-language change into the process default precisely so code
 * with no Context can still render in the chosen language.
 *
 * **This is not a general-purpose translation layer and must not grow into
 * one.** Anything with a Context available belongs in `strings.xml`; anything
 * that is an *error* belongs in a typed failure the UI can map. What is left
 * here is only the placeholder text that has nowhere else to live.
 */
object CoreText {

    private val isPortuguese: Boolean
        get() = Locale.getDefault().language.equals("pt", ignoreCase = true)

    private val isFrench: Boolean
        get() = Locale.getDefault().language.equals("fr", ignoreCase = true)

    /** Stands in for a title the catalogue never supplied. */
    val untitled: String
        get() = when {
            isPortuguese -> "Sem título"
            isFrench -> "Sans titre"
            else -> "Untitled"
        }

    /** Stands in for a review whose author field came back blank. */
    val anonymous: String
        get() = when {
            isPortuguese -> "Anônimo"
            isFrench -> "Anonyme"
            else -> "Anonymous"
        }

    /** Rotten Tomatoes' audience score, as opposed to the critics' one. */
    val audienceScore: String
        get() = when {
            isPortuguese -> "RT Público"
            isFrench -> "Public RT"
            else -> "RT Audience"
        }

    /** Stands in for a Stremio collection entry with neither a name nor a manifest to read one from. */
    val unnamedAddon: String
        get() = when {
            isPortuguese -> "addon sem nome"
            isFrench -> "addon sans nom"
            else -> "unnamed addon"
        }

    /**
     * The four account-native home rows' default titles, before a user ever
     * renames one — see `HomeFeedsRepository.defaults()`.
     *
     * Portuguese deliberately borrows the English word "Watchlist", matching
     * the rest of that catalogue. French uses "Liste de suivi", matching its
     * translated detail and settings resources.
     */
    val upNext: String
        get() = when {
            isPortuguese -> "A Seguir"
            isFrench -> "À suivre"
            else -> "Up Next"
        }

    val recentlyAdded: String
        get() = when {
            isPortuguese -> "Adicionados Recentemente"
            isFrench -> "Ajoutés récemment"
            else -> "Recently Added"
        }

    val watchlist: String get() = if (isFrench) "Liste de suivi" else "Watchlist"

    val recentlyWatched: String
        get() = when {
            isPortuguese -> "Assistidos Recentemente"
            isFrench -> "Vus récemment"
            else -> "Recently Watched"
        }
}
