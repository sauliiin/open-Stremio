package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OMDb answers in TitleCase and writes "N/A" where it means null. */
@Serializable
data class OmdbDto(
    @SerialName("Response") val response: String = "False",
    @SerialName("Rated") val rated: String? = null,
    @SerialName("Plot") val plot: String? = null,
    @SerialName("Awards") val awards: String? = null,
    @SerialName("Metascore") val metascore: String? = null,
    @SerialName("imdbRating") val imdbRating: String? = null,
    @SerialName("imdbVotes") val imdbVotes: String? = null,
    @SerialName("BoxOffice") val boxOffice: String? = null,
    @SerialName("Ratings") val ratings: List<OmdbRatingDto> = emptyList(),
) {
    val ok: Boolean get() = response.equals("True", true)
}

@Serializable
data class OmdbRatingDto(
    @SerialName("Source") val source: String = "",
    @SerialName("Value") val value: String = "",
)

/** OMDb's "N/A" is a value, not a null, so it has to be filtered explicitly. */
fun String?.orNullIfNA(): String? =
    this?.takeIf { it.isNotBlank() && !it.equals("N/A", true) }
