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

    @Volatile var TMDB_KEY = ""
        private set
    @Volatile var OMDB_KEY = ""
        private set
    @Volatile var FANART_TV_API_KEY = ""
        private set

    /**
     * Trakt, the alternative source for the five account-owned home rows —
     * watchlist, collection, watched, up next and continue watching. Which of
     * the two answers them is a setting; see `UiPreferencesStore.libraryProvider`.
     *
     * Two hosts, not one: the device-code dance lives on `auth.` and
     * everything else on `api.`. Sending an OAuth request to the API host
     * answers 404, which is exactly the kind of failure that looks like a bad
     * client id.
     */
    const val TRAKT_API_BASE = "https://api.trakt.tv/"
    const val TRAKT_AUTH_BASE = "https://auth.trakt.tv/"
    const val TRAKT_API_VERSION = "2"

    /** Where the user types the code the device flow shows them. */
    const val TRAKT_ACTIVATE_URL = "https://trakt.tv/activate"

    /**
     * Not this project's own Trakt registration — creating a new one now
     * requires Trakt VIP, and this app's own previous registration was
     * deleted by Trakt at some point after it stopped being used. This is the
     * client id/secret plugin.video.pov (a Kodi addon) ships as the default
     * value of a hidden setting in its `settings.xml`, shared by every
     * install of that addon the same way. Verified live before reuse — a
     * `POST oauth/device/code` with just this id answers `200` with a real
     * device code, not the `401 invalid_client` this app's deleted app now
     * returns.
     *
     * Borrowing it carries the same risk any shared key does: it is another
     * project's identity with the Trakt, revocable by Trakt or by that
     * project at any time, for reasons this app has no visibility into and no
     * control over. If Trakt calls start failing across the board, this is
     * the first thing to check.
     */
    @Volatile var TRAKT_CLIENT_ID = ""
        private set
    @Volatile var TRAKT_CLIENT_SECRET = ""
        private set

    val traktConfigured: Boolean
        get() = TRAKT_CLIENT_ID.isNotBlank() && TRAKT_CLIENT_SECRET.isNotBlank()

    /** Simkl's PIN flow for TVs uses only this public application identifier. */
    const val SIMKL_API_BASE = "https://api.simkl.com/"
    const val SIMKL_APP_NAME = "omnistream"
    @Volatile var SIMKL_CLIENT_ID = ""
        private set
    val SIMKL_APP_VERSION: String get() = APP_VERSION

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
    @Volatile var OPENSUBTITLES_API_KEY = ""
        private set
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
    @Volatile var WYZIE_API_KEY = ""
        private set

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
        "es" -> "es-419"
        "fr" -> "fr-FR"
        else -> tag
    }

    @Volatile private var APP_VERSION: String = "unknown"

    val USER_AGENT: String get() = "omnistream/$APP_VERSION (Android TV)"

    /** Injects build-local credentials before the network graph is first used. */
    fun configure(credentials: ApiCredentials) {
        TMDB_KEY = credentials.tmdb
        OMDB_KEY = credentials.omdb
        FANART_TV_API_KEY = credentials.fanartTv
        TRAKT_CLIENT_ID = credentials.traktClientId
        TRAKT_CLIENT_SECRET = credentials.traktClientSecret
        SIMKL_CLIENT_ID = credentials.simklClientId
        OPENSUBTITLES_API_KEY = credentials.openSubtitles
        WYZIE_API_KEY = credentials.wyzie
        APP_VERSION = credentials.appVersion.ifBlank { "unknown" }
    }
}

data class ApiCredentials(
    val tmdb: String,
    val omdb: String,
    val fanartTv: String,
    val traktClientId: String,
    val traktClientSecret: String,
    val simklClientId: String,
    val openSubtitles: String,
    val wyzie: String,
    val appVersion: String,
)
