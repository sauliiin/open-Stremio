package com.mdblisthub.tv.core.data.repository.source

import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.TraktTokenStore
import com.mdblisthub.tv.core.data.mapper.toResumeEntity
import com.mdblisthub.tv.core.database.entity.ResumeEntity
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.ScrobbleTarget
import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.TraktApi
import com.mdblisthub.tv.core.network.dto.TraktIdsDto
import com.mdblisthub.tv.core.network.dto.TraktScrobbleDto
import com.mdblisthub.tv.core.network.dto.TraktScrobbleEpisodeDto
import com.mdblisthub.tv.core.network.dto.TraktWriteItemDto
import kotlin.math.roundToInt
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/**
 * Where playback position lives — which is not in this app.
 *
 * `pause` and `stop` store the point, `start` replaces it, and the paused ones
 * come back as rows. That is what makes a film resumable on the phone after
 * being left half-watched on the television, and it works the same way at both
 * providers.
 */
interface PlaybackSource {

    /**
     * Paused sessions as Room rows, or null when there is no account to ask.
     * Null leaves the cached rows alone; an empty list clears them, because
     * that answer means every session really was finished elsewhere.
     */
    suspend fun sessions(now: Long): List<ResumeEntity>?

    suspend fun start(target: ScrobbleTarget, progress: Float)
    suspend fun pause(target: ScrobbleTarget, progress: Float)
    suspend fun stop(target: ScrobbleTarget, progress: Float)

    /**
     * Drops a stored session — "remove from continue watching".
     *
     * [playbackId] is the provider's own id for the session, carried in
     * `ResumeEntity.playbackId`. Only Trakt needs it; mdblist addresses the
     * title instead.
     */
    suspend fun clear(target: ScrobbleTarget, playbackId: Long?)
}

class MdblistPlaybackSource(
    private val api: MdblistApi,
    private val session: SessionStore,
) : PlaybackSource {

    override suspend fun sessions(now: Long): List<ResumeEntity>? {
        val key = session.currentKey()
        if (key.isBlank()) return null
        return api.playback(key).mapNotNull { it.toResumeEntity(now) }
    }

    override suspend fun start(target: ScrobbleTarget, progress: Float) = send("start", target, progress)
    override suspend fun pause(target: ScrobbleTarget, progress: Float) = send("pause", target, progress)
    override suspend fun stop(target: ScrobbleTarget, progress: Float) = send("stop", target, progress)

    override suspend fun clear(target: ScrobbleTarget, playbackId: Long?) = send("clear", target, 0f)

    /**
     * The body goes out as nested JSON, which is what mdblist actually reads.
     *
     * It used to be form-encoded with the target in bracket notation, on the
     * reading that mdblist's schema documented that shape. It does not accept
     * it: every such call answered `400 {"non_field_errors":["Either 'movie'
     * or 'show' must be provided"]}`, because the bracket keys are never
     * decoded back into a nested object.
     *
     * A show additionally has to carry `season` — the API rejects a show
     * target without one outright — and both kinds need at least one id
     * inside `ids`, which the guard below already assures.
     */
    private suspend fun send(action: String, target: ScrobbleTarget, progress: Float) {
        val key = session.currentKey()
        if (key.isBlank() || (target.imdbId == null && target.tmdbId == null)) return

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

/**
 * Trakt.
 *
 * Two shape differences from mdblist. An episode scrobble names the *show* and
 * gives the episode as season plus number, rather than putting both under one
 * `show` object — this app never learns an episode's own Trakt id, and that
 * form is the one Trakt documents for exactly this case. And there is no
 * "clear" scrobble action: a paused session is deleted by its own id, which is
 * why [ResumeEntity.playbackId] exists at all.
 */
class TraktPlaybackSource(
    private val api: TraktApi,
    private val tokens: TraktTokenStore,
) : PlaybackSource {

    override suspend fun sessions(now: Long): List<ResumeEntity>? {
        if (!tokens.isLinked()) return null
        val movies = api.playback("movies", limit = LIMIT)
        val episodes = api.playback("episodes", limit = LIMIT)
        return (movies + episodes).mapNotNull { dto ->
            val isEpisode = dto.episode != null || dto.type == "episode"
            val parent = if (isEpisode) dto.show else dto.movie
            val ids = parent?.ids
            val tmdbId = ids?.tmdb
            val imdbId = ids?.imdb
            if (tmdbId == null && imdbId == null) return@mapNotNull null

            val mediaType = if (isEpisode) MediaType.SHOW else MediaType.MOVIE
            val season = dto.episode?.season
            val episode = dto.episode?.number

            ResumeEntity(
                key = "${mediaType.mdblist}:${tmdbId ?: imdbId}:${season ?: 0}:${episode ?: 0}",
                type = mediaType.mdblist,
                tmdbId = tmdbId,
                imdbId = imdbId,
                title = parent.title ?: "Sem título",
                // Filled by the artwork pass in PlaybackRepository, from the
                // TMDB detail this row already carries the id for.
                posterUrl = null,
                backdropUrl = null,
                score = null,
                season = season,
                episode = episode,
                // No extrapolation, unlike the mdblist path: Trakt reports a
                // *paused* position, and a paused film does not keep advancing
                // while the app is closed.
                progress = ((dto.progress * 10).roundToInt() / 10.0).coerceIn(0.0, 100.0).toFloat(),
                updatedAt = dto.pausedAt,
                fetchedAt = now,
                playbackId = dto.id.takeIf { it > 0 },
            )
        }
    }

    override suspend fun start(target: ScrobbleTarget, progress: Float) = scrobble("start", target, progress)
    override suspend fun pause(target: ScrobbleTarget, progress: Float) = scrobble("pause", target, progress)
    override suspend fun stop(target: ScrobbleTarget, progress: Float) = scrobble("stop", target, progress)

    /**
     * Nothing to call without an id: the session is addressed by its own, and
     * a row that never came from a Trakt refresh has none. The repository
     * still drops it locally, which is what the user asked for — the next
     * refresh reconciles whatever Trakt still thinks.
     */
    override suspend fun clear(target: ScrobbleTarget, playbackId: Long?) {
        if (!tokens.isLinked() || playbackId == null) return
        val response = api.deletePlayback(playbackId)
        check(response.isSuccessful) { "Trakt respondeu ${response.code()}" }
    }

    private suspend fun scrobble(action: String, target: ScrobbleTarget, progress: Float) {
        if (!tokens.isLinked()) return
        val ids = when {
            target.imdbId != null -> TraktIdsDto(imdb = target.imdbId)
            target.tmdbId != null -> TraktIdsDto(tmdb = target.tmdbId)
            else -> return
        }

        val season = target.season
        val episode = target.episode
        val body = if (target.type == MediaType.SHOW) {
            // A series with no episode picked out is not something Trakt can
            // record a position against, and sending the show alone would
            // scrobble the wrong thing rather than nothing.
            if (season == null || episode == null) return
            TraktScrobbleDto(
                progress = progress.toDouble(),
                show = TraktWriteItemDto(ids),
                episode = TraktScrobbleEpisodeDto(season, episode),
            )
        } else {
            TraktScrobbleDto(progress = progress.toDouble(), movie = TraktWriteItemDto(ids))
        }

        val response = api.scrobble(action, body)
        check(response.isSuccessful) { "scrobble/$action respondeu ${response.code()}" }
    }

    private companion object {
        const val LIMIT = 100
    }
}
