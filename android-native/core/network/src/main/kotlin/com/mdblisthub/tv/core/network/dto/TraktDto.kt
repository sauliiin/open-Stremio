package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ------------------------------------------------------------------- oauth

@Serializable
data class TraktDeviceCodeRequestDto(
    @SerialName("client_id") val clientId: String,
)

@Serializable
data class TraktDeviceCodeDto(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_url") val verificationUrl: String = "",
    /** Seconds the pair stays valid — ten minutes in practice. */
    @SerialName("expires_in") val expiresIn: Int = 600,
    /** Seconds between polls. Polling faster than this earns a 429. */
    val interval: Int = 5,
)

@Serializable
data class TraktDeviceTokenRequestDto(
    /** The `device_code`, under a different name than the one it arrived as. */
    val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)

@Serializable
data class TraktRefreshRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    /**
     * Required even though nothing is redirected anywhere: Trakt validates the
     * field's presence on every `/oauth/token` grant, device flow included.
     * This is the out-of-band value its own documentation prescribes.
     */
    @SerialName("redirect_uri") val redirectUri: String = "urn:ietf:wg:oauth:2.0:oob",
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
data class TraktRevokeRequestDto(
    val token: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)

@Serializable
data class TraktTokenDto(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("token_type") val tokenType: String = "bearer",
    /** Seconds. Seven days at the time of writing; read, never assumed. */
    @SerialName("expires_in") val expiresIn: Long = 0,
    /**
     * Single use. Each refresh invalidates the token that bought it, so this
     * has to be stored over the old one rather than alongside it — a retry
     * with the previous value answers `invalid_grant` and drops the link.
     */
    @SerialName("refresh_token") val refreshToken: String = "",
    val scope: String = "public",
    /** Unix seconds at issue; `created_at + expires_in` is the real deadline. */
    @SerialName("created_at") val createdAt: Long = 0,
)

// -------------------------------------------------------------------- user

@Serializable
data class TraktUserSettingsDto(val user: TraktUserDto? = null)

@Serializable
data class TraktUserDto(
    val username: String = "",
    val name: String? = null,
    val ids: TraktUserIdsDto? = null,
)

@Serializable
data class TraktUserIdsDto(val slug: String? = null)

// ------------------------------------------------------------------ titles

/**
 * Every id Trakt knows a title by. `trakt` is the only guaranteed one; this
 * app cares about `tmdb` (the key its own Room tables use) and `imdb` (what
 * addons are queried with, and the id writes travel as).
 */
@Serializable
data class TraktIdsDto(
    val trakt: Int? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
    val tvdb: Int? = null,
)

/**
 * A movie or a show. The two are the same shape everywhere this app reads
 * them, so one class covers both and the wrapper says which it was.
 *
 * `images` is deliberately absent even though `extended=full` returns it:
 * every item here carries `ids.tmdb`, and the artwork pass these feeds
 * already run (`MediaRepository.ensureDetail`) fetches posters from TMDB.
 * Reading Trakt's copies instead would make cards look different depending
 * on which provider filled the row.
 */
@Serializable
data class TraktTitleDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIdsDto? = null,
    val runtime: Int? = null,
)

@Serializable
data class TraktEpisodeDto(
    val season: Int? = null,
    /** Trakt's name for what mdblist calls `episode`. */
    val number: Int? = null,
    val title: String? = null,
    val ids: TraktIdsDto? = null,
    val runtime: Int? = null,
)

// ------------------------------------------------------------------- reads

/** `/sync/watchlist/{type}/added/desc`. */
@Serializable
data class TraktWatchlistItemDto(
    val rank: Int? = null,
    @SerialName("listed_at") val listedAt: String? = null,
    val type: String? = null,
    val movie: TraktTitleDto? = null,
    val show: TraktTitleDto? = null,
)

/**
 * `/sync/collection/{type}`. Movies date the entry `collected_at`; shows use
 * `last_collected_at`, because a show's collection is per-episode and the
 * entry only reports the newest one. Both are read so "Recently Added" can
 * sort the two kinds against each other.
 */
@Serializable
data class TraktCollectionItemDto(
    @SerialName("collected_at") val collectedAt: String? = null,
    @SerialName("last_collected_at") val lastCollectedAt: String? = null,
    val type: String? = null,
    val movie: TraktTitleDto? = null,
    val show: TraktTitleDto? = null,
) {
    val addedAt: String? get() = collectedAt ?: lastCollectedAt
}

/**
 * `/sync/history/{type}` — newest first, one row per play.
 *
 * An episode row carries both `episode` and its `show`; a movie row only
 * `movie`. The "Recently Watched" feed shows the series card either way, so
 * the episode is read for its label and nothing else.
 */
