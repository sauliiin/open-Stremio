package com.mdblisthub.tv.core.data.repository.source

import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.TraktTokenStore
import com.mdblisthub.tv.core.data.SimklTokenStore
import com.mdblisthub.tv.core.model.AppError
import com.mdblisthub.tv.core.model.requireOrFail
import com.mdblisthub.tv.core.model.LibraryBucket
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.TraktApi
import com.mdblisthub.tv.core.network.SimklApi
import com.mdblisthub.tv.core.network.dto.LibraryKeyDto
import com.mdblisthub.tv.core.network.dto.LibraryWriteEpisodeDto
import com.mdblisthub.tv.core.network.dto.LibraryWriteDto
import com.mdblisthub.tv.core.network.dto.LibraryWriteSeasonDto
import com.mdblisthub.tv.core.network.dto.TraktIdsDto
import com.mdblisthub.tv.core.network.dto.TraktSyncWriteDto
import com.mdblisthub.tv.core.network.dto.TraktWriteEpisodeDto
import com.mdblisthub.tv.core.network.dto.TraktWriteItemDto
import com.mdblisthub.tv.core.network.dto.TraktWriteSeasonDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Where watchlist / watched / collection membership comes from.
 *
 * Only the network half: which titles are in a bucket, and adding or removing
 * one. Mirroring the answer into Room and deciding when it is stale belong to
 * `LibraryRepository`, which is the same for both providers and has no reason
 * to be written twice.
 */
data class WatchedEpisodeId(val showTmdbId: Int, val seasonNumber: Int, val episodeNumber: Int)

data class LibrarySyncResult(
    val titleIds: List<Int>,
    val episodeIds: List<WatchedEpisodeId> = emptyList()
)

interface LibrarySource {

    /**
     * Every TMDB id currently in [bucket], or null when this source has no
     * account to ask — an unlinked Trakt, or a session with no mdblist key.
     * Null means "leave what is cached alone"; an empty list means the bucket
     * really is empty and the cache should be emptied with it.
     */
    suspend fun membership(bucket: LibraryBucket): LibrarySyncResult?

    /** Adds or removes one title. Throws with a readable message on failure. */
    suspend fun write(
        bucket: LibraryBucket,
        type: MediaType,
        tmdbId: Int,
        imdbId: String?,
        add: Boolean,
    )

    /** Adds or removes one concrete episode from watched history. */
    suspend fun writeEpisodeWatched(
        showTmdbId: Int,
        showImdbId: String?,
        season: Int,
        episode: Int,
        add: Boolean,
    )
}

/**
 * mdblist.
 *
 * The writes are plain JSON POSTs. That is worth stating, because in the
 * browser they are not: a cross-origin JSON POST needs a CORS preflight and
 * mdblist answers OPTIONS with 405, which is why the web build routes them
 * through a dev proxy. A native client has no such rule.
 */
