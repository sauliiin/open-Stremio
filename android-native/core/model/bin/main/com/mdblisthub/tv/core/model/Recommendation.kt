package com.mdblisthub.tv.core.model

/**
 * A "porque você assistiu {seedTitle}" row — TMDB's recommendations for one
 * recently watched title, with anything already in the watch history
 * filtered out.
 */
data class RecommendationRow(
    val seedTitle: String,
    val items: List<MediaItem>,
)
