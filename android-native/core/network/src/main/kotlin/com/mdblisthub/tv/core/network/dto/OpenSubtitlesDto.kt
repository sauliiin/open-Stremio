package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OpenSubtitles.com's own REST API — see `ApiConfig.OPENSUBTITLES_BASE`. */
@Serializable
data class OpenSubtitlesSearchDto(val data: List<OpenSubtitlesItemDto> = emptyList())

@Serializable
data class OpenSubtitlesItemDto(
    val attributes: OpenSubtitlesAttributesDto = OpenSubtitlesAttributesDto(),
)

@Serializable
data class OpenSubtitlesAttributesDto(
    val language: String? = null,
    /**
     * The release the upload was synced to, e.g.
     * `"John.Wick.Chapter.3.Parabellum.2019.1080p.BluRay.x264-SPARKS"` — this
     * is the whole reason this API is queried directly instead of relying on
     * the Stremio addon layer alone: the addon protocol has nowhere to carry
     * this, and without it there is nothing for `SubtitleMatcher` to compare
     * against the playing release.
     */
    val release: String? = null,
    val files: List<OpenSubtitlesFileDto> = emptyList(),
    /** Used as a tiebreaker in `SubtitleMatcher` when the token match is a wash. */
    @SerialName("download_count") val downloadCount: Int = 0,
)

@Serializable
data class OpenSubtitlesFileDto(
    @SerialName("file_id") val fileId: Long,
    @SerialName("file_name") val fileName: String? = null,
)

/**
 * A search result names a file; only `/download` mints an actual URL for it,
 * and doing so spends against the account's daily quota. Requested with
 * exactly this shape — see [com.mdblisthub.tv.core.network.OpenSubtitlesApi.download].
 */
@Serializable
data class OpenSubtitlesDownloadRequestDto(@SerialName("file_id") val fileId: Long)

@Serializable
data class OpenSubtitlesDownloadDto(val link: String? = null)