class MdblistLibrarySource(
    private val api: MdblistApi,
    private val session: SessionStore,
) : LibrarySource {

    override suspend fun membership(bucket: LibraryBucket): LibrarySyncResult? {
        val key = session.currentKey()
        if (key.isBlank()) return null
        val response = api.wholeBucket("$ROOT${bucket.readPath}", key)
        val titleIds = response.tmdbIds()
        
        // MDBList sync/watched JSON returns a flat array of episodes at the top level
        val episodeIds = if (bucket == LibraryBucket.WATCHED) {
            response.episodes.mapNotNull { entry ->
                val ep = entry.episode ?: return@mapNotNull null
                val showTmdbId = ep.show?.ids?.tmdb ?: return@mapNotNull null
                val seasonNum = ep.season ?: return@mapNotNull null
                val episodeNum = ep.number ?: return@mapNotNull null
                WatchedEpisodeId(showTmdbId, seasonNum, episodeNum)
            }
        } else emptyList()
        
        return LibrarySyncResult(titleIds, episodeIds)
    }

    override suspend fun write(
        bucket: LibraryBucket,
        type: MediaType,
        tmdbId: Int,
        imdbId: String?,
        add: Boolean,
    ) {
        val key = session.currentKey()
        requireOrFail(key.isNotBlank()) { AppError.MdblistSessionExpired }

        val entry = if (imdbId != null) LibraryKeyDto(imdb = imdbId) else LibraryKeyDto(tmdb = tmdbId)
        val body = if (type == MediaType.SHOW) {
            LibraryWriteDto(shows = listOf(entry))
        } else {
            LibraryWriteDto(movies = listOf(entry))
        }

        val path = if (add) bucket.addPath else bucket.removePath
        val response = api.bucketWrite("$ROOT$path", key, body)
        requireOrFail(response.isSuccessful) { AppError.MdblistWriteRejected(response.code()) }
    }

    override suspend fun writeEpisodeWatched(
        showTmdbId: Int,
        showImdbId: String?,
        season: Int,
        episode: Int,
        add: Boolean,
    ) {
        val key = session.currentKey()
        requireOrFail(key.isNotBlank()) { AppError.MdblistSessionExpired }

        val body = LibraryWriteDto(
            shows = listOf(
                LibraryKeyDto(
                    imdb = showImdbId,
                    tmdb = showTmdbId.takeIf { it > 0 },
                    seasons = listOf(
                        LibraryWriteSeasonDto(
                            number = season,
                            episodes = listOf(LibraryWriteEpisodeDto(episode)),
                        ),
                    ),
                ),
            ),
        )
        val path = if (add) LibraryBucket.WATCHED.addPath else LibraryBucket.WATCHED.removePath
        val response = api.bucketWrite("$ROOT$path", key, body)
        requireOrFail(response.isSuccessful) { AppError.MdblistWriteRejected(response.code()) }
    }

    /**
     * The endpoints, which used to live on [LibraryBucket] itself. They are
     * mdblist's spelling and no other provider's, so they belong here.
     */
    private val LibraryBucket.readPath: String
        get() = when (this) {
            LibraryBucket.WATCHLIST -> "watchlist/items"
            LibraryBucket.WATCHED -> "sync/watched"
            LibraryBucket.COLLECTION -> "sync/collection"
        }

    private val LibraryBucket.addPath: String
        get() = when (this) {
            LibraryBucket.WATCHLIST -> "watchlist/items/add"
            LibraryBucket.WATCHED -> "sync/watched"
            LibraryBucket.COLLECTION -> "sync/collection"
        }

    private val LibraryBucket.removePath: String
        get() = when (this) {
            LibraryBucket.WATCHLIST -> "watchlist/items/remove"
            LibraryBucket.WATCHED -> "sync/watched/remove"
            LibraryBucket.COLLECTION -> "sync/collection/remove"
        }

    private companion object {
        const val ROOT = "https://api.mdblist.com/"
    }
}

/**
 * Trakt.
 *
 * Two differences from mdblist shape the code below. Films and series are
 * always separate calls — Trakt has no equivalent of `unified=true`, so every
 * bucket read here is two requests merged. And "watched" is not a bucket at
 * all but the play history: adding marks a play, removing erases the plays,
 * which is the same whole-title meaning the detail screen's button already
 * promises.
 */