@Serializable
data class TraktHistoryItemDto(
    val id: Long = 0,
    @SerialName("watched_at") val watchedAt: String? = null,
    val action: String? = null,
    val type: String? = null,
    val movie: TraktTitleDto? = null,
    val show: TraktTitleDto? = null,
    val episode: TraktEpisodeDto? = null,
)

/**
 * `/sync/watched/{type}` — the whole watched set, not a recent slice. This is
 * what the detail screen's "watched" button reads its state from.
 */
@Serializable
data class TraktWatchedItemDto(
    val plays: Int = 0,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    val movie: TraktTitleDto? = null,
    val show: TraktTitleDto? = null,
    val seasons: List<TraktWatchedSeasonDto>? = null,
)

@Serializable
data class TraktWatchedSeasonDto(
    val number: Int,
    val episodes: List<TraktWatchedEpisodeDto>? = null,
)

@Serializable
data class TraktWatchedEpisodeDto(
    val number: Int,
    val plays: Int = 0,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
)

/** `/sync/progress/up_next`. */
@Serializable
data class TraktUpNextItemDto(
    val show: TraktTitleDto? = null,
    val progress: TraktProgressDto? = null,
)

@Serializable
data class TraktProgressDto(
    val aired: Int = 0,
    val completed: Int = 0,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    @SerialName("next_episode") val nextEpisode: TraktEpisodeDto? = null,
    @SerialName("last_episode") val lastEpisode: TraktEpisodeDto? = null,
)

/** `/sync/playback/{type}` — the paused sessions behind "Continue Watching". */
@Serializable
data class TraktPlaybackDto(
    val id: Long = 0,
    /** 0–100. */
    val progress: Double = 0.0,
    @SerialName("paused_at") val pausedAt: String? = null,
    val type: String? = null,
    val movie: TraktTitleDto? = null,
    val show: TraktTitleDto? = null,
    val episode: TraktEpisodeDto? = null,
)

// ------------------------------------------------------------------ writes

/**
 * The body every library write takes: arrays keyed by kind, each entry
 * identified by any one id Trakt recognises.
 *
 * `/sync/history` additionally accepts `watched_at` per entry; left null it
 * means "now", which is the only thing this app ever wants.
 */
@Serializable
data class TraktSyncWriteDto(
    val movies: List<TraktWriteItemDto>? = null,
    val shows: List<TraktWriteItemDto>? = null,
)

@Serializable
data class TraktWriteItemDto(val ids: TraktIdsDto)

/**
 * What a write answers with.
 *
 * Worth reading rather than trusting the status code: Trakt answers `201` for
 * a body whose every id it failed to resolve, reporting them under
 * `not_found` — so an id it does not know looks exactly like a success unless
 * this is checked. The mdblist path has no equivalent and only reads the code.
 */
@Serializable
data class TraktSyncResponseDto(
    val added: TraktSyncCountsDto? = null,
    val deleted: TraktSyncCountsDto? = null,
    val existing: TraktSyncCountsDto? = null,
    @SerialName("not_found") val notFound: TraktNotFoundDto? = null,
) {
    /** True when the call resolved nothing at all — the only real failure here. */
    fun resolvedNothing(): Boolean {
        val touched = (added?.total ?: 0) + (deleted?.total ?: 0) + (existing?.total ?: 0)
        return touched == 0 && (notFound?.total ?: 0) > 0
    }
}

@Serializable
data class TraktSyncCountsDto(
    val movies: Int = 0,
    val shows: Int = 0,
    val seasons: Int = 0,
    val episodes: Int = 0,
) {
    val total: Int get() = movies + shows + seasons + episodes
}

@Serializable
data class TraktNotFoundDto(
    val movies: List<TraktWriteItemDto> = emptyList(),
    val shows: List<TraktWriteItemDto> = emptyList(),
    val seasons: List<TraktWriteItemDto> = emptyList(),
    val episodes: List<TraktWriteItemDto> = emptyList(),
) {
    val total: Int get() = movies.size + shows.size + seasons.size + episodes.size
}

// --------------------------------------------------------------- scrobbling

/**
 * `/scrobble/{start,pause,stop}`.
 *
 * A movie carries `movie`; an episode carries `episode` *and* the `show` it
 * belongs to, because an episode's own Trakt id is not something this app
 * tracks — season and number against the show is.
 */
@Serializable
data class TraktScrobbleDto(
    val progress: Double,
    val movie: TraktWriteItemDto? = null,
    val show: TraktWriteItemDto? = null,
    val episode: TraktScrobbleEpisodeDto? = null,
)

@Serializable
data class TraktScrobbleEpisodeDto(
    val season: Int,
    val number: Int,
)
