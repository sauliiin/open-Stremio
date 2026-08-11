package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.mapper.OPENSUBTITLES_FILE_SCHEME
import com.mdblisthub.tv.core.data.mapper.SubtitleFileParser
import com.mdblisthub.tv.core.data.mapper.rankForPlayback
import com.mdblisthub.tv.core.data.mapper.rankSubtitles
import com.mdblisthub.tv.core.data.mapper.toOption
import com.mdblisthub.tv.core.data.mapper.toPlayable
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.core.model.SubtitleTrack
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.OpenSubtitlesApi
import com.mdblisthub.tv.core.network.StremioApi
import com.mdblisthub.tv.core.network.WyzieApi
import com.mdblisthub.tv.core.network.dto.OpenSubtitlesDownloadRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Where a title's playable links come from.
 *
 * The result is deliberately not a menu. Nothing in this app ever shows the
 * user a list of sources to choose between: `candidates` returns them already
 * ranked, and the player walks that order until one plays. Which mirror or
 * release ends up on screen is an implementation detail of getting the film
 * playing, not a question worth interrupting someone with.
 */
class StreamsRepository(
    private val api: StremioApi,
    private val addons: AddonsRepository,
    addonClient: OkHttpClient,
    private val openSubtitlesApi: OpenSubtitlesApi,
    private val wyzieApi: WyzieApi,
) {

    /**
     * Tuned for a yes/no signal, not for the download that follows: the
     * shared addon client's own timeouts (6s connect / 8s read) are sized for
     * fetching a JSON manifest, not for finding out in a hurry whether a
     * video mirror is even alive.
     */
    private val probeClient = addonClient.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * A subtitle is a whole file, not a yes/no answer, so it gets room to
     * finish where [probeClient] is tuned to give up fast.
     */
    private val subtitleClient = addonClient.newBuilder()
        .callTimeout(SUBTITLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Ranked, deduplicated, playable-only — delivered as they are verified.
     *
     * A flow rather than a list, and that is the whole point. The previous
     * version probed every mirror in parallel and then waited for the *slowest*
     * one before returning anything, so a link that answered in 200ms still
     * sat behind three and a half seconds of dead hosts timing out, and only
     * then did ExoPlayer get to start. Here the probes still all run at once,
     * but each result is handed over the moment it lands.
     *
     * Awaiting them in rank order rather than completion order is deliberate:
     * emitting whoever answers first would quietly hand the film to a 480p
     * mirror because it happened to be closer than the 1080p one. So the top
     * candidate is waited for — at most one probe timeout — and everything
     * behind it has already been probed concurrently by then, so the rest
     * arrive with no further delay.
     *
     * An addon that fails contributes nothing rather than failing the call:
     * with a dozen installed, one being down is the normal case.
     */
    fun candidates(type: MediaType, id: String): Flow<PlayableStream> = channelFlow {
        val providers = addons.addons().filter { it.serves("stream", type, id) }
        if (providers.isEmpty()) return@channelFlow

        val encoded = URLEncoder.encode(id, "UTF-8")

        // Each addon reports into here the moment it answers. This used to be
        // an `awaitAll()`, which meant the *first* playable candidate could
        // not be handed over until the *slowest* addon had finished — and with
        // a dozen installed, one being down is the normal case, so the common
        // outcome was the player waiting out a twelve-second call timeout on a
        // dead host before opening a link that had been ready in 200ms.
        //
        // A null is the settle window closing; see below.
        val inbox = Channel<List<PlayableStream>?>(Channel.UNLIMITED)

        // `Dispatchers.Default`, and that is the second half of the fix. The
        // network call already runs off the main thread, but the work after it
        // did not: `toPlayable` applies three regexes per stream, and a dozen
        // addons returning forty streams each put roughly fifteen hundred
        // regex matches on the main thread — while the loading veil is
        // supposed to be animating.
        launch(Dispatchers.Default) {
            // `finally`, not a plain trailing statement. The gather loops below
            // block on this channel until it closes, so anything that escapes
            // the fan-out — a cancellation, or a failure `runCatching` does not
            // cover — would otherwise leave them waiting forever with the veil
            // up and no way out.
            try {
                coroutineScope {
                    providers.forEach { addon ->
                        launch {
                            val streams = runCatching {
                                api.streams("${addon.base}/stream/${type.stremio}/$encoded.json")
                                    .streams
                                    .mapIndexed { index, dto -> dto.toPlayable(addon, index) }
                            }.getOrDefault(emptyList())
                            inbox.send(streams)
                        }
                    }
                }
            } finally {
                inbox.close()
            }
        }

        launch {
            delay(SETTLE_WINDOW_MS)
            inbox.trySend(null)
        }

        val firstWave = mutableListOf<PlayableStream>()
        var answered = 0

        // Gather whatever has arrived by the end of the window. Ranking across
        // a few addons rather than emitting whoever answered first still
        // matters — otherwise a 480p mirror wins simply for being closer — but
        // the window bounds how long that costs.
        while (answered < providers.size) {
            val result = inbox.receiveCatching()
            if (result.isClosed) break
            // A null element is the settle timer, not the end of the addons.
            val batch = result.getOrNull() ?: break
            answered++
            firstWave += batch
        }

        // The window expired with nothing usable in hand. Starting is not an
        // option, so wait for whoever answers first, however long that takes;
        // the addon client's own timeouts bound it.
        while (firstWave.none { it.playable } && answered < providers.size) {
            val result = inbox.receiveCatching()
            if (result.isClosed) break
            val batch = result.getOrNull() ?: continue
            answered++
            firstWave += batch
        }

        val ranked = withContext(Dispatchers.Default) { firstWave.rankForPlayback() }
        val seen = ranked.mapNotNullTo(mutableSetOf()) { it.url }

        if (ranked.isNotEmpty()) {
            // The best-ranked candidate goes out unprobed, immediately.
            // Actually opening it in the player is a stricter test than a range
            // request anyway, so making it wait behind a probe only adds that
            // probe's timeout to the one case that matters most — and debrid
            // endpoints in particular routinely refuse a two-byte request,
            // then stream fine.
            send(ranked.first())
            sendProbed(ranked.drop(1))
        }

        // Everything that arrived after the window closed, appended behind the
        // first wave rather than dropped. The controller's queue is
        // append-only for exactly this reason, so a late addon is still usable
        // as fallback material without disturbing a cascade already running.
        while (true) {
            val result = inbox.receiveCatching()
            if (result.isClosed) break
            val batch = result.getOrNull()
            if (batch.isNullOrEmpty()) continue
            val late = withContext(Dispatchers.Default) {
                batch.rankForPlayback().filter { it.url != null && seen.add(it.url!!) }
            }
            sendProbed(late)
        }
    }

    /**
     * Probes a group in parallel and emits it best-first, deprioritising —
     * never dropping — whatever flunks: a failed probe is weak evidence, for
     * the same reason the top candidate skips the probe entirely.
     */
    private suspend fun ProducerScope<PlayableStream>.sendProbed(streams: List<PlayableStream>) {
        if (streams.isEmpty()) return

        // Bounded so a title with forty mirrors does not open forty sockets at
        // once on a set-top box; eight is enough to keep the queue moving.
        val gate = Semaphore(PROBE_CONCURRENCY)
        val probes = streams.map { stream ->
            async { stream to gate.withPermit { isReachable(stream) } }
        }

        val unverified = mutableListOf<PlayableStream>()
        for (probe in probes) {
            val (stream, reachable) = probe.await()
            if (reachable) send(stream) else unverified += stream
        }
        unverified.forEach { send(it) }
    }

    private suspend fun isReachable(stream: PlayableStream): Boolean {
        val url = stream.url ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-1")
                    .apply { stream.headers.forEach { (key, value) -> header(key, value) } }
                    .build()
                probeClient.newCall(request).execute().use { it.code < 400 }
            }.getOrDefault(false)
        }
    }

    /**
     * Downloads and parses one subtitle file into cues the player owns.
     *
     * The URL used to go straight to Media3 as part of the video's MediaItem.
     * It no longer does: holding the cues ourselves is what makes adjusting
     * the synchronization offset instant instead of a full re-prepare of the
     * video. See [SubtitleFileParser].
     */
    suspend fun subtitleTrack(option: SubtitleOption): SubtitleTrack? = withContext(Dispatchers.IO) {
        runCatching {
            val url = resolveSubtitleUrl(option.url) ?: return@runCatching null
            val request = Request.Builder().url(url).build()
            subtitleClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                SubtitleFileParser.parse(response.body.bytes(), option.encoding)
            }
        }.getOrNull()?.takeIf { !it.isEmpty }
    }

    /**
     * A normal addon URL passes through untouched. An OpenSubtitles.com file
     * id — see [OPENSUBTITLES_FILE_SCHEME] — is resolved here instead of at
     * search time, so the account's daily download quota is only ever spent
     * on the one subtitle actually chosen, not on every result a search
     * happened to return.
     */
    private suspend fun resolveSubtitleUrl(url: String): String? {
        val fileId = url.removePrefix(OPENSUBTITLES_FILE_SCHEME).toLongOrNull() ?: return url
        return runCatching {
            openSubtitlesApi.download(OpenSubtitlesDownloadRequestDto(fileId)).link
        }.getOrNull()
    }

    /**
     * Subtitles from two places at once: whatever the installed Stremio
     * addons serve, plus a direct OpenSubtitles.com search. The addon
     * protocol only guarantees `id`/`url`/`lang` — no release name — so
     * `SubtitleMatcher` has nothing reliable to match against unless this
     * second source, which does return one, is asked too.
     */
    suspend fun subtitles(type: MediaType, id: String): List<SubtitleOption> = coroutineScope {
        val addonOptions = async { addonSubtitles(type, id) }
        val openSubtitlesOptions = async { openSubtitlesSearch(id) }
        val wyzieOptions = async { wyzieSearch(id) }
        (addonOptions.await() + openSubtitlesOptions.await() + wyzieOptions.await()).rankSubtitles()
    }

    private suspend fun addonSubtitles(type: MediaType, id: String): List<SubtitleOption> = coroutineScope {
        val providers = addons.addons().filter { it.serves("subtitles", type, id) }
        if (providers.isEmpty()) return@coroutineScope emptyList()

        val encoded = URLEncoder.encode(id, "UTF-8")

        providers
            .map { addon ->
                async {
                    runCatching {
                        api.subtitles("${addon.base}/subtitles/${type.stremio}/$encoded.json")
                            .subtitles
                            .mapIndexed { index, dto -> dto.toOption(addon, index) }
                    }.getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
    }

    /** `id` is `tt1234567` for a film or `tt1234567:season:episode` for an episode. */
    private suspend fun openSubtitlesSearch(id: String): List<SubtitleOption> {
        val parts = id.split(':')
        val imdbId = parts.getOrNull(0)?.removePrefix("tt")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()

        return runCatching {
            openSubtitlesApi.search(
                imdbId = imdbId,
                languages = "pt-br,en",
                season = season,
                episode = episode,
                type = if (season != null) "episode" else "movie",
            ).data.mapIndexedNotNull { index, item -> item.toOption(index) }
        }.getOrDefault(emptyList())
    }

    /**
     * One request per language rather than a combined list — see
     * [ApiConfig.WYZIE_BASE] for why a combined `pb,pt` silently drops the
     * `pb` results — each tagged with the three-letter code the rest of the
     * app already knows how to rank and label.
     */
    private suspend fun wyzieSearch(id: String): List<SubtitleOption> = coroutineScope {
        val parts = id.split(':')
        val imdbId = parts.getOrNull(0)?.takeIf { it.startsWith("tt") } ?: return@coroutineScope emptyList()
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()

        listOf("pb" to "pob", "pt" to "por", "en" to "eng")
            .map { (wyzieLang, normalizedLang) ->
                async {
                    runCatching {
                        wyzieApi.search(
                            imdbId = imdbId,
                            language = wyzieLang,
                            apiKey = ApiConfig.WYZIE_API_KEY,
                            season = season,
                            episode = episode,
                        ).mapIndexedNotNull { index, item -> item.toOption(index, normalizedLang) }
                    }.getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
    }

    /** How many installed addons even claim to serve streams for this id. */
    suspend fun providerCount(type: MediaType, id: String): Int =
        addons.addons().count { it.serves("stream", type, id) }

    private companion object {
        /**
         * Long enough for a real mirror to answer two bytes, short enough
         * that a dozen of these in parallel still lands well under what one
         * dead source used to cost alone in the old sequential-only cascade.
         */
        const val PROBE_TIMEOUT_MS = 3_500L
        const val SUBTITLE_TIMEOUT_MS = 15_000L
        const val PROBE_CONCURRENCY = 16

        /**
         * How long the first wave is allowed to gather before the best of it
         * is handed over.
         *
         * Long enough that a healthy addon set is almost always complete —
         * they answer in a few hundred milliseconds — and short enough that a
         * single hung host cannot hold the film hostage. Whatever misses the
         * window is not lost, only queued behind.
         */
        const val SETTLE_WINDOW_MS = 900L
    }
}
