package com.mdblisthub.tv.ui.player

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.isActive
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.CastMember
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.core.ui.component.FanartBackdrop
import com.mdblisthub.tv.core.ui.component.HubSpinner
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubEffects
import com.mdblisthub.tv.core.ui.theme.HubMotion
import com.mdblisthub.tv.core.ui.theme.HubShapes
import com.mdblisthub.tv.core.ui.theme.HubStrokes
import com.mdblisthub.tv.player.ExoVideoSurface
import com.mdblisthub.tv.player.MAX_SUBTITLE_OFFSET_MS
import com.mdblisthub.tv.player.NO_TRACK
import com.mdblisthub.tv.player.PlaybackFailure
import com.mdblisthub.tv.player.PlaybackPhase
import com.mdblisthub.tv.player.PlaybackPosition
import com.mdblisthub.tv.player.TrackInfo
import com.mdblisthub.tv.player.VideoScaleType
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.hubViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private const val OSD_TIMEOUT_MS = 4_000L

/**
 * Below this much time left, Back tears the player down instead of shrinking
 * it to the floating window — a couple of minutes of credits is not worth
 * carrying a decoder around Home for.
 */
private const val MINI_PLAYER_MIN_REMAINING_MS = 5 * 60 * 1000L

/**
 * Caption metrics. The line height is ~1.35x the glyph size — roughly what
 * broadcast subtitles use, and enough air that a two-line cue reads as two
 * lines from a sofa rather than as one block of text.
 */
private val SUBTITLE_FONT_SIZE = 26.sp
private val SUBTITLE_LINE_HEIGHT = 35.sp
private const val SEEK_STEP_MS = 10_000L
private const val SUBTITLE_OFFSET_STEP_MS = 100L

/**
 * The subtitle sync bar's geometry and its hold behaviour.
 *
 * The resting sizes are what the bar looks like when something else has focus,
 * which on this panel is never — but the focused/unfocused pair is the cue the
 * OSD's seek bar already uses, and a control that never reacts to focus reads
 * as disabled next to one that does.
 */
private val SLIDER_TRACK_RESTING = 3.dp
private val SLIDER_TRACK_FOCUSED = 5.dp
private val SLIDER_THUMB_RESTING = 5.dp
private val SLIDER_THUMB_FOCUSED = 7.dp
private val SLIDER_TICK_WIDTH = 2.dp

/** Held past this, a direction key stops being a single step and starts sliding. */
private const val SLIDER_HOLD_DELAY_MS = 400L
private const val SLIDER_REPEAT_INTERVAL_MS = 60L

/**
 * How stale the last key-down may get before a slide gives up on its own.
 *
 * A repeat that only ends on key-up is a repeat that runs forever the day a
 * key-up goes missing — and one does: an injected long press, a remote whose
 * release is swallowed, focus torn away mid-hold. That is not hypothetical
 * here, it was watched happening, the offset sliding to the end of its range
 * on its own with nothing held.
 *
 * So key-up is no longer the only thing that stops it. While a key really is
 * down the platform re-delivers it as auto-repeat every ~50ms, after an
 * initial ~400ms pause; treating those as a heartbeat means the slide stops
 * within a blink of the key actually being released, whatever happened to the
 * release event. The threshold has to clear that initial pause, or a genuine
 * hold would cut out just as it got going.
 */
private const val SLIDER_HEARTBEAT_MS = 700L

/**
 * How the hold coarsens: fine steps first so a slow adjustment stays precise,
 * then 0.5s, then 1s — which crosses the whole ±60s in about four seconds
 * without ever making the first half-second of a press imprecise.
 */
private const val SLIDER_FINE_REPEATS = 8
private const val SLIDER_MEDIUM_REPEATS = 20

/** What a remote or a keyboard uses to confirm; here, to close the panel. */
private val CONFIRM_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar)
private const val FOCUS_RESTORE_ATTEMPTS = 3

