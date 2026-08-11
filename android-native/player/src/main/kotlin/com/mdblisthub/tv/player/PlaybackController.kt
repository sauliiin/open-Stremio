package com.mdblisthub.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

    private val dataSourceFactory: DataSource.Factory =
        MediaCache.get(context)?.let { cache ->
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } ?: upstreamFactory

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

    val player: ExoPlayer = ExoPlayer.Builder(
        context.applicationContext,
        // Without the fallback, a decoder that fails to *initialise* — routine
        // on cheap boxes for some HEVC and MKV profiles — surfaces as a
        // playback error, and the cascade throws away a link that the
        // secondary decoder would have played perfectly.
        DefaultRenderersFactory(context.applicationContext).setEnableDecoderFallback(true),
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
                        // Deliberately above the 2s default: resuming from a
                        // rebuffer with almost nothing in hand is what turns
                        // one stall into a run of them. Only this one is
                        // raised — `bufferForPlaybackMs` stays at the default
                        // so the *first* frame is still fast.
                        /* bufferForPlaybackAfterRebufferMs = */ 8_000,
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

    /**
     * Decoys hit back to back, without a real source in between.
     *
     * One decoy means one dead mirror. Several in a row means the debrid
     * provider itself is refusing everything — rate limiting, most often —
     * and the placeholder is the same clip no matter which candidate asks for
     * it. Walking sixty more candidates cannot fix that; it just spends a
     * minute arriving at the same wall, so the streak is what turns "try the
     * next one" into "stop and say what is actually wrong".
     */
    private var decoyStreak = 0

    private var watchdog: Job? = null
    private var ticker: Job? = null

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
    ) {
        stopInternal()
        subtitleTrack = null

        queue = mutableListOf()
        queueIndex = -1
        passes = 0
        decoyIndices.clear()
        decoyStreak = 0
        manualMode = false
        committedStream = null
        committedRetries = 0
        expectedRuntimeMs = expectedRuntimeMinutes
            ?.takeIf { it > 0 }
            ?.let { it * 60_000L }
        candidatesCollecting = true
        awaitingCandidate = true
        resumePercent = resumeAtPercent?.takeIf { it > 1f && it < 95f }
        resumeApplied = false

        _state.value = PlaybackState(phase = PlaybackPhase.RESOLVING, candidateCount = 0)

        candidatesJob = scope.launch {
            candidates
                .filter { it.playable }
                // A producer failure here would otherwise crash the app over
                // something no worse than "an addon behaved oddly" — the same
                // thing an individual addon failing already tolerates.
                .catch { }
                .collect { stream ->
                    queue += stream
                    _state.update { it.copy(candidateCount = queue.size) }
                    if (awaitingCandidate) {
                        awaitingCandidate = false
                        tryAdvance()
                    }
                }
            candidatesCollecting = false
            if (awaitingCandidate) {
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
        decoyStreak = 0
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
                currentAudioId = audio.indexOfFirst { entry -> entry.third },
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
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val index = out.size

                out += Triple(
                    // Only what the container actually declares. Composing a
                    // display name here would bake a language into the engine
                    // — the label a file carries is already in whatever
                    // language its author chose, and the fallback for a track
                    // that has neither label nor language code is a sentence,
                    // which belongs in the UI.
                    TrackInfo(id = index, label = format.label, language = format.language),
                    TrackSelectionOverride(group.mediaTrackGroup, i),
                    group.isTrackSelected(i),
                )
            }
        }
        return out
    }

    private fun onReady() {
        if (isDecoy()) return rejectDecoy()

        // A real source proves the provider is answering properly after all,
        // so any run of decoys before it was mirror-by-mirror bad luck rather
        // than the provider being down.
        decoyStreak = 0
        watchdog?.cancel()
        watchdog = null

        // Reaching READY is what commits the app to this link: from here on an
        // error means "reconnect to this", never "try a different one". The
        // retry counter resets on every successful ready, so a film that
        // hiccups once an hour never exhausts its allowance.
        committedStream = _state.value.activeStream
        committedRetries = 0

        // `resumeApplied` is set *inside* the branch, not before it. Setting it
        // unconditionally meant that a first READY arriving before the
        // duration was demuxed — which happens — permanently consumed the
        // resume point without ever using it, and the film silently restarted
        // from the beginning.
        val duration = player.duration
        val percent = resumePercent
        if (!resumeApplied && percent != null && duration > 0) {
            val exact = (duration * percent / 100f).toLong().coerceIn(0L, duration)
            // The metadata runtime already placed the range request close to
            // here (see `open`), so this is a correction, not a seek. Skipping
            // it when it would move by less than a few seconds avoids paying
            // for a rebuffer to fix an error nobody can perceive.
            if (kotlin.math.abs(exact - player.currentPosition) > RESUME_TOLERANCE_MS) {
                player.seekTo(exact)
            }
            resumeApplied = true
        }

        _state.update {
            it.copy(
                phase = if (player.isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED,
                durationMs = player.duration.coerceAtLeast(0),
                error = null,
            )
        }
        failoverPositionMs = null
        failoverPlayWhenReady = null
        startTicker()
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

        decoyIndices += queueIndex
        decoyStreak++

        if (decoyStreak >= DECOY_STREAK_LIMIT) {
            fail(PlaybackFailure.DecoyStreak)
            return
        }

        // `resumeApplied` is deliberately left alone: nothing was watched, so
        // whatever resume point the user had is still owed to the next
        // candidate that turns out to be the real film.
        tryAdvance()
    }

    private fun fail(failure: PlaybackFailure) {
        watchdog?.cancel()
        ticker?.cancel()
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

    private fun publishPosition() {
        _state.update {
            it.copy(
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.coerceAtLeast(0),
                phase = nextPollPhase(it.phase),
            )
        }
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
        player.pause()
        _state.update { if (it.canShowVideo) it.copy(phase = PlaybackPhase.PAUSED) else it }
    }.let { }

    fun resume() = runCatching {
        player.play()
        _state.update { if (it.canShowVideo) it.copy(phase = PlaybackPhase.PLAYING) else it }
    }.let { }

    fun seekTo(positionMs: Long) {
        val duration = _state.value.durationMs
        if (duration <= 0) return
        val clamped = positionMs.coerceIn(0, duration)
        player.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
        updateActiveCueNow()
    }

    fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

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

    fun selectAudioTrack(id: Int) {
        val override = audioOverrides.getOrNull(id) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(override)
            .build()
        _state.update { it.copy(currentAudioId = id) }
    }

    fun selectSubtitleTrack(id: Int) {
        val params = player.trackSelectionParameters.buildUpon()
        val override = subtitleOverrides.getOrNull(id)

        if (override == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(override)
        }

        player.trackSelectionParameters = params.build()
        _state.update { it.copy(currentSubtitleId = id) }
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

        _state.update {
            it.copy(
                externalSubtitle = option.takeIf { resolvedTrack != null },
                subtitleOffsetMs = 0L,
                activeSubtitleCue = null,
            )
        }

        if (resolvedTrack == null) {
            subtitleTicker?.cancel()
            subtitleTicker = null
        } else {
            startSubtitleTicker()
        }
    }

    /**
     * Applies a signed adjustment, clamped to a useful TV-friendly range.
     *
     * Nothing here reaches the player — this is why the offset can be nudged
     * with the film still rolling: the next tick of [subtitleTicker] simply
     * looks up a different point in the same in-memory cue list.
     */
    fun adjustSubtitleOffset(deltaMs: Long) {
        if (subtitleTrack == null) return
        val adjustedMs = (_state.value.subtitleOffsetMs + deltaMs)
            .coerceIn(-MAX_SUBTITLE_OFFSET_MS, MAX_SUBTITLE_OFFSET_MS)
        if (adjustedMs == _state.value.subtitleOffsetMs) return

        _state.update { it.copy(subtitleOffsetMs = adjustedMs) }
        updateActiveCueNow()
    }

    fun resetSubtitleOffset() {
        if (subtitleTrack == null) return
        if (_state.value.subtitleOffsetMs == 0L) return

        _state.update { it.copy(subtitleOffsetMs = 0L) }
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
        val text = track.cueAt(timeMs)?.text
        if (_state.value.activeSubtitleCue != text) {
            _state.update { it.copy(activeSubtitleCue = text) }
        }
    }

    /** Keeps an already-started film in place if Media3 needs another URL. */
    private fun rememberPlaybackForFailover() {
        val position = maxOf(_state.value.positionMs, player.currentPosition).coerceAtLeast(0L)
        if (!_state.value.canShowVideo && position == 0L) return

        failoverPositionMs = position
        failoverPlayWhenReady = player.playWhenReady
    }

    /** Where the scrobbler reads from: 0–100 of the running title. */
    fun progressPercent(): Float = _state.value.progress * 100f

    private fun stopInternal() {
        watchdog?.cancel()
        ticker?.cancel()
        candidatesJob?.cancel()
        subtitleTicker?.cancel()
        watchdog = null
        ticker = null
        candidatesJob = null
        subtitleTicker = null
        candidatesCollecting = false
        awaitingCandidate = false
        committedStream = null
        committedRetries = 0
        failoverPositionMs = null
        failoverPlayWhenReady = null
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

        /** Decoys in a row that mean the provider, not the mirror, is the problem. */
        const val DECOY_STREAK_LIMIT = 3
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

        const val SUBTITLE_TICK_MS = 120L

        /** How far off the runtime estimate may be before a resume is corrected. */
        const val RESUME_TOLERANCE_MS = 5_000L

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

        const val MAX_SUBTITLE_OFFSET_MS = 10_000L
    }
}
