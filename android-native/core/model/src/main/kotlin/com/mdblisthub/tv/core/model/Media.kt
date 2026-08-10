package com.mdblisthub.tv.core.model

import kotlinx.serialization.Serializable

/** One of the signed-in account's mdblist lists — a row on the home screen. */
@Serializable
data class MediaList(
    val id: Long,
    /** Local display name; users may rename a row without changing MDBList. */
    val name: String,
    /** The untouched mdblist name, needed to look the list up again. */
    val originalName: String,
    val slug: String = "",
    val description: String = "",
    val mediaType: MediaType? = null,
    val itemCount: Int = 0,
    val dynamic: Boolean = false,
    val private: Boolean = false,
    val hidden: Boolean = false,
    /** Shared home-screen order, including positions occupied by Stremio catalogs. */
    val position: Int = 0,
    val updatedAt: String? = null,
)

/** A poster card. Deliberately small — a row holds hundreds of these. */
@Serializable
data class MediaItem(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val imdbId: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    /** Best available score, 0–100, for the corner badge on the card. */
    val score: Int? = null,
) {
    /** Stable identity across lists, used as the Compose list key. */
    val key: String get() = "${type.mdblist}:${tmdbId.takeIf { it > 0 } ?: imdbId.orEmpty()}"
}

@Serializable
data class CastMember(
    val id: Int,
    val name: String,
    val character: String?,
    val profileUrl: String?,
)

/** Where a mirrored review came from — mdblist proxies both. */
@Serializable
enum class ReviewProvider { TRAKT, TMDB }

@Serializable
data class Review(
    val author: String,
    val content: String,
    val rating: Double? = null,
    val provider: ReviewProvider = ReviewProvider.TMDB,
    val updatedAt: String? = null,
)

@Serializable
data class SeasonSummary(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val airDate: String? = null,
    val posterUrl: String? = null,
)

@Serializable
data class Episode(
    val id: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val overview: String? = null,
    val stillUrl: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null,
    val voteAverage: Double? = null,
)

/**
 * Everything the detail screen paints, assembled from mdblist + TMDB + OMDb.
 * It is stored whole in the metadata cache, so opening a title a second time
 * is a database read and nothing else.
 */
@Serializable
data class MediaDetail(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val imdbId: String? = null,
    val originalTitle: String? = null,
    val tagline: String? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    /** TMDB clearlogo. Kodi's skins lead with it instead of a text title. */
    val logoUrl: String? = null,
    val year: Int? = null,
    val releaseDate: String? = null,
    val runtimeMinutes: Int? = null,
    val seasonCount: Int? = null,
    val episodeCount: Int? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val certification: String? = null,
    val trailerKey: String? = null,
    val ratings: List<RatingBadge> = emptyList(),
    val seasons: List<SeasonSummary> = emptyList(),
    val recommendations: List<MediaItem> = emptyList(),
    val reviews: List<Review> = emptyList(),
    val budget: Long? = null,
    val revenue: Long? = null,
) {
    fun toItem(): MediaItem = MediaItem(
        tmdbId = tmdbId,
        type = type,
        title = title,
        imdbId = imdbId,
        year = year,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        genres = genres,
        runtimeMinutes = runtimeMinutes,
        score = ratings.firstOrNull()?.score,
    )
}

/** The account behind the mdblist API key. */
@Serializable
data class HubUser(
    val userId: Long,
    val username: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val plan: String? = null,
)
