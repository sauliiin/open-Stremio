package com.mdblisthub.tv.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RatingTone { IMDB, RT_FRESH, RT_ROTTEN, METACRITIC, TRAKT, TMDB, LETTERBOXD, NEUTRAL }

/** A score normalised for display, whichever aggregator it came from. */
@Serializable
data class RatingBadge(
    val key: String,
    val label: String,
    /** Already formatted for the screen: `8,4`, `92%`, `74`. */
    val display: String,
    /** 0–100, drives the ring. Null when the source gave no comparable value. */
    val score: Int?,
    val votes: Long? = null,
    val tone: RatingTone = RatingTone.NEUTRAL,
)
