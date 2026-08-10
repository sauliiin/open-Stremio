package com.mdblisthub.tv.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.data.mapper.SubtitleMatcher
import com.mdblisthub.tv.core.model.MediaDetail
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.ScrobbleTarget
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.player.PlaybackController
import com.mdblisthub.tv.player.PlaybackPhase
import com.mdblisthub.tv.player.label
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
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
    val noAddons: Boolean = false,
    val missingImdbId: Boolean = false,
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
) : ViewModel() {

    val controller = PlaybackController(context, viewModelScope)

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    val subtitleColor: StateFlow<String> = graph.uiPreferences.subtitleColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "white")

    private var target: ScrobbleTarget? = null
    private var lastReportedProgress = 0f

    init {
        viewModelScope.launch { start() }
        viewModelScope.launch { reportPlaybackToMdblist() }
        viewModelScope.launch { autoSelectAudio() }
        viewModelScope.launch { autoSelectSubtitle() }
    }

    private suspend fun start() {
        graph.media.ensureDetail(type, tmdbId)
        val detail = graph.media.observeDetail(type, tmdbId).first()

        _ui.update {
            it.copy(
                title = detail?.title.orEmpty(),
                backdropUrl = detail?.backdropUrl,
                episodeLabel = if (type == MediaType.SHOW && season != null && episode != null) {
                    "T${season}E$episode"
                } else {
                    detail?.year?.toString()
                },
                logoUrl = detail?.logoUrl,
                overview = detail?.overview,
            )
        }

        val imdbId = detail?.imdbId
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
        controller.play(candidates, resumeAt, expectedRuntimeMinutes(detail))

        // Subtitles are fetched after playback has been handed off: they take
        // as long as the streams did, and nothing should wait on them.
        viewModelScope.launch {
            val options = graph.streams.subtitles(type, stremioId)
            _ui.update { it.copy(subtitles = options) }
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
    private suspend fun expectedRuntimeMinutes(detail: MediaDetail?): Int? {
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
        return detail?.runtimeMinutes?.takeIf { it > 0 }
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
                lastReportedProgress = state.progress * 100f

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
     * Downloads and parses the file before handing it to the controller,
     * which only ever holds cues, never a URL — see `PlaybackController`.
     * The previous request is cancelled outright rather than raced: a user
     * who taps through three options quickly should only ever end up with
     * the last one they actually meant.
     */
    private fun applySubtitle(option: SubtitleOption?) {
        subtitleFetchJob?.cancel()
        if (option == null) {
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
        val (playback, uiState) = combine(controller.state, ui, ::Pair)
            .first { (playback, uiState) -> playback.canShowVideo && uiState.subtitles.isNotEmpty() }

        if (subtitleChosenByUser) return

        val autoDownload = graph.uiPreferences.subtitleAutoDownload.first()
        if (!autoDownload) return

        val preferredLang = graph.uiPreferences.subtitleLanguage.first()

        val playingRelease = playback.activeStream
            ?.let { listOfNotNull(it.title, it.filename).joinToString(" ") }
        SubtitleMatcher.bestMatch(uiState.subtitles, playingRelease, preferredLang)?.let(::applySubtitle)
    }

    private suspend fun autoSelectAudio() {
        val playback = controller.state
            .first { playback -> playback.canShowVideo && playback.audioTracks.isNotEmpty() }

        val preferredLang = graph.uiPreferences.audioLanguage.first()

        val trackIndex = playback.audioTracks.indexOfFirst { track ->
            track.label.lowercase().contains(preferredLang)
        }

        if (trackIndex >= 0) {
            controller.selectAudioTrack(trackIndex)
        }
    }

    override fun onCleared() {
        val current = target
        val progress = controller.progressPercent().takeIf { it > 0f } ?: lastReportedProgress

        // Fire-and-forget on the application scope: the ViewModel's own scope
        // is already cancelled by the time this runs, and losing the stop is
        // losing the resume point.
        if (current != null && progress > 0f) {
            graph.scope.launch { graph.playback.stop(current, progress) }
        }
        controller.release()
        super.onCleared()
    }
}
