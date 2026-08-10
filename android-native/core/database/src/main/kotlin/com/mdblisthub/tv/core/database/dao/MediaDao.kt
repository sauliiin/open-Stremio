package com.mdblisthub.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.mdblisthub.tv.core.database.entity.EpisodeEntity
import com.mdblisthub.tv.core.database.entity.MediaDetailEntity
import com.mdblisthub.tv.core.database.entity.MediaEntity
import kotlinx.coroutines.flow.Flow

/** A title the hydration worker still owes a detail row. */
data class MediaKey(val tmdbId: Int, val type: String)

@Dao
interface MediaDao {

    @Upsert
    suspend fun upsert(items: List<MediaEntity>)

    @Upsert
    suspend fun upsertDetail(detail: MediaDetailEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(episodes: List<EpisodeEntity>)

    /**
     * The cheap half of the cache — the card, which a list sync already wrote.
     *
     * This is what lets the player ask the addons for streams without first
     * waiting on the expensive three-API detail hydration: the IMDb id the
     * addons are indexed by, and a runtime good enough to recognise a decoy,
     * are both already here.
     */
    @Query("SELECT * FROM media WHERE tmdbId = :tmdbId AND type = :type")
    suspend fun card(tmdbId: Int, type: String): MediaEntity?

    @Query("SELECT * FROM media_detail WHERE tmdbId = :tmdbId AND type = :type")
    fun observeDetail(tmdbId: Int, type: String): Flow<MediaDetailEntity?>

    @Query("SELECT * FROM media_detail WHERE tmdbId = :tmdbId AND type = :type")
    suspend fun detail(tmdbId: Int, type: String): MediaDetailEntity?

    @Query("SELECT tmdbId FROM media WHERE imdbId = :imdbId AND type = :type LIMIT 1")
    suspend fun tmdbIdForImdb(imdbId: String, type: String): Int?

    @Query(
        """
        SELECT m.* FROM media m
        INNER JOIN list_items li ON li.tmdbId = m.tmdbId AND li.type = m.type
        WHERE li.listId = :listId
        ORDER BY li.position ASC
        """
    )
    fun observeListItems(listId: Long): Flow<List<MediaEntity>>

    @Query(
        """
        SELECT * FROM episodes
        WHERE showTmdbId = :showTmdbId AND seasonNumber = :seasonNumber
        ORDER BY episodeNumber ASC
        """
    )
    fun observeEpisodes(showTmdbId: Int, seasonNumber: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT fetchedAt FROM episodes WHERE showTmdbId = :showTmdbId AND seasonNumber = :seasonNumber LIMIT 1")
    suspend fun episodesFetchedAt(showTmdbId: Int, seasonNumber: Int): Long?

    /**
     * What the hydration worker chews through: titles whose card is cached but
     * whose detail is missing or older than `cutoff`.
     *
     * Ordered by how recently the card was seen, so a list the user just
     * scrolled past is hydrated before one they have not opened in a month.
     */
    @Query(
        """
        SELECT m.tmdbId AS tmdbId, m.type AS type FROM media m
        LEFT JOIN media_detail d ON d.tmdbId = m.tmdbId AND d.type = m.type
        WHERE d.tmdbId IS NULL OR d.fetchedAt < :cutoff
        ORDER BY m.fetchedAt DESC
        LIMIT :limit
        """
    )
    suspend fun staleDetails(cutoff: Long, limit: Int): List<MediaKey>

    /** Poster and fanart URLs the artwork worker pre-warms into the image cache. */
    @Query(
        """
        SELECT m.posterUrl FROM media m
        INNER JOIN list_items li ON li.tmdbId = m.tmdbId AND li.type = m.type
        WHERE li.listId = :listId AND m.posterUrl IS NOT NULL
        ORDER BY li.position ASC
        LIMIT :limit
        """
    )
    suspend fun posterUrls(listId: Long, limit: Int): List<String>

    /** Drops detail rows nobody has opened, when the cache outgrows its budget. */
    @Query(
        """
        DELETE FROM media_detail WHERE tmdbId || ':' || type IN (
            SELECT d.tmdbId || ':' || d.type FROM media_detail d
            LEFT JOIN list_items li ON li.tmdbId = d.tmdbId AND li.type = d.type
            WHERE li.listId IS NULL AND d.fetchedAt < :cutoff
        )
        """
    )
    suspend fun pruneDetails(cutoff: Long): Int

    @Query("DELETE FROM episodes WHERE fetchedAt < :cutoff")
    suspend fun pruneEpisodes(cutoff: Long): Int

    @Query("DELETE FROM episodes")
    suspend fun clearEpisodes()

    @Query("DELETE FROM media_detail")
    suspend fun clearDetails()

    @Query("DELETE FROM media")
    suspend fun clearMedia()
}