class TraktLibrarySource(
    private val api: TraktApi,
    private val tokens: TraktTokenStore,
) : LibrarySource {

    override suspend fun membership(bucket: LibraryBucket): LibrarySyncResult? {
        if (!tokens.isLinked()) return null

        return when (bucket) {
            LibraryBucket.WATCHLIST ->
                LibrarySyncResult(
                    paged { page -> api.watchlist(type = "movies", limit = PAGE, page = page) }
                        .mapNotNull { it.movie?.ids?.tmdb } +
                    paged { page -> api.watchlist(type = "shows", limit = PAGE, page = page) }
                        .mapNotNull { it.show?.ids?.tmdb }
                )

            LibraryBucket.COLLECTION ->
                LibrarySyncResult(
                    paged { page -> api.collection(type = "movies", limit = PAGE, page = page) }
                        .mapNotNull { it.movie?.ids?.tmdb } +
                    paged { page -> api.collection(type = "shows", limit = PAGE, page = page) }
                        .mapNotNull { it.show?.ids?.tmdb }
                )

            // Not paginated: this endpoint answers with the account's whole
            // watched set in one response.
            LibraryBucket.WATCHED -> {
                try {
                    val movies = api.watched("movies").mapNotNull { it.movie?.ids?.tmdb }
                    val showsDto = api.watched("shows")
                    val shows = showsDto.mapNotNull { it.show?.ids?.tmdb }
                    val episodes = showsDto.flatMap { showDto ->
                        val showTmdbId = showDto.show?.ids?.tmdb ?: return@flatMap emptyList()
                        showDto.seasons?.flatMap { season ->
                            season.episodes?.map { ep ->
                                WatchedEpisodeId(showTmdbId, season.number, ep.number)
                            } ?: emptyList()
                        } ?: emptyList()
                    }
                    LibrarySyncResult(movies + shows, episodes)
                } catch (e: Exception) {
                    android.util.Log.e("TraktLibrarySource", "Failed to sync watched", e)
                    throw e
                }
            }
        }
    }

    override suspend fun write(
        bucket: LibraryBucket,
        type: MediaType,
        tmdbId: Int,
        imdbId: String?,
        add: Boolean,
    ) {
        requireOrFail(tokens.isLinked()) { AppError.TraktNotLinked }

        // IMDb first, because it is the id Trakt resolves most reliably for
        // titles this app knows; the TMDB id is what it always has.
        val ids = if (imdbId != null) TraktIdsDto(imdb = imdbId) else TraktIdsDto(tmdb = tmdbId)
        val item = listOf(TraktWriteItemDto(ids))
        val body = if (type == MediaType.SHOW) {
            TraktSyncWriteDto(shows = item)
        } else {
            TraktSyncWriteDto(movies = item)
        }

        val response = when (bucket) {
            LibraryBucket.WATCHLIST ->
                if (add) api.addToWatchlist(body) else api.removeFromWatchlist(body)

            LibraryBucket.COLLECTION ->
                if (add) api.addToCollection(body) else api.removeFromCollection(body)

            LibraryBucket.WATCHED ->
                if (add) api.addToHistory(body) else api.removeFromHistory(body)
        }

        // A `201` whose every id landed in `not_found` is a failure wearing a
        // success code — see [TraktSyncResponseDto.resolvedNothing]. Without
        // this the button would settle on a state Trakt never stored.
        requireOrFail(!response.resolvedNothing()) { AppError.TraktTitleNotRecognized }
    }

    override suspend fun writeEpisodeWatched(
        showTmdbId: Int,
        showImdbId: String?,
        season: Int,
        episode: Int,
        add: Boolean,
    ) {
        requireOrFail(tokens.isLinked()) { AppError.TraktNotLinked }

        val body = TraktSyncWriteDto(
            shows = listOf(
                TraktWriteItemDto(
                    ids = TraktIdsDto(
                        imdb = showImdbId,
                        tmdb = showTmdbId.takeIf { it > 0 },
                    ),
                    seasons = listOf(
                        TraktWriteSeasonDto(
                            number = season,
                            episodes = listOf(TraktWriteEpisodeDto(episode)),
                        ),
                    ),
                ),
            ),
        )
        val response = if (add) api.addToHistory(body) else api.removeFromHistory(body)
        requireOrFail(!response.resolvedNothing()) { AppError.TraktTitleNotRecognized }
    }

    /**
     * Walks pages until one comes back short.
     *
     * Trakt's paginated reads default to ten items — not "everything" — so a
     * membership read has to ask for the rest explicitly. Counting the answer
     * rather than reading `X-Pagination-Page-Count` keeps the API interface
     * returning plain lists instead of `Response<List<T>>` everywhere.
     *
     * [MAX_PAGES] is a stop, not a target: it bounds a pathologically large
     * account at a few thousand titles instead of letting one refresh page
     * through a library forever.
     */
    private suspend fun <T> paged(fetch: suspend (Int) -> List<T>): List<T> {
        val all = mutableListOf<T>()
        for (page in 1..MAX_PAGES) {
            val batch = fetch(page)
            all += batch
            if (batch.size < PAGE) break
        }
        return all
    }

    private companion object {
        const val PAGE = 100
        const val MAX_PAGES = 20
    }
}

