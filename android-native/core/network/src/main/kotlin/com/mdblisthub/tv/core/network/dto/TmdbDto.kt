package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbDetailDto(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    val seasons: List<TmdbSeasonSummaryDto> = emptyList(),
    val status: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Long = 0,
    val budget: Long? = null,
    val revenue: Long? = null,
    val genres: List<TmdbNamedDto> = emptyList(),
    @SerialName("production_companies") val companies: List<TmdbNamedDto> = emptyList(),
    val credits: TmdbCreditsDto? = null,
    @SerialName("aggregate_credits") val aggregateCredits: TmdbCreditsDto? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIdsDto? = null,
    val videos: TmdbVideosDto? = null,
    val recommendations: TmdbPageDto? = null,
    val images: TmdbImagesDto? = null,
    @SerialName("content_ratings") val contentRatings: TmdbContentRatingsDto? = null,
    @SerialName("release_dates") val releaseDates: TmdbReleaseDatesDto? = null,
    val reviews: TmdbReviewsDto? = null,
) {
    val displayTitle: String get() = title ?: name ?: ""
    val displayOriginalTitle: String? get() = originalTitle ?: originalName
    val date: String? get() = releaseDate?.takeIf { it.isNotBlank() } ?: firstAirDate?.takeIf { it.isNotBlank() }
    val minutes: Int? get() = runtime?.takeIf { it > 0 } ?: episodeRunTime.firstOrNull()
}

@Serializable
data class TmdbNamedDto(val id: Int = 0, val name: String = "")

@Serializable
data class TmdbExternalIdsDto(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
)

@Serializable
data class TmdbCreditsDto(
    val cast: List<TmdbCastDto> = emptyList(),
    val crew: List<TmdbCrewDto> = emptyList(),
)

@Serializable
data class TmdbCastDto(
    val id: Int = 0,
    val name: String = "",
    val character: String? = null,
    /** `aggregate_credits` puts the character here instead. */
    val roles: List<TmdbRoleDto> = emptyList(),
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int? = null,
) {
    val role: String? get() = character?.takeIf { it.isNotBlank() } ?: roles.firstOrNull()?.character
}

@Serializable
data class TmdbRoleDto(val character: String? = null)

@Serializable
data class TmdbCrewDto(
    val id: Int = 0,
    val name: String = "",
    val job: String? = null,
    /** Same split as the cast: `credits` has `job`, `aggregate_credits` `jobs`. */
    val jobs: List<TmdbJobDto> = emptyList(),
    val department: String? = null,
) {
    fun holds(titles: Set<String>): Boolean =
        job in titles || jobs.any { it.job in titles }
}

@Serializable
data class TmdbJobDto(val job: String? = null)

@Serializable
data class TmdbVideosDto(val results: List<TmdbVideoDto> = emptyList())

@Serializable
data class TmdbVideoDto(
    val key: String = "",
    val name: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
)

@Serializable
data class TmdbReviewsDto(val results: List<TmdbReviewDto> = emptyList())

@Serializable
data class TmdbReviewDto(
    val author: String = "",
    @SerialName("author_details") val authorDetails: TmdbReviewAuthorDto? = null,
    val content: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class TmdbReviewAuthorDto(
    val name: String? = null,
    val username: String? = null,
    val rating: Double? = null,
)

@Serializable
data class TmdbImagesDto(
    val logos: List<TmdbImageDto> = emptyList(),
    /** Full landscape collection; the highest-voted image feeds Primefly cards. */
    val backdrops: List<TmdbImageDto> = emptyList(),
)

@Serializable
data class TmdbImageDto(
    @SerialName("file_path") val filePath: String = "",
    @SerialName("iso_639_1") val language: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
)

@Serializable
data class TmdbPageDto(val results: List<TmdbSearchResultDto> = emptyList())

@Serializable
data class TmdbFindDto(
    @SerialName("movie_results") val movieResults: List<TmdbSearchResultDto> = emptyList(),
    @SerialName("tv_results") val tvResults: List<TmdbSearchResultDto> = emptyList(),
)

@Serializable
data class TmdbSearchResultDto(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    /**
     * How many people that average is made of.
     *
     * Only the spotlight reads it, and it is the difference between a real
     * recommendation and a title carrying a 9.0 built out of three votes —
     * which, sorted by score, is exactly what floats to the top of a
     * "destaques" carousel. See `RecommendationsRepository.MIN_SPOTLIGHT_VOTES`.
     */
    @SerialName("vote_count") val voteCount: Int = 0,
)

@Serializable
data class TmdbSeasonSummaryDto(
    val id: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
data class TmdbSeasonDto(
    val id: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    val episodes: List<TmdbEpisodeDto> = emptyList(),
)

@Serializable
data class TmdbEpisodeDto(
    val id: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
)

// Certification: shows carry `content_ratings`, films `release_dates`.
@Serializable
data class TmdbContentRatingsDto(val results: List<TmdbContentRatingDto> = emptyList())

@Serializable
data class TmdbContentRatingDto(
    @SerialName("iso_3166_1") val country: String = "",
    val rating: String = "",
)

@Serializable
data class TmdbReleaseDatesDto(val results: List<TmdbReleaseCountryDto> = emptyList())

@Serializable
data class TmdbReleaseCountryDto(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("release_dates") val releaseDates: List<TmdbReleaseDateDto> = emptyList(),
)

@Serializable
data class TmdbReleaseDateDto(val certification: String = "")

@Serializable
data class TmdbPersonDto(
    val id: Int = 0,
    val name: String = "",
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbKeywordPageDto(val results: List<TmdbKeywordDto> = emptyList())

@Serializable
data class TmdbKeywordDto(
    val id: Int = 0,
    val name: String = "",
)
