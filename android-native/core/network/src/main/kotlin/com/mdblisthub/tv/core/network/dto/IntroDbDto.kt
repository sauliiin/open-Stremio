package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * IntroDB's `/segments` answer — see `ApiConfig.INTRODB_BASE`.
 *
 * Every segment is null until someone has timed it, and the endpoint answers
 * 200 with all of them null rather than 404 for an episode it has never seen.
 */
@Serializable
data class IntroDbSegmentsDto(
    val intro: IntroDbSegmentDto? = null,
    val recap: IntroDbSegmentDto? = null,
    val outro: IntroDbSegmentDto? = null,
)

@Serializable
data class IntroDbSegmentDto(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    /** 0–1, how well the community submissions for this segment agree. */
    val confidence: Double? = null,
)
