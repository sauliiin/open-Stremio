package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Request used by MDBList's show-level dropped-status endpoint. */
@Serializable
data class MdblistDroppedWriteDto(
    val shows: List<MdblistDroppedShowDto>,
)

@Serializable
data class MdblistDroppedShowDto(
    val ids: MdblistDroppedIdsDto,
    @SerialName("dropped_at") val droppedAt: String,
)

@Serializable
data class MdblistDroppedIdsDto(
    val imdb: String? = null,
    val tmdb: Int? = null,
)

/** MDBList may answer 200 even when it did not resolve the submitted show. */
@Serializable
data class MdblistDroppedResponseDto(
    val updated: MdblistDroppedCountsDto = MdblistDroppedCountsDto(),
    val existing: MdblistDroppedCountsDto = MdblistDroppedCountsDto(),
    @SerialName("not_found") val notFound: MdblistDroppedCountsDto = MdblistDroppedCountsDto(),
    val errors: List<JsonObject> = emptyList(),
) {
    fun acceptedShow(): Boolean = updated.shows > 0 || existing.shows > 0
}

@Serializable
data class MdblistDroppedCountsDto(
    val shows: Int = 0,
)
