package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MdbUserDto(
    @SerialName("user_id") val userId: Long = 0,
    val username: String = "",
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val plan: String? = null,
)

@Serializable
data class MdbListDto(
    val id: Long = 0,
    val name: String = "",
    val slug: String = "",
    val description: String = "",
    val mediatype: String? = null,
    val items: Int = 0,
    val likes: Int = 0,
    val dynamic: Boolean = false,
    val private: Boolean = false,
    @SerialName("last_updated_at") val lastUpdatedAt: String? = null,
)

@Serializable
data class MdbIdsDto(
    val imdb: String? = null,
    val tmdb: Int? = null,
    val tvdb: Int? = null,
    val trakt: Int? = null,
)

@Serializable
data class MdbItemDto(
    /** Already the TMDB id — mdblist keys its unified items by it. */
    val id: Int = 0,
    val mediatype: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val ids: MdbIdsDto? = null,
    val title: String = "",
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    val poster: String? = null,
    val genre: List<String>? = null,
    val rank: Int? = null,
    val ratings: List<MdbRatingDto>? = null,
    /** Present on `/watchlist/items`; used to enforce newest-first locally too. */
    @SerialName("watchlist_at") val watchlistAt: String? = null,
    /** Present on unified `/sync/collection`; source for Recently Added. */
    @SerialName("collected_at") val collectedAt: String? = null,
)

@Serializable
data class MdbRatingDto(
    val source: String = "",
    val value: Double? = null,
    /**
     * `Double`, not `Int`, however integral it looks: mdblist sends
     * `metacriticuser` as `89.0` on essentially every title, and kotlinx
     * decoding `89.0` into an `Int` throws — which fails the *whole*
     * `MdbInfoDto`, not just this field, taking the title's ratings and
     * reviews with it. That single mismatch is what left most titles with
     * neither.
     */
    val score: Double? = null,
    val votes: Long? = null,
    /** Rotten Tomatoes only: 1 fresh, 0 rotten. Absent means "decide by value". */
    val fresh: Int? = null,
)

@Serializable
data class MdbReviewDto(
    val author: String = "",
    val content: String = "",
    val rating: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** 1 = Trakt, 2 = TMDB. */
    @SerialName("provider_id") val providerId: Int = 0,
)

@Serializable
data class MdbInfoDto(
    val title: String? = null,
    val year: Int? = null,
    val released: String? = null,
    val description: String? = null,
    val tagline: String? = null,
    val runtime: Int? = null,
    val budget: Long? = null,
    val revenue: Long? = null,
    val ids: MdbIdsDto? = null,
    val type: String? = null,
    val ratings: List<MdbRatingDto> = emptyList(),
    val reviews: List<MdbReviewDto> = emptyList(),
)

// ------------------------------------------------------------- library sync

/**
 * `/watchlist/items` returns items directly while the `sync` reads wrap each
 * one in `{movie: {…}}` or `{show: {…}}`, so both shapes are declared here and
 * whichever arrives is read.
 */
@Serializable
data class BucketEntryDto(
    val id: Int? = null,
    val ids: MdbIdsDto? = null,
    val movie: BucketTitleDto? = null,
    val show: BucketTitleDto? = null,
    /**
     * `sync/watched` only — mdblist mirrors Trakt's sync shape here, where
     * this is what orders "most recently watched". Unverified against a live
     * response (the field could be named differently); a title that never
     * rises to the top of "porque você assistiu" despite being watched
     * recently is the symptom to look for if this guess is wrong.
     */
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
)

@Serializable
data class BucketEpisodeEntryDto(
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    val episode: BucketEpisodeDto? = null,
)

@Serializable
data class BucketEpisodeDto(
    val season: Int? = null,
    val number: Int? = null,
    val ids: MdbIdsDto? = null,
    val show: BucketTitleDto? = null,
)

@Serializable
data class BucketTitleDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: MdbIdsDto? = null,
    val poster: String? = null,
    val runtime: Int? = null,
)

/** `next_cursor` is what the following page is asked for by. */
@Serializable
data class BucketPaginationDto(
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class BucketResponseDto(
    val movies: List<BucketEntryDto> = emptyList(),
    val shows: List<BucketEntryDto> = emptyList(),
    val episodes: List<BucketEpisodeEntryDto> = emptyList(),
    /**
     * Absent on a response this app assembled by concatenating pages — see
     * `wholeBucket`. Its absence there is the point: a merged bucket is not a
     * page and has no page after it.
     */
    val pagination: BucketPaginationDto? = null,
) {
    fun tmdbIds(): List<Int> = (movies + shows).mapNotNull {
        it.movie?.ids?.tmdb ?: it.show?.ids?.tmdb ?: it.ids?.tmdb ?: it.id
    }
}

// ----------------------------------------------------------- home feeds

@Serializable
data class UpNextResponseDto(
    val items: List<UpNextItemDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class UpNextItemDto(
    val show: UpNextShowDto? = null,
    @SerialName("next_episode") val nextEpisode: UpNextEpisodeDto? = null,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
)

@Serializable
data class UpNextShowDto(
    val title: String = "",
    val year: Int? = null,
    val ids: MdbIdsDto? = null,
    val poster: String? = null,
)

@Serializable
data class UpNextEpisodeDto(
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val ids: MdbIdsDto? = null,
    val runtime: Int? = null,
    val still: String? = null,
    @SerialName("air_date") val airDate: String? = null,
)

// ---------------------------------------------------------------- playback

@Serializable
data class TitleRefDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: MdbIdsDto? = null,
)

@Serializable
data class EpisodeRefDto(
    val title: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    val episode: Int? = null,
    val ids: MdbIdsDto? = null,
)

@Serializable
data class PlaybackSessionDto(
    val id: Long = 0,
    /** 0–100, as captured at `updated_at`. */
    val progress: Double = 0.0,
    @SerialName("progress_at_update") val progressAtUpdate: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("updated_at_ts") val updatedAtTs: Long? = null,
    /** Minutes, or 0 when mdblist does not know it. */
    val runtime: Int? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    val type: String = "movie",
    val movie: TitleRefDto? = null,
    val show: TitleRefDto? = null,
    val episode: EpisodeRefDto? = null,
)
