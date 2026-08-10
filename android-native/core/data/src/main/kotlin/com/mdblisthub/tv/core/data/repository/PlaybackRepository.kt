package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.mapper.toDomain
import com.mdblisthub.tv.core.data.mapper.toResumeEntity
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.database.entity.ResumeEntity
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.ResumePoint
import com.mdblisthub.tv.core.model.ScrobbleTarget
import com.mdblisthub.tv.core.network.MdblistApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/**
 * Playback position, kept by mdblist rather than by this app.
 *
 * `pause` and `stop` store the point, `start` replaces it, and
 * `/sync/playback` hands the paused ones back — which is what makes a film
 * resumable on the phone after being left half-watched on the television.
 * Past 80% mdblist marks the title watched on its own.
 */
class PlaybackRepository(
    private val api: MdblistApi,
    private val session: SessionStore,
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
        val key = session.currentKey()
        if (key.isBlank()) return@runCatching

        val now = System.currentTimeMillis()
        val sessions = api.playback(key).mapNotNull { it.toResumeEntity(now) }
        dao.replaceResumePoints(withArtwork(sessions))
    }

    /**
     * mdblist's playback sync hands back ids and progress only, no artwork or
     * rating — see [toResumeEntity] — so each row borrows [MediaRepository]'s
     * detail cache for its poster and score. `ensureDetail` is a no-op once a
     * title has been opened anywhere in the app, and cheap to run for the
     * handful of rows "Continuar assistindo" ever holds.
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
                        // First in the list is IMDb when mdblist reported one
                        // — same order every other card's badge reads from.
                        score = detail.ratings.firstOrNull()?.score,
                    )
                }
            }
        }.awaitAll()
    }

    suspend fun resumeFor(target: ScrobbleTarget): Float? =
        resumePoints.first().firstOrNull { it.matches(target) }?.progress

    suspend fun start(target: ScrobbleTarget, progress: Float) = send("start", target, progress)
    suspend fun pause(target: ScrobbleTarget, progress: Float) = send("pause", target, progress)
    suspend fun stop(target: ScrobbleTarget, progress: Float) = send("stop", target, progress)

    /** Drops a stored session — "remover de continuar assistindo". */
    suspend fun clear(target: ScrobbleTarget): Result<Unit> {
        val result = send("clear", target, 0f)
        if (result.isSuccess) {
            val key = "${target.type.mdblist}:${target.tmdbId ?: target.imdbId}:" +
                "${target.season ?: 0}:${target.episode ?: 0}"
            dao.deleteResumePoint(key)
        }
        return result
    }

    /**
     * The body goes out as nested JSON, which is what mdblist actually reads.
     *
     * A show additionally has to carry `season` — the API rejects a show
     * target without one outright — and both kinds need at least one id
     * inside `ids`, which the guard above already assures.
     */
    private suspend fun send(
        action: String,
        target: ScrobbleTarget,
        progress: Float,
    ): Result<Unit> = runCatching {
        val key = session.currentKey()
        if (key.isBlank() || (target.imdbId == null && target.tmdbId == null)) return@runCatching

        val ids = buildJsonObject {
            target.imdbId?.let { put("imdb", it) }
            target.tmdbId?.let { put("tmdb", it) }
        }

        val body = buildJsonObject {
            put("progress", String.format(Locale.US, "%.2f", progress).toDouble())
            if (target.type == MediaType.SHOW) {
                putJsonObject("show") {
                    put("ids", ids)
                    target.season?.let { put("season", it) }
                    target.episode?.let { put("episode", it) }
                }
            } else {
                putJsonObject("movie") { put("ids", ids) }
            }
        }

        val response = api.scrobble(action, key, body)
        check(response.isSuccessful) { "scrobble/$action respondeu ${response.code()}" }
    }
}

private fun ResumePoint.matches(target: ScrobbleTarget): Boolean {
    val sameTitle = if (target.imdbId != null) imdbId == target.imdbId else tmdbId == target.tmdbId
    if (!sameTitle) return false
    if (target.type != MediaType.SHOW) return true
    return season == target.season && episode == target.episode
}
