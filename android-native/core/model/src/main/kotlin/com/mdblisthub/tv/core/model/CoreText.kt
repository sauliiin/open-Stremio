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

    /** Stands in for a title the catalogue never supplied. */
    val untitled: String
        get() = if (isPortuguese) "Sem título" else "Untitled"

    /** Stands in for a review whose author field came back blank. */
    val anonymous: String
        get() = if (isPortuguese) "Anônimo" else "Anonymous"

    /** Rotten Tomatoes' audience score, as opposed to the critics' one. */
    val audienceScore: String
        get() = if (isPortuguese) "RT Público" else "RT Audience"

    /** Stands in for a Stremio collection entry with neither a name nor a manifest to read one from. */
    val unnamedAddon: String
        get() = if (isPortuguese) "addon sem nome" else "unnamed addon"

    /**
     * The four account-native home rows' default titles, before a user ever
     * renames one — see `HomeFeedsRepository.defaults()`.
     *
     * "Watchlist" is deliberately identical in both branches: the app's own
     * Portuguese copy already borrows the English word rather than
     * translating it (see `detail_add_watchlist`, `settings_library_provider`
     * in `strings.xml`), so translating it here would be the one place that
     * disagreed with the rest of the interface.
     */
    val upNext: String
        get() = if (isPortuguese) "A Seguir" else "Up Next"

    val recentlyAdded: String
        get() = if (isPortuguese) "Adicionados Recentemente" else "Recently Added"

    val watchlist: String get() = "Watchlist"

    val recentlyWatched: String
        get() = if (isPortuguese) "Assistidos Recentemente" else "Recently Watched"
}
