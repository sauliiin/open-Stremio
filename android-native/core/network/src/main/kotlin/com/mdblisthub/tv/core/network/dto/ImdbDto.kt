package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.Serializable

/**
 * IMDb's GraphQL endpoint, which is how its own site fetches trailers.
 *
 * Two round trips, because that is how the schema is shaped: a title knows
 * the id of its latest trailer, and a video knows where its files are.
 */
@Serializable
data class ImdbGraphqlRequest(
    val query: String,
    val variables: Map<String, String>,
    val operationName: String? = null,
)

// ------------------------------------------------- 1. title -> trailer id

@Serializable
data class ImdbTrailerIdResponse(val data: ImdbTrailerIdData? = null)

@Serializable
data class ImdbTrailerIdData(val title: ImdbTitle? = null)

@Serializable
data class ImdbTitle(val latestTrailer: ImdbLatestTrailer? = null)

@Serializable
data class ImdbLatestTrailer(val id: String? = null)

// --------------------------------------------- 2. trailer id -> file URLs

@Serializable
data class ImdbPlaybackResponse(val data: ImdbPlaybackData? = null)

@Serializable
data class ImdbPlaybackData(val video: ImdbVideo? = null)

@Serializable
data class ImdbVideo(val playbackURLs: List<ImdbPlaybackUrl> = emptyList())

/**
 * One file. `displayName` is a human label — "1080p", "720p", "SD" — and is
 * the only place the resolution appears, so picking a quality means reading
 * it. `videoMimeType` separates the plain MP4s from the HLS master.
 */
@Serializable
data class ImdbPlaybackUrl(
    val displayName: ImdbDisplayName? = null,
    val videoMimeType: String? = null,
    val url: String? = null,
)

@Serializable
data class ImdbDisplayName(val value: String? = null)