@Composable
fun PlayerScreen(
    graph: DataGraph,
    type: MediaType,
    tmdbId: Int,
    season: Int?,
    episode: Int?,
    manualSelect: Boolean = false,
    onBack: () -> Unit,
    onOpenAddons: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel = hubViewModel(key = "player-$type-$tmdbId-$season-$episode-$manualSelect") {
        PlayerViewModel(graph, appContext, type, tmdbId, season, episode, manualSelect)
    }

    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val castPreview by viewModel.castPreview.collectAsStateWithLifecycle()
    val playback by viewModel.controller.state.collectAsStateWithLifecycle()
    val position by viewModel.controller.position.collectAsStateWithLifecycle()
    
    val subtitleColorString by viewModel.subtitleColor.collectAsStateWithLifecycle()
    val subtitleTextOpacity by viewModel.subtitleTextOpacity.collectAsStateWithLifecycle()
    val subtitleBackgroundEnabled by viewModel.subtitleBackgroundEnabled.collectAsStateWithLifecycle()
    val subtitleBackgroundOpacity by viewModel.subtitleBackgroundOpacity.collectAsStateWithLifecycle()

    val parsedSubtitleColor = when (subtitleColorString) {
        "white" -> Color.White
        "red" -> Color.Red
        "blue" -> Color.Blue
        "black" -> Color.Black
        else -> Color.Yellow
    }.copy(alpha = subtitleTextOpacity.coerceIn(0, 100) / 100f)
    val parsedSubtitleBackgroundOpacity = if (subtitleBackgroundEnabled) {
        subtitleBackgroundOpacity.coerceIn(0, 100) / 100f
    } else {
        0f
    }

    var osdVisibleUntil by remember { mutableLongStateOf(System.currentTimeMillis() + OSD_TIMEOUT_MS) }
    /**
     * One flip at the deadline, rather than a clock the UI reads.
     *
     * This used to hold `now`, re-sampled every 250ms, which recomposed the
     * whole player screen sixteen times over a four-second OSD just to
     * discover it was still visible. Nothing on screen shows the current
     * time, so the only question worth asking is "has it expired yet", and
     * that has exactly one answer change.
     */
    var osdExpired by remember { mutableStateOf(false) }
    var subtitlePickerOpen by remember { mutableStateOf(false) }
    var subtitleSyncOpen by remember { mutableStateOf(false) }
    var audioPickerOpen by remember { mutableStateOf(false) }
    var castRailOpen by remember { mutableStateOf(false) }
    val overlayOpen = subtitlePickerOpen || subtitleSyncOpen || audioPickerOpen

    /**
     * The sync overlay deliberately does not count here.
     *
     * Judging whether a subtitle is early or late means watching it move, so
     * the caption has to stay on screen while the offset is being nudged —
     * hiding it left the user adjusting a number against a film with no
     * subtitles on it. The two pickers do count: they are lists that cover the
     * picture anyway, and a caption behind one is only clutter.
     */
    val listOverlayOpen = subtitlePickerOpen || audioPickerOpen
    // Whether one of the OSD buttons currently holds focus. While it does,
    // left/right have to move focus between the buttons instead of seeking —
    // see the key handler below.
    var controlsFocused by remember { mutableStateOf(false) }
    val playButtonFocusRequester = remember { FocusRequester() }
    val firstFlatControlFocusRequester = remember { FocusRequester() }
    // Set the moment OK/Enter wakes a hidden OSD, so focus can land on Play
    // as soon as it composes — the button does not exist yet in the same
    // frame the key press arrives in.
    var wantsPlayFocus by remember { mutableStateOf(true) }
    // Direction Down has a different intent from OK: it means "enter the
    // controls", so land directly on the first action row (Subtitles) rather
    // than making the viewer press Down a second time from Play.
    var wantsFlatControlFocus by remember { mutableStateOf(false) }

    // Paused: the OSD has nothing to hide behind, so it stays up.
    // Buffering no longer forces the OSD visible to prevent flashing during micro-stutters.
    val osdVisible = !osdExpired || playback.phase == PlaybackPhase.PAUSED || castRailOpen

    LaunchedEffect(osdVisibleUntil) {
        osdExpired = false
        val remaining = osdVisibleUntil - System.currentTimeMillis()
        if (remaining > 0) delay(remaining)
        osdExpired = true
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Tells the controller whether anything is reading the position, so it can
    // drop from polling twice a second to once every four while the OSD is
    // away — which is most of a film.
    LaunchedEffect(osdVisible) { viewModel.controller.setOsdVisible(osdVisible) }

    // Closing a picker and re-composing the OSD happen in the same snapshot.
    // Wait a frame so a disappearing focus target cannot clear the new OSD
    // focus afterwards. Only consume the intent once Compose reports that
    // the newly attached button accepted focus.
    LaunchedEffect(
        osdVisible,
        wantsPlayFocus,
        wantsFlatControlFocus,
        playback.canShowVideo,
        overlayOpen,
    ) {
        if (!osdVisible || (!wantsPlayFocus && !wantsFlatControlFocus) ||
            !playback.canShowVideo || overlayOpen
        ) {
            return@LaunchedEffect
        }
        repeat(FOCUS_RESTORE_ATTEMPTS) {
            withFrameNanos { }
            if (!osdVisible || (!wantsPlayFocus && !wantsFlatControlFocus) ||
                !playback.canShowVideo || overlayOpen
            ) {
                return@LaunchedEffect
            }
            val accepted = if (wantsFlatControlFocus) {
                firstFlatControlFocusRequester.requestFocus(FocusDirection.Enter)
            } else {
                playButtonFocusRequester.requestFocus(FocusDirection.Enter)
            }
            if (accepted) {
                wantsPlayFocus = false
                wantsFlatControlFocus = false
                return@LaunchedEffect
            }
        }
    }

    // Both flip `osdExpired` directly rather than leaving it to the effect
    // above: the effect runs a frame later, which on the hide path is long
    // enough to see the OSD blink back before it goes.
    fun poke() {
        osdExpired = false
        osdVisibleUntil = System.currentTimeMillis() + OSD_TIMEOUT_MS
    }

    fun hideNow() { osdExpired = true }

    BackHandler {
        when {
            subtitleSyncOpen -> {
                subtitleSyncOpen = false
                subtitlePickerOpen = true
                poke()
            }
            subtitlePickerOpen -> {
                subtitlePickerOpen = false
                wantsPlayFocus = true
                poke()
            }
            audioPickerOpen -> {
                audioPickerOpen = false
                wantsPlayFocus = true
                poke()
            }
            castRailOpen -> {
                castRailOpen = false
                wantsPlayFocus = true
                poke()
            }
            // The OSD is up because something just woke it, not because the
            // film is paused — `osdVisible` alone would also be true while
            // paused (see its definition above), and gating on that would
            // trap back behind an OSD that never auto-hides. Playing is what
            // makes this "still watching, just glanced at the controls"
            // rather than "stopped and looking at a screen with a back
            // button on it".
            osdVisible && playback.isPlaying -> hideNow()
            else -> {
                // Shrink to the floating window instead of tearing playback
                // down, but only for a title genuinely still running —
                // `canShowVideo` excludes the resolving/failed/selecting
                // phases, where there is nothing worth keeping alive, and the
                // remaining-time floor keeps a couple of minutes of credits
                // from being carried around Home as a decoder no one is
                // watching. See `PlayerViewModel.minimize`.
                val remainingMs = position.durationMs - position.positionMs
                android.util.Log.w(
                    "BACKPLAYER",
                    "canShowVideo=${playback.canShowVideo} durationMs=${position.durationMs} " +
                        "positionMs=${position.positionMs} remainingMs=$remainingMs " +
                        "osdVisible=$osdVisible isPlaying=${playback.isPlaying}",
                )
                if (playback.canShowVideo &&
                    position.durationMs > 0 &&
                    remainingMs > MINI_PLAYER_MIN_REMAINING_MS
                ) {
                    android.util.Log.w("BACKPLAYER", "minimizing")
                    viewModel.minimize()
                } else {
                    android.util.Log.w("BACKPLAYER", "NOT minimizing")
                }
                onBack()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(HubColors.Background)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Nothing to seek, toggle or wake an OSD for without a video
                // — and this handler sits on the root Box, an ancestor of
                // everything including `FailureVeil`, so left unconditional
                // it swallows Enter/Left/Right before they ever reach the
                // "Ver addons"/"Voltar" buttons on a failed playback. Every
                // key falls through untouched so Compose's own focus
                // traversal and click handling run instead.
                if (!playback.canShowVideo) return@onPreviewKeyEvent false
                // Pickers and the sync panel own the remote while they are
                // open. In particular, OK must activate their focused row —
                // never fall through to the player's play/pause shortcut.
                if (overlayOpen) return@onPreviewKeyEvent false
                // Back has its own handler below, and it needs to be able to
                // *hide* the OSD — which an unconditional poke() here would
                // undo in the same keypress, since this preview handler runs
                // first and would re-wake it right before (or after)
                // `BackHandler` puts it away.
                if (event.key == Key.Back) return@onPreviewKeyEvent false
                // Read before the poke() below moves it, so this still
                // reflects whether the OSD was hidden *before* this press.
                val wasHidden = !osdVisible
                poke()

                when (event.key) {
                    // Three different things, depending on what OK/Enter
                    // finds: a focused button gets to handle its own press; a
                    // hidden OSD wakes with focus landing directly on Play,
                    // so the first press is never a blind guess at what it
                    // just did; and only once the OSD is already up with
                    // nothing focused does the old "OK toggles play" shortcut
                    // apply.
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        when {
                            controlsFocused -> false
                            wasHidden -> {
                                wantsFlatControlFocus = false
                                wantsPlayFocus = true
                                true
                            }
                            else -> {
                                viewModel.controller.togglePlayPause(); true
                            }
                        }
                    }
                    Key.MediaPlayPause -> {
                        viewModel.controller.togglePlayPause(); true
                    }
                    // Same story for left/right: they seek while browsing the
                    // video, but the moment a control is focused they have to
                    // fall through so the D-pad walks between the buttons
                    // instead of always skipping the film forward/back.
                    Key.DirectionRight -> {
                        if (controlsFocused) {
                            false
                        } else {
                            viewModel.controller.seekBy(SEEK_STEP_MS); true
                        }
                    }
                    Key.DirectionLeft -> {
                        if (controlsFocused) {
                            false
                        } else {
                            viewModel.controller.seekBy(-SEEK_STEP_MS); true
                        }
                    }
                    Key.DirectionDown -> {
                        if (controlsFocused) {
                            false
                        } else {
                            wantsPlayFocus = false
                            wantsFlatControlFocus = true
                            true
                        }
                    }
                    Key.MediaFastForward -> {
                        viewModel.controller.seekBy(SEEK_STEP_MS); true
                    }
                    Key.MediaRewind -> {
                        viewModel.controller.seekBy(-SEEK_STEP_MS); true
                    }
                    // Any other key only wakes the OSD, which is what a remote
                    // user expects from pressing "something".
                    else -> false
                }
            }
            // A remote has an "any key wakes the OSD" gesture built in; a
            // touchscreen has nothing equivalent unless a tap is wired to the
            // same effect. Without this, the OSD fades four seconds in and a
            // phone has no way left to bring it back.
            .pointerInput(playback.canShowVideo) {
                if (!playback.canShowVideo) return@pointerInput
                detectTapGestures(
                    onTap = {
                        if (osdVisible && playback.isPlaying) hideNow() else poke()
                    },
                )
            },
    ) {
        ExoVideoSurface(
            controller = viewModel.controller,
            scaleType = playback.scaleType,
            subtitleColor = parsedSubtitleColor,
            subtitleBackgroundOpacity = parsedSubtitleBackgroundOpacity,
            modifier = Modifier.fillMaxSize(),
        )

        /*
         * The veil.
         *
         * Everything the cascade does happens under this: nine sources may be
         * probed and abandoned while it is up, and the only thing on screen is
         * the film's own artwork and a line saying it is starting. That is the
         * entire point of hiding the sources — the failover has to be
         * invisible, or it is just a slower version of a picker.
         */
        if (ui.searching || playback.phase == PlaybackPhase.RESOLVING) {
            ResolvingVeil(
                backdropUrl = ui.backdropUrl,
                logoUrl = ui.logoUrl,
                overview = ui.overview,
                title = ui.title,
                subtitle = ui.episodeLabel,
                attempt = playback.attempt,
                total = playback.candidateCount,
            )
        }

        if (playback.phase == PlaybackPhase.SELECTING) {
            FailureVeil(
                backdropUrl = ui.backdropUrl,
                title = stringResource(R.string.player_select_title),
                message = if (playback.availableSources.isEmpty()) {
                    stringResource(R.string.player_select_searching)
                } else {
                    stringResource(R.string.player_select_pick)
                },
                showAddons = false,
                onOpenAddons = onOpenAddons,
                onBack = onBack,
                sources = playback.availableSources,
                onSelectSource = { stream -> viewModel.controller.playManual(stream) },
            )
        }

        if (playback.phase == PlaybackPhase.FAILED || ui.noAddons || ui.missingImdbId) {
            FailureVeil(
                backdropUrl = ui.backdropUrl,
                title = stringResource(R.string.player_failed_title),
                message = when {
                    ui.noAddons -> stringResource(R.string.player_no_addons)
                    ui.missingImdbId -> stringResource(R.string.player_no_imdb)
                    else -> playback.error?.message()
                        ?: stringResource(R.string.player_generic_error)
                },
                showAddons = ui.noAddons || playback.phase == PlaybackPhase.FAILED,
                onOpenAddons = onOpenAddons,
                onBack = onBack,
                sources = playback.availableSources,
                onSelectSource = { stream -> viewModel.controller.playManual(stream) },
            )
        }

        // `canShowVideo` deliberately excludes ENDED — without a veil here the
        // OSD (which does too) simply disappears the moment a film finishes,
        // leaving a black screen with no visible way off it.
        if (playback.phase == PlaybackPhase.ENDED) {
            FailureVeil(
                backdropUrl = ui.backdropUrl,
                title = stringResource(R.string.player_ended_title),
                message = ui.title,
                showAddons = false,
                onOpenAddons = onOpenAddons,
                onBack = onBack,
            )
        }

        // Whether the controls are really drawn, which is not the same question
        // as whether they have timed out: a paused film keeps `osdVisible` true
        // while an overlay is up and the OSD itself is not composed. The
        // caption lifts for the controls, so it has to read the same flag they
        // do — otherwise opening sync on a paused film shunts the subtitle up
        // to clear a gradient that is not there.
        val osdOnScreen = osdVisible && playback.canShowVideo && !overlayOpen

        // Independent of the OSD gradient: a caption still belongs on screen
        // while the controls are hidden, which is most of a film's runtime.
        //
        // The cue is collected *inside* the layer rather than here, and that
        // is deliberate: it changes once per line of dialogue, and read at
        // this level it recomposed the whole player screen every couple of
        // seconds for the length of a conversation. See [PlaybackPosition].
        if (playback.canShowVideo && !listOverlayOpen) {
            ExternalSubtitleLayer(
                cue = viewModel.controller.activeSubtitleCue,
                liftForOsd = osdOnScreen,
                liftForCast = castRailOpen,
                color = parsedSubtitleColor,
                subtitleBackgroundOpacity = parsedSubtitleBackgroundOpacity,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (osdOnScreen) {
            PlayerTitlePlate(
                title = ui.title,
                date = ui.episodeLabel,
                logoUrl = ui.logoUrl,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            PlayerOsd(
                // The flow, not two Longs. Collected inside the OSD so the
                // twice-a-second tick recomposes the seek bar and the two
                // time labels instead of this whole screen — and stops
                // reaching composition at all once the OSD hides itself.
                position = viewModel.controller.position,
                playing = playback.isPlaying,
                subtitleActive = playback.externalSubtitle != null ||
                    playback.currentSubtitleId != NO_TRACK,
                scaleType = playback.scaleType,
                onTogglePlay = { viewModel.controller.togglePlayPause(); poke() },
                onSeek = { deltaMs -> viewModel.controller.seekBy(deltaMs); poke() },
                onCycleScale = { viewModel.controller.cycleScale(); poke() },
                onOpenSubtitles = {
                    castRailOpen = false
                    subtitlePickerOpen = true
                    poke()
                },
                onOpenAudio = {
                    castRailOpen = false
                    audioPickerOpen = true
                    poke()
                },
                cast = ui.cast,
                castRailOpen = castRailOpen,
                castPreview = castPreview,
                onToggleCast = {
                    castRailOpen = !castRailOpen
                    poke()
                },
                onPreviewCast = { member ->
                    viewModel.previewCast(member)
                    poke()
                },
                onControlsFocusChanged = { controlsFocused = it },
                playButtonFocusRequester = playButtonFocusRequester,
                firstFlatControlFocusRequester = firstFlatControlFocusRequester,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (subtitlePickerOpen) {
        SubtitlePickerOverlay(
            options = ui.subtitles,
            embedded = playback.subtitleTracks,
            active = playback.externalSubtitle,
            activeEmbeddedId = playback.currentSubtitleId,
            offsetMs = playback.subtitleOffsetMs,
            onOpenSync = {
                subtitlePickerOpen = false
                subtitleSyncOpen = true
                hideNow()
            },
            onSelect = { option ->
                viewModel.selectSubtitle(option)
                subtitlePickerOpen = false
                wantsPlayFocus = true
                poke()
            },
            onSelectEmbedded = { id ->
                viewModel.selectEmbeddedSubtitle(id)
                subtitlePickerOpen = false
                wantsPlayFocus = true
                poke()
            },
            onDismiss = {
                subtitlePickerOpen = false
                wantsPlayFocus = true
                poke()
            },
        )
    }

    if (subtitleSyncOpen && playback.externalSubtitle != null) {
        SubtitleSyncOverlay(
            offsetMs = playback.subtitleOffsetMs,
            onAdjust = viewModel.controller::adjustSubtitleOffset,
            onSet = viewModel.controller::setSubtitleOffset,
            onDismiss = {
                subtitleSyncOpen = false
                wantsPlayFocus = true
                poke()
            },
        )
    }

    if (audioPickerOpen) {
        AudioPickerOverlay(
            options = playback.audioTracks,
            activeId = playback.currentAudioId,
            silenced = playback.audioSilenced,
            onSelect = { id ->
                viewModel.controller.selectAudioTrack(id)
                audioPickerOpen = false
                wantsPlayFocus = true
                poke()
            },
            onDismiss = {
                audioPickerOpen = false
                wantsPlayFocus = true
                poke()
            },
        )
    }
}

@Composable
private fun AutoScrollText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        scrollState.scrollTo(0)
        delay(7000)
        while (isActive) {
            val max = scrollState.maxValue
            if (max > 0) {
                // 190ms/px, matching the home hero's copy of this helper.
                scrollState.animateScrollTo(max, animationSpec = tween(durationMillis = max * 190, easing = LinearEasing))
                delay(4000)
                scrollState.animateScrollTo(0, animationSpec = tween(durationMillis = 800))
                delay(3000)
            } else {
                delay(1000)
            }
        }
    }
    Text(
        text = text,
        style = style,
        color = color,
        // The veil is a centred column, which centres this text's *box* but
        // says nothing about the lines inside it — without this the synopsis
        // sat ragged-right against a centred title and spinner. The home
        // screen's own copy of this helper deliberately stays left-aligned,
        // since its hero panel is a left-aligned layout.
        textAlign = TextAlign.Center,
        modifier = modifier.verticalScroll(scrollState)
    )
}

@Composable
private fun ResolvingVeil(
    backdropUrl: String?,
    logoUrl: String?,
    overview: String?,
    title: String,
    subtitle: String?,
    attempt: Int,
    total: Int,
) {
    // Height reserved for the status footer below, so the centred block above
    // can never grow into it — see the comment on that footer for why this
    // used to be one unbroken column instead of two.
    val footerHeight = 96.dp

    Box(Modifier.fillMaxSize()) {
        FanartBackdrop(url = backdropUrl, scrim = 0.9f)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp)
                .padding(top = 64.dp, bottom = 64.dp + footerHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Weighted rather than a plain Center-arranged sibling of the
            // synopsis below: spinner + clearlogo + both spacers can add up
            // to more height than a shorter TV screen has to give — with
            // both in one Center-arranged column, that overflow ate
            // straight into the synopsis's own fixed-height box, since
            // Center distributes the overflow across every child in the
            // block equally rather than sparing any one of them. Giving
            // this block the weight instead means it is the one that
            // yields when space is tight, and the synopsis below always
            // gets its full, undiminished height.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            HubSpinner(size = 44.dp)
            Spacer(Modifier.height(32.dp))

            val infiniteTransition = rememberInfiniteTransition(label = "ClearlogoTransition")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.15f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "ClearlogoFade"
            )
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.97f,
                targetValue = 1.03f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = HubMotion.StandardEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "ClearlogoScale"
            )

            if (!logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 112.dp)
                        .fillMaxWidth(0.35f)
                        .scale(scale)
                        .alpha(alpha)
                )
            } else {
                Text(
                    text = title.ifBlank { stringResource(R.string.player_preparing_short) },
                    style = MaterialTheme.typography.headlineLarge,
                    color = HubColors.Text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .scale(scale)
                        .alpha(alpha)
                )
            }
            }

            if (!overview.isNullOrBlank()) {
                Spacer(Modifier.height(48.dp))
                AutoScrollText(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HubColors.TextDim,
                    modifier = Modifier
                        .widthIn(max = 700.dp)
                        // Exactly 5 lines, guaranteed: this box is a fixed
                        // sibling of the weighted spinner/logo block above,
                        // not sharing a Center arrangement with it, so it is
                        // never squeezed by that block overflowing on a
                        // shorter screen. Not simply 5 * bodyMedium's nominal
                        // 21.sp line height (105dp) — measured on device that
                        // undershoots by roughly a fifth of a line, almost
                        // certainly Android's default font padding/leading
                        // that the nominal lineHeight doesn't account for.
                        // 126dp is the value that actually renders 5 full
                        // lines; text past that scrolls via AutoScrollText
                        // rather than being cut off blind.
                        .height(126.dp)
                )
            }
        }

        // Pinned to the bottom edge on its own, independent of the block
        // above. This used to be the last child of that same Column, arranged
        // with `Center` and no scroll — so on a title with a long synopsis or
        // a title that wrapped to two lines, the whole stack grew taller than
        // the screen and `Center` overflowed it symmetrically: the spinner
        // clipped against the top edge while this text was pushed past the
        // bottom one. Anchoring it here with its own fixed margin is what
        // guarantees it stays a safe distance from the edge no matter how
        // tall the content above happens to be.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .height(footerHeight),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.player_preparing),
                style = MaterialTheme.typography.bodyLarge,
                color = HubColors.TextDim,
            )
            // Deliberately vague about *what* is being tried. The count is
            // there to show progress, not to invite a choice.
            if (total > 1 && attempt > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.player_attempt, attempt, total),
                    style = MaterialTheme.typography.labelSmall,
                    color = HubColors.TextFaint,
                )
            }
        }
    }
}

