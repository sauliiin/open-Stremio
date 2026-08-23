package com.mdblisthub.tv.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mdblisthub.tv.core.model.CastMember
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.RatingBadge
import com.mdblisthub.tv.core.model.Review
import com.mdblisthub.tv.core.model.SeasonSummary

/**
 * The metadata cache is split in two on purpose.
 *
 * `media` is the card: what a list sync already hands over, cheap to write for
 * a thousand titles at once. `media_detail` is the screen: cast, ratings,
 * artwork, seasons — expensive, fetched from three APIs, and only worth having
 * for titles someone actually looks at.
 *
 * Keeping them apart means a nightly list refresh can rewrite every card
 * without touching the detail rows it took a worker minutes to build, and
 * staleness can be tracked at two different rates.
 */
@Entity(
    tableName = "media",
    primaryKeys = ["tmdbId", "type"],
    indices = [
        // Stremio catalogues identify a title only by IMDb id, so resolving
        // one to a TMDB id is on the path of opening any addon-sourced card.
        // Without this the lookup scans a table that holds every title in
        // every list the account has.
        Index(value = ["imdbId"]),
        // What the hydration worker orders by. Same story: a `LEFT JOIN` plus
        // a sort over the whole table, every four hours.
        Index(value = ["fetchedAt"]),
    ],
)
data class MediaEntity(
    val tmdbId: Int,
    val type: String,
    val imdbId: String?,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val landscapeUrl: String?,
    val backdropUrl: String?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    /** 0–100, best available, for the corner badge. */
    val score: Int?,
    val fetchedAt: Long,
)

@Entity(
    tableName = "media_detail",
    primaryKeys = ["tmdbId", "type"],
)
data class MediaDetailEntity(
    val tmdbId: Int,
    val type: String,
    val imdbId: String?,
    val title: String,
    val originalTitle: String?,
    val tagline: String?,
    val overview: String?,
    val posterUrl: String?,
    val landscapeUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val year: Int?,
    val releaseDate: String?,
    val runtimeMinutes: Int?,
    val seasonCount: Int?,
    val episodeCount: Int?,
    val status: String?,
    val certification: String?,
    val trailerKey: String?,
    val budget: Long?,
    val revenue: Long?,
    val genres: List<String>,
    val directors: List<String>,
    val writers: List<String>,
    val studios: List<String>,
    val castMembers: List<CastMember>,
    val ratings: List<RatingBadge>,
    val seasons: List<SeasonSummary>,
    val recommendations: List<MediaItem>,
    val reviews: List<Review>,
    /**
     * False when a supplementary source (mdblist, OMDb) threw while this row
     * was built, so the row is missing things it should have had rather than
     * things that do not exist. Without this the two look identical, and a
     * single rate-limited call hides a title's ratings and reviews for the
     * whole `DETAIL_MS` week.
     */
    val metadataComplete: Boolean = true,
    val fetchedAt: Long,
)

/** A list of the signed-in account. `position` is the row order on the home. */
@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val originalName: String,
    val slug: String,
    val description: String,
    val mediaType: String?,
    val itemCount: Int,
    val dynamic: Boolean,
    val private: Boolean,
    val hidden: Boolean,
    val updatedAt: String?,
    val position: Int,
    /** Last successful `/lists/{id}/items` refresh, independent from the list index. */
    @ColumnInfo(defaultValue = "0") val itemsFetchedAt: Long = 0,
    /** Last successful `/lists/user` index refresh. */
    val fetchedAt: Long,
)

/**
 * Which titles are in which list, and in what order. Ordering lives here
 * rather than on `media`, because the same title sits at a different rank in
 * every list that holds it.
 */
@Entity(
    tableName = "list_items",
    primaryKeys = ["listId", "tmdbId", "type"],
    indices = [Index(value = ["listId", "position"])],
)
data class ListItemEntity(
    val listId: Long,
    val tmdbId: Int,
    val type: String,
    val position: Int,
)

@Entity(
    tableName = "episodes",
    primaryKeys = ["showTmdbId", "seasonNumber", "episodeNumber"],
)
data class EpisodeEntity(
    val showTmdbId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val id: Int,
    val name: String,
    val overview: String?,
    val stillUrl: String?,
    val airDate: String?,
    val runtimeMinutes: Int?,
    val voteAverage: Double?,
    val fetchedAt: Long,
)

@Entity(tableName = "addons")
data class AddonEntity(
    @PrimaryKey val base: String,
    /**
     * The manifest exactly as the addon served it.
     *
     * Kept verbatim rather than rebuilt from the columns below, because this
     * row round-trips through the cross-device sync: the web app stores whole
     * manifests, and re-serialising a parsed one would quietly drop whatever
     * this app does not model — catalogue definitions, per-resource type
     * filters, extras. Syncing must not degrade the other client's data.
     */
    val manifestJson: String,
    val addonId: String,
    val name: String,
    val description: String?,
    val logoUrl: String?,
    val version: String?,
    val types: List<String>,
    val resources: List<String>,
    val idPrefixes: List<String>,
    val configurable: Boolean,
    val addedAt: Long,
)

/** A part-watched title, mirrored from the library provider so the row paints offline. */
@Entity(tableName = "resume_points")
data class ResumeEntity(
    @PrimaryKey val key: String,
    val type: String,
    val tmdbId: Int?,
    val imdbId: String?,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    /** 0–100, IMDb first — same scale and source order as every other card. */
    val score: Int?,
    val season: Int?,
    val episode: Int?,
    val progress: Float,
    val updatedAt: String?,
    val fetchedAt: Long,
    /**
     * The session's own id at the provider, when it has one.
     *
     * Trakt and Simkl need it: dropping a paused session there is
     * `DELETE /sync/playback/{id}` against the *session*, not the title, and
     * that id exists nowhere else in this app. mdblist clears by scrobbling
     * the title itself and leaves this null.
     */
    @ColumnInfo(defaultValue = "NULL") val playbackId: Long? = null,
)

/** Watchlist / watched / collection membership, so the buttons paint instantly. */
@Entity(
    tableName = "library",
    primaryKeys = ["bucket", "tmdbId"],
)
data class LibraryEntity(
    val bucket: String,
    val tmdbId: Int,
    @ColumnInfo(defaultValue = "0") val fetchedAt: Long,
)

/** Episodes that have been watched, specifically from Trakt. */
@Entity(
    tableName = "watched_episodes",
    primaryKeys = ["showTmdbId", "seasonNumber", "episodeNumber"]
)
data class WatchedEpisodeEntity(
    val showTmdbId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
)