class SimklLibrarySource(
    private val api: SimklApi,
    private val tokens: SimklTokenStore,
) : LibrarySource {
    override suspend fun membership(bucket: LibraryBucket): LibrarySyncResult? {
        if (!tokens.isLinked()) return null
        if (bucket == LibraryBucket.COLLECTION) return LibrarySyncResult(emptyList())
        val status = if (bucket == LibraryBucket.WATCHLIST) "plantowatch" else "completed"
        val full = bucket == LibraryBucket.WATCHED
        val movies = api.items("movies", status, if (full) "full" else "ids_only",
            if (full) "yes" else null, if (full) "yes" else null)
        val shows = api.items("shows", status, if (full) "full" else "ids_only",
            if (full) "yes" else null, if (full) "yes" else null)
        val watching = if (full) api.items("shows", "watching", "full", "yes", "yes") else null
        val entries = movies["movies"].asArray() + shows["shows"].asArray() + shows["anime"].asArray() +
            watching?.get("shows").asArray() + watching?.get("anime").asArray()
        val titleIds = entries.mapNotNull { it.jsonObject.titleObject()?.tmdbId() }
        val episodes = if (!full) emptyList() else entries.flatMap { entry ->
            val obj = entry.jsonObject
            val tmdb = obj.titleObject()?.tmdbId() ?: return@flatMap emptyList()
            obj["seasons"].asArray().flatMap { seasonEl ->
                val season = seasonEl.jsonObject
                val number = season["number"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@flatMap emptyList()
                season["episodes"].asArray().mapNotNull { episodeEl ->
                    episodeEl.jsonObject["number"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?.let { WatchedEpisodeId(tmdb, number, it) }
                }
            }
        }
        return LibrarySyncResult(titleIds.distinct(), episodes.distinct())
    }

    override suspend fun write(bucket: LibraryBucket, type: MediaType, tmdbId: Int, imdbId: String?, add: Boolean) {
        check(tokens.isLinked()) { "Simkl is not connected" }
        check(bucket != LibraryBucket.COLLECTION) { "Simkl does not provide Collection" }
        val body = syncBody(
            type = type,
            tmdbId = tmdbId,
            imdbId = imdbId,
            watchlistStatus = "plantowatch".takeIf { bucket == LibraryBucket.WATCHLIST && add },
            markWholeShowWatched = bucket == LibraryBucket.WATCHED && add && type == MediaType.SHOW,
        )
        val response = when {
            bucket == LibraryBucket.WATCHLIST && add -> api.addToList(body)
            add -> api.addHistory(body)
            else -> api.removeHistory(body)
        }
        requireResolved(response)
    }

    override suspend fun writeEpisodeWatched(showTmdbId: Int, showImdbId: String?, season: Int, episode: Int, add: Boolean) {
        check(tokens.isLinked()) { "Simkl is not connected" }
        val body = buildJsonObject { putJsonArray("shows") { add(buildJsonObject {
            putJsonObject("ids") { showImdbId?.let { put("imdb", it) }; put("tmdb", showTmdbId.toString()) }
            putJsonArray("seasons") { add(buildJsonObject { put("number", season); putJsonArray("episodes") { add(buildJsonObject { put("number", episode) }) } }) }
        }) } }
        val response = if (add) api.addHistory(body) else api.removeHistory(body)
        requireResolved(response)
    }

    private fun syncBody(
        type: MediaType,
        tmdbId: Int,
        imdbId: String?,
        watchlistStatus: String? = null,
        markWholeShowWatched: Boolean = false,
    ) = buildJsonObject {
        putJsonArray(if (type == MediaType.SHOW) "shows" else "movies") { add(buildJsonObject {
            watchlistStatus?.let { put("to", it) }
            if (markWholeShowWatched) put("status", "completed")
            putJsonObject("ids") { imdbId?.let { put("imdb", it) }; put("tmdb", tmdbId.toString()) }
        }) }
    }

    private fun requireResolved(response: JsonObject) {
        val notFound = response["not_found"] as? JsonObject
        val rejected = listOf("movies", "shows", "episodes")
            .sumOf { key -> notFound?.get(key).asArray().size }
        check(rejected == 0) { "Simkl could not identify this title or episode" }
    }
}

private fun kotlinx.serialization.json.JsonElement?.asArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.titleObject(): JsonObject? = (this["show"] ?: this["movie"]) as? JsonObject
private fun JsonObject.tmdbId(): Int? = this["ids"]?.jsonObject?.get("tmdb")?.jsonPrimitive?.content?.toIntOrNull()