@Composable
private fun FailureVeil(
    backdropUrl: String?,
    title: String,
    message: String,
    showAddons: Boolean,
    onOpenAddons: () -> Unit,
    onBack: () -> Unit,
    /**
     * Every source the automatic cascade tried, offered up for a manual pick
     * now that trying them itself has failed. Empty for every failure that
     * isn't [PlaybackPhase.FAILED] — a missing addon or IMDb id has no
     * candidates to list in the first place.
     */
    sources: List<PlayableStream> = emptyList(),
    onSelectSource: (PlayableStream) -> Unit = {},
) {
    // Without this the D-pad has nothing focused to move from, since the
    // only thing that ever claimed focus was the player screen's own root
    // Box underneath this veil. Re-keyed on the source list so a manual pick
    // that itself fails re-focuses the first row again, not empty space.
    val primaryFocus = remember { FocusRequester() }

    // Retried across frames rather than requested once. When [sources] is
    // non-empty the target is a LazyColumn row, and a lazy list composes and
    // places its items during *layout* — after this effect first runs. A
    // single request therefore fires before row zero exists, throws, and
    // leaves nothing focused at all: the picker still answers a tap, so it
    // looks fine on a phone or under `adb input tap`, while on the remote
    // this screen actually ships to, every key press does nothing.
    LaunchedEffect(sources) {
        repeat(FOCUS_RESTORE_ATTEMPTS) {
            withFrameNanos { }
            if (runCatching { primaryFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Box(Modifier.fillMaxSize()) {
        FanartBackdrop(url = backdropUrl, scrim = 0.94f)

        Column(
            modifier = Modifier.fillMaxSize().padding(72.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = HubColors.Text,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = HubColors.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 640.dp),
            )

            if (sources.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text(
                    text = stringResource(R.string.player_pick_source),
                    style = MaterialTheme.typography.titleMedium,
                    color = HubColors.Text,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(HubColors.Surface)
                        .border(1.dp, HubColors.Border, RoundedCornerShape(14.dp)),
                ) {
                    itemsIndexed(sources, key = { _, stream -> stream.key }) { index, stream ->
                        SourceRow(
                            stream = stream,
                            onClick = { onSelectSource(stream) },
                            modifier = if (index == 0) Modifier.focusRequester(primaryFocus) else Modifier,
                        )
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (showAddons) {
                    HubButton(
                        stringResource(R.string.player_open_addons),
                        onOpenAddons,
                        modifier = if (sources.isEmpty()) Modifier.focusRequester(primaryFocus) else Modifier,
                        primary = true,
                    )
                    HubButton(stringResource(R.string.player_back), onBack)
                } else {
                    HubButton(
                        stringResource(R.string.player_back),
                        onBack,
                        modifier = if (sources.isEmpty()) Modifier.focusRequester(primaryFocus) else Modifier,
                    )
                }
            }
        }
    }
}

/** One row of [FailureVeil]'s manual source list — an addon, a quality, a size. */
@Composable
private fun SourceRow(
    stream: PlayableStream,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(if (focused) HubColors.Accent.copy(alpha = 0.3f) else HubColors.Background.copy(alpha = 0f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = stream.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) HubColors.AccentSoft else HubColors.Text,
        )
        val meta = listOfNotNull(stream.quality, stream.size, stream.addon).joinToString("  •  ")
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = HubColors.TextDim,
            )
        }
    }
}

/**
 * Holds the subscription to the cue flow so [ExternalSubtitleOverlay] can stay
 * a pure drawing composable — and, more to the point, so that a new line of
 * dialogue recomposes this and nothing above it.
 *
 * The blank check lives here rather than at the call site for the same reason:
 * a gap between cues is the common case, and deciding it upstream would mean
 * reading the cue upstream.
 */
@Composable
private fun ExternalSubtitleLayer(
    cue: StateFlow<String?>,
    liftForOsd: Boolean,
    liftForCast: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.Yellow,
    subtitleBackgroundOpacity: Float = 0f,
) {
    val text by cue.collectAsStateWithLifecycle()
    val line = text
    if (line.isNullOrBlank()) return
    ExternalSubtitleOverlay(
        text = line,
        liftForOsd = liftForOsd,
        liftForCast = liftForCast,
        color = color,
        subtitleBackgroundOpacity = subtitleBackgroundOpacity,
        modifier = modifier,
    )
}

/**
 * The external subtitle's current line, drawn by this app rather than handed
 * to ExoPlayer as a side-loaded track — see `PlaybackController`. Owning the
 * draw is what let synchronizing become a pure UI operation: adjusting the
 * offset changes which cue this reads on the next tick, nothing about the
 * film underneath it.
 *
 * Styled to match the embedded-subtitle look this app forces on container
 * subtitles (`ExoVideoSurface`): selected text colour and opacity, with an
 * optional black background controlled independently.
 */
@Composable
private fun ExternalSubtitleOverlay(
    text: String,
    liftForOsd: Boolean,
    liftForCast: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.Yellow,
    subtitleBackgroundOpacity: Float = 0f,
) {
    val bottomPadding by animateDpAsState(
        when {
            liftForCast -> 350.dp
            liftForOsd -> 168.dp
            else -> 40.dp
        },
        focusTween(),
        label = "subtitle-lift",
    )
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = SUBTITLE_FONT_SIZE,
        // Set explicitly, and that is the whole fix for two-line cues
        // crowding each other. `fontSize` was being overridden to 26sp
        // while `lineHeight` kept coming from `bodyLarge`, which is sized
        // for a ~16sp body — so the gap between lines was *smaller* than
        // the glyphs themselves and a two-liner read as one dense block.
        lineHeight = SUBTITLE_LINE_HEIGHT,
        shadow = Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 5f),
    )
    Text(
        text = text,
        modifier = modifier
            .padding(horizontal = 32.dp)
            .padding(bottom = bottomPadding)
            .then(
                if (subtitleBackgroundOpacity > 0f) {
                    Modifier
                        .background(
                            Color.Black.copy(alpha = subtitleBackgroundOpacity.coerceIn(0f, 1f)),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                } else {
                    Modifier
                },
            )
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = color,
        textAlign = TextAlign.Center,
        style = textStyle,
    )
}

@Composable
private fun PlayerOsd(
    position: StateFlow<PlaybackPosition>,
    playing: Boolean,
    subtitleActive: Boolean,
    scaleType: VideoScaleType,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleScale: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
    cast: List<CastMember>,
    castRailOpen: Boolean,
    castPreview: PlayerCastPreviewState,
    onToggleCast: () -> Unit,
    onPreviewCast: (CastMember) -> Unit,
    onControlsFocusChanged: (Boolean) -> Unit,
    playButtonFocusRequester: FocusRequester,
    firstFlatControlFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    // Collected here rather than passed in as two Longs. The tick is twice a
    // second while the controls are up, and this is the deepest point that
    // still covers all three readers below — the elapsed label, the bar and
    // the remaining label. Above this, nothing hears it; below it, the focus
    // requesters would be rebuilt on every tick.
    val playhead by position.collectAsStateWithLifecycle()
    val positionMs = playhead.positionMs
    val durationMs = playhead.durationMs
    val scaleLabel = stringResource(
        when (scaleType) {
            VideoScaleType.FIT -> R.string.player_scale_fit
            VideoScaleType.STRETCH -> R.string.player_scale_stretch
            VideoScaleType.ZOOM -> R.string.player_scale_zoom
        },
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            // One focus group owns the state for every OSD target, including
            // the progress bar. Independent callbacks used to race and leave
            // the player root stealing OK/left/right from a focused control.
            .focusGroup()
            .onFocusChanged { onControlsFocusChanged(it.hasFocus) }
            .background(
                Brush.verticalGradient(
                    listOf(
                        HubColors.Background.copy(alpha = 0f),
                        HubColors.Background.copy(alpha = 0.64f),
                        HubColors.Background.copy(alpha = 0.96f),
                    )
                )
            )
            .padding(horizontal = 40.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (castRailOpen) {
            CastRail(
                cast = cast,
                preview = castPreview,
                onPreview = onPreviewCast,
                onMoveDown = { playButtonFocusRequester.requestFocus() },
            )
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.width((maxWidth * 0.8f) + 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            TimelinePlayButton(
                playing = playing,
                onClick = onTogglePlay,
                modifier = Modifier
                    .focusRequester(playButtonFocusRequester)
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                onSeek(-SEEK_STEP_MS)
                                true
                            }
                            Key.DirectionRight -> {
                                onSeek(SEEK_STEP_MS)
                                true
                            }
                            Key.DirectionDown -> firstFlatControlFocusRequester.requestFocus()
                            else -> false
                        }
                    },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatTime(positionMs),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = (MaterialTheme.typography.titleMedium.fontSize.value - 1f).sp,
                ),
                color = if (HubColors.isCyberpunk) HubColors.Accent else HubColors.Text,
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(HubShapes.Pill))
                    .background(Color(0xFFD9D9D9).copy(alpha = 0.6f))
            ) {
                val fraction = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(RoundedCornerShape(HubShapes.Pill))
                        .background(HubColors.Accent),
                )
            }
            Text(
                text = formatTime((durationMs - positionMs).coerceAtLeast(0L)),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = (MaterialTheme.typography.titleMedium.fontSize.value - 1f).sp,
                ),
                color = if (HubColors.isCyberpunk) HubColors.Accent else HubColors.TextDim,
            )
        }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-4).dp)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                        playButtonFocusRequester.requestFocus(); true
                    } else false
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlatOsdButton(
                label = stringResource(R.string.player_subtitles),
                onClick = onOpenSubtitles,
                active = subtitleActive,
                modifier = Modifier.focusRequester(firstFlatControlFocusRequester),
            )
            FlatOsdButton(
                label = stringResource(R.string.player_audio_short),
                onClick = onOpenAudio,
            )
            FlatOsdButton(
                label = scaleLabel,
                onClick = onCycleScale,
            )
            FlatOsdButton(
                label = stringResource(R.string.player_cast),
                onClick = onToggleCast,
                active = castRailOpen,
                enabled = cast.isNotEmpty(),
            )
        }
    }
}

