package com.mdblisthub.tv.core.data.repository

import android.os.SystemClock
import android.util.Log
import com.mdblisthub.tv.core.data.mapper.OPENSUBTITLES_FILE_SCHEME
import com.mdblisthub.tv.core.data.mapper.SubtitleFileParser
import com.mdblisthub.tv.core.data.mapper.rankForPlayback
import com.mdblisthub.tv.core.data.mapper.rankSubtitles
import com.mdblisthub.tv.core.data.mapper.toOption
import com.mdblisthub.tv.core.data.mapper.toPlayable
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.SourceReadiness
import com.mdblisthub.tv.core.model.StreamKind
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.core.model.SubtitleTrack
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.OpenSubtitlesApi
import com.mdblisthub.tv.core.network.ResolvedLinks
import com.mdblisthub.tv.core.network.StremioApi
import com.mdblisthub.tv.core.network.WyzieApi
import com.mdblisthub.tv.core.network.dto.OpenSubtitlesDownloadRequestDto
import com.mdblisthub.tv.torrent.TorrentStreaming
import kotlinx.coroutines.CompletableDeferred
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
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
     * video mirror is even alive. The same protocols as `HttpClients.playback`,
     * because they are part of what makes a pooled connection reusable: with any
     * other list, the socket this warms up is one the player can never pick up.
     *
     * [ResolvedLinks] for the same reason: a probe of a debrid link is a full resolve, and
     * keeping where it led lets the player open that link on the CDN without another.
     */
    private val probeClient = addonClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .addInterceptor(ResolvedLinks)
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
     * then did ExoPlayer get to start. Here the probes still run in parallel,
     * within [ProbeGate]'s limits, and each result is handed over the moment it
     * lands.
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
    fun candidates(
        type: MediaType,
        id: String,
        runtimeMinutes: Int? = null,
    ): Flow<PlayableStream> = channelFlow {
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
                            val askedAt = SystemClock.elapsedRealtime()
                            val streams = runCatching {
                                api.streams("${addon.base}/stream/${type.stremio}/$encoded.json")
                                    .streams
                                    .mapIndexed { index, dto -> dto.toPlayable(addon, index) }
                                    .map(::withTorrentUrl)
                            }.getOrDefault(emptyList())
                            Log.i(
                                TAG,
                                "addon ${addon.name} answered in ${SystemClock.elapsedRealtime() - askedAt}ms " +
                                    "with ${streams.size} links",
                            )
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
        // the addon client's own timeouts bound it. A link the debrid service
        // has not fetched yet does not count as usable: it would be opened
        // first, and it takes far longer to play than a slower addon takes to
        // answer with one that is ready. Nor does a torrent from its swarm, for
        // the same reason, so a debrid addon answering a second late still
        // goes ahead of it.
        while (
            firstWave.none { it.playable && it.readiness < SourceReadiness.PEER_TO_PEER } &&
            answered < providers.size
        ) {
            val result = inbox.receiveCatching()
            if (result.isClosed) break
            val batch = result.getOrNull() ?: continue
            answered++
            firstWave += batch
        }

        val torrentRoom = TorrentStreaming.roomBytes()
        val ranked = withContext(Dispatchers.Default) { firstWave.rankForPlayback(runtimeMinutes, torrentRoom) }
        val seen = ranked.mapNotNullTo(mutableSetOf()) { it.url }

        // One gate for the whole call, not one per batch. Since the late
        // batches below probe concurrently, a semaphore built inside
        // `sendProbed` would bound each batch to PROBE_CONCURRENCY and the call
        // to nothing at all — a dozen addons answering late would mean a dozen
        // times that many sockets open at once on a set-top box. These probes
        // are synchronous OkHttp calls, which are exempt from the client
        // dispatcher's own limits, so this is the only thing bounding them.
        // The per-host limits live in it too, for the same reason.
        val gate = ProbeGate()

        if (ranked.isNotEmpty()) {
            // The best-ranked candidate goes out unprobed, immediately.
            // Actually opening it in the player is a stricter test than a range
            // request anyway, so making it wait behind a probe only adds that
            // probe's timeout to the one case that matters most — and debrid
            // endpoints in particular routinely refuse a two-byte request,
            // then stream fine.
            send(ranked.first())
            sendProbed(ranked.drop(1), gate)
        }

        // Everything that arrived after the window closed, appended behind the
        // first wave rather than dropped. The controller's queue is
        // append-only for exactly this reason, so a late addon is still usable
        // as fallback material without disturbing a cascade already running.
        //
        // `launch`, not a plain call, and that is the whole point of this loop
        // now. `sendProbed` suspends until every probe in its batch has
        // resolved, so awaiting it here made each late addon wait out the one
        // before it: five slow addons meant five probe timeouts end to end,
        // ~17s of the cascade sitting on `awaitingCandidate` while candidates
        // it could have tried were queued behind a host that was never going to
        // answer. Probing the batches concurrently costs nothing — they all
        // share the one `gate` above, so the socket count is bounded exactly as
        // before — and `channelFlow` will not close the channel until these
        // children finish.
        //
        // Rank order within each batch is untouched; only the order *between*
        // late addons becomes arrival-driven, which is what it already was.
        while (true) {
            val result = inbox.receiveCatching()
            if (result.isClosed) break
            val batch = result.getOrNull()
            if (batch.isNullOrEmpty()) continue
            // `seen` stays on this loop's thread. The filtering has to happen
            // here rather than inside the launched child, or two batches
            // arriving together could both pass the same URL through.
            val late = withContext(Dispatchers.Default) {
                batch.rankForPlayback(runtimeMinutes, torrentRoom).filter { it.url != null && seen.add(it.url!!) }
            }
            launch { sendProbed(late, gate) }
        }
    }

    /**
     * Probes a group in parallel and emits it best-first, deprioritising —
     * never dropping — whatever flunks: a failed probe is weak evidence, for
     * the same reason the top candidate skips the probe entirely.
     *
     * Not every link is probed. A probe of a debrid link is a full resolve on
     * the addon, and the addon's limits are shared with every device on the
     * same connection — see [ProbeGate]. A link the debrid service has not
     * fetched is not probed at all: resolving one tells the service to start
     * downloading the torrent, and it is ranked last anyway. Nor is a torrent
     * played from its swarm: probing one would start downloading it here.
     */
    private suspend fun ProducerScope<PlayableStream>.sendProbed(
        streams: List<PlayableStream>,
        // Passed in rather than built here, because `candidates` may be running
        // several of these at once and the bounds have to hold across all of
        // them. See where it is constructed.
        gate: ProbeGate,
    ) {
        if (streams.isEmpty()) return

        val startedAt = SystemClock.elapsedRealtime()
        val probes = streams.map { stream ->
            val host = stream.url?.toHttpUrlOrNull()?.host.orEmpty()
            val skip = stream.readiness == SourceReadiness.NOT_CACHED || stream.kind == StreamKind.TORRENT
            if (skip || !gate.claim(host)) {
                CompletableDeferred(Verdict.UNTESTED)
            } else {
                async { gate.withPermit(host) { probe(stream) } }
            }
        }

        val broken = mutableListOf<PlayableStream>()
        val verdicts = mutableListOf<Verdict>()
        for ((stream, probe) in streams.zip(probes)) {
            val verdict = probe.await()
            verdicts += verdict
            if (verdict == Verdict.BROKEN) broken += stream else send(stream)
        }
        broken.forEach { send(it) }
        Log.i(
            TAG,
            "probed ${streams.size} links in ${SystemClock.elapsedRealtime() - startedAt}ms: " +
                verdicts.groupingBy { it }.eachCount().entries.joinToString { "${it.value} ${it.key}" },
        )
    }

    /**
     * A torrent's link is the torrent engine's own address, which is what makes it
     * playable. Without the engine (it is only missing in tests) it stays unplayable.
     */
    private fun withTorrentUrl(stream: PlayableStream): PlayableStream {
        if (stream.kind != StreamKind.TORRENT) return stream
        val hash = stream.infoHash ?: return stream
        return stream.copy(url = TorrentStreaming.url(hash, stream.fileIndex, stream.trackers, stream.filename))
    }

    /** What a probe learned. Only [BROKEN] moves a link down. */
    private enum class Verdict {
        WORKS,
        BROKEN,

        /** The addon would not resolve it right now, which says nothing about the link. */
        REFUSED,

        /** Not probed: see [sendProbed]. */
        UNTESTED,
    }

    private suspend fun probe(stream: PlayableStream): Verdict {
        val url = stream.url ?: return Verdict.BROKEN
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-1")
                    .apply { stream.headers.forEach { (key, value) -> header(key, value) } }
                    .build()
                probeClient.newCall(request).execute().use { response ->
                    // Torrentio's nginx answers 429 once its resolve route is busy.
                    if (response.code == 429) return@use Verdict.REFUSED
                    if (response.code >= 400) return@use Verdict.BROKEN

                    // A removed link often returns HTTP 200 with an HTML/JSON
                    // notice. Treating only the status as validation is what
                    // let those through as healthy, so the cascade ranked a
                    // dead mirror above a working one.
                    val contentType = response.header("Content-Type")
                        ?.substringBefore(';')
                        ?.trim()
                        ?.lowercase()
                    if (contentType == "text/html" || contentType == "application/json") {
                        return@use Verdict.BROKEN
                    }

                    // Progressive removal notices may still be real videos.
                    // Their actual file is tiny compared with the size the
                    // addon advertised for the film, and a range response
                    // exposes that total without downloading the file.
                    val expectedBytes = stream.sizeBytes
                    val actualBytes = response.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                        ?: response.body.contentLength().takeIf { response.code == 200 && it > 0 }
                    val adaptiveManifest = contentType?.contains("mpegurl") == true ||
                        contentType == "application/dash+xml" ||
                        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                        url.substringBefore('?').endsWith(".mpd", ignoreCase = true)
                    val plausible = adaptiveManifest || expectedBytes == null || actualBytes == null ||
                        actualBytes >= expectedBytes * MIN_EXPECTED_SIZE_FRACTION
                    if (plausible) Verdict.WORKS else Verdict.BROKEN
                }
            }.getOrDefault(Verdict.BROKEN)
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
                languages = "pt-br,en,hr,sr,bs",
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

        listOf(
            "pb" to "pob",
            "pt" to "por",
            "en" to "eng",
            "hr" to "hrv",
            "sr" to "srp",
            "bs" to "bos",
        )
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

    /**
     * How many probes may run, in total and against one host.
     *
     * The total bounds sockets on a set-top box. The per-host limits exist
     * because of what a probe of a debrid link is: the addon resolving it with
     * the debrid service, which is the one expensive thing the addon does.
     * Measured on Torrentio with sixteen at once, its nginx answered 429 to
     * most of the next twenty, and Real-Debrid answered some that did get
     * through with Torrentio's "failed" notice instead of the file. Each of
     * those pushed a working link down the order, and the player's own request,
     * sent alongside them, met the same limit. That limit is per public IP, so
     * every other device on the same connection spends from it too.
     *
     * So more probes at once made the start slower, not faster. Two at a time
     * per host keeps the next candidates checked ahead of a cascade that takes
     * seconds per attempt. Past [PROBES_PER_HOST] links on one host, the
     * cascade reaching them is itself the test.
     */
    private class ProbeGate {
        private val total = Semaphore(PROBE_CONCURRENCY)
        private val perHost = ConcurrentHashMap<String, Semaphore>()
        private val claimed = ConcurrentHashMap<String, Int>()

        /** False once [host] has had its share of probes for this title. */
        fun claim(host: String): Boolean =
            claimed.merge(host, 1, Int::plus)!! <= PROBES_PER_HOST

        suspend fun <T> withPermit(host: String, block: suspend () -> T): T =
            // The host's permit first, so a probe waiting on its host does not
            // hold one of the shared ones while it waits.
            perHost.computeIfAbsent(host) { Semaphore(PROBES_AT_ONCE_PER_HOST) }
                .withPermit { total.withPermit { block() } }
    }

    private companion object {
        const val TAG = "Playback"

        /**
         * Long enough for a real mirror to answer two bytes, short enough
         * that a dozen of these in parallel still lands well under what one
         * dead source used to cost alone in the old sequential-only cascade.
         */
        const val PROBE_TIMEOUT_MS = 3_500L
        const val SUBTITLE_TIMEOUT_MS = 15_000L
        const val PROBE_CONCURRENCY = 16

        /** See [ProbeGate]. */
        const val PROBES_AT_ONCE_PER_HOST = 2
        const val PROBES_PER_HOST = 8

        /**
         * How small a mirror's real file may be, against the size the addon
         * advertised, before the probe stops believing it is the film.
         *
         * Loose on purpose — a quarter. The thing this catches is a removal
         * notice of a few megabytes standing in for a feature of several
         * gigabytes, and nothing legitimate lands anywhere near the line.
         */
        const val MIN_EXPECTED_SIZE_FRACTION = 0.25

        /**
         * How long the first wave is allowed to gather before the best of it
         * is handed over.
         *
         * Long enough that a healthy addon set is almost always complete —
         * they answer in a few hundred milliseconds — and short enough that a
         * single hung host cannot hold the film hostage. Whatever misses the
         * window is not lost, only queued behind.
         *
         * Raised from 900ms: on mobile networks or when debrid addons are
         * cold-starting, the old window expired before a single addon had
         * answered, leaving the first wave empty.
         */
        const val SETTLE_WINDOW_MS = 1_500L
    }
}
