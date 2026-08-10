package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.Serializable

/**
 * Wyzie's own subtitle search — see `ApiConfig.WYZIE_BASE`. The endpoint
 * answers with a bare array, not an object wrapping one.
 */
@Serializable
data class WyzieItemDto(
    val id: String? = null,
    val url: String? = null,
    val encoding: String? = null,
    val language: String? = null,
    /** The release the file was synced to — what `SubtitleMatcher` needs. */
    val release: String? = null,
    val fileName: String? = null,
    /** Used as a tiebreaker in `SubtitleMatcher` when the token match is a wash. */
    val downloadCount: Int = 0,
)
