package com.mdblisthub.tv.core.network

/**
 * Every external service the app talks to.
 *
 * The mdblist key is absent on purpose: it belongs to whoever signed in and
 * lives in the session store, never in the binary. The TMDB and OMDb keys are
 * the app's own and are the same ones the web build ships.
 */
object ApiConfig {
    const val MDBLIST_BASE = "https://api.mdblist.com/"
    const val TMDB_BASE = "https://api.themoviedb.org/3/"
    const val OMDB_BASE = "https://www.omdbapi.com/"
    const val STREMIO_ACCOUNT_BASE = "https://api.strem.io/"
    const val FANART_TV_BASE = "https://webservice.fanart.tv/v3.2/"

    /** The authenticated Realtime Database belonging to safevault-fcbdc. */
    const val FIREBASE_BASE = "https://safevault-fcbdc-default-rtdb.firebaseio.com/"
    const val FIREBASE_USERS_ROOT = "users"

    /** IMDb's own (undocumented) GraphQL API — see [ImdbApi] for why it's worth using. */
    const val IMDB_GRAPHQL_BASE = "https://graphql.prod.api.imdb.a2z.com/"

    const val TMDB_KEY = "703cf5598b9fd74adac824baf7923126"
    const val OMDB_KEY = "b2f2fcca"
    const val FANART_TV_API_KEY = "a7ad21743fd710fccb738232f2fbdcfc"

    /**
     * OpenSubtitles.com's own API — a different service from the "OpenSubtitles
     * v3" Stremio addon of a similar name. Queried directly for [SubtitleMatcher]:
     * the Stremio subtitle protocol only guarantees `id`/`url`/`lang`, so a
     * subtitle from an addon rarely carries the release name a good automatic
     * match needs, where this API returns one for every result.
     *
     * Key and user agent are a personal OpenSubtitles.com API registration,
     * not this project's — used here with the account owner's permission,
     * on the same 100-download/day quota their own tools already share.
     */
    const val OPENSUBTITLES_BASE = "https://api.opensubtitles.com/api/v1/"
    const val OPENSUBTITLES_API_KEY = "9eBRI85k0K0D7teGENPWBhCrCH4jnsLF"
    const val OPENSUBTITLES_USER_AGENT = "mestreyoddarossi api for kodi"

    /**
     * Wyzie's own subtitle search — a second, independent source queried for
     * the same reason as OpenSubtitles.com above: a release name per result.
     * Its `language` query param takes exactly one code at a time in
     * practice — asking for `pb,pt` together has been observed to silently
     * drop every `pb` (Brazilian Portuguese) result, which is why
     * `StreamsRepository.wyzieSearch` queries each language on its own
     * rather than trusting a combined list.
     *
     * Key is a personal Wyzie registration, not this project's — used here
     * with the account owner's permission.
     */
    const val WYZIE_BASE = "https://sub.wyzie.io/"
    const val WYZIE_API_KEY = "wyzie-s9qb8pabb1bllkptwqe0z19ufdnpa5sa"

    /**
     * Metadata language, with an English fallback wherever TMDB supports one.
     *
     * A `var`, deliberately, and the one piece of mutable state in this file.
     * Interface strings following the language setting is only half of the job:
     * overviews, titles, certifications and taglines all come from TMDB, and
     * with this pinned to `pt-BR` an English interface still described every
     * film in Portuguese. Set once at startup and on each change from
     * `UiPreferencesStore.language` — see `HubApplication`.
     *
     * Not a `Flow` because every reader is a Retrofit query parameter deep in
     * a suspend call; threading a locale through all of them to change a
     * default nobody overrides would be ceremony for its own sake.
     */
    @Volatile
    var LANGUAGE: String = DEFAULT_LANGUAGE

    const val DEFAULT_LANGUAGE = "en-US"

    /**
     * Turns an interface language tag into the region-qualified one TMDB
     * expects. `pt` alone returns European Portuguese metadata, which is not
     * what a `pt` interface setting means for this app's audience.
     */
    fun metadataLanguageFor(tag: String): String = when (tag.lowercase()) {
        "pt", "pt-br" -> "pt-BR"
        "pt-pt" -> "pt-PT"
        "en" -> "en-US"
        else -> tag
    }

    const val USER_AGENT = "mdblist-hub-tv/0.1 (Android TV)"
}
