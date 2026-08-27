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
    /**
     * The key this app files its own playback notes under — see
     * [PlaybackHint].
     *
     * Deliberately not the provider's key: these rows are local, and the
     * provider's are replaced wholesale on every sync.
     */
    fun localKey(): String =
        listOfNotNull(type.name, tmdbId?.toString() ?: imdbId, season?.toString(), episode?.toString())
            .joinToString(":")

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
 * What this app remembers about a title it played, beyond what the provider
 * keeps.
 *
 * Two things, both about *speed* rather than about correctness — losing them
 * costs one slower resume and nothing else, which is what keeps them safe to
 * store in a cache that is thrown away on a schema change:
 *
 * - **[positionMs]**, because the provider only syncs a percentage. Turning
 *   that back into a position needs a runtime, and the only one available
 *   before the file is open is the metadata's, which routinely disagrees with
 *   the file by minutes. An exact position lets the very first range request
 *   land where the viewer left off, instead of landing near it and paying for
 *   a correction seek.
 * - **[sourceFilename]**, because resuming the release that already worked
 *   skips the cascade that found it, and hits a disk cache that is keyed by
 *   exactly this name. It also keeps the audio and subtitle tracks the viewer
 *   picked from changing under them between sessions.
 *
 * [durationMs] exists to check the other two are still current: a percentage
 * from the provider that disagrees with `positionMs / durationMs` means the
 * title was watched somewhere else since, and the provider wins.
 */
data class PlaybackHint(
    val positionMs: Long,
    val durationMs: Long,
    val sourceFilename: String?,
) {
    /** Where this hint sits in the title, on the provider's own scale. */
    val progressPercent: Float?
        get() = if (durationMs > 0) positionMs.toFloat() / durationMs * 100f else null
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

/**
 * The three buckets a title can sit in.
 *
 * Carries no endpoints any more: mdblist and Trakt spell all three
 * differently — Trakt's "watched" is not even a bucket but the play history —
 * so each provider's `LibrarySource` owns its own paths and this stays the
 * name the rest of the app reasons in.
 */
@Serializable
enum class LibraryBucket { WATCHLIST, WATCHED, COLLECTION }
