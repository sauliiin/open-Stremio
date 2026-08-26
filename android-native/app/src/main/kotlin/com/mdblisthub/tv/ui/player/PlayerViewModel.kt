package com.mdblisthub.tv.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.data.mapper.Languages
import com.mdblisthub.tv.core.data.mapper.SubtitleMatcher
import com.mdblisthub.tv.core.model.CastMember
import com.mdblisthub.tv.core.model.MediaDetail
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.ClockPosition
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.PersonSummary
import com.mdblisthub.tv.core.model.ScrobbleTarget
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.core.model.WikipediaLookup
import com.mdblisthub.tv.player.NO_TRACK
import com.mdblisthub.tv.player.PlaybackController
import com.mdblisthub.tv.player.PlaybackPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val title: String = "",
    val episodeLabel: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val overview: String? = null,
    /** Set only while the addons are being asked, before the cascade starts. */
    val searching: Boolean = true,
    val subtitles: List<SubtitleOption> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val noAddons: Boolean = false,
    val missingImdbId: Boolean = false,
)

/** The compact biography card that follows focus in the player's cast rail. */
data class PlayerCastPreviewState(
    val member: CastMember? = null,
    val loading: Boolean = false,
    val summary: PersonSummary? = null,
    val unavailable: Boolean = false,
)

/**
 * Drives one playback.
 *
 * The order of operations is the whole user-visible design: ask the addons,
 * rank what comes back, hand the *entire* ranked list to the controller and
 * let it find one that works. Pressing play is the last decision the user
 * makes — unless the cascade exhausts every candidate, at which point
 * `PlaybackController.playManual` lets them make one more.
 */