/** Title identity anchored halfway down the left edge of the picture. */
@Composable
private fun PlayerTitlePlate(
    title: String,
    date: String?,
    logoUrl: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(start = 40.dp)
            .widthIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier = Modifier
                    .width(256.dp)
                    .heightIn(max = 76.dp),
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = HubColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        date?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = HubColors.TextDim,
            )
        }
    }
}

@Composable
private fun TimelinePlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, focusTween(), label = "play-scale")

    Box(
        modifier = modifier
            .scale(scale)
            .size(50.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else HubColors.Accent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
            tint = HubColors.Background,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun FlatOsdButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val background by animateColorAsState(
        when {
            focused && enabled -> Color.White
            active -> HubColors.Accent.copy(alpha = 0.22f)
            else -> HubColors.Surface.copy(alpha = 0.52f)
        },
        focusTween(),
        label = "flat-control-background",
    )
    val foreground = when {
        !enabled -> HubColors.TextFaint.copy(alpha = 0.45f)
        focused -> HubColors.Background
        active -> HubColors.AccentSoft
        else -> HubColors.TextDim
    }

    Box(
        modifier = Modifier
            .width(94.dp)
            .height(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = modifier
                .width(82.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(HubShapes.Pill))
                .background(background)
                .border(
                    width = if (focused && enabled) HubStrokes.Focus else HubStrokes.Hairline,
                    color = when {
                        focused && enabled -> Color.White
                        active -> HubColors.Accent
                        else -> HubColors.Border
                    },
                    shape = RoundedCornerShape(HubShapes.Pill),
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** D-pad focus is the TV equivalent of hover; touch selects the same portrait. */
@Composable
private fun CastRail(
    cast: List<CastMember>,
    preview: PlayerCastPreviewState,
    onPreview: (CastMember) -> Unit,
    onMoveDown: () -> Unit,
) {
    val firstCastFocusRequester = remember { FocusRequester() }

    LaunchedEffect(cast) {
        val first = cast.firstOrNull() ?: return@LaunchedEffect
        onPreview(first)
        repeat(FOCUS_RESTORE_ATTEMPTS) {
            withFrameNanos { }
            if (firstCastFocusRequester.requestFocus()) return@LaunchedEffect
        }
    }

    val railShape = RoundedCornerShape(HubShapes.Card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(railShape)
            .background(HubColors.Surface.copy(alpha = HubEffects.GlassSurfaceAlpha + 0.12f))
            .border(HubStrokes.Hairline, HubColors.Border.copy(alpha = 0.7f), railShape)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        CastBiographyCard(preview)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        onMoveDown(); true
                    } else false
                },
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(cast.take(16), key = { it.id }) { member ->
                CastBubble(
                    member = member,
                    selected = preview.member?.id == member.id,
                    onPreview = { onPreview(member) },
                    modifier = if (member.id == cast.firstOrNull()?.id) {
                        Modifier.focusRequester(firstCastFocusRequester)
                    } else Modifier,
                )
            }
        }
    }
}

@Composable
private fun CastBiographyCard(preview: PlayerCastPreviewState) {
    val member = preview.member
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(HubColors.SurfaceStrong),
            contentAlignment = Alignment.Center,
        ) {
            val photo = member?.profileUrl ?: preview.summary?.thumbnailUrl
            if (photo != null) {
                AsyncImage(
                    model = photo,
                    contentDescription = member?.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Filled.Person, null, tint = HubColors.TextFaint, modifier = Modifier.size(28.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = member?.name ?: stringResource(R.string.player_cast),
                style = MaterialTheme.typography.titleMedium,
                color = HubColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            member?.character?.let { role ->
                Text(
                    text = role,
                    style = MaterialTheme.typography.labelSmall,
                    color = HubColors.AccentSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                preview.loading -> HubSpinner(size = 22.dp)
                preview.summary != null -> Text(
                    text = preview.summary.extract,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HubColors.TextDim,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                preview.unavailable && member != null -> Text(
                    text = stringResource(R.string.detail_wikipedia_error, member.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HubColors.TextFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CastBubble(
    member: CastMember,
    selected: Boolean,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bubbleScale by animateFloatAsState(
        if (focused) 1.12f else 1f,
        focusTween(),
        label = "cast-bubble-scale",
    )
    val borderWidth by animateDpAsState(
        if (focused || selected) 3.dp else 1.dp,
        focusTween(),
        label = "cast-bubble-border",
    )

    Box(
        modifier = modifier
            .scale(bubbleScale)
            .size(54.dp)
            .clip(CircleShape)
            .background(HubColors.SurfaceStrong)
            .border(
                borderWidth,
                if (focused || selected) HubColors.Accent else HubColors.Border,
                CircleShape,
            )
            .onFocusChanged { if (it.isFocused) onPreview() }
            .clickable(interactionSource = interaction, indication = null, onClick = onPreview),
        contentAlignment = Alignment.Center,
    ) {
        if (member.profileUrl != null) {
            AsyncImage(
                model = member.profileUrl,
                contentDescription = member.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.Person,
                member.name,
                tint = HubColors.TextFaint,
                modifier = Modifier.size(25.dp),
            )
        }
    }
}

/** Shared by every OSD control so the row moves as one consistent motion. */
private fun <T> focusTween(): FiniteAnimationSpec<T> = tween(
    durationMillis = HubMotion.Focus,
    easing = HubMotion.StandardEasing,
)

@Composable
private fun SubtitlePickerOverlay(
    options: List<SubtitleOption>,
    /**
     * The container's own subtitle tracks, listed above the addons' because
     * they need no download and are cut for the release actually playing.
     * Each row says where it came from, since "Português" from the file and
     * "Português" from OpenSubtitles are otherwise the same row twice.
     */
    embedded: List<TrackInfo>,
    active: SubtitleOption?,
    activeEmbeddedId: Int,
    offsetMs: Long,
    onOpenSync: () -> Unit,
    onSelect: (SubtitleOption?) -> Unit,
    onSelectEmbedded: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Opening the sheet does not move focus on its own — it was left sitting
    // on the OSD's "Legenda" button, now hidden behind the scrim, which is
    // why the remote looked dead. Landing focus on the first row is what
    // gives the d-pad something inside the list to move from.
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRowFocus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(HubColors.Background.copy(alpha = 0.7f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 480.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(HubColors.Surface)
                .border(1.dp, HubColors.Border, RoundedCornerShape(14.dp))
                // An explicit empty tap detector, not a disabled `clickable`:
                // consuming the gesture here is what stops a tap inside the
                // sheet from also reaching the scrim's dismiss handler behind
                // it, and a real gesture detector is the part of the contract
                // that is actually guaranteed to consume it.
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.player_subtitles),
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                item(key = "sync") {
                    SubtitleRow(
                        // The offset shifts cues this app parses and draws
                        // itself. A container track is drawn by the player's
                        // own renderer and cannot be moved, so it gets a
                        // reason of its own — "selecione uma legenda" in front
                        // of a plainly selected subtitle reads as a bug.
                        label = when {
                            active != null -> stringResource(
                                R.string.player_subtitle_sync_value,
                                formatSubtitleOffset(offsetMs),
                            )
                            activeEmbeddedId != NO_TRACK ->
                                stringResource(R.string.player_subtitle_sync_embedded)
                            else -> stringResource(R.string.player_subtitle_sync_disabled)
                        },
                        selected = offsetMs != 0L,
                        enabled = active != null,
                        modifier = if (active != null) {
                            Modifier.focusRequester(firstRowFocus)
                        } else {
                            Modifier
                        },
                        onClick = onOpenSync,
                    )
                }
                item(key = "none") {
                    SubtitleRow(
                        label = stringResource(R.string.player_no_subtitle),
                        selected = active == null && activeEmbeddedId == NO_TRACK,
                        modifier = if (active == null) {
                            Modifier.focusRequester(firstRowFocus)
                        } else {
                            Modifier
                        },
                        onClick = { onSelect(null) },
                    )
                }
                // Prefixed keys, not the bare id: a track index and an addon
                // option's key are unrelated namespaces sharing one LazyColumn,
                // and a collision between them is a crash.
                items(embedded, key = { "embedded-${it.id}" }) { track ->
                    SubtitleRow(
                        label = stringResource(
                            R.string.player_subtitle_option,
                            track.displayLabel(),
                            stringResource(R.string.player_subtitle_embedded),
                        ),
                        selected = track.id == activeEmbeddedId,
                        enabled = track.playable,
                        supporting = if (track.playable) {
                            null
                        } else {
                            stringResource(R.string.player_track_unsupported)
                        },
                        onClick = { onSelectEmbedded(track.id) },
                    )
                }
                items(options, key = { "addon-${it.key}" }) { option ->
                    SubtitleRow(
                        label = stringResource(R.string.player_subtitle_option, option.label, option.addon),
                        selected = active?.key == option.key,
                        onClick = { onSelect(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * A second, quieter line under [label] — why a row is greyed out, when
     * being greyed out on its own would read as a bug. Kept out of [label]
     * so the reason cannot elbow the track's actual name off the row.
     */
    supporting: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .background(
                when {
                    focused && enabled -> HubColors.Accent.copy(alpha = 0.3f)
                    selected -> HubColors.Accent.copy(alpha = 0.14f)
                    else -> HubColors.Background.copy(alpha = 0f)
                }
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> HubColors.TextFaint
                focused || selected -> HubColors.AccentSoft
                else -> HubColors.TextDim
            },
        )
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = HubColors.TextFaint,
            )
        }
    }
}

/**
 * Kept at the top so the film and its captions remain visible while the user
 * nudges timing with the remote. The picker closes before this opens; there
 * is deliberately no dark scrim over the video to judge synchronization
 * against, and the caption overlay keeps drawing underneath this panel while
 * it is up (see `listOverlayOpen` in [PlayerScreen]) — the subtitle moving in
 * step with the number is the whole feedback loop this screen exists for.
 */
@Composable
private fun SubtitleSyncOverlay(
    offsetMs: Long,
    onAdjust: (Long) -> Unit,
    onSet: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sliderFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { sliderFocus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            // A pointer-only dismiss layer: `clickable` would make this
            // full-screen transparent box an invisible D-pad focus target.
            .pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp)
                // A definite width, where this used to wrap its content: a bar
                // is the one child here with no natural width of its own, and
                // inside a column that only ever grew to fit its widest child
                // it would have collapsed to nothing.
                //
                // Half of what it first took. A panel this is meant to be
                // glanced at over the film, not read — and at 62% of the screen
                // it was covering enough of the picture to make judging the
                // subtitle underneath it harder, which is the one thing it
                // exists to help with.
                .fillMaxWidth(0.31f)
                .clip(RoundedCornerShape(12.dp))
                .background(HubColors.Surface.copy(alpha = 0.96f))
                .border(1.dp, HubColors.Border, RoundedCornerShape(12.dp))
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.player_subtitle_sync),
                style = MaterialTheme.typography.titleSmall,
                color = HubColors.Text,
            )
            Text(
                text = formatSubtitleOffset(offsetMs),
                style = MaterialTheme.typography.titleLarge,
                color = if (offsetMs == 0L) HubColors.Text else HubColors.AccentSoft,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(10.dp))
            SubtitleOffsetSlider(
                offsetMs = offsetMs,
                onAdjust = onAdjust,
                onSet = onSet,
                onDismiss = onDismiss,
                modifier = Modifier.focusRequester(sliderFocus),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Formatted from the same constant the bar and the controller
                // use, rather than written into strings.xml: an end label that
                // disagrees with where the bar actually stops is worse than no
                // label at all.
                Text(
                    text = formatSubtitleOffset(-MAX_SUBTITLE_OFFSET_MS),
                    style = MaterialTheme.typography.labelSmall,
                    color = HubColors.TextFaint,
                )
                Text(
                    text = formatSubtitleOffset(MAX_SUBTITLE_OFFSET_MS),
                    style = MaterialTheme.typography.labelSmall,
                    color = HubColors.TextFaint,
                )
            }
            // On its own line now rather than between the two end labels:
            // at half the width there is no longer room for three things
            // across, and the end labels are the pair that has to stay
            // pinned to the ends of the bar.
            Text(
                text = stringResource(R.string.player_subtitle_sync_controls),
                style = MaterialTheme.typography.labelSmall,
                color = HubColors.TextDim,
            )
        }
    }
}

/**
 * The whole subtitle-sync control: one bar, dragged with a finger or walked
 * with the d-pad.
 *
 * It replaced a row of −/reset/+/done buttons. Four focus targets to move a
 * number is a lot of remote work for what is really one continuous value, and
 * at ±60s the stepping buttons stopped being viable at all — six hundred
 * presses from one end to the other.
 *
 * Both input styles have to stay honest about precision, which is why the two
 * of them do not share a path:
 *
 * - **A single d-pad press** moves exactly [SUBTITLE_OFFSET_STEP_MS] — 0.1s,
 *   fine enough to land on a line that is only slightly out. It fires inline
 *   in the key handler rather than from the effect below, because a press
 *   short enough to arrive and release inside one frame would otherwise be
 *   dropped: the effect's key would go true and back to false with no
 *   recomposition in between, so it would never restart. Injected key events
 *   and mouse clicks are routinely that short.
 * - **Holding** it hands over to the effect, which keeps the fine step for
 *   half a second and then coarsens — the only way a minute of range is
 *   crossable without lifting a finger.
 * - **Dragging** is absolute: the position of the finger is the value, snapped
 *   to the same 0.1s grid so a drag can still land exactly on zero.
 */
@Composable
private fun SubtitleOffsetSlider(
    offsetMs: Long,
    onAdjust: (Long) -> Unit,
    onSet: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val trackHeight by animateDpAsState(
        if (focused) SLIDER_TRACK_FOCUSED else SLIDER_TRACK_RESTING,
        focusTween(),
        label = "sync-track-height",
    )
    val thumbRadius by animateDpAsState(
        if (focused) SLIDER_THUMB_FOCUSED else SLIDER_THUMB_RESTING,
        focusTween(),
        label = "sync-thumb-radius",
    )
    val thumbRadiusPx = with(LocalDensity.current) { thumbRadius.toPx() }
    // Fixed, unlike the animating radius above: the geometry a drag is read
    // against must not move while the thumb is growing into focus, or the
    // value under the finger shifts on its own.
    val insetPx = with(LocalDensity.current) { SLIDER_THUMB_FOCUSED.toPx() }

    // -1 for left, +1 for right, 0 when nothing is held.
    var heldDirection by remember { mutableIntStateOf(0) }
    // Last time the key was seen down, auto-repeats included — the heartbeat
    // the slide below checks so a missing key-up cannot leave it running.
    var lastKeyDownMs by remember { mutableLongStateOf(0L) }
    val adjust by rememberUpdatedState(onAdjust)

    LaunchedEffect(heldDirection) {
        val direction = heldDirection
        if (direction == 0) return@LaunchedEffect
        // The press itself already moved one fine step; this is the hold.
        delay(SLIDER_HOLD_DELAY_MS)
        var fired = 0
        while (SystemClock.uptimeMillis() - lastKeyDownMs < SLIDER_HEARTBEAT_MS) {
            val step = when {
                fired < SLIDER_FINE_REPEATS -> SUBTITLE_OFFSET_STEP_MS
                fired < SLIDER_MEDIUM_REPEATS -> SUBTITLE_OFFSET_STEP_MS * 5
                else -> SUBTITLE_OFFSET_STEP_MS * 10
            }
            adjust(direction * step)
            delay(SLIDER_REPEAT_INTERVAL_MS)
            fired++
        }
        // Reached only when the heartbeat stopped, i.e. the key-up never came.
        heldDirection = 0
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(SLIDER_THUMB_FOCUSED * 2)
            .onFocusChanged { if (!it.isFocused) heldDirection = 0 }
            .onKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    // Nothing above or below is focusable — this panel is the
                    // only thing on screen taking a remote — so the vertical
                    // directions are swallowed rather than allowed to escape
                    // to the player root behind it.
                    Key.DirectionUp, Key.DirectionDown -> return@onKeyEvent true
                    // The "concluir" button went with the rest of them; OK is
                    // what closes this now. Back still returns to the subtitle
                    // list, which is a different destination on purpose.
                    in CONFIRM_KEYS -> {
                        if (event.type == KeyEventType.KeyUp) onDismiss()
                        return@onKeyEvent true
                    }
                    else -> return@onKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        // Auto-repeat key-downs do not step the value — once a
                        // direction is held the effect above owns the cadence
                        // rather than inheriting the system's — but they are
                        // what keeps the heartbeat alive.
                        lastKeyDownMs = SystemClock.uptimeMillis()
                        if (heldDirection != direction) {
                            onAdjust(direction * SUBTITLE_OFFSET_STEP_MS)
                            heldDirection = direction
                        }
                        true
                    }
                    KeyEventType.KeyUp -> { heldDirection = 0; true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Consumed so the press cannot also reach the panel's
                    // tap-to-dismiss layer underneath.
                    down.consume()
                    onSet(offsetAt(down.position.x, size.width.toFloat(), insetPx))
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                onSet(offsetAt(change.position.x, size.width.toFloat(), insetPx))
                            }
                            change.consume()
                        }
                        pressed = event.changes.any { it.pressed }
                    }
                }
            }
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        val centreY = size.height / 2f
        val usable = (size.width - insetPx * 2f).coerceAtLeast(1f)
        val zeroX = insetPx + usable / 2f
        val valueX = insetPx + usable *
            ((offsetMs + MAX_SUBTITLE_OFFSET_MS).toFloat() / (MAX_SUBTITLE_OFFSET_MS * 2f))

        val trackPx = trackHeight.toPx()
        val corner = CornerRadius(trackPx / 2f, trackPx / 2f)

        drawRoundRect(
            color = HubColors.Border,
            topLeft = Offset(insetPx, centreY - trackPx / 2f),
            size = Size(usable, trackPx),
            cornerRadius = corner,
        )

        // Filled from the centre outwards rather than from the left edge: zero
        // is the meaningful origin here, and which side of it the value sits on
        // is the first thing to read off the bar.
        drawRoundRect(
            color = if (focused) HubColors.Accent else HubColors.AccentSoft,
            topLeft = Offset(minOf(zeroX, valueX), centreY - trackPx / 2f),
            size = Size(kotlin.math.abs(valueX - zeroX), trackPx),
            cornerRadius = corner,
        )

        // The zero detent, drawn over the fill so it stays visible when the
        // value is sitting right next to it.
        val tickHalf = SLIDER_TICK_WIDTH.toPx() / 2f
        drawRoundRect(
            color = HubColors.Text,
            topLeft = Offset(zeroX - tickHalf, centreY - trackPx),
            size = Size(tickHalf * 2f, trackPx * 2f),
            cornerRadius = CornerRadius(tickHalf, tickHalf),
        )

        drawCircle(
            color = if (focused) HubColors.Accent else HubColors.Text,
            radius = thumbRadiusPx,
            center = Offset(valueX, centreY),
        )
    }
}

/**
 * Where along the bar an x lands, in milliseconds, snapped to the d-pad's own
 * step so that a drag can still finish exactly on zero — and so the readout
 * above never shows a value the buttons could not have produced.
 */
private fun offsetAt(x: Float, width: Float, insetPx: Float): Long {
    val usable = (width - insetPx * 2f).coerceAtLeast(1f)
    val fraction = ((x - insetPx) / usable).coerceIn(0f, 1f)
    val raw = (fraction * 2f - 1f) * MAX_SUBTITLE_OFFSET_MS
    return (raw / SUBTITLE_OFFSET_STEP_MS).roundToInt() * SUBTITLE_OFFSET_STEP_MS
}

/**
 * Same sheet as [SubtitlePickerOverlay], one list over: libVLC's own audio
 * tracks rather than an addon's subtitle options, so there is no "nenhuma"
 * row — a file always has at least one audio track playing.
 *
 * Every track the container declares appears here, including ones no decoder
 * on this device could be found for. Hiding those, which is what this did,
 * made a DTS-only Portuguese dub indistinguishable from a release that simply
 * has no Portuguese. They stay selectable, with the warning under them:
 * the check only speaks for this box, and a receiver on the far end of the
 * HDMI cable may decode exactly what the box cannot.
 */
@Composable
private fun AudioPickerOverlay(
    options: List<TrackInfo>,
    activeId: Int,
    /** The selected track turned out to be undecodable and is playing silent. */
    silenced: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Same fix as SubtitlePickerOverlay: without this, focus stays on the
    // now-hidden "Áudio" OSD button and the d-pad has nothing to move.
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRowFocus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(HubColors.Background.copy(alpha = 0.7f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 480.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(HubColors.Surface)
                .border(1.dp, HubColors.Border, RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.player_audio),
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(options, key = { _, option -> option.id }) { index, option ->
                    SubtitleRow(
                        label = option.displayLabel(),
                        selected = option.id == activeId,
                        // Reads back what actually happened, not what was
                        // predicted: a track flagged undecodable that plays
                        // anyway — over HDMI to a receiver — drops the warning
                        // once it is the one running, and a track that really
                        // failed says so instead of leaving silence unexplained.
                        supporting = when {
                            option.id == activeId && silenced ->
                                stringResource(R.string.player_track_silenced)
                            option.id == activeId -> null
                            option.playable -> null
                            else -> stringResource(R.string.player_track_unsupported)
                        },
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstRowFocus)
                        } else {
                            Modifier
                        },
                        onClick = { onSelect(option.id) },
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * Decimal separator from the locale, not from a hard-coded comma. The number
 * sits beside "+0.1 s"/"−0,1 s" buttons that come from the string resources,
 * so it has to agree with whichever language is selected.
 */
@Composable
private fun formatSubtitleOffset(ms: Long): String {
    val locale = androidx.compose.ui.text.intl.Locale.current
    val javaLocale = remember(locale.toLanguageTag()) {
        java.util.Locale.forLanguageTag(locale.toLanguageTag())
    }
    val seconds = ((kotlin.math.abs(ms) + 50L) / 100L) / 10.0
    val magnitude = String.format(javaLocale, "%.1f s", seconds)
    return when {
        ms == 0L -> magnitude
        ms < 0L -> "−$magnitude"
        else -> "+$magnitude"
    }
}

/**
 * What a track is called, from what the container declared — its own label
 * first, then its language, and only then a positional fallback — followed by
 * the codec and channel layout.
 *
 * The technical half is what makes the list usable on the files people
 * actually play. A remux typically labels nothing, so every track came out as
 * "Faixa POR" / "Faixa ENG"; two Portuguese tracks — the dub in 5.1 and the
 * original in stereo — were then the same string twice, and the only way to
 * tell them apart was to pick one and listen.
 */
@Composable
private fun TrackInfo.displayLabel(): String {
    val name = label?.takeIf { it.isNotBlank() }
        ?: languageName()
        ?: stringResource(R.string.player_track_index, id + 1)

    val codec = codecName()
    val detail = listOfNotNull(codec, channelName()).joinToString(" ")

    // A container that spelled the codec into its own label already said this
    // — "Português 5.1 AC3 · AC3 5.1" reads as a bug rather than as detail.
    val alreadySaid = codec != null && name.contains(codec, ignoreCase = true)

    return if (detail.isEmpty() || alreadySaid) name else "$name · $detail"
}

/**
 * The track's language in the language the app is running in — "Português",
 * not "POR".
 *
 * Media3 normalizes [TrackInfo.language] to a BCP-47 tag, so the ISO 639-2
 * codes containers carry ("por", "ger") arrive here already folded to their
 * two-letter form and resolve. When one does not, `getDisplayLanguage` hands
 * the code straight back, and that is the case the uppercased fallback covers
 * — printing a raw "qaa" as if it were a language name would be worse than
 * admitting it is a code.
 */
@Composable
private fun TrackInfo.languageName(): String? {
    val code = language?.takeIf { it.isNotBlank() } ?: return null
    val uiTag = androidx.compose.ui.text.intl.Locale.current.toLanguageTag()
    val resolved = remember(code, uiTag) {
        java.util.Locale.forLanguageTag(code)
            .getDisplayLanguage(java.util.Locale.forLanguageTag(uiTag))
            .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?.replaceFirstChar { it.uppercase() }
    }
    return resolved ?: stringResource(R.string.player_track_language, code.uppercase())
}

/**
 * The short name people recognize for a sample MIME type.
 *
 * Audio only: this same [TrackInfo] carries embedded subtitle tracks too, and
 * appending "SUBRIP" to a caption's name is noise, not detail. The `else`
 * branch keeps an unmapped audio codec readable rather than dropping it —
 * new formats appear faster than this list is updated.
 */
private fun TrackInfo.codecName(): String? {
    val mime = mimeType?.lowercase()?.takeIf { it.startsWith("audio/") } ?: return null
    return when (mime) {
        "audio/mp4a-latm", "audio/aac" -> "AAC"
        "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2" -> "MP3"
        "audio/ac3" -> "AC3"
        "audio/eac3" -> "EAC3"
        "audio/eac3-joc" -> "EAC3 JOC"
        "audio/ac4" -> "AC-4"
        "audio/true-hd" -> "TrueHD"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/vnd.dts.uhd" -> "DTS:X"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/alac" -> "ALAC"
        "audio/raw", "audio/wav", "audio/x-wav" -> "PCM"
        else -> mime.removePrefix("audio/").removePrefix("x-").removePrefix("vnd.").uppercase()
    }
}

/**
 * The channel layout as a listener would name it. Only the layouts a film
 * actually ships in get a name of their own; the rest fall back to a count,
 * which is still enough to tell two tracks apart.
 */
@Composable
private fun TrackInfo.channelName(): String? {
    val count = channelCount ?: return null
    return when (count) {
        1 -> stringResource(R.string.player_track_channels_mono)
        2 -> stringResource(R.string.player_track_channels_stereo)
        3 -> "2.1"
        4 -> "4.0"
        5 -> "5.0"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> pluralStringResource(R.plurals.player_track_channels, count, count)
    }
}

/** Turns the engine's typed failure into a sentence in the selected language. */
@Composable
private fun PlaybackFailure.message(): String = when (this) {
    PlaybackFailure.NoCandidates -> stringResource(R.string.player_error_no_candidates)
    is PlaybackFailure.AllCandidatesFailed ->
        pluralStringResource(R.plurals.player_error_all_failed, count, count)
    PlaybackFailure.DecoyStreak -> stringResource(R.string.player_error_decoy_streak)
    PlaybackFailure.ManualNoLink -> stringResource(R.string.player_error_manual_no_link)
    PlaybackFailure.ManualUnresponsive ->
        stringResource(R.string.player_error_manual_unresponsive)
    PlaybackFailure.ManualFailed -> stringResource(R.string.player_error_manual_failed)
    PlaybackFailure.ManualDecoy -> stringResource(R.string.player_error_manual_decoy)
}
