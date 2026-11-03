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
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.Loader
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.PlaybackHint
import com.mdblisthub.tv.core.model.SourceReadiness
import com.mdblisthub.tv.core.model.StreamKind
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
import java.io.EOFException

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
 *
 * Before changing how this recovers from a stall, read `player/PLAYBACK_STALLS.md`:
 * what has been tried, what the measurements said, and the rules that came out of it.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
    private val scope: CoroutineScope,
    /**
     * The client whose connection pool the mirror probe already warmed, so the
     * TCP+TLS handshake `StreamsRepository.probe` just paid for is reused
     * rather than repeated. Must not carry a `callTimeout`: here a single call is
     * the whole film — see `HttpClients.playback`.
     */
    callFactory: okhttp3.Call.Factory,
    /**
     * Told of every source opened, and of null on [release]. The app uses it to drop a
     * torrent the moment the player moves to another source, so a swarm never goes on
     * downloading a film nobody is watching.
     */
    private val onSourceOpened: (PlayableStream?) -> Unit = {},
) : Player.Listener {

    private val appContext = context.applicationContext

    private val dataPath = PlaybackDataPath(context, callFactory, scope)
    private val prefetcher = dataPath.prefetcher

    /**
     * What a failed load means, which decides whether trying again can help.
     *
     * Retried without a limit by default: a hiccup on the link should never become
     * "this source is dead", and the load resumes from the byte it reached. The stall
     * watch is what gives up on a link, and it has the evidence to.
     *
     * **Bytes the demuxer rejects are the exception.** The same bytes come back on
     * every retry, from every connection, so waiting cannot fix them. It is recorded
     * for the stall watch, which skips past the damage once the film reaches it — see
     * [skipDamage]. A link the host says is gone is fatal at once, because asking it
     * again a dozen times is minutes of frozen picture.
     *
     * `errorCount` restarts at 1 when a load makes progress, and the first error after
     * that is already 2, so 3 is the first count that always means the same bytes failed
     * twice in a row: one bad answer from a host is not a bad file.
     *
     * Runs on the playback thread, where touching `player` throws.
     */
    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy(Int.MAX_VALUE) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val error = info.exception
            Log.w(TAG, "load error #${info.errorCount}: $error")
            if (error.isLinkGone()) return C.TIME_UNSET
            if (error.causes().any { it is HttpDataSource.InvalidResponseCodeException }) {
                hostRefusedAtMs = SystemClock.elapsedRealtime()
            }
            // Before a first frame there is a queue waiting behind this link, and no film
            // to protect. A dead host should cost seconds, not the attempt's whole cap.
            if (committedStream == null && info.errorCount >= UNCOMMITTED_LOAD_ERRORS) {
                openingAbandonedAtMs = SystemClock.elapsedRealtime()
                return C.TIME_UNSET
            }
            if (info.errorCount >= DAMAGE_CONFIRMED_ERRORS && error.isDamagedContent()) {
                damage.record(SystemClock.elapsedRealtime())
            }
            val attempt = info.errorCount.coerceAtLeast(1) - 1
            return (LOAD_RETRY_FIRST_MS + attempt * LOAD_RETRY_STEP_MS).coerceAtMost(LOAD_RETRY_MAX_MS)
        }
    }

    private val damage = DamageEvidence(DAMAGE_PASSED_MS)

    /** When the host last answered with an error status. Written on the playback thread. */
    @Volatile
    private var hostRefusedAtMs = 0L

    /**
     * When the policy gave up on a link that had not played yet. Written on the playback
     * thread. Giving up raises no error while samples are queued: with nothing left to
     * load the engine calls that READY, with a clock that cannot move. See [onReady].
     */
    @Volatile
    private var openingAbandonedAtMs = 0L

    private val mediaSourceFactory = DefaultMediaSourceFactory(dataPath.playerSource)
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
        .also { bytes ->
            MemoryPressure.install(appContext)
            // The three readings the budget was chosen from, not just the
            // result: a buffer that came out small is a different problem
            // depending on whether the heap ceiling, the free RAM or the
            // per-tier floor is what decided it, and after the fact there is
            // no way to tell them apart from the number alone.
            Log.i(
                TAG,
                "buffer budget ${bytes / MB}MB from " +
                    "heap ${Runtime.getRuntime().maxMemory() / MB}MB " +
                    "(${HeapBudget.headroomBytes() / MB}MB free), " +
                    "ram ${HeapBudget.totalRamBytes(appContext)?.div(MB)}MB " +
                    "(${HeapBudget.spareRamBytes(appContext)?.div(MB)}MB spare); " +
                    "disk cache ${MediaCache.budgetBytes / MB}MB",
            )
        }

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
                        // Every profile waits for 5s before the first frame
                        // and 10s after a rebuffer. This gives the disk cache
                        // a real forward cushion instead of immediately
                        // returning to another freeze/resume cycle.
                        /* bufferForPlaybackMs = */
                        HeapBudget.bufferForPlaybackMs(),
                        // RAM changes the byte ceiling, not this recovery
                        // threshold: every device needs the same time cushion.
                        /* bufferForPlaybackAfterRebufferMs = */
                        HeapBudget.bufferForPlaybackAfterRebufferMs(),
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
     * Candidates not worth opening again, by queue position: decoys, and files
     * given up on as damaged.
     *
     * Kept because the cascade walks the queue twice: without this, the
     * second pass would cheerfully re-open the same "this file was removed"
     * clip that was just rejected, and the user would watch it start again.
     */
    private val rejectedIndices = mutableSetOf<Int>()

    private var watchdog: Job? = null
    private var ticker: Job? = null

    /**
     * Supervises a source that is *already playing*. [watchdog] deliberately
     * stops at the moment this one starts — see [startStallWatch].
     */
    private var stallWatch: Job? = null

    /**
     * When the loader was last sent somewhere new. The silence a seek causes is not a
     * dead host, and on a cold debrid link the new range request can take well over
     * ten seconds to answer.
     */
    private var lastSeekAtMs = 0L

    /** How many times this stretch of film has been skipped for damage, and when last. */
    private var damageSkips = 0
    private var lastDamageSkipAtMs = 0L
    private var damageStartedAtMs = 0L

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
     * When the picture last stopped to rebuffer, or zero when it has not.
     *
     * Exists for [TAG] alone, and measures the one thing the viewer actually
     * reports: not that a rebuffer happened, but how long it lasted. A stall
     * the engine recovers from in two seconds and one that sits there until a
     * skip is pressed are the same event in every other log line.
     */
    private var rebufferStartedAtMs = 0L

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
    @Volatile
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
        rejectedIndices.clear()
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
                    // bookkeeping honest: `rejectedIndices` holds indices into
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
     *
     * A short queue is walked again after that, with a growing pause, until
     * [PERSIST_MS] have passed since [play]. One or two links, which is what a
     * little-known film often has, fail their two passes in a few seconds when
     * the host is briefly refusing: a 429 from an addon's rate limit, a 503
     * from a busy debrid server. Measured in the lab, that was an error four
     * seconds in, on a link that worked fifteen seconds later. A link given
     * up on for what it is, a notice or a damaged file, is never walked again.
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
            val retryable = queue.indices.any { it !in rejectedIndices && queue[it].url != null }
            if (!retryable || (passes >= MAX_PASSES && sincePlayMs() >= PERSIST_MS)) {
                fail(
                    // Every link a notice the debrid service shows while it fetches the
                    // file: there is nothing to play yet, and saying so is the useful answer.
                    if (queue.all { it.readiness == SourceReadiness.NOT_CACHED }) {
                        PlaybackFailure.NotCachedYet
                    } else {
                        PlaybackFailure.AllCandidatesFailed(queue.size)
                    },
                )
                return
            }
            queueIndex = -1
            if (passes >= MAX_PASSES) {
                val pauseMs = (PASS_PAUSE_MS shl (passes - MAX_PASSES)).coerceAtMost(MAX_PASS_PAUSE_MS)
                Log.i(TAG, "queue of ${queue.size} walked $passes times by +${sincePlayMs()}ms, again in ${pauseMs}ms")
                // On [watchdog], so anything that ends this playback ends the wait too.
                watchdog = scope.launch {
                    delay(pauseMs)
                    tryAdvance()
                }
                return
            }
            tryAdvance()
            return
        }

        queueIndex = nextIndex
        if (queueIndex in rejectedIndices) return tryAdvance()

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
                "addon=${stream.addon} ${stream.readiness} quality=${stream.quality} " +
                "size=${stream.sizeBytes} file=${stream.filename}",
        )

        dataPath.switchTo(stream.headers)
        onSourceOpened(stream)

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setCustomCacheKey(stream.cacheKey())
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
            Player.STATE_BUFFERING -> {
                // Before the state is even published: the picture has stopped, so
                // every byte the link can deliver belongs to the player.
                prefetcher?.onLoadingChanged(true)
                if (committedStream != null && rebufferStartedAtMs == 0L) {
                    rebufferStartedAtMs = SystemClock.elapsedRealtime()
                    Log.i(
                        TAG,
                        "rebuffer at ${player.currentPosition}ms " +
                            "with ${player.totalBufferedDuration}ms buffered, " +
                            "${allocator.totalBytesAllocated / MB}/${targetBufferBytes / MB}MB allocated, " +
                            "loading=${player.isLoading}",
                    )
                }
                _state.update {
                    // A buffer stall mid-playback is worth showing; one during
                    // the cascade is not, since the veil is already up.
                    if (it.phase == PlaybackPhase.RESOLVING) {
                        it
                    } else {
                        it.copy(phase = PlaybackPhase.BUFFERING)
                    }
                }
            }
            else -> Unit
        }
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        prefetcher?.onLoadingChanged(isLoading)
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
            // The host said the link is gone, so asking it again changes nothing.
            if (error.errorCode in LINK_GONE_ERROR_CODES) committedRetries = COMMITTED_RETRY_LIMIT
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

        // Not a working link, only one that stopped with a few samples queued. With no
        // other source, committing is still the better answer: the stall watch can skip
        // what it cannot read.
        if (committedStream == null && openingAbandonedAtMs > attemptStartedAtMs && hasOtherCandidate()) {
            logAttemptEnd("abandoned")
            tryAdvance()
            return
        }

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
            if (!exactFits && percent != null && player.isCurrentMediaItemSeekable) {
                val corrected = (duration * percent / 100f).toLong().coerceIn(0L, duration)
                // The metadata runtime already placed the range request close
                // to here (see `open`), so this is a correction, not a seek.
                // Skipping it when it would move by less than a few seconds
                // avoids paying for a rebuffer to fix an imperceptible error.
                if (kotlin.math.abs(corrected - player.currentPosition) > RESUME_TOLERANCE_MS) {
                    Log.i(TAG, "resume correction to ${corrected}ms (was ${player.currentPosition}ms)")
                    noteSeek(corrected)
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
        if (rebufferStartedAtMs != 0L) {
            Log.i(
                TAG,
                "rebuffer ended after ${SystemClock.elapsedRealtime() - rebufferStartedAtMs}ms " +
                    "with ${player.totalBufferedDuration}ms buffered",
            )
            rebufferStartedAtMs = 0L
        }
        _position.update { it.copy(durationMs = player.duration.coerceAtLeast(0)) }
        failoverPositionMs = null
        failoverPlayWhenReady = null
        updatePrefetch()
        startTicker()
        startStallWatch()
    }

    /**
     * Called from [onReady] and from every position tick, because `STATE_READY` can
     * arrive before the duration is demuxed, and the tick does not know when the
     * source changed. `MediaPrefetcher.start` is a no-op once running for this file.
     */
    private fun updatePrefetch() {
        val prefetcher = prefetcher ?: return
        val stream = committedStream ?: return
        // A torrent is already on this device's disk as it arrives. Reading it ahead into
        // the cache would store the film twice, and the swarm, not a socket, sets the pace.
        if (stream.kind == StreamKind.TORRENT) return
        val url = stream.url ?: return
        prefetcher.start(
            uri = url.toUri(),
            cacheKey = stream.cacheKey(),
            durationMs = player.duration,
        )
        prefetcher.onPosition(
            positionMs = player.currentPosition.coerceAtLeast(0L),
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
        rejectedIndices += queueIndex

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
     * Watches a source that is already playing, from its first frame to the end.
     *
     * A mirror rarely *closes* a connection when it gives up; it stops sending, or
     * sends too little. Neither is an error, and the player will happily wait out
     * the read timeout on the first and sit on the second for the whole film. So the
     * connection is judged while the loader is asking it for bytes, and well before
     * the buffer runs out — see [StallTracker] — and a poor one is replaced in place,
     * see [reconnect], which nobody can see. Only a link that has ignored new
     * connections with the picture already stopped reaches [reopenCommitted], the one
     * rung that resets the decoders.
     */
    private fun startStallWatch() {
        // Called on every READY, and the tracker's record has to outlive a rebuffer:
        // starting over would lose the backoff on exactly the link that needs it.
        if (stallWatch?.isActive == true) return
        stallWatch = scope.launch {
            val tracker = StallTracker(
                pollMs = STALL_POLL_MS,
                windowMs = STALL_WINDOW_MS,
                waitingWindowMs = STALL_WAITING_WINDOW_MS,
                maxWindowMs = STALL_MAX_WINDOW_MS,
                healthyRate = STALL_HEALTHY_RATE,
                deadRate = STALL_DEAD_RATE,
                reconnectsBeforeReopen = RECONNECTS_BEFORE_REOPEN,
            )
            val wedgeDetector = DemuxWedgeDetector(WEDGE_UNUSED_BYTES)
            var damagedWaitMs = 0L
            var loaderIdleMs = 0L
            var lastPlayingPosition = C.TIME_UNSET
            var clockFrozenMs = 0L

            while (isActive) {
                delay(STALL_POLL_MS)

                val committed = committedStream != null
                val waiting = committed && player.playWhenReady &&
                    player.playbackState == Player.STATE_BUFFERING
                val buffered = player.bufferedPosition

                damage.onPoll(buffered)
                // Not on a torrent. Its pieces are checked against their hashes, so what the
                // demuxer gets is the file as published, and the torrent server hands over
                // what has arrived and then waits, which reads just like a wedge. Measured:
                // after a swarm left, a reopen was taken for damage and 5s of film skipped.
                val torrent = committedStream?.kind == StreamKind.TORRENT
                val wedged = wedgeDetector.onPoll(
                    waiting = waiting && !torrent,
                    bytesRead = dataPath.bytesRead,
                    allocatedBytes = allocator.totalBytesAllocated.toLong(),
                    bufferedMs = buffered,
                )
                if (wedged) {
                    Log.w(TAG, "bytes arriving and no film coming out at ${player.currentPosition}ms")
                    damage.record(SystemClock.elapsedRealtime())
                }

                // The film has reached bytes the demuxer cannot use. Nothing else below
                // can help: every connection delivers the same bytes.
                val damaged = committed && damage.standsSince(lastSeekAtMs)
                damagedWaitMs = if (waiting && damaged) damagedWaitMs + STALL_POLL_MS else 0L
                // Whatever is left in front of the damage cannot grow, so a player that
                // wants more than that before restarting would wait for ever. It is given
                // a moment only because it may simply be about to restart on it.
                val reached = player.totalBufferedDuration < DAMAGE_REACHED_MS
                if (damagedWaitMs > 0L && (reached || damagedWaitMs >= DAMAGE_STUCK_MS)) {
                    damagedWaitMs = 0L
                    skipDamage()
                    continue
                }

                // Waiting for a picture with the loader not asked to load is not a
                // slow link, it is a deadlock: nothing inside the engine will ask the
                // LoadControl again. There is nothing to tell apart here, so the
                // repair runs after two polls. The end of the file is excluded:
                // loading has legitimately finished there.
                val duration = player.duration
                val loaderIdle = waiting && !player.isLoading &&
                    duration > 0 && buffered < duration - END_OF_FILE_GUARD_MS
                loaderIdleMs = if (loaderIdle) loaderIdleMs + STALL_POLL_MS else 0L
                if (loaderIdleMs >= LOADER_IDLE_STALL_MS) {
                    loaderIdleMs = 0L
                    Log.w(
                        TAG,
                        "buffering with the loader idle at ${player.currentPosition}ms " +
                            "(${player.totalBufferedDuration}ms buffered, " +
                            "${allocator.totalBytesAllocated / MB}MB allocated) — flushing",
                    )
                    kickLoaderAt(player.currentPosition + FLUSH_NUDGE_MS)
                    continue
                }

                // The freeze that never reaches STATE_BUFFERING: READY and playing,
                // with the clock standing still. An audio sink whose track stopped
                // consuming holds the video with it, since video is timed against
                // audio. A seek flushes the renderers.
                val position = player.currentPosition
                val playing = committed && player.isPlaying
                clockFrozenMs = if (
                    playing && lastPlayingPosition != C.TIME_UNSET &&
                    position - lastPlayingPosition < CLOCK_MIN_ADVANCE_MS
                ) {
                    clockFrozenMs + STALL_POLL_MS
                } else {
                    0L
                }
                lastPlayingPosition = if (playing) position else C.TIME_UNSET
                if (clockFrozenMs >= CLOCK_FROZEN_MS) {
                    clockFrozenMs = 0L
                    lastPlayingPosition = C.TIME_UNSET
                    Log.w(TAG, "clock frozen at ${position}ms while READY and playing — flushing renderers")
                    kickLoaderAt(position + FLUSH_NUDGE_MS)
                    continue
                }

                val action = tracker.onPoll(
                    loaderActive = committed && player.isLoading && !damaged,
                    waiting = waiting,
                    bufferedMs = buffered,
                    settling = SystemClock.elapsedRealtime() - lastSeekAtMs < SEEK_SETTLE_MS,
                )
                when (action) {
                    StallTracker.Action.WAIT -> Unit
                    StallTracker.Action.RECONNECT -> reconnect()
                    StallTracker.Action.REOPEN -> {
                        rememberPlaybackForFailover()
                        // Answering at all means the network is fine and this host is the
                        // problem, so a cold start would only get the same answer. With
                        // nowhere else to go, waiting it out is still the best there is.
                        if (isHostRefusing() && hasOtherCandidate()) committedRetries = COMMITTED_RETRY_LIMIT
                        reopenCommitted()
                    }
                }
            }
        }
    }

    private fun isHostRefusing(): Boolean =
        SystemClock.elapsedRealtime() - hostRefusedAtMs < HOST_REFUSING_MS

    private fun hasOtherCandidate(): Boolean = !manualMode && (
        candidatesCollecting ||
            queue.indices.any { it != queueIndex && it !in rejectedIndices && queue[it].url != null }
        )

    /**
     * Replaces the connection feeding the player, without touching anything else.
     *
     * The load fails, and resumes from the byte it had reached on a new socket. What
     * is buffered stays buffered, nothing is skipped, and the picture never knows.
     */
    private fun reconnect() {
        Log.i(
            TAG,
            "reconnecting at ${player.currentPosition}ms: buffer edge ${player.bufferedPosition}ms, " +
                "state ${player.playbackState}",
        )
        dataPath.abortPlayerRead()
    }

    /**
     * Moves past a stretch of the file the demuxer cannot read, as a viewer would
     * by pressing skip — which until now was the only thing that got past one.
     *
     * The step grows while the damage keeps going, so a single bad block costs a few
     * seconds of film. A file that is damaged for minutes at a time is not worth
     * crossing, and is handed to the cascade like any other source that stopped
     * working, which picks the film up from where it first broke: the next source has
     * no damage there, and the skips were only ever a way around this one's.
     *
     * Neither is a file with no index, which cannot be crossed at all. Every seek in one
     * lands back at zero, and the film would replay up to the same damage for ever.
     */
    private fun skipDamage() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDamageSkipAtMs > DAMAGE_QUIET_MS) damageSkips = 0
        lastDamageSkipAtMs = now
        val position = player.currentPosition
        if (damageSkips == 0) damageStartedAtMs = position

        if (damageSkips >= MAX_DAMAGE_SKIPS || !player.isCurrentMediaItemSeekable) {
            Log.w(TAG, "damaged at ${position}ms after $damageSkips skips — giving up on this source")
            damageSkips = 0
            damage.clear()
            rememberPlaybackForFailover()
            failoverPositionMs = damageStartedAtMs
            rejectedIndices += queueIndex
            committedStream = null
            committedRetries = 0
            if (manualMode) fail(PlaybackFailure.ManualFailed) else tryAdvance()
            return
        }

        val step = (DAMAGE_SKIP_MS shl damageSkips).coerceAtMost(DAMAGE_MAX_SKIP_MS)
        damageSkips++
        Log.w(TAG, "damaged file at ${position}ms — skipping ${step}ms (skip #$damageSkips)")
        seekTo(position + step)
    }

    /** An exact seek, which flushes the renderers wherever it lands. */
    private fun kickLoaderAt(targetMs: Long) {
        _state.update { it.copy(phase = PlaybackPhase.BUFFERING, error = null) }
        player.setSeekParameters(SeekParameters.EXACT)
        noteSeek(targetMs)
        player.seekTo(targetMs)
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }

    /**
     * Prepares the loader for a seek, which only concerns it when the target is outside
     * the buffer. Inside, nothing new is requested, so there is no silence to excuse
     * and no read to end. Outside, the new load needs the loader's thread, which a read
     * on a quiet socket would hold until the timeout — see [AbortableCallFactory].
     */
    private fun noteSeek(targetMs: Long) {
        if (targetMs in player.currentPosition..player.bufferedPosition) return
        lastSeekAtMs = SystemClock.elapsedRealtime()
        dataPath.abortPlayerRead()
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
        player.pause()
        _state.update { if (it.canShowVideo) it.copy(phase = PlaybackPhase.PAUSED) else it }
    }.let { }

    fun resume() = runCatching {
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
        if (duration <= 0 || !player.isCurrentMediaItemSeekable) return

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
        // A file with no index answers any seek by going back to the start of the film.
        if (duration <= 0 || !player.isCurrentMediaItemSeekable) return
        // An absolute seek — the seek bar dragged, a resume correction — ends
        // any burst rather than racing it.
        clearPendingSeek()
        val clamped = positionMs.coerceIn(0, duration)
        noteSeek(clamped)
        player.seekTo(clamped)
        // The chunk in flight was fetched for where the viewer just left, and
        // on a slow link it can be tens of seconds of download standing between
        // them and the bytes they now need.
        prefetcher?.invalidate()
        _position.update { it.copy(positionMs = clamped) }
        updateActiveCueNow()
    }

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
        damage.clear()
        damageSkips = 0
        lastDamageSkipAtMs = 0L
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
        onSourceOpened(null)
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

        const val MAX_PASSES = 2

        /**
         * How long a short queue keeps being walked, from [play], before the cascade fails.
         * Past [MAX_PASSES], each walk waits [PASS_PAUSE_MS], doubling to [MAX_PASS_PAUSE_MS],
         * so a single dead link is tried about seven times. A long queue has spent this on
         * its two passes already, and fails as it did.
         */
        const val PERSIST_MS = 60_000L
        const val PASS_PAUSE_MS = 2_000L
        const val MAX_PASS_PAUSE_MS = 15_000L

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

        /** For the byte figures in [TAG]'s lines; nothing reads it but a log. */
        const val MB = 1024 * 1024

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

        /** How often a committed source is checked. Two field reads on the main thread. */
        const val STALL_POLL_MS = 1_000L

        /**
         * How long a connection is measured before it is judged, with film in hand.
         * Short enough to replace a bad one before the buffer runs out.
         */
        const val STALL_WINDOW_MS = 8_000L

        /** The same with the picture stopped, because now somebody is waiting. */
        const val STALL_WAITING_WINDOW_MS = 5_000L

        /** Where the backoff stops on a link that is simply slower than the film. */
        const val STALL_MAX_WINDOW_MS = 64_000L

        /**
         * Share of real time the buffer must gain for a connection to count as healthy.
         * Under 1, because a scene above the file's average bitrate is not a bad link.
         */
        const val STALL_HEALTHY_RATE = 0.6

        /** Below this, with the picture stopped, the link is dead rather than slow. */
        const val STALL_DEAD_RATE = 0.2

        /** New connections tried on a dead link before it is reopened from cold. */
        const val RECONNECTS_BEFORE_REOPEN = 2

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
         * How far a flush moves the playhead. Below half a frame at any frame rate, and
         * above the rounding the engine compares seek targets with — a target that rounds
         * to the position already held is discarded as "already there".
         */
        const val FLUSH_NUDGE_MS = 10L

        /**
         * How long the player may wait for a picture with nothing loading
         * before the stall watch flushes it — see [startStallWatch].
         * Two polls, so one sampled between a seek and its load starting
         * cannot trigger it.
         */
        const val LOADER_IDLE_STALL_MS = 2_000L

        /** Within this of the end, an idle loader means the file is all read. */
        const val END_OF_FILE_GUARD_MS = 1_000L

        /**
         * Playhead progress per poll below which a READY, playing player
         * counts as frozen. Well under real time, well over the few
         * milliseconds `currentPosition` extrapolates between engine updates.
         */
        const val CLOCK_MIN_ADVANCE_MS = 250L

        /** How long the clock may stand still while playing before it is flushed. */
        const val CLOCK_FROZEN_MS = 4_000L

        /**
         * Read with nothing to show for it before the demuxer counts as stuck — see
         * [DemuxWedgeDetector]. Well above any header, and small enough to be reached in
         * seconds at any speed a film could play at.
         */
        const val WEDGE_UNUSED_BYTES = 2L * 1024 * 1024

        /** How the two errors [isLinkGone] recognises come back from the player. */
        val LINK_GONE_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )

        /** The first `errorCount` that always means the same bytes failed twice. */
        const val DAMAGE_CONFIRMED_ERRORS = 3

        /** A buffer edge this far past a report of damage means the load got through. */
        const val DAMAGE_PASSED_MS = 2_000L

        /**
         * Damage is recorded when the loader reaches it, often minutes ahead of the
         * picture. Skipping waits until the film has really played up to it.
         */
        const val DAMAGE_REACHED_MS = 1_000L

        /** How long the picture may stay stopped with damage ahead and film still in hand. */
        const val DAMAGE_STUCK_MS = 3_000L

        /** The first skip past damage, doubling up to [DAMAGE_MAX_SKIP_MS]. */
        const val DAMAGE_SKIP_MS = 5_000L
        const val DAMAGE_MAX_SKIP_MS = 60_000L

        /** Over five minutes of film at those steps: not a bad block, a bad file. */
        const val MAX_DAMAGE_SKIPS = 8

        /** Playing this long without more damage ends the episode and resets the step. */
        const val DAMAGE_QUIET_MS = 60_000L

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
         * Load errors a candidate may have before it has produced a frame. Three read
         * timeouts is about the whole attempt cap, so a cold debrid link still gets all of
         * its time, and a host refusing connections is gone in a second and a half.
         */
        const val UNCOMMITTED_LOAD_ERRORS = 3

        /** The ramp between load retries: fast after a reconnect, gentle on a bad host. */
        const val LOAD_RETRY_FIRST_MS = 250L
        const val LOAD_RETRY_STEP_MS = 1_000L
        const val LOAD_RETRY_MAX_MS = 5_000L

        /** Twice the slowest load retry, so a host still refusing is always this recent. */
        const val HOST_REFUSING_MS = 2 * LOAD_RETRY_MAX_MS

        /** Full re-prepares of an already-playing link before the cascade takes over. */
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

