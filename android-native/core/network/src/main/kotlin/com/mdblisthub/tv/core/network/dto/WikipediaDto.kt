package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The REST `page/summary/{title}` response, trimmed to what the popup shows. */
@Serializable
data class WikipediaSummaryDto(
    val title: String? = null,
    val extract: String? = null,
    val thumbnail: WikipediaImageDto? = null,
    @SerialName("content_urls") val contentUrls: WikipediaContentUrlsDto? = null,
)

@Serializable
data class WikipediaImageDto(val source: String? = null)

@Serializable
data class WikipediaContentUrlsDto(val desktop: WikipediaPageUrlDto? = null)

@Serializable
data class WikipediaPageUrlDto(val page: String? = null)
