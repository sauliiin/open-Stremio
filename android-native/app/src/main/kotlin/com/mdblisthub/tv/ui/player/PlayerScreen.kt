package com.mdblisthub.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.isActive
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.core.ui.component.FanartBackdrop
import com.mdblisthub.tv.core.ui.component.HubSpinner
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.player.ExoVideoSurface
import com.mdblisthub.tv.player.PlaybackFailure
import com.mdblisthub.tv.player.PlaybackPhase
import com.mdblisthub.tv.player.TrackInfo
import com.mdblisthub.tv.player.VideoScaleType
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.hubViewModel
import kotlinx.coroutines.delay

private const val OSD_TIMEOUT_MS = 4_000L

/**
 * Caption metrics. The line height is ~1.35x the glyph size — roughly what
 * broadcast subtitles use, and enough air that a two-line cue reads as two
 * lines from a sofa rather than as one block of text.
 */
private val SUBTITLE_FONT_SIZE = 26.sp
private val SUBTITLE_LINE_HEIGHT = 35.sp
private const val SEEK_STEP_MS = 10_000L
private const val SUBTITLE_OFFSET_STEP_MS = 100L
private const val FOCUS_RESTORE_ATTEMPTS = 3

@Composable
fun PlayerScreen(
    graph: DataGraph,
    type: MediaType,
    tmdbId: Int,
    season: Int?,
    episode: Int?,
    onBack: () -> Unit,
    onOpenAddons: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel = hubViewModel(key = "player-$type-$tmdbId-$season-$episode") {
        PlayerViewModel(graph, appContext, type, tmdbId, season, episode)
    }

    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playback by viewModel.controller.state.collectAsStateWithLifecycle()
    
    val subtitleColorString by viewModel.subtitleColor.collectAsStateWithLifecycle()

    val parsedSubtitleColor = when (subtitleColorString) {
        "white" -> Color.White
        "red" -> Color.Red
        "blue" -> Color.Blue
        else -> Color.Yellow
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
    val overlayOpen = subtitlePickerOpen || subtitleSyncOpen || audioPickerOpen
    // Whether one of the OSD buttons currently holds focus. While it does,
    // left/right have to move focus between the buttons instead of seeking —
    // see the key handler below.
    var controlsFocused by remember { mutableStateOf(false) }
    val playButtonFocusRequester = remember { FocusRequester() }
    // Set the moment OK/Enter wakes a hidden OSD, so focus can land on Play
    // as soon as it composes — the button does not exist yet in the same
    // frame the key press arrives in.
    var wantsPlayFocus by remember { mutableStateOf(false) }

    // Paused: the OSD has nothing to hide behind, so it stays up.
    // Buffering no longer forces the OSD visible to prevent flashing during micro-stutters.
    val osdVisible = !osdExpired || playback.phase == PlaybackPhase.PAUSED

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
    // Wait a frame so the picker's disappearing focus target cannot clear the
    // new Play focus afterwards. Only consume the intent once Compose reports
    // that the newly attached button accepted focus.
    LaunchedEffect(osdVisible, wantsPlayFocus, playback.canShowVideo, overlayOpen) {
        if (!osdVisible || !wantsPlayFocus || !playback.canShowVideo || overlayOpen) {
            return@LaunchedEffect
        }
        repeat(FOCUS_RESTORE_ATTEMPTS) {
            withFrameNanos { }
            if (!osdVisible || !wantsPlayFocus || !playback.canShowVideo || overlayOpen) {
                return@LaunchedEffect
            }
            if (playButtonFocusRequester.requestFocus(FocusDirection.Enter)) {
                wantsPlayFocus = false
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
            // The OSD is up because something just woke it, not because the
            // film is paused — `osdVisible` alone would also be true while
            // paused (see its definition above), and gating on that would
            // trap back behind an OSD that never auto-hides. Playing is what
            // makes this "still watching, just glanced at the controls"
            // rather than "stopped and looking at a screen with a back
            // button on it".
            osdVisible && playback.isPlaying -> hideNow()
            else -> onBack()
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
                if (overlayOpen) return@onPreviewKeyEvent event.key != Key.Back
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
                    Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                        when {
                            controlsFocused -> false
                            wasHidden -> {
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

        // Independent of the OSD gradient: a caption still belongs on screen
        // while the controls are hidden, which is most of a film's runtime.
        val subtitleCue = playback.activeSubtitleCue
        if (playback.canShowVideo && !overlayOpen && !subtitleCue.isNullOrBlank()) {
            ExternalSubtitleOverlay(
                text = subtitleCue,
                liftForOsd = osdVisible,
                color = parsedSubtitleColor,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (osdVisible && playback.canShowVideo && !overlayOpen) {
            PlayerOsd(
                title = ui.title,
                subtitle = ui.episodeLabel,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                playing = playback.isPlaying,
                scaleLabel = playback.scaleType.label(),
                subtitleActive = playback.externalSubtitle != null,
                onTogglePlay = { viewModel.controller.togglePlayPause(); poke() },
                onCycleScale = { viewModel.controller.cycleScale(); poke() },
                onOpenSubtitles = { subtitlePickerOpen = true; poke() },
                onOpenAudio = { audioPickerOpen = true; poke() },
                onControlsFocusChanged = { controlsFocused = it },
                playButtonFocusRequester = playButtonFocusRequester,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (subtitlePickerOpen) {
        SubtitlePickerOverlay(
            options = ui.subtitles,
            active = playback.externalSubtitle,
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
            onReset = viewModel.controller::resetSubtitleOffset,
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
        delay(4000)
        while (isActive) {
            val max = scrollState.maxValue
            if (max > 0) {
                // 80ms/px
                scrollState.animateScrollTo(max, animationSpec = tween(durationMillis = max * 80, easing = LinearEasing))
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
                    animation = tween(1500, easing = FastOutSlowInEasing),
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
 * The external subtitle's current line, drawn by this app rather than handed
 * to ExoPlayer as a side-loaded track — see `PlaybackController`. Owning the
 * draw is what let synchronizing become a pure UI operation: adjusting the
 * offset changes which cue this reads on the next tick, nothing about the
 * film underneath it.
 *
 * Styled to match the embedded-subtitle look this app already forces on
 * container subtitles (`ExoVideoSurface`) — yellow, drop shadow, no
 * background — so a title with an addon subtitle and one with an embedded
 * one read as the same feature.
 */
@Composable
private fun ExternalSubtitleOverlay(
    text: String,
    liftForOsd: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.Yellow,
) {
    val bottomPadding by animateDpAsState(
        if (liftForOsd) 168.dp else 40.dp,
        focusTween(),
        label = "subtitle-lift",
    )
    Text(
        text = text,
        modifier = modifier
            .padding(horizontal = 32.dp)
            .padding(bottom = bottomPadding)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = color,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = SUBTITLE_FONT_SIZE,
            // Set explicitly, and that is the whole fix for two-line cues
            // crowding each other. `fontSize` was being overridden to 26sp
            // while `lineHeight` kept coming from `bodyLarge`, which is sized
            // for a ~16sp body — so the gap between lines was *smaller* than
            // the glyphs themselves and a two-liner read as one dense block.
            lineHeight = SUBTITLE_LINE_HEIGHT,
            shadow = Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 6f),
        ),
    )
}

@Composable
private fun PlayerOsd(
    title: String,
    subtitle: String?,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    scaleLabel: String,
    subtitleActive: Boolean,
    onTogglePlay: () -> Unit,
    onCycleScale: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
    onControlsFocusChanged: (Boolean) -> Unit,
    playButtonFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    // The seek bar and the button row hand focus back and forth on up/down
    // rather than leaving it to Compose's default spatial search: the bar is
    // a thin 6dp target sitting well below the buttons, exactly the kind of
    // geometry that search picks the wrong neighbour for.
    val progressBarFocusRequester = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(HubColors.Background.copy(alpha = 0f), HubColors.Background.copy(alpha = 0.94f))
                )
            )
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = HubColors.Text)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = HubColors.TextDim)
            }
        }

        // ---------------------------------------------------------- controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier
                    // Tracked so the screen's key handler knows when to hand
                    // left/right to focus movement between these buttons
                    // instead of using them as seek shortcuts.
                    .onFocusChanged { onControlsFocusChanged(it.hasFocus) }
                    // Bubbles up from whichever button is actually focused,
                    // so every button gets this without repeating it four
                    // times.
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            progressBarFocusRequester.requestFocus()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                OsdIconButton(
                    icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                    onClick = onTogglePlay,
                    modifier = Modifier.focusRequester(playButtonFocusRequester),
                )
                OsdIconButton(
                    icon = Icons.Filled.Subtitles,
                    contentDescription = stringResource(R.string.player_subtitles),
                    onClick = onOpenSubtitles,
                    active = subtitleActive,
                )
                OsdIconButton(
                    icon = Icons.Filled.Audiotrack,
                    contentDescription = stringResource(R.string.player_audio),
                    onClick = onOpenAudio,
                )
                OsdIconButton(
                    icon = Icons.Filled.AspectRatio,
                    contentDescription = stringResource(R.string.player_scale, scaleLabel),
                    onClick = onCycleScale,
                )
            }
        }

        val progressBarInteraction = remember { MutableInteractionSource() }
        val progressBarFocused by progressBarInteraction.collectIsFocusedAsState()
        val progressBarHeight by animateDpAsState(
            if (progressBarFocused) 12.dp else 6.dp,
            focusTween(),
            label = "progress-bar-height",
        )
        val progressBarGlow by animateDpAsState(
            if (progressBarFocused) 3.dp else 0.dp,
            focusTween(),
            label = "progress-bar-glow",
        )

        // A ring around the track, not just a colour swap on the fill: the
        // fill is a thin sliver at the very start of playback, and a colour
        // change alone on almost-no-area is exactly the kind of cue this
        // session's OSD buttons already learned reads as "not evident enough"
        // from a couch. The ring stays legible regardless of how much of the
        // bar is actually filled in.
        Box(
            Modifier
                .fillMaxWidth()
                .height(progressBarHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(HubColors.Border)
                .border(
                    width = progressBarGlow,
                    color = if (progressBarFocused) HubColors.Accent else HubColors.Border.copy(alpha = 0f),
                    shape = RoundedCornerShape(6.dp),
                )
                .focusRequester(progressBarFocusRequester)
                .focusable(interactionSource = progressBarInteraction)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                        playButtonFocusRequester.requestFocus()
                        true
                    } else {
                        false
                    }
                },
        ) {
            val fraction = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (progressBarFocused) HubColors.AccentSoft else HubColors.Accent),
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = HubColors.TextDim,
            )
            Text(
                text = scaleLabel,
                style = MaterialTheme.typography.labelSmall,
                color = HubColors.TextFaint,
            )
        }
    }
}

/**
 * A circular control that reads the same whether it is pressed with a thumb
 * or a D-pad OK: focus and touch both land on the same target, sized for a
 * fingertip first since that is what most of this app's testing has been on.
 *
 * Every OSD button — play, legenda, áudio, esticar — shares this one size, so
 * play does not read as more important than the controls beside it and the
 * row scans evenly left to right.
 *
 * Every property that changes with focus — scale, background, border —
 * animates on the same [FOCUS_TWEEN], so walking the row with left/right
 * reads as one continuous slide from button to button rather than a series
 * of snaps. `.scale()` is applied outside the `.size()`/`.clip()` chain
 * specifically so growing to 1.15x never breaks the circle back into an
 * ellipse.
 */
@Composable
private fun OsdIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val scale by animateFloatAsState(if (focused) 1.15f else 1f, focusTween(), label = "osd-scale")
    val background by animateColorAsState(
        when {
            focused -> HubColors.Accent
            active -> HubColors.Accent.copy(alpha = 0.28f)
            else -> HubColors.Surface.copy(alpha = 0.7f)
        },
        focusTween(),
        label = "osd-background",
    )
    val borderColor by animateColorAsState(
        when {
            focused -> HubColors.Accent
            active -> HubColors.Accent.copy(alpha = 0.6f)
            else -> HubColors.Border
        },
        focusTween(),
        label = "osd-border-color",
    )
    val borderWidth by animateDpAsState(if (focused) 2.dp else 1.dp, focusTween(), label = "osd-border-width")

    Box(
        modifier = modifier
            .scale(scale)
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .border(width = borderWidth, color = borderColor, shape = CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (focused || active) HubColors.Text else HubColors.TextDim,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Shared by every OSD control so the row moves as one consistent motion. */
private fun <T> focusTween(): FiniteAnimationSpec<T> = tween(durationMillis = 200, easing = FastOutSlowInEasing)

@Composable
private fun SubtitlePickerOverlay(
    options: List<SubtitleOption>,
    active: SubtitleOption?,
    offsetMs: Long,
    onOpenSync: () -> Unit,
    onSelect: (SubtitleOption?) -> Unit,
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
                        label = if (active == null) {
                            stringResource(R.string.player_subtitle_sync_disabled)
                        } else {
                            stringResource(
                                R.string.player_subtitle_sync_value,
                                formatSubtitleOffset(offsetMs),
                            )
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
                        selected = active == null,
                        modifier = if (active == null) {
                            Modifier.focusRequester(firstRowFocus)
                        } else {
                            Modifier
                        },
                        onClick = { onSelect(null) },
                    )
                }
                items(options, key = { it.key }) { option ->
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
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
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
    }
}

/**
 * Kept at the top so the film and its captions remain visible while the user
 * nudges timing with the remote. The picker closes before this opens; there
 * is deliberately no dark scrim over the video to judge synchronization
 * against.
 */
@Composable
private fun SubtitleSyncOverlay(
    offsetMs: Long,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstButtonFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstButtonFocus.requestFocus() }

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
                .widthIn(max = 640.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(HubColors.Surface.copy(alpha = 0.96f))
                .border(1.dp, HubColors.Border, RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.player_subtitle_sync),
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatSubtitleOffset(offsetMs),
                style = MaterialTheme.typography.headlineMedium,
                color = if (offsetMs == 0L) HubColors.Text else HubColors.AccentSoft,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = stringResource(R.string.player_subtitle_sync_hint),
                style = MaterialTheme.typography.labelSmall,
                color = HubColors.TextDim,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.onPreviewKeyEvent { event ->
                    // There is intentionally nowhere above or below this
                    // compact popup for focus to go. Consuming the vertical
                    // directions prevents it escaping to the player root.
                    event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                },
            ) {
                HubButton(
                    text = stringResource(R.string.player_offset_minus),
                    onClick = { onAdjust(-SUBTITLE_OFFSET_STEP_MS) },
                    modifier = Modifier
                        .focusRequester(firstButtonFocus)
                        .onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft
                        },
                )
                // Keep this focusable at zero: disabling the currently
                // focused button after a reset leaves a TV remote with no
                // focus anchor for the next left/right press.
                HubButton(text = stringResource(R.string.player_offset_reset), onClick = onReset)
                HubButton(
                    text = stringResource(R.string.player_offset_plus),
                    onClick = { onAdjust(SUBTITLE_OFFSET_STEP_MS) },
                )
                HubButton(
                    text = stringResource(R.string.player_done),
                    onClick = onDismiss,
                    modifier = Modifier.onPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight
                    },
                    primary = true,
                )
            }
        }
    }
}

/**
 * Same sheet as [SubtitlePickerOverlay], one list over: libVLC's own audio
 * tracks rather than an addon's subtitle options, so there is no "nenhuma"
 * row — a file always has at least one audio track playing.
 */
@Composable
private fun AudioPickerOverlay(
    options: List<TrackInfo>,
    activeId: Int,
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
                        modifier = if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier,
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

/** The human name for a scale mode — see the note in `PlaybackState`. */
@Composable
private fun VideoScaleType.label(): String = stringResource(
    when (this) {
        VideoScaleType.FIT -> R.string.player_scale_fit
        VideoScaleType.ZOOM -> R.string.player_scale_zoom
        VideoScaleType.STRETCH -> R.string.player_scale_stretch
    },
)

/**
 * What a track is called, from what the container declared — its own label
 * first, then its language code, and only then a positional fallback.
 */
@Composable
private fun TrackInfo.displayLabel(): String {
    val declared = label?.takeIf { it.isNotBlank() }
    if (declared != null) return declared

    val code = language?.takeIf { it.isNotBlank() }
    return if (code != null) {
        stringResource(R.string.player_track_language, code.uppercase())
    } else {
        stringResource(R.string.player_track_index, id + 1)
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
