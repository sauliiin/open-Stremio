package com.mdblisthub.tv.core.model

import kotlinx.serialization.Serializable

/** What a scrobble call is about — one title, or one episode of one. */
@Serializable
data class ScrobbleTarget(
    val type: MediaType,
    val tmdbId: Int?,
    val imdbId: String?,
    val season: Int? = null,
    val episode: Int? = null,
) {
    /** The id a Stremio addon is queried with: IMDb, with episode suffix. */
    fun stremioId(): String? {
        val imdb = imdbId ?: return null
        return if (type == MediaType.SHOW && season != null && episode != null) {
            "$imdb:$season:$episode"
        } else {
            imdb
        }
    }
}

/**
 * A title left part-watched, as mdblist hands it back from `/sync/playback`.
 * Keeping the session server-side is what makes a film resumable on another
 * device without this app owning the state.
 */
@Serializable
data class ResumePoint(
    val type: MediaType,
    val tmdbId: Int?,
    val imdbId: String?,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    /** 0–100, IMDb first — same scale and source order as every other card. */
    val score: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** 0–100. */
    val progress: Float = 0f,
    val updatedAt: String? = null,
) {
    val key: String get() = "${type.mdblist}:${tmdbId ?: imdbId}:${season ?: 0}:${episode ?: 0}"

    fun toTarget(): ScrobbleTarget =
        ScrobbleTarget(type, tmdbId, imdbId, season, episode)
}

/** The three mdblist buckets a title can sit in. */
@Serializable
enum class LibraryBucket(val readPath: String, val addPath: String, val removePath: String) {
    WATCHLIST("watchlist/items", "watchlist/items/add", "watchlist/items/remove"),
    WATCHED("sync/watched", "sync/watched", "sync/watched/remove"),
    COLLECTION("sync/collection", "sync/collection", "sync/collection/remove"),
}
