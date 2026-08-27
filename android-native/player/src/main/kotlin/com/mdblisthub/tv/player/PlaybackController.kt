package com.mdblisthub.tv.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.PlaybackHint
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.core.model.SubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a title, deciding on its own which link to use — until it can't.
 *
 * The design rule this class serves: **the user does not pick a source while
 * automatic playback still has a chance.** They press play, and a queue of
 * candidates is walked until one produces a frame. A dead mirror, an expired
 * debrid token or a container the decoder chokes on are all the same event
 * here — move to the next one — and none of them is worth a dialog. Only once
 * every candidate has failed (see [fail]) is the same queue handed to the UI
 * as [PlaybackState.availableSources], so the user can try one by hand via
 * [playManual] — at that point automatic failover has already proven it
 * cannot decide for them.
 *
 * Candidates arrive as a [Flow] rather than a finished [List]: the repository
 * probes every mirror in parallel and hands each one over the moment it is
 * verified, so the first attempt can start while the rest are still being
 * checked. See [tryAdvance] for how the queue grows underneath an
 * already-running cascade.
 *
 * Success is defined as the player reaching `STATE_READY`, not as a command
 * being accepted — a 200-with-an-error-page opens a connection just as
 * readily as a real stream, but only a real one gets demuxed far enough to
 * report tracks and a duration.
 *
 * Every call here happens on the thread this was constructed on (the
 * ViewModel's main thread): ExoPlayer is single-threaded by contract, and
 * `viewModelScope` dispatches to Main, so the coroutines below stay on it.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
    private val scope: CoroutineScope,
    /**
     * The client whose connection pool the mirror probe already warmed.
     *
     * When supplied, the player reads over OkHttp instead of the JDK's
     * `HttpURLConnection`, which is what lets the TCP+TLS handshake
     * `StreamsRepository.isReachable` just paid for be reused rather than
     * repeated — typically a few hundred milliseconds off every start on
     * home Wi-Fi. Null falls back to the built-in stack, so the module still
     * works standalone.
     */
    callFactory: okhttp3.Call.Factory? = null,
) : Player.Listener {

    private val appContext = context.applicationContext

    /**
     * Per-candidate headers land here rather than on the MediaItem: some
     * debrid links 403 without the right user agent or referer, and the
     * factory is re-read on every `prepare`, so mutating it between attempts
     * is what makes the header follow the candidate being tried.
     */
    private val httpDataSourceFactory: HttpDataSource.Factory = callFactory
        ?.let {
            // Timeouts come from the client itself; note that it must not
            // carry a `callTimeout`, since here a single call is the whole
            // film rather than a request for a JSON manifest.
            OkHttpDataSource.Factory(it)
        }
        ?: DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            // Generous on purpose. A debrid endpoint answers the redirect
            // instantly and then leaves the connection open while the file is
            // prepared upstream, which measurably runs past thirty seconds on a
            // cold link; the tighter values these replaced turned that ordinary
            // warm-up into a failed source.
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)

    /**
     * Reads from disk before it reads from the network, when a cache could be
     * opened at all.
     *
     * `FLAG_IGNORE_CACHE_ON_ERROR` matters more here than in most apps: a
     * partially written span from a mirror that died mid-download must never
     * become the reason a *working* mirror cannot play.
     */
    private val upstreamFactory: DataSource.Factory =
        DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory)

    /**
     * Hoisted out of [dataSourceFactory] because [prefetcher] needs the same
     * instance: `SimpleCache` throws if two of them are pointed at one
     * directory, and a prefetcher filling a *different* cache from the one the
     * player reads is worse than no prefetcher at all.
     */
    private val mediaCache = MediaCache.get(context)

    private val dataSourceFactory: DataSource.Factory =
        mediaCache?.let { cache ->
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } ?: upstreamFactory

    /**
     * Keeps the disk cache filled ahead of the playhead — the deep buffer that
     * used to have to live in the Java heap.
     *
     * Null when no cache could be opened, which is the same condition that
     * makes [dataSourceFactory] stream straight through: with nowhere to write,
     * there is nothing to work ahead into.
     */
    private val prefetcher = mediaCache?.let { cache ->
        MediaPrefetcher(
            cache = cache,
            upstreamFactory = upstreamFactory,
            scope = scope,
            maxWindowBytes = (MediaCache.budgetBytes * PREFETCH_CACHE_SHARE).toLong(),
        )
    }

    /**
     * The first line of defence against a source going quiet, and the one that
     * matters most.
     *
     * A read timing out mid-film is a *load* error, and Media3 retries load
     * errors a few times before giving up and escalating to a fatal
     * `PlaybackException`. Retrying far more, with a longer backoff, keeps a
     * mirror that pauses for half a minute from ever becoming "this source is
     * dead" — the buffer drains, the picture waits, and playback resumes on
     * the same link once bytes flow again. Without this, the escalation is
     * what triggered a source switch mid-playback.
     */
    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy(LOAD_RETRY_COUNT) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            // Ramps 1s, 2s, 3s… to a ceiling, instead of the default's much
            // shorter schedule. A stalled CDN is usually back within a minute,
            // and waiting costs nothing but a paused picture.
            val attempt = info.errorCount.coerceAtLeast(1)
            return (attempt * LOAD_RETRY_STEP_MS).coerceAtMost(LOAD_RETRY_MAX_MS)
        }
    }

    private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        .setLoadErrorHandlingPolicy(loadErrorPolicy)

    /**
     * Owned here rather than left to `DefaultLoadControl` so that
     * [AdaptiveLoadControl] can read what the buffer is really consuming.
     */
    private val allocator = DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    private val targetBufferBytes = HeapBudget.targetBufferBytes(context.applicationContext)
        // Registered before the player exists, so the first trim notice cannot
        // arrive with nothing listening. Idempotent — one registration per
        // process, however many films are played in it.
        .also { MemoryPressure.install(appContext) }

    val player: ExoPlayer = ExoPlayer.Builder(
        context.applicationContext,
        // Without the fallback, a decoder that fails to *initialise* — routine
        // on cheap boxes for some HEVC and MKV profiles — surfaces as a
        // playback error, and the cascade throws away a link that the
        // secondary decoder would have played perfectly.
        DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
            // Software audio decoding for the formats Android itself often has
            // no decoder for — DTS above all, then TrueHD and E-AC3, which is
            // what a Portuguese dub on a remux is usually encoded in.
            //
            // `_ON`, never `_PREFER`: extension renderers go *after* the
            // MediaCodec ones, so hardware decoding and HDMI passthrough to a
            // receiver both keep winning when they are available. FFmpeg is
            // reached only where the alternative was silence. The renderer is
            // found by name through reflection, so this line is inert if the
            // `lib-decoder-ffmpeg` AAR is ever dropped from the build.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
    )
        .setMediaSourceFactory(mediaSourceFactory)
        // The whole reason this app moved off mpv. mpv buffers by byte count,
        // which on a mirror that trickles is a fixed and often far-too-small
        // amount of *time*; this budgets in seconds instead, so a slow source
        // simply takes longer to fill 30s than a fast one rather than
        // stuttering through playback.
        .setLoadControl(
            AdaptiveLoadControl(
                delegate = DefaultLoadControl.Builder()
                    .setAllocator(allocator)
                    .setBufferDurationsMs(
                        // Both raised a lot. These are the numbers that decide
                        // how long a source may go quiet before the picture
                        // stops, and the old 30s/120s left almost no cushion on
                        // a mirror that trickles — which is the normal case
                        // here, not the exceptional one.
                        /* minBufferMs = */ 120_000,
                        /* maxBufferMs = */ 600_000,
                        // These two are Media3's own defaults, and that is the
                        // point. They used to be 2_500/5_000, justified by a
                        // comment claiming the default was 500ms — it is 1_000
                        // (`DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS`).
                        // So the old values were not caution, they were an
                        // extra 1.5s on every start and 3s on every rebuffer,
                        // paid for a benefit that was never being missed.
                        /* bufferForPlaybackMs = */ DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        // Above the 2s default, but no longer by the same
                        // amount everywhere — see the function, which drops it
                        // on constrained boxes. Refilling 8s of a high-bitrate
                        // remux over a Fire TV Stick's radio can outlast the
                        // outage it is recovering from, and the picture stays
                        // stopped for the whole of it. Only this one varies;
                        // `bufferForPlaybackMs` stays at the default so the
                        // *first* frame is still fast.
                        /* bufferForPlaybackAfterRebufferMs = */
                        HeapBudget.bufferForPlaybackAfterRebufferMs(context.applicationContext),
                    )
                    // `DefaultAllocator` takes its `byte[]` from the *Java*
                    // heap, not native memory, so this is bounded by what the
                    // process has free rather than by what the device has —
                    // see `HeapBudget`, which measures instead of assuming.
                    .setTargetBufferBytes(targetBufferBytes)
                    // Left at the default of zero here and supplied by the
                    // wrapper instead: as a constant it cannot stay in
                    // proportion to the byte budget, and a back buffer out of
                    // proportion is what starves the forward one.
                    .setBackBuffer(
                        /* backBufferDurationMs = */ 0,
                        /* retainBackBufferFromKeyframe = */ true,
                    )
                    .build(),
                allocator = allocator,
                targetBufferBytes = targetBufferBytes,
            ),
        )
        // Without this nothing pauses the film when another app takes the
        // audio, and two things talk over each other until one is closed.
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()
        .apply {
            // The screen going off must not take playback with it. The view
            // keeps the screen awake while something is running (see
            // `ExoVideoSurface`); this covers the window between that and the
            // CPU being allowed to suspend.
            setWakeMode(C.WAKE_MODE_NETWORK)
            // The OSD only ever seeks in ten-second steps, where landing on
            // the exact frame is worth nothing and the cost is real: an exact
            // seek in a progressive MKV has to fetch back to the preceding
            // sync sample before it can show anything, which is what made
            // every skip feel like the remote had stopped responding.
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            addListener(this@PlaybackController)
        }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /**
     * The playhead, published apart from [state] — see [PlaybackPosition] for
     * why. Nothing but the seek bar reads it, and it changes on a timer.
     */
    private val _position = MutableStateFlow(PlaybackPosition())
    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    /**
     * The external subtitle line on screen right now, or null in a gap.
     *
     * Its own flow for the same reason as [position], and with more to gain:
     * this changes once per line of dialogue, which through [state] meant the
     * entire player screen recomposing every couple of seconds for the length
     * of a conversation.
     */
    private val _activeSubtitleCue = MutableStateFlow<String?>(null)
    val activeSubtitleCue: StateFlow<String?> = _activeSubtitleCue.asStateFlow()

    /**
     * Candidates tried so far, in rank order. Append-only: [candidatesJob]
     * grows it as the repository's probe flow delivers verified mirrors,
     * which may still be happening while [tryAdvance] is already walking the
     * front of it.
     */
    private var queue: MutableList<PlayableStream> = mutableListOf()
    private var queueIndex = -1
    private var passes = 0

    /** True while [candidatesJob] might still deliver more candidates. */
    private var candidatesCollecting = false

    /**
     * True when [tryAdvance] ran off the end of a queue that might still
     * grow. The next candidate to arrive — or the flow finishing, settling
     * that no more are coming — is what resumes it.
     */
    private var awaitingCandidate = false
    private var candidatesJob: Job? = null

    /**
     * True once the user has picked a source by hand, via [playManual].
     *
     * The automatic cascade's whole job is to fail through candidates
     * silently and only bother the user once none of them work; once they
     * are choosing themselves, a failure has to say so plainly instead of
     * quietly trying something else in its place, so every failure path
     * checks this before falling back to [tryAdvance].
     */
    private var manualMode = false

    /** Resume point, applied once a source is actually ready. */
    private var resumePercent: Float? = null
    private var resumeApplied = false

    /**
     * The resume point as a position rather than a percentage, when this app
     * has played the title before — see [PlaybackHint].
     *
     * It is what lets [open] place the very first range request on the frame
     * the viewer left, instead of near it: the percentage has to be turned
     * back into a position using the *metadata* runtime, which disagrees with
     * the file by minutes often enough that the correction in [onReady] used
     * to fire on almost every resume, and each of those is a rebuffer.
     */
    private var resumeExactMs: Long? = null

    /**
     * The release that played last time, if the cascade should try it first.
     *
     * The name, never the URL: debrid links are signed and expire, which is
     * the same reason the cache is keyed by filename in [open]. Matching one
     * means resuming into a disk cache that is already warm around the resume
     * point, with the audio and subtitle tracks the viewer picked still in
     * place — and, when the first-ranked candidate happens to be dead, without
     * paying for it.
     */
    private var preferredFilename: String? = null

    /**
     * True while the first attempt is being held back for [preferredFilename].
     *
     * Bounded by [PREFERRED_GRACE_MS], because a preference is worth a short
     * wait and nothing more: if the release is gone, the cascade has to get on
     * with its job.
     */
    private var awaitingPreferred = false
    private var preferredWatch: Job? = null

    /**
     * How long the title is supposed to run, from the metadata — the only
     * thing that makes a decoy recognisable. Null when unknown, which
     * disables the check rather than guessing.
     */
    private var expectedRuntimeMs: Long? = null

    /**
     * Candidates already unmasked as decoys, by queue position.
     *
     * Kept because the cascade walks the queue twice: without this, the
     * second pass would cheerfully re-open the same "this file was removed"
     * clip that was just rejected, and the user would watch it start again.
     */
    private val decoyIndices = mutableSetOf<Int>()

    private var watchdog: Job? = null
    private var ticker: Job? = null

    /**
     * Supervises a source that is *already playing*. [watchdog] deliberately
     * stops at the moment this one starts — see [startStallWatch].
     */
    private var stallWatch: Job? = null

    /**
     * When the loader was last sent to fetch from a *new* offset — the
     * viewer's seek, the resume correction in [onReady], or [softReconnect]'s
     * own.
     *
     * [startStallWatch] reads it so that the silence a seek necessarily causes
     * is never mistaken for a dead host. A seek closes the connection that was
     * feeding the buffer and opens another one at a different byte offset, and
     * on a cold debrid link that round trip measurably runs past ten seconds —
     * which is exactly the window the stall watch used to fire in, tearing
     * down a link whose only crime was being asked for a different part of the
     * file.
     */
    private var lastSeekAtMs = 0L

    /**
     * Set when the connection is *presumed* dead without any error saying so.
     *
     * The one case that produces it: a film paused for long enough that the
     * socket has been sitting idle. ExoPlayer does not close a connection when
     * the buffer fills — it stops reading and leaves it open — so a pause of a
     * couple of minutes hands the host, and every NAT between here and it,
     * ample time to drop a socket nobody is using. Nothing reports that. The
     * picture comes back, plays out whatever was buffered, and only then does
     * the next read begin waiting out a thirty-second timeout on a socket that
     * will never answer.
     *
     * Cleared by the first byte that moves the buffer, which is proof the
     * connection survived after all — so the suspicion costs nothing when it
     * is wrong, and saves most of the freeze when it is right.
     */
    private var connectionSuspect = false

    /** Elapsed-realtime stamp of the last [pause]; read once, by [resume]. */
    private var pausedAtMs = 0L

    /**
     * When [play] was called, and when the candidate now open was opened.
     *
     * Both exist for [TAG] alone. Startup here is a cascade, not a single
     * request, and the only honest way to know whether it is slow because the
     * addons are slow or because four dead mirrors were walked first is to
     * record which candidate was opened, why it was abandoned, and how long
     * each of those took.
     */
    private var playStartedAtMs = 0L
    private var attemptStartedAtMs = 0L

    /**
     * Where a burst of skips has walked to, before any of it has been asked of
     * the engine — see [nudge].
     */
    private var pendingSeekTargetMs: Long? = null
    private var pendingSeekJob: Job? = null

    /** How many skips this burst has taken, which is what grows the step. */
    private var nudgeCount = 0

    /** Set by the screen; see [setOsdVisible]. Starts true, since the OSD does. */
    private var osdVisible = true

    /**
     * The source that has actually produced a picture, if any.
     *
     * Non-null means the cascade is over and this link is the one being
     * watched — see [onPlayerError], which reconnects to it rather than
     * moving on. Cleared whenever a new playback starts, so it can never leak
     * across titles.
     */
    private var committedStream: PlayableStream? = null
    private var committedRetries = 0

    /** Position/intention carried while a failed source is replaced. */
    private var failoverPositionMs: Long? = null
    private var failoverPlayWhenReady: Boolean? = null

    /**
     * The external subtitle, held as parsed cues rather than a URL Media3
     * side-loads. Owning it here — instead of it being part of the
     * `MediaItem` — is what makes [adjustSubtitleOffset] instant: nothing
     * about the video needs to change for the captions drawn over it to move.
     * It also means a subtitle file can never be the reason a video candidate
     * gets abandoned, since the engine never learns it exists.
     */
    private var subtitleTrack: SubtitleTrack? = null
    private var subtitleTicker: Job? = null

    /**
     * ExoPlayer addresses tracks by (group, index); the pickers upstream
     * address them by a flat Int. These keep the translation, rebuilt every
     * time the track list changes.
     */
    private var audioOverrides: List<TrackSelectionOverride> = emptyList()
    private var subtitleOverrides: List<TrackSelectionOverride> = emptyList()

    /**
     * The audio track the *user* asked for, as opposed to the one the track
     * selector picked. [recoverFromDecoderFailure] only silences a track when
     * one of these exists: a decoder error on a track nobody chose means the
     * file itself is broken, which is the cascade's problem, not the picker's.
     */
    private var forcedAudioId: Int? = null

    /**
     * The forced track that turned out to have no decoder. Kept so
     * [onTracksChanged] — which recomputes the selection from what the player
     * actually enabled, and would otherwise report "none" — keeps showing the
     * picker the row the user is looking at.
     */
    private var silencedAudioId: Int? = null

    /**
     * Starts the cascade. `resumeAtPercent` is applied to whichever source
     * ends up working, not to the first one tried.
     *
     * `candidates` is consumed as it is produced: the first verified mirror
     * is attempted the moment it arrives rather than after every mirror has
     * been checked, which is what makes opening a title fast instead of
     * waiting on whichever addon or probe answers slowest.
     */
    fun play(
        candidates: Flow<PlayableStream>,
        resumeAtPercent: Float? = null,
        expectedRuntimeMinutes: Int? = null,
        // When true, candidates are still collected in the background but
        // none is opened automatically: the queue is surfaced as
        // PlaybackState.availableSources as it grows, and the caller picks
        // one by hand via [playManual]. See [PlaybackPhase.SELECTING].
        selectManually: Boolean = false,
        /** What this app remembers about the last time it played this title. */
        hint: PlaybackHint? = null,
    ) {
        stopInternal()
        subtitleTrack = null

        queue = mutableListOf()
        queueIndex = -1
        passes = 0
        decoyIndices.clear()
        manualMode = false
        committedStream = null
        committedRetries = 0
        // Per-title, like everything above: the next film's track 2 has nothing
        // to do with this one's, and a stale silence would mute it on sight.
        forcedAudioId = null
        silencedAudioId = null
        expectedRuntimeMs = expectedRuntimeMinutes
            ?.takeIf { it > 0 }
            ?.let { it * 60_000L }
        candidatesCollecting = true
        awaitingCandidate = true
        resumePercent = resumeAtPercent?.takeIf { it > 1f && it < 95f }
        resumeApplied = false

        // Which of the two positions is the later one, not which is the more
        // official one.
        //
        // The note here is refreshed on a timer as well as on every phase
        // change; the provider only hears about phase changes. So a film
        // watched straight through with no pause leaves the note tens of
        // minutes ahead of the provider — measured at 50.6% against 24.9% on
        // one run — and reading that gap as "watched elsewhere" threw away the
        // fresher of the two every time.
        //
        // The provider still wins when it is genuinely *ahead*, because that
        // can only mean another device carried the title further than this one
        // ever did. Tolerance rather than a strict comparison, so the rounding
        // both providers do to one decimal place is not mistaken for progress.
        //
        // The release name is kept whichever way it goes: a file is still the
        // same file wherever it was last watched.
        val storedPercent = hint?.progressPercent
        val syncedPercent = resumePercent
        val stillCurrent = storedPercent != null &&
            (syncedPercent == null || storedPercent >= syncedPercent - HINT_PERCENT_TOLERANCE)
        resumeExactMs = hint?.positionMs?.takeIf { it > 0 && stillCurrent }
        preferredFilename = hint?.sourceFilename?.takeIf { it.isNotBlank() }

        preferredWatch?.cancel()
        awaitingPreferred = preferredFilename != null && !selectManually
        preferredWatch = if (awaitingPreferred) {
            scope.launch {
                delay(PREFERRED_GRACE_MS)
                awaitingPreferred = false
                Log.i(TAG, "preferred source did not arrive in ${PREFERRED_GRACE_MS}ms")
                if (awaitingCandidate && queue.isNotEmpty()) {
                    awaitingCandidate = false
                    tryAdvance()
                }
            }
        } else {
            null
        }

        playStartedAtMs = SystemClock.elapsedRealtime()

        val initialPhase = if (selectManually) PlaybackPhase.SELECTING else PlaybackPhase.RESOLVING
        _state.value = PlaybackState(phase = initialPhase, candidateCount = 0)

        candidatesJob = scope.launch {
            candidates
                .filter { it.playable }
                // A producer failure here would otherwise crash the app over
                // something no worse than "an addon behaved oddly" — the same
                // thing an individual addon failing already tolerates.
                .catch { }
                .collect { stream ->
                    queue += stream

                    // Promoted to *next*, whenever it turns up.
                    //
                    // The grace window above only covers the case where the
                    // release that worked last time is in the first probed
                    // batch. Measurement says it often is not — the batches
                    // land seconds apart — and a candidate that arrives after
                    // the walk has started is still the one most likely to
                    // play, so it jumps the rest of the queue instead of
                    // waiting its turn behind forty others.
                    //
                    // Inserting at `queueIndex + 1` is what keeps the
                    // bookkeeping honest: `decoyIndices` holds indices into
                    // this list, and every one it holds is at or before
                    // `queueIndex`, so nothing already recorded moves.
                    //
                    // Only until something commits: once a source has produced
                    // a picture the cascade is over, and a preference is not a
                    // reason to interrupt a film that is already playing.
                    if (committedStream == null && preferredFilename != null &&
                        stream.filename != null && stream.filename == preferredFilename
                    ) {
                        queue.removeAt(queue.lastIndex)
                        queue.add((queueIndex + 1).coerceIn(0, queue.size), stream)
                        preferredFilename = null
                        awaitingPreferred = false
                        preferredWatch?.cancel()
                        Log.i(TAG, "preferred source promoted to next: ${stream.filename}")
                    }

                    _state.update {
                        it.copy(
                            candidateCount = queue.size,
                            availableSources = if (selectManually) queue.toList() else it.availableSources,
                        )
                    }
                    if (!selectManually && awaitingCandidate && !awaitingPreferred) {
                        awaitingCandidate = false
                        tryAdvance()
                    }
                }
            candidatesCollecting = false
            // Nothing more is coming, so there is nothing left to hold for.
            awaitingPreferred = false
            preferredWatch?.cancel()
            if (selectManually) {
                // Nothing arrived to choose from — that is a failure the same
                // way it is for the automatic cascade, just reached without
                // ever trying to open anything.
                if (queue.isEmpty()) fail(PlaybackFailure.NoCandidates)
            } else if (awaitingCandidate) {
                awaitingCandidate = false
                tryAdvance()
            }
        }
    }

    /**
     * Picks the next candidate, waiting on more from the probe flow if the
     * queue has been fully walked but might still grow, and wrapping for a
     * second pass once it is known no more are coming.
     *
     * The queue is walked a second time before failing: a CDN that 403s on the
     * first pass has often minted a fresh token by the time it comes round
     * again, and one more attempt is far cheaper than telling someone to try
     * later.
     */
    private fun tryAdvance() {
        watchdog?.cancel()
        val nextIndex = queueIndex + 1

        if (nextIndex >= queue.size) {
            if (candidatesCollecting) {
                awaitingCandidate = true
                _state.update { it.copy(phase = PlaybackPhase.RESOLVING) }
                return
            }
            if (queue.isEmpty()) {
                fail(PlaybackFailure.NoCandidates)
                return
            }
            passes++
            if (passes >= MAX_PASSES) {
                fail(PlaybackFailure.AllCandidatesFailed(queue.size))
                return
            }
            queueIndex = -1
            tryAdvance()
            return
        }

        queueIndex = nextIndex
        if (queueIndex in decoyIndices) return tryAdvance()

        val candidate = queue[queueIndex]
        if (candidate.url == null) return tryAdvance()

        _state.update {
            it.copy(
                phase = PlaybackPhase.RESOLVING,
                attempt = queueIndex + 1,
                candidateCount = queue.size,
                error = null,
                activeStream = candidate,
            )
        }

        open(candidate)
        watchdog = scope.launch { watchAttempt() }
    }

    /**
     * Hands one candidate to the engine, at the position it should start from.
     *
     * Starting *at* the resume point rather than seeking to it after the fact
     * is the difference between one buffering round and two: the old code
     * prepared at zero, waited for `STATE_READY`, and only then sought — which
     * threw away every byte it had just buffered and re-opened the connection
     * at a new offset. The runtime from the metadata is close enough to place
     * the range request correctly; [onReady] trims the remaining seconds once
     * the real duration is known.
     */
    private fun open(stream: PlayableStream) {
        val url = stream.url ?: return

        attemptStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "open #${queueIndex + 1}/${queue.size} at +${sincePlayMs()}ms " +
                "addon=${stream.addon} quality=${stream.quality} " +
                "size=${stream.sizeBytes} file=${stream.filename}",
        )

        // Stopped before the headers move, not after. The prefetcher builds its
        // requests from the same shared factory, so a chunk still in flight
        // when this line runs would finish under the *next* candidate's headers
        // — and write whatever that host returned into the previous file's
        // cache key.
        prefetcher?.stop()

        // Replaced wholesale rather than merged: the factory is shared across
        // every attempt, so a header the previous mirror needed would
        // otherwise leak into this one's request.
        httpDataSourceFactory.setDefaultRequestProperties(stream.headers)

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            // Debrid links are signed and expire, so the URL is useless as a
            // cache identity — the same file comes back tomorrow under a
            // different query string and would miss every time. The release
            // filename is the one stable thing an addon reliably reports.
            // Without one, the URL remains the key, which still serves a
            // backward seek inside the current session.
            .setCustomCacheKey(stream.filename?.takeIf { it.isNotBlank() })
            .build()

        val startPositionMs = failoverPositionMs ?: estimatedResumePositionMs()
        if (startPositionMs != null) {
            player.setMediaItem(mediaItem, startPositionMs)
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        player.playWhenReady = failoverPlayWhenReady ?: true
    }

    /**
     * Where to open the file, from the metadata runtime — null when either the
     * runtime or the resume point is unknown, in which case playback starts at
     * the beginning and [onReady] does the seek the old way.
     */
    private fun estimatedResumePositionMs(): Long? {
        if (resumeApplied) return null
        // An exact position needs no estimating, and is the whole point of
        // keeping one — see [resumeExactMs].
        resumeExactMs?.let { return it }
        val percent = resumePercent ?: return null
        val runtimeMs = expectedRuntimeMs ?: return null
        return (runtimeMs * percent / 100f).toLong().coerceAtLeast(0L)
    }

    /**
     * Plays exactly the source the user picked, bypassing the cascade
     * entirely — called once [PlaybackPhase.FAILED] has already offered up
     * [PlaybackState.availableSources] and the user tapped one of them.
     *
     * [queue] and [expectedRuntimeMs] are left untouched: the list the veil
     * is showing came from `queue`, and re-failing has to be able to show it
     * again, while a decoy is still a decoy regardless of who chose it.
     */
    fun playManual(stream: PlayableStream) {
        watchdog?.cancel()
        ticker?.cancel()
        candidatesJob?.cancel()
        candidatesCollecting = false
        awaitingCandidate = false
        manualMode = true
        committedStream = null
        committedRetries = 0

        if (stream.url == null) {
            fail(PlaybackFailure.ManualNoLink)
            return
        }

        _state.update {
            // attempt/candidateCount reset to 0 rather than left at whatever
            // the cascade last set them to: the veil reads "N de M" off them,
            // and a leftover "6 de 42" from the automatic pass would claim
            // this one pick is somehow the sixth of forty-two.
            it.copy(
                phase = PlaybackPhase.RESOLVING,
                attempt = 0,
                candidateCount = 0,
                error = null,
                activeStream = stream,
            )
        }

        open(stream)
        watchdog = scope.launch { watchAttempt() }
    }

    /**
     * Decides when a candidate has had long enough, by watching whether it is
     * actually downloading rather than by a stopwatch alone.
     *
     * A flat deadline gets this wrong in both directions: a cold debrid link
     * can spend ten seconds minting and redirecting before a single byte
     * moves, while a host that accepted the connection and then went silent
     * will happily hold the screen for the whole timeout. So a source is
     * abandoned once its buffer has stopped growing for [ATTEMPT_STALL_MS] —
     * which catches the dead one sooner than the old fixed 12s did — and a
     * source that *is* still pulling data is given up to [ATTEMPT_HARD_CAP_MS]
     * to produce a frame.
     */
    private suspend fun watchAttempt() {
        var bestBuffered = 0L
        var everDelivered = false
        var stalledMs = 0L
        var waitedMs = 0L

        while (currentCoroutineContext().isActive) {
            delay(WATCHDOG_POLL_MS)
            waitedMs += WATCHDOG_POLL_MS

            val buffered = player.totalBufferedDuration
            if (buffered > bestBuffered) {
                bestBuffered = buffered
                everDelivered = true
                stalledMs = 0L
            } else {
                stalledMs += WATCHDOG_POLL_MS
            }

            // Stall detection deliberately does not start until the source
            // has delivered something. A debrid link is commonly cold on
            // first use: the endpoint 302s immediately and then spends tens
            // of seconds having the file prepared upstream before a single
            // byte arrives, and nothing about that is distinguishable from a
            // dead host by buffer level alone. Meanwhile a genuinely dead
            // host is not this loop's job — it fails its connect or read
            // timeout and arrives at `onPlayerError` on its own, faster than
            // any deadline here. This is only the backstop for a host that
            // accepts a connection and then goes quiet forever.
            val dead = everDelivered && stalledMs >= ATTEMPT_STALL_MS
            if (dead || waitedMs >= ATTEMPT_HARD_CAP_MS) {
                logAttemptEnd(if (dead) "stalled" else "hard-cap")
                if (manualMode) {
                    fail(PlaybackFailure.ManualUnresponsive)
                } else {
                    tryAdvance()
                }
                return
            }
        }
    }

    // ------------------------------------------------------------ listener

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> onReady()
            Player.STATE_ENDED -> onEnded()
            Player.STATE_BUFFERING -> _state.update {
                // A buffer stall mid-playback is worth showing; one during the
                // cascade is not, since the veil is already up.
                if (it.phase == PlaybackPhase.RESOLVING) it else it.copy(phase = PlaybackPhase.BUFFERING)
            }
            else -> Unit
        }
    }

    /**
     * Any playback error is a dead source, not a dead playback.
     *
     * This is the single biggest simplification over the mpv engine: mpv's
     * `end-file` fired identically whether a stream died or finished, so the
     * old code had to guess from how far the position was from the runtime.
     * ExoPlayer reports failures here and completion as `STATE_ENDED`, so the
     * two cases never need telling apart.
     *
     * A subtitle can no longer be the cause: it is drawn by this app, not fed
     * to the engine, so nothing about it is part of what just failed.
     */
    override fun onPlayerError(error: PlaybackException) {
        // Before anything else, because everything below assumes the *source*
        // is what went wrong. A track the user forced onto a decoder that
        // cannot open it fails here too, and reading that as a dead mirror is
        // how picking an audio track used to restart the film from a different
        // source. See [recoverFromDecoderFailure].
        if (recoverFromDecoderFailure(error)) return

        Log.w(
            TAG,
            "error code=${error.errorCodeName} committed=${committedStream != null} " +
                "after ${SystemClock.elapsedRealtime() - attemptStartedAtMs}ms",
        )
        rememberPlaybackForFailover()

        // A source that has already produced a picture is never swapped out.
        //
        // This is the rule that was missing. The cascade exists to find *a*
        // working link, and once one is playing its job is done — but this
        // handler kept treating every error the same, so a mirror that went
        // quiet for a moment mid-film was abandoned and the next candidate
        // opened in its place. From the sofa that is the film restarting on a
        // different-quality source for no visible reason.
        //
        // Committed sources are reopened at the same position instead, as many
        // times as it takes, because the overwhelmingly likely cause is a
        // network hiccup that will pass.
        if (committedStream != null) {
            reopenCommitted()
            return
        }

        if (manualMode) {
            fail(PlaybackFailure.ManualFailed)
        } else {
            tryAdvance()
        }
    }

    /**
     * Handles the one failure that is about a *track* rather than about the
     * link: the audio the user asked for has no decoder on this box.
     *
     * The film keeps playing, silently, and the picker keeps showing which
     * track is the silent one. That is the whole contract — the alternative
     * this replaces was the cascade tearing the source down and reopening a
     * different mirror, which from the sofa looks like the film restarting
     * because you touched the audio menu.
     *
     * Only counted as such when it lands on the audio renderer and the error
     * is a decoder one; a decoder error on video is a genuinely broken stream
     * and still belongs to the cascade. `prepare()` is what resumes playback —
     * an [ExoPlaybackException] leaves the player idle — and it picks up at
     * the current position with the audio renderer now disabled.
     *
     * Returns true when it took ownership of [error].
     */
    private fun recoverFromDecoderFailure(error: PlaybackException): Boolean {
        val exo = error as? ExoPlaybackException ?: return false
        if (exo.type != ExoPlaybackException.TYPE_RENDERER) return false
        // No `rendererType` on this exception, only the `Format` the failing
        // renderer was fed — the MIME prefix is the only way back to "was
        // this the audio renderer" from here.
        if (exo.rendererFormat?.sampleMimeType?.startsWith("audio/") != true) return false

        val decoderFailure = error.errorCode in DECODER_ERROR_CODES
        if (!decoderFailure) return false

        // Nothing to fall back to if the box could not decode the track it
        // chose by itself — that is a broken file, not a user's forced pick.
        val forced = forcedAudioId ?: return false

        silencedAudioId = forced
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        _state.update { it.copy(currentAudioId = forced, audioSilenced = true, error = null) }
        player.prepare()
        return true
    }

    /**
     * Re-prepares the source that was already playing, where it left off.
     *
     * Deliberately generous: [COMMITTED_RETRY_LIMIT] attempts with the load
     * policy's own backoff underneath means minutes of tolerance before this
     * concedes. Falling back to the cascade at all is a last resort, and only
     * happens when the same link has failed repeatedly rather than once.
     */
    private fun reopenCommitted() {
        val stream = committedStream ?: return

        if (committedRetries >= COMMITTED_RETRY_LIMIT) {
            // The link really is gone, not merely slow. Release the commitment
            // so the cascade can do its job again.
            committedStream = null
            committedRetries = 0
            if (manualMode) fail(PlaybackFailure.ManualFailed) else tryAdvance()
            return
        }

        committedRetries++
        Log.w(TAG, "reopening committed source, retry $committedRetries")
        _state.update { it.copy(phase = PlaybackPhase.BUFFERING, error = null) }
        open(stream)
    }

    override fun onTracksChanged(tracks: Tracks) {
        val audio = collectTracks(tracks, C.TRACK_TYPE_AUDIO)
        val text = collectTracks(tracks, C.TRACK_TYPE_TEXT)

        audioOverrides = audio.map { it.second }
        subtitleOverrides = text.map { it.second }

        _state.update {
            it.copy(
                audioTracks = audio.map { entry -> entry.first },
                subtitleTracks = text.map { entry -> entry.first },
                // A silenced track is enabled nowhere in the player, so the
                // selection read back from it is "none". Reporting that would
                // bounce the picker's tick off the row the user just chose and
                // onto "no track", which reads as the choice not having taken.
                currentAudioId = silencedAudioId
                    ?: audio.indexOfFirst { entry -> entry.third },
                currentSubtitleId = text.indexOfFirst { entry -> entry.third },
            )
        }
    }

    /** Flattens ExoPlayer's group/index tracks into `(info, override, selected)`. */
    private fun collectTracks(
        tracks: Tracks,
        trackType: Int,
    ): List<Triple<TrackInfo, TrackSelectionOverride, Boolean>> {
        val out = mutableListOf<Triple<TrackInfo, TrackSelectionOverride, Boolean>>()

        for (group in tracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val index = out.size

                out += Triple(
                    // Only what the container actually declares. Composing a
                    // display name here would bake a language into the engine
                    // — the label a file carries is already in whatever
                    // language its author chose, and the fallback for a track
                    // that has neither label nor language code is a sentence,
                    // which belongs in the UI.
                    TrackInfo(
                        id = index,
                        label = format.label,
                        language = format.language,
                        mimeType = format.sampleMimeType,
                        channelCount = format.channelCount.takeIf { it != Format.NO_VALUE },
                        // A format that merely *exceeds* a decoder's limits
                        // still counts as playable: the decoder exists, and
                        // forcing the override is how someone feeding an AVR
                        // gets the track their setup can actually handle.
                        // Only FORMAT_UNSUPPORTED_* — no decoder at all — is
                        // off limits, and even that is listed rather than
                        // hidden. See [TrackInfo.playable].
                        playable = group.isTrackSupported(
                            i,
                            /* allowExceedsCapabilities = */ true,
                        ),
                    ),
                    TrackSelectionOverride(group.mediaTrackGroup, i),
                    group.isTrackSelected(i),
                )
            }
        }
        return out
    }

    private fun onReady() {
        if (isDecoy()) return rejectDecoy()

        watchdog?.cancel()
        watchdog = null

        // Reaching READY is what commits the app to this link: from here on an
        // error means "reconnect to this", never "try a different one". The
        // retry counter resets on every successful ready, so a film that
        // hiccups once an hour never exhausts its allowance.
        if (committedStream == null) {
            Log.i(
                TAG,
                "playing #${queueIndex + 1} after ${sincePlayMs()}ms " +
                    "(attempt took ${SystemClock.elapsedRealtime() - attemptStartedAtMs}ms)",
            )
        }
        committedStream = _state.value.activeStream
        committedRetries = 0

        // `resumeApplied` is set *inside* the branch, not before it. Setting it
        // unconditionally meant that a first READY arriving before the
        // duration was demuxed — which happens — permanently consumed the
        // resume point without ever using it, and the film silently restarted
        // from the beginning.
        val duration = player.duration
        val percent = resumePercent
        val exactMs = resumeExactMs
        if (!resumeApplied && duration > 0 && (exactMs != null || percent != null)) {
            // An exact position placed the range request itself, so there is
            // nothing to correct — unless it does not fit this file at all,
            // which means the cascade landed on a different, shorter release
            // than the one the position was measured in. Then the percentage
            // is the only thing that still means something.
            val exactFits = exactMs != null && exactMs < duration - RESUME_TOLERANCE_MS
            if (!exactFits && percent != null) {
                val corrected = (duration * percent / 100f).toLong().coerceIn(0L, duration)
                // The metadata runtime already placed the range request close
                // to here (see `open`), so this is a correction, not a seek.
                // Skipping it when it would move by less than a few seconds
                // avoids paying for a rebuffer to fix an imperceptible error.
                if (kotlin.math.abs(corrected - player.currentPosition) > RESUME_TOLERANCE_MS) {
                    Log.i(TAG, "resume correction to ${corrected}ms (was ${player.currentPosition}ms)")
                    noteSeek()
                    player.seekTo(corrected)
                }
            }
            resumeApplied = true
        }

        _state.update {
            it.copy(
                phase = if (player.isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED,
                error = null,
            )
        }
        _position.update { it.copy(durationMs = player.duration.coerceAtLeast(0)) }
        failoverPositionMs = null
        failoverPlayWhenReady = null
        updatePrefetch()
        startTicker()
        startStallWatch()
    }

    /**
     * Points the prefetcher at the committed source and tells it where the
     * playhead is.
     *
     * Called from [onReady] and from every position tick, because neither is
     * sufficient alone: `STATE_READY` is where a source becomes committed but
     * can arrive before the duration is demuxed, and the tick has the duration
     * but no idea when the source changed. `MediaPrefetcher.start` is a no-op
     * once it is already running for the same file, so calling it on every tick
     * costs a string comparison.
     *
     * Reads `player` on the main thread only; the prefetcher gets plain values.
     */
    private fun updatePrefetch() {
        val prefetcher = prefetcher ?: return
        val stream = committedStream ?: return
        val url = stream.url ?: return
        prefetcher.start(
            uri = url.toUri(),
            // Must match what `open` puts on the MediaItem, or the two write
            // to different keys and every prefetched byte is downloaded twice.
            cacheKey = stream.filename?.takeIf { it.isNotBlank() },
            durationMs = player.duration,
        )
        prefetcher.onPosition(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playerBufferedMs = player.totalBufferedDuration,
            playerLoading = player.isLoading,
        )
    }

    /**
     * `canShowVideo` does not include ENDED, so without this the OSD and its
     * "voltar" button disappear along with the video the instant a film
     * finishes, leaving a black screen with no way off it but the physical
     * back button. Sitting on FAILED-with-no-error-styled-as-ended is what
     * keeps a "Voltar" reachable by the same D-pad path as a real failure.
     */
    private fun onEnded() {
        // A decoy can reach here rather than READY: carrying a resume position
        // from a previous source seeks straight past the end of a two-minute
        // clip. Announcing "Fim" for a film nobody watched is the worst
        // possible reading of that, so it is checked here too.
        if (isDecoy()) return rejectDecoy()

        watchdog?.cancel()
        watchdog = null
        ticker?.cancel()
        ticker = null
        stallWatch?.cancel()
        stallWatch = null
        // Nothing left to work ahead of. Without this the loop survives the
        // film, polling every couple of seconds against a window that is
        // permanently full, for as long as the ENDED screen is up.
        prefetcher?.stop()
        _state.update { it.copy(phase = PlaybackPhase.ENDED) }
    }

    /**
     * Whether what just opened is too short to be the title that was asked
     * for — the "this file has been removed" clip a lot of dead mirrors serve
     * in place of the film, which opens and plays perfectly and is the one
     * failure the source cascade cannot otherwise see. Every other kind of
     * dead source refuses to open; this one succeeds at being the wrong
     * thing.
     *
     * Both conditions have to hold. The fraction alone would throw away a
     * good file whenever the metadata's runtime is wrong, which happens; the
     * absolute cap alone would throw away genuinely short titles. Together
     * they only ever reject something that is both far shorter than expected
     * and short in its own right, which is exactly the decoy and very little
     * else.
     */
    private fun isDecoy(): Boolean {
        val expected = expectedRuntimeMs ?: return false
        val duration = player.duration
        if (duration <= 0) return false
        return duration < expected * DECOY_MAX_FRACTION && duration < DECOY_ABSOLUTE_CAP_MS
    }

    /** Drops the current candidate and moves on, without disturbing the resume point. */
    private fun rejectDecoy() {
        watchdog?.cancel()
        watchdog = null

        if (manualMode) {
            fail(PlaybackFailure.ManualDecoy)
            return
        }

        // The two numbers the verdict was reached on, because "decoy" on its
        // own cannot be told apart from "the duration had not been demuxed
        // yet" — and the second would be rejecting perfectly good files.
        logAttemptEnd("decoy duration=${player.duration} expected=$expectedRuntimeMs")
        decoyIndices += queueIndex

        // `resumeApplied` is deliberately left alone: nothing was watched, so
        // whatever resume point the user had is still owed to the next
        // candidate that turns out to be the real film.
        tryAdvance()
    }

    private fun fail(failure: PlaybackFailure) {
        watchdog?.cancel()
        ticker?.cancel()
        stallWatch?.cancel()
        prefetcher?.stop()
        runCatching { player.stop() }
        _state.update {
            it.copy(phase = PlaybackPhase.FAILED, error = failure, availableSources = queue.toList())
        }
    }

    /**
     * Whether anything on screen is actually reading the position.
     *
     * The screen reports this rather than the controller guessing, because
     * "is the OSD up" is a UI fact. It is the difference between polling twice
     * a second for two hours and polling only while someone is looking.
     */
    fun setOsdVisible(visible: Boolean) {
        if (osdVisible == visible) return
        osdVisible = visible
        // Coming back from the slow cadence, the stored position can be
        // several seconds stale — refresh before the bar is drawn rather than
        // letting it appear wrong and correct itself a tick later.
        if (visible) publishPosition()
    }

    /**
     * ExoPlayer has no position callback, so the seek bar is polled. The tick
     * also keeps the play/pause phase honest, since pausing through the
     * engine (rather than through this class) is possible.
     *
     * The cadence follows the OSD. Every tick allocates a new
     * `PlaybackState` and wakes every collector of it, so running at the fast
     * rate while nothing displays the position is pure cost — and on a set-top
     * box it is cost paid against the same frame budget the video decode needs.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(if (osdVisible) TICK_MS else IDLE_TICK_MS)
                publishPosition()
            }
        }
    }

    /**
     * Catches the stall that never becomes an error.
     *
     * [watchdog] covers the opposite half of a source's life: it decides
     * whether a candidate is worth waiting for, and [onReady] cancels it
     * precisely because a link that has produced a picture has proven itself.
     * From there until the film ends nothing was watching — and the failure
     * this repairs lives entirely in that gap.
     *
     * A mirror behind a Fire TV Stick's radio rarely *closes* the connection
     * when it gives up; it simply stops sending. Nothing about that is an
     * error, so the engine goes on waiting for a socket that will never
     * deliver another byte, and the only thing that ends the wait is the
     * thirty-second `readTimeout` in `HttpClients.playback` — with the retry
     * ladder in [loadErrorPolicy] stacked after it. From the sofa that is a
     * picture frozen for upwards of half a minute. And the reason seeking
     * "fixes" it is not that the position was wrong: a seek closes the dead
     * socket and opens a new one, which is the one thing a waiting player will
     * not do for itself.
     *
     * So this does it instead, and sooner. A frozen [Player.getBufferedPosition]
     * is the honest signal — a source that is merely slow still advances it,
     * however slowly, while one that has gone quiet cannot move it at all.
     *
     * The repair is a ladder, and the order of its two rungs is the whole
     * point. [softReconnect] goes first: it opens a new socket at the same
     * moment of the film without touching the player's media item, so the
     * decoder, the track selection and the picture on the surface all survive
     * it — from the sofa it is indistinguishable from a moment's buffering.
     * Only when that has not helped does [reopenCommitted] run, and *that* one
     * is a full re-prepare: the surface is reset, every track is reselected,
     * and the link is opened cold. Doing it first, which is what this used to
     * do, is why a pause or a long skip ended in a black screen that looked
     * for all the world like the app had gone looking for a different link.
     */
    private fun startStallWatch() {
        stallWatch?.cancel()
        stallWatch = scope.launch {
            var frozenMs = 0L
            var lastBuffered = C.TIME_UNSET
            var softReconnects = 0

            while (isActive) {
                delay(STALL_POLL_MS)

                // Paused is not stalled, and neither is anything the cascade is
                // still deciding about. `committedStream` is what separates
                // them — without one there is nothing for [reopenCommitted] to
                // reopen, and the attempt watchdog owns that phase anyway.
                val waiting = committedStream != null &&
                    player.playWhenReady &&
                    player.playbackState == Player.STATE_BUFFERING

                val buffered = player.bufferedPosition
                val changed = buffered != lastBuffered
                val comparable = lastBuffered != C.TIME_UNSET

                // A byte demuxed is a socket alive. This is the only thing that
                // clears the suspicion `resume` raises, and it has to be
                // checked whether or not the player is waiting: after a pause
                // the buffer is full, so the proof arrives while the picture is
                // playing happily rather than while it is stalled.
                if (changed && comparable) connectionSuspect = false

                // The ladder is per-stall, not per-film: a source that recovers
                // and plays on has earned a fresh soft attempt the next time.
                if (!waiting) softReconnects = 0

                if (!waiting || changed) {
                    lastBuffered = buffered
                    frozenMs = 0L
                    continue
                }

                frozenMs += STALL_POLL_MS
                // A presumed-dead socket is not worth ten seconds of proof —
                // see [connectionSuspect], which is only ever set when the
                // connection has already been idle long enough to be dropped.
                val threshold = if (connectionSuspect) STALL_SUSPECT_MS else STALL_FROZEN_MS
                if (frozenMs < threshold) continue

                // Nothing arriving is the *expected* state right after a seek,
                // whoever asked for it: the old connection is gone and the new
                // range request has not come back yet. Acting inside this
                // window is how a slow skip turned into a reopen, and a reopen
                // into the cascade. The freeze counter keeps running underneath
                // — this only postpones the response, never forgives it.
                if (SystemClock.elapsedRealtime() - lastSeekAtMs < SEEK_SETTLE_MS) continue

                // Cleared before the repair rather than after: both rungs
                // return long before anything is delivered, and the next poll
                // must not measure staleness against a buffer position that
                // belongs to the connection just abandoned.
                frozenMs = 0L
                lastBuffered = C.TIME_UNSET
                connectionSuspect = false

                if (softReconnects < SOFT_RECONNECT_LIMIT) {
                    softReconnects++
                    softReconnect()
                    continue
                }

                // The link is not merely quiet, it has ignored a reconnection
                // too. The counter inside `reopenCommitted` is what stops one
                // that is genuinely gone from being reopened forever: after
                // COMMITTED_RETRY_LIMIT of them the cascade takes over, which
                // is the right answer once a source has stopped responding
                // that many times in a row.
                softReconnects = 0
                rememberPlaybackForFailover()
                reopenCommitted()
            }
        }
    }

    /**
     * Opens a new socket on the same file, at the same moment of it, without
     * disturbing anything the viewer can see.
     *
     * This is the app doing deliberately what a viewer does by accident when
     * they nudge the remote to "unstick" a frozen film. A seek makes
     * `ProgressiveMediaPeriod` abandon the load in flight — dead socket
     * included — and start another one at the byte offset for the new
     * position, while the renderers, the decoder, the track selection and the
     * last frame on the surface are all left alone. `prepare()` shares none of
     * that: it resets the renderers, which is what puts the shutter up.
     *
     * Two details make it reliable rather than occasionally a no-op:
     *
     * - **The target is past everything buffered**, by a margin far below one
     *   frame. A seek the sample queues can satisfy from memory is served from
     *   memory, and serving it from memory reconnects nothing — which is the
     *   one outcome this must never have. In a starved stall the playhead and
     *   the buffer are at the same place anyway, so nothing is skipped.
     * - **[SeekParameters.EXACT] for this one seek.** The player runs on
     *   `CLOSEST_SYNC` because the OSD's ten-second skips are not worth a
     *   fetch back to the preceding keyframe — but snapping is exactly wrong
     *   here: the nearest sync sample can be *behind* the playhead and still
     *   inside the back buffer, which lands right back in the memory-served
     *   case. Both messages reach the playback thread in the order they are
     *   sent, so restoring it immediately still leaves this seek exact.
     */
    private fun softReconnect() {
        Log.i(TAG, "soft reconnect at ${player.currentPosition}ms")
        val target = maxOf(player.currentPosition, player.bufferedPosition)
            .coerceAtLeast(0L) + RECONNECT_NUDGE_MS
        _state.update { it.copy(phase = PlaybackPhase.BUFFERING, error = null) }
        player.setSeekParameters(SeekParameters.EXACT)
        noteSeek()
        player.seekTo(target)
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }

    /**
     * Records that the loader is about to be sent somewhere new — see
     * [lastSeekAtMs]. Every `player.seekTo` in this class is paired with one
     * of these, because a seek nobody recorded is a stall nobody can explain.
     */
    private fun noteSeek() {
        lastSeekAtMs = SystemClock.elapsedRealtime()
    }

    private fun sincePlayMs(): Long = SystemClock.elapsedRealtime() - playStartedAtMs

    /** One line per candidate the cascade gives up on, with the reason. */
    private fun logAttemptEnd(reason: String) {
        Log.i(
            TAG,
            "drop #${queueIndex + 1} reason=$reason " +
                "after ${SystemClock.elapsedRealtime() - attemptStartedAtMs}ms " +
                "(+${sincePlayMs()}ms since play)",
        )
    }

    private fun publishPosition() {
        updatePrefetch()
        // A burst owns the published position until it commits. Without this
        // the tick would drag the seek bar back to the playhead between
        // presses, and a hold would look like it was fighting the viewer.
        if (pendingSeekTargetMs == null) {
            _position.value = PlaybackPosition(
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.coerceAtLeast(0),
            )
        }
        // Separate from the position above, and that separation is the point:
        // the phase is the same on almost every tick, so this `copy` compares
        // equal and `MutableStateFlow` drops it without waking a single
        // collector. The position lands on its own flow, where only the seek
        // bar is listening.
        _state.update { it.copy(phase = nextPollPhase(it.phase)) }
    }

    private fun nextPollPhase(current: PlaybackPhase): PlaybackPhase = when (current) {
        PlaybackPhase.RESOLVING, PlaybackPhase.FAILED, PlaybackPhase.ENDED, PlaybackPhase.IDLE -> current
        else -> when {
            player.playbackState == Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
            player.isPlaying -> PlaybackPhase.PLAYING
            else -> PlaybackPhase.PAUSED
        }
    }

    // ----------------------------------------------------------- controls

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else resume()
    }

    fun pause() = runCatching {
        pausedAtMs = SystemClock.elapsedRealtime()
        player.pause()
        _state.update { if (it.canShowVideo) it.copy(phase = PlaybackPhase.PAUSED) else it }
    }.let { }

    fun resume() = runCatching {
        // See [connectionSuspect]. A pause long enough for an idle socket to
        // have been dropped is the one moment this app knows more about the
        // connection than the engine does, and saying so here is what lets the
        // stall watch act in seconds rather than waiting out a read timeout it
        // could have predicted.
        if (pausedAtMs != 0L &&
            SystemClock.elapsedRealtime() - pausedAtMs >= PAUSE_SOCKET_IDLE_MS
        ) {
            connectionSuspect = true
        }
        pausedAtMs = 0L
        player.play()
        _state.update { if (it.canShowVideo) it.copy(phase = PlaybackPhase.PLAYING) else it }
    }.let { }

    /**
     * One press of skip-forward/back, which is not one seek.
     *
     * Holding the D-pad does not deliver one key event. The platform
     * auto-repeats it every ~50ms after an initial pause, so a two-second hold
     * used to reach the engine as some thirty separate `seekTo` calls — thirty
     * range requests, each cancelling the one before it, thirty codec flushes,
     * and the read-ahead chunk in flight thrown away thirty times. The skip
     * that felt slowest was the one the viewer asked for most clearly.
     *
     * So a burst is accumulated instead. The target moves on [position]
     * immediately, which is what the seek bar and the time label read, and the
     * engine is only asked once the presses stop for [SEEK_COMMIT_DELAY_MS].
     * Crossing twenty minutes of film costs exactly one range request.
     *
     * The step grows with the burst — see [stepForBurst] — so the same hold
     * that used to walk ten seconds at a time crosses half an hour without the
     * first press losing its precision.
     */
    fun nudge(forward: Boolean) {
        val duration = _position.value.durationMs
        if (duration <= 0) return

        // From the pending target, never from the playhead: the film is still
        // playing underneath a burst, and reading the live position would let
        // playback creep into the arithmetic between presses.
        val base = pendingSeekTargetMs ?: _position.value.positionMs
        val step = stepForBurst(nudgeCount)
        nudgeCount++

        val target = (base + if (forward) step else -step).coerceIn(0, duration)
        pendingSeekTargetMs = target
        _position.update { it.copy(positionMs = target) }

        pendingSeekJob?.cancel()
        pendingSeekJob = scope.launch {
            delay(SEEK_COMMIT_DELAY_MS)
            commitPendingSeek()
        }
    }

    /** Hands the burst's final target to the engine, as a single seek. */
    private fun commitPendingSeek() {
        val target = pendingSeekTargetMs ?: return
        Log.i(TAG, "seek burst of $nudgeCount press(es) committed to ${target}ms")
        clearPendingSeek()
        seekTo(target)
    }

    private fun clearPendingSeek() {
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        pendingSeekTargetMs = null
        nudgeCount = 0
    }

    /**
     * How far one press moves, given how many have already landed in this
     * burst.
     *
     * Fine steps first so a small correction stays a small correction, then
     * coarser ones — the same shape the subtitle-offset slider already uses,
     * and for the same reason: the first half-second of a hold must stay
     * precise, and the fifth second must cover ground.
     */
    private fun stepForBurst(count: Int): Long = when {
        count < NUDGE_FINE_STEPS -> NUDGE_STEP_MS
        count < NUDGE_MEDIUM_STEPS -> NUDGE_STEP_MS * 3
        else -> NUDGE_STEP_MS * 6
    }

    fun seekTo(positionMs: Long) {
        val duration = _position.value.durationMs
        if (duration <= 0) return
        // An absolute seek — the seek bar dragged, a resume correction — ends
        // any burst rather than racing it.
        clearPendingSeek()
        val clamped = positionMs.coerceIn(0, duration)
        noteSeek()
        player.seekTo(clamped)
        // The chunk in flight was fetched for where the viewer just left, and
        // on a slow link it can be tens of seconds of download standing between
        // them and the bytes they now need.
        prefetcher?.invalidate()
        _position.update { it.copy(positionMs = clamped) }
        updateActiveCueNow()
    }

    fun seekBy(deltaMs: Long) = seekTo(_position.value.positionMs + deltaMs)

    /**
     * Walks [SCALE_CYCLE], wrapping — the "esticar"/aspect-ratio button.
     *
     * Only records the choice; the surface reads it and applies the matching
     * resize mode, because the mode belongs to the view, not the engine.
     */
    fun cycleScale() {
        val next = SCALE_CYCLE.getOrElse(SCALE_CYCLE.indexOf(_state.value.scaleType) + 1) { SCALE_CYCLE[0] }
        _state.update { it.copy(scaleType = next) }
    }

    /**
     * Switches audio, including to a track this device may not be able to
     * decode.
     *
     * Nothing is refused here. A track that turns out to be undecodable fails
     * in the renderer and is caught by [recoverFromDecoderFailure], which
     * leaves the film playing without sound rather than tearing the source
     * down — so trying one costs the user a silent film they can undo, not a
     * restart. Guessing in advance would be worse than letting it try:
     * `isTrackSupported` cannot see a receiver that will happily decode the
     * DTS the box itself cannot.
     */
    fun selectAudioTrack(id: Int) {
        val override = audioOverrides.getOrNull(id) ?: return

        forcedAudioId = id
        silencedAudioId = null
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            // Undone explicitly: a previous pick may have silenced the renderer
            // (see [recoverFromDecoderFailure]), and an override alone does not
            // re-enable a track type that was switched off.
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(override)
            .build()
        _state.update { it.copy(currentAudioId = id, audioSilenced = false) }
    }

    /**
     * Turns on one of the container's own subtitle tracks — drawn by
     * `PlayerView`'s subtitle renderer, not by this app's overlay, so none of
     * [adjustSubtitleOffset] applies to it. [NO_TRACK], or any id with no
     * track behind it, turns the text renderer off.
     */
    fun selectSubtitleTrack(id: Int) {
        val track = _state.value.subtitleTracks.getOrNull(id)

        // Same refusal as [selectAudioTrack]: a caption format with no decoder
        // is listed so the user can see the release has it, but forcing the
        // selection on would throw inside the renderer.
        if (track != null && !track.playable) return

        val override = track?.let { subtitleOverrides.getOrNull(id) }
        val params = player.trackSelectionParameters.buildUpon()

        if (override == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(override)

            // One subtitle at a time — see [selectExternalSubtitle]. Cleared
            // before the state update below, since that call does not touch
            // `currentSubtitleId` and so cannot undo what is set here.
            selectExternalSubtitle(null, null)
        }

        player.trackSelectionParameters = params.build()
        _state.update { it.copy(currentSubtitleId = if (override == null) NO_TRACK else id) }
    }

    /**
     * Adopts an addon's subtitle, already downloaded and parsed by the
     * repository (see `StreamsRepository.subtitleTrack`). Nothing here
     * touches the player: the cues are drawn by this app's own overlay, so
     * selecting one — or clearing it — never interrupts the film.
     */
    fun selectExternalSubtitle(option: SubtitleOption?, track: SubtitleTrack?) {
        // A `track` of null with an `option` given means the download or
        // parse failed; that resolves to the same "nothing selected" state as
        // an explicit clear, not a crash or a stuck loading spinner.
        val resolvedTrack = track.takeIf { option != null }
        subtitleTrack = resolvedTrack

        // One subtitle at a time. The container's captions are drawn by
        // `PlayerView` and an addon's by this app's own overlay, and nothing
        // downstream knows the other exists — so turning an addon subtitle on
        // over a container track ExoPlayer had already selected by itself (it
        // does that for any track flagged DEFAULT) stacked two lines of text
        // on top of each other. No recursion back here: the branch below only
        // runs for a real track, and this clears with a null one.
        if (resolvedTrack != null) selectSubtitleTrack(NO_TRACK)

        _state.update {
            it.copy(
                externalSubtitle = option.takeIf { resolvedTrack != null },
                subtitleOffsetMs = 0L,
            )
        }
        _activeSubtitleCue.value = null

        if (resolvedTrack == null) {
            subtitleTicker?.cancel()
            subtitleTicker = null
        } else {
            startSubtitleTicker()
        }
    }

    /**
     * Applies a signed adjustment relative to whatever the offset is *now*.
     *
     * Relative rather than "read the value, add, write it back" in the caller:
     * the sync bar repeats this many times a second while a key is held, and
     * the value it renders is a recomposition behind. Computing the sum here,
     * against the authoritative state, is what keeps a fast hold from
     * occasionally repeating a step it has already taken.
     */
    fun adjustSubtitleOffset(deltaMs: Long) {
        if (subtitleTrack == null) return
        setSubtitleOffset(_state.value.subtitleOffsetMs + deltaMs)
    }

    /**
     * Moves straight to an offset — what a drag along the sync bar reports,
     * where the finger's position *is* the value rather than a delta.
     *
     * Nothing here reaches the player, which is why the offset can be dragged
     * with the film still rolling: the next tick of [subtitleTicker] simply
     * looks up a different point in the same in-memory cue list.
     */
    fun setSubtitleOffset(offsetMs: Long) {
        if (subtitleTrack == null) return
        val clamped = offsetMs.coerceIn(-MAX_SUBTITLE_OFFSET_MS, MAX_SUBTITLE_OFFSET_MS)
        if (clamped == _state.value.subtitleOffsetMs) return

        _state.update { it.copy(subtitleOffsetMs = clamped) }
        updateActiveCueNow()
    }

    private fun startSubtitleTicker() {
        subtitleTicker?.cancel()
        subtitleTicker = scope.launch {
            while (isActive) {
                updateActiveCueNow()
                delay(SUBTITLE_TICK_MS)
            }
        }
    }

    /**
     * A video position `T` with a shift `S` applied shows whatever cue owns
     * file-time `T − S`: shifting the offset positive ("atrasa") is what
     * makes a cue that used to arrive at `T` now wait until `T + S`, which
     * this lookup reproduces by searching earlier in the file for it.
     */
    private fun updateActiveCueNow() {
        val track = subtitleTrack ?: return
        val timeMs = player.currentPosition - _state.value.subtitleOffsetMs
        // No guard against an unchanged line: `MutableStateFlow` compares with
        // `equals` before emitting, so writing the same text eight times a
        // second wakes nobody.
        _activeSubtitleCue.value = track.cueAt(timeMs)?.text
    }

    /** Keeps an already-started film in place if Media3 needs another URL. */
    private fun rememberPlaybackForFailover() {
        val position = maxOf(_position.value.positionMs, player.currentPosition).coerceAtLeast(0L)
        if (!_state.value.canShowVideo && position == 0L) return

        failoverPositionMs = position
        failoverPlayWhenReady = player.playWhenReady
    }

    /** Where the scrobbler reads from: 0–100 of the running title. */
    fun progressPercent(): Float = _position.value.progress * 100f

    /**
     * What is worth remembering about this playback — see [PlaybackHint].
     *
     * Null until there is something true to say: a position of zero is the
     * state before anything has been watched, and a duration of zero means the
     * file has not been demuxed far enough to know what a position even means.
     */
    fun hint(): PlaybackHint? {
        val position = _position.value.positionMs
        val duration = _position.value.durationMs
        if (position <= 0 || duration <= 0) return null
        return PlaybackHint(
            positionMs = position,
            durationMs = duration,
            sourceFilename = committedStream?.filename,
        )
    }

    private fun stopInternal() {
        prefetcher?.stop()
        watchdog?.cancel()
        ticker?.cancel()
        stallWatch?.cancel()
        candidatesJob?.cancel()
        subtitleTicker?.cancel()
        watchdog = null
        ticker = null
        stallWatch = null
        candidatesJob = null
        subtitleTicker = null
        candidatesCollecting = false
        awaitingCandidate = false
        committedStream = null
        committedRetries = 0
        failoverPositionMs = null
        failoverPlayWhenReady = null
        lastSeekAtMs = 0L
        connectionSuspect = false
        pausedAtMs = 0L
        clearPendingSeek()
        preferredWatch?.cancel()
        preferredWatch = null
        awaitingPreferred = false
        preferredFilename = null
        resumeExactMs = null
        // Reset alongside the state the three of them used to live in. Every
        // caller of this — `play`, `stop`, `release` — is a new title or no
        // title, and a playhead left over from the last one would be read by
        // the seek bar before the first tick of the next.
        _position.value = PlaybackPosition()
        _activeSubtitleCue.value = null
        runCatching { player.stop() }
    }

    fun stop() {
        stopInternal()
        subtitleTrack = null
        manualMode = false
        _state.value = PlaybackState()
    }

    /**
     * Tears the player down for good.
     *
     * Unlike the mpv and libVLC engines this replaced — both single, shared,
     * process-lifetime objects — an ExoPlayer is per-playback and cheap to
     * build, so it is released with the screen rather than kept warm.
     */
    fun release() {
        stopInternal()
        subtitleTrack = null
        player.removeListener(this)
        player.release()
    }

    private companion object {
        /**
         * Share of the disk cache the read-ahead window may occupy.
         *
         * The rest is left for what has already been watched. Both directions
         * come out of one `LeastRecentlyUsedCacheEvictor`, so this is the line
         * between "rewind is free" and "the next several minutes are already
         * here" — and taking all of it for the second would make a backward
         * seek re-download every time.
         *
         * Weighted three-to-one toward read-ahead because the two sides are not
         * worth the same. Behind the playhead, a quarter of a large cache is
         * already many minutes of instant rewind, and the cost of missing is a
         * re-download the viewer waits a moment for. Ahead of it, the cost of
         * missing is the picture stopping.
         */
        const val PREFETCH_CACHE_SHARE = 0.75

        /** How long a source may go without buffering anything before it is dead. */
        const val ATTEMPT_STALL_MS = 9_000L

        /**
         * The ceiling even for a source that never stops trickling. Past this
         * it does not matter that it is alive — it is not going to play at a
         * watchable rate.
         */
        const val ATTEMPT_HARD_CAP_MS = 45_000L
        const val WATCHDOG_POLL_MS = 1_500L

        /**
         * A removal notice runs 30s to a couple of minutes against a feature
         * of ninety-plus, so the real gap is enormous; half is a deliberately
         * loose line that no correct file gets anywhere near.
         */
        const val DECOY_MAX_FRACTION = 0.5
        const val DECOY_ABSOLUTE_CAP_MS = 15 * 60_000L

        const val MAX_PASSES = 2

        /** Position polling while the OSD is up — see [startTicker]. */
        const val TICK_MS = 500L

        /**
         * Position polling with the OSD hidden, which is most of a film.
         *
         * Nothing on screen shows the position then; the tick only has to keep
         * the phase honest and leave the seek bar roughly right for the moment
         * it reappears. At the old flat 500ms this emitted ~14,400 states over
         * a two-hour film, every one of them recomposing the player screen.
         */
        const val IDLE_TICK_MS = 4_000L

        /**
         * `adb logcat -s Playback` is the whole point: startup here is a
         * cascade, and without these lines the only thing anyone can say about
         * a slow start is that it was slow.
         */
        const val TAG = "Playback"

        const val SUBTITLE_TICK_MS = 120L

        /**
         * How long the presses have to stop before a burst of skips is handed
         * to the engine — see [nudge].
         *
         * Above the ~50ms the platform auto-repeats a held key at, by enough
         * that no hold is ever cut in half, and below what anyone reads as lag
         * between letting go and the picture moving.
         */
        const val SEEK_COMMIT_DELAY_MS = 350L

        /** What one press moves at the start of a burst. */
        const val NUDGE_STEP_MS = 10_000L

        /**
         * Where the step coarsens: 10s while the burst is short, then 30s,
         * then 60s. Four seconds of holding covers about half an hour.
         */
        const val NUDGE_FINE_STEPS = 4
        const val NUDGE_MEDIUM_STEPS = 12

        /**
         * How often a committed source is checked for a frozen buffer.
         *
         * Two field reads on the main thread, so the cost is nil, and a second
         * is fine enough that quantisation is a rounding error against
         * [STALL_FROZEN_MS].
         */
        const val STALL_POLL_MS = 1_000L

        /**
         * How long `bufferedPosition` may stand perfectly still, while the
         * viewer is waiting for a picture, before the link is reopened.
         *
         * Two bounds decide this. Long enough that a source still trickling is
         * never touched — any byte demuxed moves the buffer and resets the
         * count, so this can only fire on a link delivering literally nothing.
         * Short enough to land well inside the thirty-second read timeout it
         * exists to pre-empt, since waiting for that timeout is the freeze
         * being fixed.
         */
        const val STALL_FROZEN_MS = 10_000L

        /**
         * The same measurement, for a connection already presumed dead.
         *
         * Only ever reached through [connectionSuspect], which is only ever set
         * after a pause long enough to have cost the socket. There is no
         * evidence left to gather at that point — the ten seconds above buy
         * certainty this case already has — and the repair it leads to is the
         * cheap one.
         */
        const val STALL_SUSPECT_MS = 3_000L

        /**
         * How long a seek is allowed to go unanswered before the silence it
         * causes counts as a stall.
         *
         * Sized off the same observation as the connect timeout: a cold debrid
         * host can spend well over ten seconds on a range request before the
         * first byte of it appears. Anything shorter here punishes a long skip
         * for being a long skip.
         */
        const val SEEK_SETTLE_MS = 15_000L

        /**
         * Soft reconnections before a stall is escalated to a full re-prepare.
         *
         * One is enough to answer the failure this exists for — an idle socket
         * dropped during a pause, or one the host stopped writing to — and a
         * second would only delay the cascade for a link that is really gone.
         */
        const val SOFT_RECONNECT_LIMIT = 1

        /**
         * How far past the buffer [softReconnect] aims.
         *
         * Below half a frame at any frame rate, so no picture is skipped, and
         * comfortably above the millisecond rounding the engine compares seek
         * targets with — a target that rounds to the position already held is
         * discarded as "already there", which would leave the dead socket
         * exactly where it was.
         */
        const val RECONNECT_NUDGE_MS = 10L

        /**
         * How long a pause has to last before the connection is assumed lost.
         *
         * Well under the minute or two an idle socket typically survives, and
         * well over an ad-break-length glance at the phone. Being wrong in the
         * generous direction costs nothing: [connectionSuspect] is cleared by
         * the first byte that arrives.
         */
        const val PAUSE_SOCKET_IDLE_MS = 45_000L

        /** How far off the runtime estimate may be before a resume is corrected. */
        const val RESUME_TOLERANCE_MS = 5_000L

        /**
         * How far *behind* the provider the stored position may sit and still
         * be used.
         *
         * Only rounding has to fit under this: both providers report to one
         * decimal place, and the two numbers are written from the same
         * playhead moments apart. Anything beyond it means another device got
         * further, and then the provider is the one telling the truth.
         */
        const val HINT_PERCENT_TOLERANCE = 2f

        /**
         * How long the cascade waits for the release that played last time
         * before opening whatever it already has.
         *
         * Covers the first probed batch, and no more: the flow emits its
         * best-ranked candidate immediately — unprobed, and measurably often a
         * thirty-second "not available" clip — then the rest in concurrent
         * batches. Waiting that long to skip opening the dud is worth it; a
         * release that has not appeared by then is caught by the promotion in
         * the collector instead, which costs nothing at all.
         *
         * Spent only on a title this app has played before, and only once.
         */
        const val PREFERRED_GRACE_MS = 3_000L

        /**
         * Load-level retries before an error is allowed to become fatal.
         *
         * Media3's default is three, sized for a well-behaved CDN. The hosts
         * here are neither, and the cost of waiting is a paused picture where
         * the cost of giving up is the film restarting somewhere else.
         */
        const val LOAD_RETRY_COUNT = 12
        const val LOAD_RETRY_STEP_MS = 1_000L
        const val LOAD_RETRY_MAX_MS = 10_000L

        /**
         * Full re-prepares of an already-playing link before the cascade is
         * allowed to take over again. With the load retries above underneath
         * each one, this is several minutes of patience.
         */
        const val COMMITTED_RETRY_LIMIT = 6

        /**
         * The errors that mean "this decoder could not open this format",
         * as opposed to the far more common "the bytes stopped arriving".
         * [recoverFromDecoderFailure] answers for these and only these.
         */
        val DECODER_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        // The offset range itself lives in `PlaybackState.kt`, public, because
        // the sync bar has to draw exactly the range this clamps to.
    }
}
