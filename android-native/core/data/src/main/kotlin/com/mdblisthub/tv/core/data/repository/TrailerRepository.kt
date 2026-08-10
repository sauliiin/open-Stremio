package com.mdblisthub.tv.core.data.repository

import android.net.Uri
import android.util.Log
import com.mdblisthub.tv.core.network.ImdbApi
import com.mdblisthub.tv.core.network.dto.ImdbGraphqlRequest
import com.mdblisthub.tv.core.network.dto.ImdbPlaybackUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * IMDb's own trailer file, played by Media3 instead of handed off to YouTube.
 *
 * This is the first tier of the fallback the user described from their Kodi
 * setup: IMDb's trailer first, a low-quality YouTube trailer second, and the
 * YouTube app itself as the last resort. IMDb wins the top spot because it is
 * a plain MP4 — no embed policy, no WebView, nothing that can answer "error
 * 152" — and the logic here is a direct port of the same two-step GraphQL
 * lookup `script.showimdb`'s `imdb_trailer_api.py` uses: a title id resolves
 * to a trailer video id, and a video id resolves to a list of files.
 *
 * That script backs its lookups with a SQLite cache (30 days for the trailer
 * id, ~2 days for the playback URL, 12h for a confirmed miss) because the
 * point of it is a Kodi box that stays running. This app doesn't stay running
 * the same way — Android TV reclaims it on memory pressure between almost
 * every use — so an in-memory cache buys the only thing that matters here:
 * not re-issuing both GraphQL calls every time the user reopens the same
 * detail screen in one sitting. The same TTL reasoning is kept, just against
 * the process, not the disk.
 */
class TrailerRepository(private val api: ImdbApi) {

    private data class Cached<T>(val value: T, val goodUntilMs: Long) {
        fun isFresh(now: Long) = now < goodUntilMs
    }

    private val trailerIds = ConcurrentHashMap<String, Cached<String?>>()
    private val playbackUrls = ConcurrentHashMap<String, Cached<String?>>()

    /**
     * The best MP4 for [imdbId]'s latest trailer, or null if IMDb has none —
     * either because the title genuinely lacks one, or the lookup failed.
     * Callers can't tell those apart, which is fine: either way the answer is
     * "fall through to the next tier".
     */
    suspend fun mp4For(imdbId: String, qualityPriority: List<Int> = QUALITY_PRIORITY): String? {
        val now = System.currentTimeMillis()

        playbackUrls[imdbId]?.takeIf { it.isFresh(now) }?.let { return it.value }

        val trailerId = trailerIds[imdbId]?.takeIf { it.isFresh(now) }?.value
            ?: fetchTrailerId(imdbId).also {
                trailerIds[imdbId] = Cached(it, now + if (it != null) TRAILER_ID_TTL_MS else MISS_TTL_MS)
            }

        val url = trailerId?.let { fetchPlaybackUrl(it, qualityPriority) }
        playbackUrls[imdbId] = Cached(url, now + (url?.let(::urlRemainingMs) ?: MISS_TTL_MS))
        return url
    }

    private suspend fun fetchTrailerId(imdbId: String): String? =
        runCatching {
            api.latestTrailerId(
                ImdbGraphqlRequest(
                    query = ImdbApi.LATEST_TRAILER_QUERY,
                    variables = mapOf("id" to imdbId),
                ),
            ).data?.title?.latestTrailer?.id
        }.onFailure { Log.w(TAG, "trailer id lookup failed for $imdbId", it) }.getOrNull()

    private suspend fun fetchPlaybackUrl(trailerId: String, qualityPriority: List<Int>): String? =
        runCatching {
            val urls = api.playbackUrls(
                ImdbGraphqlRequest(
                    query = ImdbApi.PLAYBACK_QUERY,
                    variables = mapOf("viconst" to trailerId),
                ),
            ).data?.video?.playbackURLs.orEmpty()
            pickBest(urls, qualityPriority)
        }.onFailure { Log.w(TAG, "playback url lookup failed for $trailerId", it) }.getOrNull()

    /**
     * Highest-priority quality that's actually present wins; if none of the
     * preferred qualities are on the list — a trailer that only shipped
     * 480p, say — the best one available is still better than nothing.
     */
    private fun pickBest(urls: List<ImdbPlaybackUrl>, qualityPriority: List<Int>): String? {
        val mp4s = urls.filter { it.videoMimeType == "MP4" && !it.url.isNullOrBlank() }
        if (mp4s.isEmpty()) return null

        val byQuality = mp4s.groupBy { extractQuality(it.displayName?.value) }
        for (quality in qualityPriority) {
            byQuality[quality]?.firstOrNull()?.url?.let { return it }
        }
        return mp4s.maxByOrNull { extractQuality(it.displayName?.value) ?: -1 }?.url
    }

    private fun extractQuality(label: String?): Int? =
        label?.let { Regex("""(\d{3,4})""").find(it)?.value?.toIntOrNull() }

    /**
     * These are signed URLs — reusing one past its `Expires`/`expires` query
     * param just fails on playback, so a URL is never cached longer than it's
     * actually good for. Five minutes of slack keeps playback from starting on
     * a link that's about to expire mid-lookup.
     */
    private fun urlRemainingMs(url: String): Long {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return PLAYBACK_URL_TTL_MS
        val epochSeconds = (uri.getQueryParameter("Expires") ?: uri.getQueryParameter("expires"))
            ?.toLongOrNull() ?: return PLAYBACK_URL_TTL_MS
        val remaining = epochSeconds * 1000 - System.currentTimeMillis() - EXPIRY_SLACK_MS
        return remaining.coerceIn(0, PLAYBACK_URL_TTL_MS)
    }

    private companion object {
        const val TAG = "TrailerRepository"
        val QUALITY_PRIORITY = listOf(1080, 720)
        val TRAILER_ID_TTL_MS = TimeUnit.DAYS.toMillis(30)
        val PLAYBACK_URL_TTL_MS = TimeUnit.DAYS.toMillis(2)
        val MISS_TTL_MS = TimeUnit.HOURS.toMillis(12)
        val EXPIRY_SLACK_MS = TimeUnit.MINUTES.toMillis(5)
    }
}