/**
 * The stable identity of the file: debrid links are signed and expire, so the URL
 * would miss on every visit. The size goes with the name because addons do report
 * generic names, and two films sharing one would read each other's spans. Null falls
 * back to the URL, which still serves a seek inside the current session.
 */
private fun PlayableStream.cacheKey(): String? {
    val name = filename?.takeIf { it.isNotBlank() } ?: return null
    return sizeBytesHint?.let { "$name#$it" } ?: name
}

internal fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

/**
 * Failures that come from the bytes rather than from the connection carrying them.
 *
 * A bare `EOFException` comes from a demuxer that believed an element ran past the end
 * of the file. Only a bare one: wrapped inside an I/O error it is a connection closing
 * while its headers were being read, which says nothing about the file. An unexpected
 * loader exception is an extractor crashing on what it read, which garbage does as
 * readily as failing to parse.
 */
@OptIn(UnstableApi::class)
private fun Throwable.isDamagedContent(): Boolean =
    this is EOFException || this is Loader.UnexpectedLoaderException ||
        causes().any { it is ParserException }

/**
 * The host saying it will not serve this film from here. Either in so many words, or with
 * nothing at the position asked for: a file shorter than its index promises answers 416,
 * and an error page sent with a 200 in place of the film ends before reaching it.
 */
@OptIn(UnstableApi::class)
private fun Throwable.isLinkGone(): Boolean = causes().any {
    when (it) {
        is HttpDataSource.InvalidResponseCodeException ->
            it.responseCode in setOf(400, 401, 403, 404, 410, 416)
        is DataSourceException -> it.reason == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        else -> false
    }
}
