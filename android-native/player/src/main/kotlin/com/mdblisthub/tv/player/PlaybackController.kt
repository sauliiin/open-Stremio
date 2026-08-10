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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
) : Player.Listener {

    /**
     * Per-candidate headers land here rather than on the MediaItem: some
     * debrid links 403 without the right user agent or referer, and the
     * factory is re-read on every `prepare`, so mutating it between attempts
     * is what makes the header follow the candidate being tried.
     */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        // Generous on purpose. A debrid endpoint answers the redirect
        // instantly and then leaves the connection open while the file is
        // prepared upstream, which measurably runs past thirty seconds on a
        // cold link; the tighter values these replaced turned that ordinary
        // warm-up into a failed source.
        .setConnectTimeoutMs(30_000)
        .setReadTimeoutMs(30_000)

    private val mediaSourceFactory = DefaultMediaSourceFactory(
        DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory),
    )

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(mediaSourceFactory)
        // The whole reason this app moved off mpv. mpv buffers by byte count,
        // which on a mirror that trickles is a fixed and often far-too-small
        // amount of *time*; this budgets in seconds instead, so a slow source
        // simply takes longer to fill 30s than a fast one rather than
        // stuttering through playback.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 30_000,
                    /* maxBufferMs = */ 60_000,
                    // Start playing after 2.5s rather than the default 0.5s:
                    // starting early is what makes the first ten seconds of a
                    // stream the stutteriest part of it.
                    /* bufferForPlaybackMs = */ 2_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000,
                )
                // Left unset, Media3 budgets 125MB for video plus 12.5MB for
                // audio — and `DefaultAllocator` takes that from the *Java*
                // heap, not native memory. A set-top box with a 128–256MB
                // heap cannot pay that alongside Compose and the artwork
                // cache, so a high-bitrate release would fill the buffer over
                // a few minutes and then die of an OOM mid-film. The cap in
                // seconds above only governs sources under ~9Mbps; this is
                // what governs the ones that actually ran the box out of
                // memory.
                .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                // Keeps half a minute behind the playhead so a small skip back
                // does not re-download what was just watched. Shorter than it
                // was, because retained samples are drawn from the same
                // allocator budget the forward buffer needs.
                .setBackBuffer(/* backBufferDurationMs = */ 30_000, /* retainBackBufferFromKeyframe = */ true)
                .build(),
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
                fail("Nenhum addon devolveu um link reproduzível para este título.")
                return
            }
            passes++
            if (passes >= MAX_PASSES) {
                fail("Testei as ${queue.size} fontes disponíveis, duas vezes, e nenhuma abriu.")
                return
            }
            queueIndex = -1
            tryAdvance()
            return
        }

        queueIndex = nextIndex
        if (queueIndex in decoyIndices) return tryAdvance()

        val candidate = queue[queueIndex]
        val url = candidate.url ?: return tryAdvance()

        _state.update {
            it.copy(
                phase = PlaybackPhase.RESOLVING,
                attempt = queueIndex + 1,
                candidateCount = queue.size,
                error = null,
                activeStream = candidate,
            )
        }

        // Replaced wholesale rather than merged: the factory is shared across
        // every attempt, so a header the previous mirror needed would
        // otherwise leak into this one's request.
        httpDataSourceFactory.setDefaultRequestProperties(candidate.headers)

        val mediaItem = MediaItem.fromUri(url)
        failoverPositionMs?.let { position ->
            player.setMediaItem(mediaItem, position)
        } ?: player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = failoverPlayWhenReady ?: true

        watchdog = scope.launch { watchAttempt() }
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

        val url = stream.url
        if (url == null) {
            fail("Essa fonte não tem um link reproduzível.")
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

        httpDataSourceFactory.setDefaultRequestProperties(stream.headers)
        val mediaItem = MediaItem.fromUri(url)
        failoverPositionMs?.let { position ->
            player.setMediaItem(mediaItem, position)
        } ?: player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = failoverPlayWhenReady ?: true

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
                    fail("Essa fonte não respondeu. Escolha outra na lista.")
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
        if (manualMode) {
            fail("Essa fonte não abriu. Escolha outra na lista.")
        } else {
            tryAdvance()
        }
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
                val label = format.label
                    ?: format.language?.let { lang -> "Faixa ${lang.uppercase()}" }
                    ?: "Faixa ${index + 1}"

                out += Triple(
                    TrackInfo(index, label),
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

        if (!resumeApplied) {
            resumeApplied = true
            val duration = player.duration
            resumePercent?.takeIf { duration > 0 }?.let { percent ->
                player.seekTo((duration * percent / 100f).toLong())
            }
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
            fail(
                "Essa fonte devolveu um aviso no lugar do filme, não o filme em si. " +
                    "Escolha outra na lista.",
            )
            return
        }

        decoyIndices += queueIndex
        decoyStreak++

        if (decoyStreak >= DECOY_STREAK_LIMIT) {
            fail(
                "As fontes estão devolvendo um aviso no lugar do filme, o que quase sempre " +
                    "é o provedor debrid limitando requisições. Espere alguns minutos e tente de novo.",
            )
            return
        }

        // `resumeApplied` is deliberately left alone: nothing was watched, so
        // whatever resume point the user had is still owed to the next
        // candidate that turns out to be the real film.
        tryAdvance()
    }

    private fun fail(message: String) {
        watchdog?.cancel()
        ticker?.cancel()
        runCatching { player.stop() }
        _state.update {
            it.copy(phase = PlaybackPhase.FAILED, error = message, availableSources = queue.toList())
        }
    }

    /**
     * ExoPlayer has no position callback, so the seek bar is polled. The tick
     * also keeps the play/pause phase honest, since pausing through the
     * engine (rather than through this class) is possible.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                _state.update {
                    it.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = player.duration.coerceAtLeast(0),
                        phase = nextPollPhase(it.phase),
                    )
                }
            }
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
        const val TICK_MS = 500L
        const val SUBTITLE_TICK_MS = 120L
        const val TARGET_BUFFER_BYTES = 48 * 1024 * 1024
        const val MAX_SUBTITLE_OFFSET_MS = 10_000L
    }
}
