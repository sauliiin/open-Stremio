package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.UiPreferencesStore
import com.mdblisthub.tv.core.data.mapper.toDomain
import com.mdblisthub.tv.core.data.repository.source.PlaybackSource
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.database.entity.ResumeEntity
import com.mdblisthub.tv.core.model.LibraryProvider
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.ResumePoint
import com.mdblisthub.tv.core.model.ScrobbleTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Playback position, kept by the library provider rather than by this app.
 *
 * `pause` and `stop` store the point, `start` replaces it, and the paused ones
 * come back as the "continue watching" row — which is what makes a film
 * resumable on the phone after being left half-watched on the television. Past
 * 80% both providers mark the title watched on their own.
 *
 * The Room mirror, the artwork pass and the "nearly finished is not resumable"
 * rule live here; the endpoints live in the selected [PlaybackSource].
 */
class PlaybackRepository(
    private val mdblist: PlaybackSource,
    private val trakt: PlaybackSource,
    private val simkl: PlaybackSource,
    private val preferences: UiPreferencesStore,
    private val database: HubDatabase,
    private val media: MediaRepository,
) {
    private val dao = database.playbackDao()

    /**
     * The "continue watching" row, mirrored into Room so it paints on a cold
     * start before the network answers. A title all but finished is not
     * something to offer resuming.
     */
    val resumePoints: Flow<List<ResumePoint>> =
        dao.observeResumePoints().map { rows ->
            rows.map { it.toDomain() }.filter { it.progress > 1f && it.progress < 95f }
        }

    suspend fun refreshResumePoints(): Result<Unit> = runCatching {
        val now = System.currentTimeMillis()
        val sessions = source().sessions(now) ?: return@runCatching
        dao.replaceResumePoints(withArtwork(sessions))
    }

    /**
     * Neither provider's playback sync hands back artwork or a rating, so each
     * row borrows [MediaRepository]'s detail cache for its poster and score.
     * `ensureDetail` is a no-op once a title has been opened anywhere in the
     * app, and cheap to run for the handful of rows "Continuar assistindo"
     * ever holds.
     */
    private suspend fun withArtwork(entities: List<ResumeEntity>): List<ResumeEntity> = coroutineScope {
        entities.map { entity ->
            async {
                val tmdbId = entity.tmdbId ?: return@async entity
                val type = MediaType.parse(entity.type)
                media.ensureDetail(type, tmdbId)
                val detail = media.observeDetail(type, tmdbId).first()
                if (detail == null) {
                    entity
                } else {
                    entity.copy(
                        posterUrl = detail.posterUrl,
                        backdropUrl = detail.backdropUrl,
                        // First in the list is IMDb when the provider reported
                        // one — same order every other card's badge reads from.
                        score = detail.ratings.firstOrNull()?.score,
                    )
                }
            }
        }.awaitAll()
    }

    suspend fun resumeFor(target: ScrobbleTarget): Float? =
        resumePoints.first().firstOrNull { it.matches(target) }?.progress

    suspend fun start(target: ScrobbleTarget, progress: Float) =
        runCatching { source().start(target, progress) }

    suspend fun pause(target: ScrobbleTarget, progress: Float) =
        runCatching { source().pause(target, progress) }

    suspend fun stop(target: ScrobbleTarget, progress: Float) =
        runCatching { source().stop(target, progress) }

    /**
     * Drops a stored session — "remover de continuar assistindo".
     *
     * The row is looked up first because Trakt deletes a session by its own
     * id, which is carried in [ResumeEntity.playbackId] and exists nowhere
     * else. The local delete happens either way: the user asked for the row to
     * go, and a provider that refuses is reconciled by the next refresh rather
     * than by leaving the row on screen.
     */
    suspend fun clear(target: ScrobbleTarget): Result<Unit> {
        val key = keyFor(target)
        val stored = dao.resumePoint(key)
        // Make the explicit user action visible immediately. A failed remote
        // delete is reconciled by the next refresh, as documented above, but
        // must not keep a card on Home while the network request is in flight.
        dao.deleteResumePoint(key)
        return runCatching { source().clear(target, stored?.playbackId) }
    }

    /**
     * Forgets sessions read from the previous provider. Called when the
     * library setting changes, for the same reason `LibraryRepository` clears
     * its buckets: a row from one account has no meaning under another.
     */
    suspend fun onProviderChanged() {
        dao.clearResumePoints()
    }

    private suspend fun source(): PlaybackSource = when (preferences.currentLibraryProvider()) {
            LibraryProvider.TRAKT -> trakt
            LibraryProvider.SIMKL -> simkl
            LibraryProvider.MDBLIST -> mdblist
    }

    private fun keyFor(target: ScrobbleTarget): String =
        "${target.type.mdblist}:${target.tmdbId ?: target.imdbId}:" +
            "${target.season ?: 0}:${target.episode ?: 0}"
}

private fun ResumePoint.matches(target: ScrobbleTarget): Boolean {
    val sameTitle = if (target.imdbId != null) imdbId == target.imdbId else tmdbId == target.tmdbId
    if (!sameTitle) return false
    if (target.type != MediaType.SHOW) return true
    return season == target.season && episode == target.episode
}
