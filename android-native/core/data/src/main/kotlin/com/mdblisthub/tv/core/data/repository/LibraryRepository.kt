package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.CachePolicy
import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.database.entity.LibraryEntity
import com.mdblisthub.tv.core.model.LibraryBucket
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.dto.LibraryKeyDto
import com.mdblisthub.tv.core.network.dto.LibraryWriteDto
import kotlinx.coroutines.flow.Flow

/**
 * Watchlist, watched and collection.
 *
 * The writes are plain JSON POSTs. That is worth stating, because in the
 * browser they are not: a cross-origin JSON POST needs a CORS preflight and
 * mdblist answers OPTIONS with 405, which is why the web build routes them
 * through a dev proxy. A native client has no such rule.
 */
class LibraryRepository(
    private val api: MdblistApi,
    private val session: SessionStore,
    private val database: HubDatabase,
) {
    private val dao = database.playbackDao()

    fun observeMembership(bucket: LibraryBucket, tmdbId: Int): Flow<Boolean> =
        dao.observeMembership(bucket.name, tmdbId)

    suspend fun refresh(bucket: LibraryBucket, force: Boolean = false): Result<Unit> = runCatching {
        val key = session.currentKey()
        if (key.isBlank()) return@runCatching

        if (!force && !CachePolicy.isStale(lastFetch[bucket], CachePolicy.LIBRARY_MS)) {
            return@runCatching
        }

        val now = System.currentTimeMillis()
        val response = api.bucket("${MDBLIST_ROOT}${bucket.readPath}", key)
        dao.replaceBucket(bucket.name, response.tmdbIds().map { LibraryEntity(bucket.name, it, now) })
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
        val key = session.currentKey()
        require(key.isNotBlank()) { "Sessão expirada." }

        val entry = if (imdbId != null) LibraryKeyDto(imdb = imdbId) else LibraryKeyDto(tmdb = tmdbId)
        val body = if (type == MediaType.SHOW) {
            LibraryWriteDto(shows = listOf(entry))
        } else {
            LibraryWriteDto(movies = listOf(entry))
        }

        val path = if (add) bucket.addPath else bucket.removePath
        val response = api.bucketWrite("${MDBLIST_ROOT}$path", key, body)
        check(response.isSuccessful) { "mdblist respondeu ${response.code()}" }

        if (add) {
            dao.upsertLibrary(listOf(LibraryEntity(bucket.name, tmdbId, System.currentTimeMillis())))
        } else {
            dao.removeFromBucket(bucket.name, tmdbId)
        }
        add
    }

    /** In-memory, because a bucket's freshness is per-session, not per-row. */
    private val lastFetch = mutableMapOf<LibraryBucket, Long>()

    private companion object {
        const val MDBLIST_ROOT = "https://api.mdblist.com/"
    }
}
