package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.CachePolicy
import com.mdblisthub.tv.core.data.UiPreferencesStore
import com.mdblisthub.tv.core.data.repository.source.LibrarySource
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.database.entity.LibraryEntity
import com.mdblisthub.tv.core.database.entity.WatchedEpisodeEntity
import com.mdblisthub.tv.core.model.LibraryBucket
import com.mdblisthub.tv.core.model.LibraryProvider
import com.mdblisthub.tv.core.model.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Watchlist, watched and collection.
 *
 * Owns the Room mirror and the staleness window; the network half belongs to
 * whichever [LibrarySource] the library setting selects. Splitting it that way
 * is what lets the mdblist and Trakt paths differ entirely in their endpoints
 * — Trakt's "watched" is the play history, not a bucket — while the membership
 * cache, the optimistic write-through and the freshness rule stay written once.
 */
class LibraryRepository(
    private val mdblist: LibrarySource,
    private val trakt: LibrarySource,
    private val preferences: UiPreferencesStore,
    private val database: HubDatabase,
) {
    private val dao = database.playbackDao()

    fun observeMembership(bucket: LibraryBucket, tmdbId: Int): Flow<Boolean> =
        dao.observeMembership(bucket.name, tmdbId)

    fun observeBucket(bucket: LibraryBucket): Flow<Set<Int>> =
        dao.observeBucket(bucket.name).map { it.toSet() }

    fun observeWatchedEpisodes(): Flow<Set<String>> =
        dao.observeWatchedEpisodes().map { list ->
            list.map { "${it.showTmdbId}:${it.seasonNumber}:${it.episodeNumber}" }.toSet()
        }

    suspend fun refresh(bucket: LibraryBucket, force: Boolean = false): Result<Unit> = runCatching {
        if (!force && !CachePolicy.isStale(lastFetch[bucket], CachePolicy.LIBRARY_MS)) {
            return@runCatching
        }

        val now = System.currentTimeMillis()
        // Null means "no account to ask" — a session without an mdblist key, or
        // an unlinked Trakt. Leaving the cache untouched there is the point:
        // clearing it would blank every button the moment a token expires.
        val syncResult = source().membership(bucket) ?: return@runCatching
        dao.replaceBucket(bucket.name, syncResult.titleIds.map { LibraryEntity(bucket.name, it, now) })
        if (bucket == LibraryBucket.WATCHED) {
            val epEntities = syncResult.episodeIds.map { 
                com.mdblisthub.tv.core.database.entity.WatchedEpisodeEntity(it.showTmdbId, it.seasonNumber, it.episodeNumber) 
            }
            dao.replaceWatchedEpisodes(epEntities)
        }
        lastFetch[bucket] = now
    }

    /**
     * Adds or removes, then writes the membership locally so the button
     * settles on the new state immediately instead of after a re-read.
     */
    suspend fun toggle(
        bucket: LibraryBucket,
        type: MediaType,
        tmdbId: Int,
        imdbId: String?,
        add: Boolean,
    ): Result<Boolean> = runCatching {
        source().write(bucket, type, tmdbId, imdbId, add)

        if (add) {
            dao.upsertLibrary(listOf(LibraryEntity(bucket.name, tmdbId, System.currentTimeMillis())))
        } else {
            dao.removeFromBucket(bucket.name, tmdbId)
        }
        add
    }

    /** Writes and mirrors one episode without changing whole-series membership. */
    suspend fun setEpisodeWatched(
        showTmdbId: Int,
        showImdbId: String?,
        season: Int,
        episode: Int,
        watched: Boolean,
    ): Result<Boolean> = runCatching {
        source().writeEpisodeWatched(showTmdbId, showImdbId, season, episode, watched)

        if (watched) {
            dao.upsertWatchedEpisodes(listOf(WatchedEpisodeEntity(showTmdbId, season, episode)))
        } else {
            val remaining = dao.observeWatchedEpisodes().first().filterNot {
                it.showTmdbId == showTmdbId &&
                    it.seasonNumber == season &&
                    it.episodeNumber == episode
            }
            dao.replaceWatchedEpisodes(remaining)
        }
        watched
    }

    /**
     * Forgets what was read from the previous provider.
     *
     * Called when the setting changes. Without it the cached membership would
     * keep painting the detail screen's three buttons from an account that is
     * no longer answering for them — a title on the mdblist watchlist showing
     * as watchlisted under a Trakt account that never heard of it.
     */
    suspend fun onProviderChanged() {
        lastFetch.clear()
        dao.clearLibrary()
    }

    private suspend fun source(): LibrarySource = when (preferences.currentLibraryProvider()) {
        LibraryProvider.TRAKT -> trakt
        LibraryProvider.MDBLIST -> mdblist
    }

    /** In-memory, because a bucket's freshness is per-session, not per-row. */
    private val lastFetch = mutableMapOf<LibraryBucket, Long>()
}