class PlayerViewModel(
    private val graph: DataGraph,
    context: Context,
    private val type: MediaType,
    private val tmdbId: Int,
    private val season: Int?,
    private val episode: Int?,
    // True when the user asked to pick a source up front, from the detail
    // screen's "select source" button, instead of letting the cascade choose.
    private val manualSelect: Boolean = false,
) : ViewModel() {

    /**
     * Non-null when this exact title is already playing in the floating
     * mini-player — see [MiniPlayerCoordinator]. Read once, at construction:
     * this ViewModel is a fresh instance either way, and there is no later
     * point at which the answer to "was I opened from the mini-player" could
     * change under it.
     */
    private val reclaimed = MiniPlayerCoordinator.reclaim(type, tmdbId, season, episode)

    /** Shared with a minimized session so one completion can only alert once. */
    private val completionNotifier = reclaimed?.completionNotifier ?: PlaybackCompletionNotifier(
        context = context.applicationContext,
        type = type,
        tmdbId = tmdbId,
        season = season,
        episode = episode,
    )

    init {
        // A viewer who opens *different* content while the floating window is
        // still running some other title would otherwise end up with two
        // decoders — and two audio tracks — playing at once. `reclaimed`
        // being null already means either nothing is floating, or it is
        // floating something else; either way, whatever is there has to go.
        if (reclaimed == null) MiniPlayerCoordinator.close(graph)
    }

    /**
     * [controller]'s own scope, deliberately not `viewModelScope`.
     *
     * A viewer who steps out of the player with time left on the clock hands
     * [controller] to [MiniPlayerCoordinator] still running — see [minimize].
     * `viewModelScope` is cancelled the moment this ViewModel clears, before
     * `onCleared()` itself even runs, so a controller meant to outlive that
     * has to be built on a scope only [onCleared] and [MiniPlayerCoordinator]
     * ever close.
     */
    private val controllerScope = reclaimed?.controllerScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val controller = reclaimed?.controller ?: PlaybackController(
        context = context,
        scope = controllerScope,
        // Same connection pool the mirror probe used, so the handshake it paid
        // for is reused rather than repeated — see `HttpClients.playback`.
        callFactory = graph.network.playbackClient,
    )

    init {
        // This scope deliberately survives the ViewModel when playback moves
        // into the mini-player. A reclaimed session already owns this exact
        // observer, so installing another would retain duplicate collectors.
        if (reclaimed == null) {
            controllerScope.launch {
                controller.state.first { it.phase == PlaybackPhase.ENDED }
                completionNotifier.show(graph.uiPreferences.language.first())
            }
        }
    }

    /** Set by [minimize]; read once, in [onCleared], to decide how to let go of [controller]. */
    private var minimizing = false

    init {
        // The home screen's posters are worth ~25% of the heap and are not on
        // screen for the next two hours; the buffer is. See `ImageMemoryTrimmer`.
        // Idempotent, so re-applying it on a reclaim costs nothing.
        graph.imageMemoryTrimmer.trimForPlayback()
    }

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private val _castPreview = MutableStateFlow(PlayerCastPreviewState())
    val castPreview: StateFlow<PlayerCastPreviewState> = _castPreview.asStateFlow()
    private val castSummaryCache = mutableMapOf<Int, PersonSummary?>()
    private var castPreviewJob: Job? = null

    /**
     * The clock overlay. No auto-hide setting here: in the player the clock
     * rides the OSD's own timeout rather than running a timer against it.
     */
    val clockEnabled: StateFlow<Boolean> = graph.uiPreferences.clockScope
        .map { it.onPlayer }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val clockPosition: StateFlow<ClockPosition> = graph.uiPreferences.clockPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClockPosition.RIGHT)

    val subtitleColor: StateFlow<String> = graph.uiPreferences.subtitleColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "white")

    val subtitleTextOpacity: StateFlow<Int> = graph.uiPreferences.subtitleTextOpacity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 100)

    val subtitleBackgroundEnabled: StateFlow<Boolean> = graph.uiPreferences.subtitleBackgroundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val subtitleBackgroundOpacity: StateFlow<Int> = graph.uiPreferences.subtitleBackgroundOpacity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 40)

    private var target: ScrobbleTarget? = null
    private var lastReportedProgress = 0f

    init {
        val session = reclaimed
        if (session != null) {
            viewModelScope.launch { resumeFromReclaim(session) }
        } else {
            viewModelScope.launch { start() }
            // Only for a fresh play: a reclaimed controller already has
            // whatever tracks it ended up with, possibly a manual pick this
            // fresh `subtitleChosenByUser`/audio state has no record of, and
            // re-running the preferred-language match could silently
            // overturn it the moment the viewer returns to full screen.
            viewModelScope.launch { autoSelectAudio() }
            viewModelScope.launch { autoSelectSubtitle() }
        }
        viewModelScope.launch { reportPlaybackToMdblist() }
    }

    /**
     * Reopens a title already playing in the mini-player, without the cascade
     * [start] runs for a fresh one: the addon search already happened, a
     * source already won it, and asking again would trade a still-playing
     * film for a loading screen over the same picture.
     *
     * Only the cheap, cache-only half of [start] runs again — the artwork a
     * warm Room row already has — so the screen has a title and backdrop to
     * show immediately rather than sitting on whatever blank state a fresh
     * [PlayerUiState] starts from.
     */
    private suspend fun resumeFromReclaim(session: MiniPlayerSession) {
        target = session.target
        _ui.update {
            it.copy(
                title = session.title,
                episodeLabel = session.subtitle,
                backdropUrl = session.backdropUrl,
                searching = false,
            )
        }
        completionNotifier.update(session.title, session.subtitle)

        val card = graph.media.cachedCard(type, tmdbId)
        val cachedDetail = graph.media.cachedDetail(type, tmdbId)
        publishArtwork(cachedDetail, card)

        val stremioId = session.target?.stremioId() ?: return
        val options = graph.streams.subtitles(type, stremioId)
        _ui.update { it.copy(subtitles = options) }
    }

    /**
     * Hands [controller] to [MiniPlayerCoordinator] instead of releasing it.
     *
     * Called by the screen, before it pops itself off the back stack — see
     * `PlayerScreen`'s `BackHandler`. [onCleared] reads [minimizing] once
     * that pop reaches this ViewModel, and lets [controller] keep running
     * instead of tearing it down.
     */
    fun minimize() {
        minimizing = true
    }

    /**
     * Everything needed to *start* comes from Room; everything else catches up.
     *
     * The order here is the whole point. Asking the addons needs an IMDb id and
     * nothing else, and the card row — which a list sync wrote for free —
     * already has one. Waiting for `ensureDetail` first, as this used to,
     * meant that a cold or week-stale cache put a TMDB call plus mdblist plus
     * OMDb in front of the first addon request, on the one screen where
     * latency is the entire experience. Now the fan-out starts from the cached
     * id and hydration runs beside it, only feeding the artwork on the veil.
     */
    private suspend fun start() {
        // Nothing here touches the network.
        val card = graph.media.cachedCard(type, tmdbId)
        val cachedDetail = graph.media.cachedDetail(type, tmdbId)
        publishArtwork(cachedDetail, card)

        // Kept running past the cascade below — it is what upgrades the veil
        // from the card's poster to a real backdrop and clearlogo.
        val hydration = viewModelScope.async {
            // Cast is now visible during playback. Complete hydration still
            // runs beside source discovery and never delays the video.
            graph.media.ensureCompleteDetail(type, tmdbId)
            graph.media.cachedDetail(type, tmdbId)
        }

        // Only awaited when Room genuinely had nothing, which is the rare path
        // (a deep link into a title that was never in a list).
        val imdbId = card?.imdbId?.takeIf { it.isNotBlank() }
            ?: cachedDetail?.imdbId?.takeIf { it.isNotBlank() }
            ?: hydration.await()?.imdbId

        viewModelScope.launch {
            hydration.await()?.let { publishArtwork(it, card) }
        }

        if (imdbId.isNullOrBlank()) {
            // Addons are indexed by IMDb id; without one there is nothing to
            // ask, and no cascade to run.
            _ui.update { it.copy(searching = false, missingImdbId = true) }
            return
        }

        val scrobbleTarget = ScrobbleTarget(type, tmdbId, imdbId, season, episode)
        target = scrobbleTarget
        val stremioId = scrobbleTarget.stremioId() ?: imdbId

        if (graph.addons.addons().isEmpty()) {
            _ui.update { it.copy(searching = false, noAddons = true) }
            return
        }

        val candidates = graph.streams.candidates(type, stremioId)
        val resumeAt = graph.playback.resumeFor(scrobbleTarget)

        _ui.update { it.copy(searching = false) }
        controller.play(
            candidates,
            resumeAt,
            expectedRuntimeMinutes(cachedDetail, card),
            selectManually = manualSelect,
        )

        // Subtitles are fetched after playback has been handed off: they take
        // as long as the streams did, and nothing should wait on them.
        viewModelScope.launch {
            val options = graph.streams.subtitles(type, stremioId)
            _ui.update { it.copy(subtitles = options) }
        }
    }

    /**
     * Whatever is known about how the title looks, from whichever source has
     * it. Called twice — once from cache, once when hydration lands — so a
     * later, richer answer never blanks out an earlier one.
     */
    private fun publishArtwork(detail: MediaDetail?, card: MediaItem?) {
        _ui.update {
            it.copy(
                title = detail?.title ?: card?.title ?: it.title,
                backdropUrl = detail?.backdropUrl ?: card?.backdropUrl ?: it.backdropUrl,
                episodeLabel = if (type == MediaType.SHOW && season != null && episode != null) {
                    "T${season}E$episode"
                } else {
                    (detail?.year ?: card?.year)?.toString() ?: it.episodeLabel
                },
                logoUrl = detail?.logoUrl ?: it.logoUrl,
                overview = detail?.overview ?: it.overview,
                cast = detail?.cast?.takeIf { members -> members.isNotEmpty() } ?: it.cast,
            )
        }
        completionNotifier.update(_ui.value.title, _ui.value.episodeLabel)
    }

    /** Loads the biography selected by D-pad focus or touch, with session cache. */
    fun previewCast(member: CastMember) {
        if (_castPreview.value.member?.id == member.id &&
            (_castPreview.value.loading || _castPreview.value.summary != null || _castPreview.value.unavailable)
        ) return

        if (castSummaryCache.containsKey(member.id)) {
            val cached = castSummaryCache[member.id]
            _castPreview.value = PlayerCastPreviewState(
                member = member,
                summary = cached,
                unavailable = cached == null,
            )
            return
        }

        castPreviewJob?.cancel()
        _castPreview.value = PlayerCastPreviewState(member = member, loading = true)
        castPreviewJob = viewModelScope.launch {
            val lookup = graph.wikipedia.summaryFor(member.id, member.name)
            val summary = (lookup as? WikipediaLookup.Found)?.summary
            castSummaryCache[member.id] = summary
            _castPreview.update { current ->
                if (current.member?.id != member.id) current
                else PlayerCastPreviewState(
                    member = member,
                    summary = summary,
                    unavailable = lookup is WikipediaLookup.NotFound,
                )
            }
        }
    }

    /**
     * How long this exact thing should run, which is what lets the controller
     * recognise a "file removed" clip served in place of the film.
     *
     * For an episode the series-level runtime is useless — a 45-minute show
     * next to a two-minute decoy is the comparison that matters, not the
     * whole season — so the episode's own runtime is preferred and the
     * series average is only the fallback. Returning null where nothing is
     * known disables the check rather than letting it guess.
     */
    private suspend fun expectedRuntimeMinutes(detail: MediaDetail?, card: MediaItem?): Int? {
        val seasonNumber = season
        val episodeNumber = episode

        if (type == MediaType.SHOW && seasonNumber != null && episodeNumber != null) {
            val episodes = runCatching {
                graph.media.observeEpisodes(tmdbId, seasonNumber).first()
            }.getOrNull()

            episodes
                ?.firstOrNull { it.episodeNumber == episodeNumber }
                ?.runtimeMinutes
                ?.takeIf { it > 0 }
                ?.let { return it }
        }
        // The card is the fallback rather than nothing, now that playback no
        // longer waits for the detail row to exist: `MdbItemDto` carries a
        // runtime, and a rough one still tells a two-minute removal notice
        // apart from a feature.
        return detail?.runtimeMinutes?.takeIf { it > 0 }
            ?: card?.runtimeMinutes?.takeIf { it > 0 }
    }

    /**
     * mdblist owns the playback position, so every transition is reported.
     * Past 80% it marks the title watched on its own.
     */
    private suspend fun reportPlaybackToMdblist() {
        controller.state
            .distinctUntilChangedBy { it.phase }
            .collect { state ->
                val current = target ?: return@collect
                // Read off the controller rather than off `state`: the
                // playhead now lives on its own flow — see [PlaybackPosition]
                // — precisely so that it moving does not wake everything
                // watching the state. This collector only wakes on a phase
                // change, and asks for the position when it does.
                lastReportedProgress = controller.progressPercent()

                when (state.phase) {
                    PlaybackPhase.PLAYING -> graph.playback.start(current, lastReportedProgress)
                    PlaybackPhase.PAUSED -> graph.playback.pause(current, lastReportedProgress)
                    PlaybackPhase.ENDED -> graph.playback.stop(current, lastReportedProgress)
                    else -> Unit
                }
            }
    }

    private var subtitleFetchJob: Job? = null

    /**
     * True once the *user* has touched the subtitle picker, at which point
     * [autoSelectSubtitle] backs off permanently — nothing it could still do
     * is worth overriding a choice someone made on purpose, even if their
     * pick was "nenhuma".
     */
    private var subtitleChosenByUser = false

    /** Called from the picker UI — the only caller allowed to set [subtitleChosenByUser]. */
    fun selectSubtitle(option: SubtitleOption?) {
        subtitleChosenByUser = true
        applySubtitle(option)
    }

    /**
     * The same picker's other half: a subtitle the container already carries,
     * which needs neither a download nor a parse — the controller hands the
     * track straight to the player's own renderer.
     *
     * Cancelling [subtitleFetchJob] is the part that is easy to miss. An addon
     * subtitle already in flight would otherwise finish afterwards and replace
     * this one, and the picker would sit there showing a choice the player had
     * silently thrown away.
     */
    fun selectEmbeddedSubtitle(id: Int) {
        subtitleChosenByUser = true
        subtitleFetchJob?.cancel()
        controller.selectSubtitleTrack(id)
    }

    /**
     * Downloads and parses the file before handing it to the controller,
     * which only ever holds cues, never a URL — see `PlaybackController`.
     * The previous request is cancelled outright rather than raced: a user
     * who taps through three options quickly should only ever end up with
     * the last one they actually meant.
     */
    private fun applySubtitle(option: SubtitleOption?) {
        subtitleFetchJob?.cancel()
        if (option == null) {
            // "Sem legenda" has to mean *neither* kind. Clearing only the
            // external one left a container track — which ExoPlayer may have
            // switched on by itself — still drawing captions over a film the
            // user had just asked to have none.
            controller.selectSubtitleTrack(NO_TRACK)
            controller.selectExternalSubtitle(null, null)
            return
        }
        subtitleFetchJob = viewModelScope.launch {
            val track = graph.streams.subtitleTrack(option)
            controller.selectExternalSubtitle(option, track)
        }
    }

    /**
     * Turns a subtitle on before anyone asks, once there is both a real
     * playing candidate to judge a match against and a list of options to
     * judge — see `SubtitleMatcher` for how the pick is made. Manual choice
     * (including "nenhuma legenda") always wins if it happens first; this is
     * a convenience, not an override.
     */
    private suspend fun autoSelectSubtitle() {
        // Waits on the container's track list, not on the addon results: the
        // two arrive at very different times, and the embedded pass below is
        // ready long before any addon answers. `audioTracks` being non-empty is
        // what says the list has actually been read — the same signal
        // [autoSelectAudio] waits on, and necessary because `canShowVideo` is
        // already true while buffering, before any track is known.
        val playback = controller.state
            .first { it.canShowVideo && it.audioTracks.isNotEmpty() }

        if (subtitleChosenByUser) return

        val preferredLang = graph.uiPreferences.subtitleLanguage.first()

        // A subtitle the file already carries beats an addon match in the same
        // language, and is tried first for two reasons: it costs no download,
        // and it was cut against this exact release — which is the failure an
        // addon subtitle has most often, and the reason the sync bar exists.
        //
        // The language code is compared with `startsWith` so "pt" matches
        // "pt-BR", the way `SubtitleMatcher` does it; the label is a
        // `contains` because it is free text, the way [autoSelectAudio] does
        // it. A track with no decoder is skipped — it is in the list to be
        // seen, not to be chosen.
        val embedded = playback.subtitleTracks.indexOfFirst { track ->
            track.playable && (
                Languages.matches(track.language, preferredLang) ||
                    track.label?.lowercase()?.contains(preferredLang) == true
                )
        }
        if (embedded >= 0) {
            // Straight to the controller, not through [selectEmbeddedSubtitle]:
            // that one records a *user* choice, and this is the convenience
            // that a user choice is supposed to be allowed to override.
            controller.selectSubtitleTrack(embedded)
            return
        }

        // Only the addon half is a download, so only it answers to the setting.
        val autoDownload = graph.uiPreferences.subtitleAutoDownload.first()
        if (!autoDownload) return

        val uiState = ui.first { it.subtitles.isNotEmpty() }
        if (subtitleChosenByUser) return

        // Re-read rather than reused from above: the cascade may have moved to
        // a different candidate while the addons were still answering, and the
        // release a subtitle is matched against has to be the one playing now.
        val playingRelease = controller.state.value.activeStream
            ?.let { listOfNotNull(it.title, it.filename).joinToString(" ") }
        SubtitleMatcher.bestMatch(uiState.subtitles, playingRelease, preferredLang)?.let(::applySubtitle)
    }

    private suspend fun autoSelectAudio() {
        val playback = controller.state
            .first { playback -> playback.canShowVideo && playback.audioTracks.isNotEmpty() }

        val preferredLang = graph.uiPreferences.audioLanguage.first()

        // Both the container's own label and its language code are candidates,
        // and both are optional — a track that declares neither cannot be
        // matched against a language preference at all.
        //
        // `playable` first: the track list now includes formats this device has
        // no decoder for, so the preferred language can match one, and auto-
        // selecting it would fail playback outright. Falling back to whatever
        // the container chose by default is the right outcome there — the
        // picker still shows the user why their language was skipped.
        val trackIndex = playback.audioTracks.indexOfFirst { track ->
            track.playable && listOfNotNull(track.label, track.language)
                .any { it.lowercase().contains(preferredLang) }
        }

        if (trackIndex >= 0) {
            controller.selectAudioTrack(trackIndex)
        }
    }

    override fun onCleared() {
        if (minimizing) {
            // `controller` and `controllerScope` are deliberately not touched
            // here — see `minimize()`. `MiniPlayerCoordinator` now owns them,
            // and will release them itself once the floating window is
            // reclaimed or closed for good.
            MiniPlayerCoordinator.minimize(
                MiniPlayerSession(
                    controller = controller,
                    controllerScope = controllerScope,
                    type = type,
                    tmdbId = tmdbId,
                    season = season,
                    episode = episode,
                    target = target,
                    title = _ui.value.title,
                    subtitle = _ui.value.episodeLabel,
                    backdropUrl = _ui.value.backdropUrl,
                    completionNotifier = completionNotifier,
                ),
            )
            super.onCleared()
            return
        }

        val current = target
        val progress = controller.progressPercent().takeIf { it > 0f } ?: lastReportedProgress

        // Fire-and-forget on the application scope: the ViewModel's own scope
        // is already cancelled by the time this runs, and losing the stop is
        // losing the resume point.
        if (current != null && progress > 0f) {
            graph.scope.launch { graph.playback.stop(current, progress) }
        }
        controller.release()
        controllerScope.cancel()
        graph.imageMemoryTrimmer.restore()
        super.onCleared()
    }
}
