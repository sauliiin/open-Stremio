package com.mdblisthub.tv.core.model

/**
 * One account-aware MDBList feed rendered alongside lists and addon catalogs.
 *
 * [position] is null until the user manually reorders the home. This lets a
 * new installation put the three feeds immediately after Continue Watching
 * without disturbing an existing custom order; the first move persists one
 * normalized order for every kind of row.
 */
data class MdblistHomeFeed(
    val key: String,
    val name: String,
    val originalName: String,
    val position: Int? = null,
    val hidden: Boolean = false,
    val items: List<MdblistHomeFeedItem> = emptyList(),
)

/** A feed card, optionally pointing directly at a TV episode. */
data class MdblistHomeFeedItem(
    val media: MediaItem,
    val season: Int? = null,
    val episode: Int? = null,
) {
    fun toResumePoint(): ResumePoint = ResumePoint(
        type = media.type,
        tmdbId = media.tmdbId.takeIf { it > 0 },
        imdbId = media.imdbId,
        title = media.title,
        posterUrl = media.posterUrl,
        backdropUrl = media.backdropUrl,
        score = media.score,
        season = season,
        episode = episode,
    )
}

object MdblistHomeFeedKeys {
    const val UP_NEXT = "mdblist-feed:up-next"
    const val RECENTLY_ADDED = "mdblist-feed:recently-added"
    const val WATCHLIST = "mdblist-feed:watchlist"
    const val RECENTLY_WATCHED = "mdblist-feed:recently-watched"
}
