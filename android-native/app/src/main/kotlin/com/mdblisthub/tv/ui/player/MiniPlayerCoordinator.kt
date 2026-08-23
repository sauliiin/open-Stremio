package com.mdblisthub.tv.ui.player

import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.ScrobbleTarget
import com.mdblisthub.tv.player.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What "Voltar" left running when the viewer stepped out of the player with
 * time still on the clock: the live [controller] and the identity needed to
 * find it — and hand it back — the next time this exact title is opened.
 *
 * [controllerScope] is not [PlayerViewModel.controller]'s usual
 * `viewModelScope`: that scope is cancelled the instant the ViewModel that
 * owned it clears, which is exactly what popping the player route off the
 * back stack does. A controller due to keep decoding after that has to run on
 * a scope nothing but this coordinator owns.
 */
data class MiniPlayerSession(
    val controller: PlaybackController,
    val controllerScope: CoroutineScope,
    val type: MediaType,
    val tmdbId: Int,
    val season: Int?,
    val episode: Int?,
    val target: ScrobbleTarget?,
    val title: String,
    val subtitle: String?,
    val backdropUrl: String?,
    val completionNotifier: PlaybackCompletionNotifier,
)

/**
 * The one floating window Home is allowed to have.
 *
 * A plain object rather than a [DataGraph] property: [PlaybackController]
 * lives in the `player` module, which `core:data` — where `DataGraph` lives —
 * does not depend on. `app` is the one module that already sees both this and
 * every screen that hands a session to it, so it holds this instead.
 */
object MiniPlayerCoordinator {
    private val _session = MutableStateFlow<MiniPlayerSession?>(null)
    val session: StateFlow<MiniPlayerSession?> = _session.asStateFlow()

    /** Called once, by the screen giving up ownership of a still-live playback. */
    fun minimize(session: MiniPlayerSession) {
        // Reachable only if a viewer somehow minimizes twice without the
        // first session ever being reclaimed or closed — not a path the UI
        // takes today, but leaving the first session's player running
        // unreferenced would leak it.
        _session.value?.takeIf { it !== session }?.let { stale ->
            stale.controller.release()
            stale.controllerScope.cancel()
        }
        _session.value = session
    }

    /** Taken back by a [PlayerViewModel] opening the exact content this is playing. */
    fun reclaim(type: MediaType, tmdbId: Int, season: Int?, episode: Int?): MiniPlayerSession? {
        val current = _session.value ?: return null
        if (current.type != type || current.tmdbId != tmdbId ||
            current.season != season || current.episode != episode
        ) {
            return null
        }
        _session.value = null
        return current
    }

    /**
     * The viewer dismissed the floating window outright, or it lost its last
     * viewer (e.g. sign-out) with no screen left to reclaim it.
     *
     * Persists one last resume point before releasing — the periodic saves
     * `PlayerViewModel.reportPlaybackToMdblist` did while the screen was open
     * stopped the moment it cleared, so whatever the viewer watched of the
     * floating window since then would otherwise be lost on the next launch.
     */
    fun close(graph: DataGraph) {
        val current = _session.value ?: return
        _session.value = null
        val progress = current.controller.progressPercent()
        val playedTarget = current.target
        if (playedTarget != null && progress > 0f) {
            graph.scope.launch { graph.playback.stop(playedTarget, progress) }
        }
        current.controller.release()
        current.controllerScope.cancel()
        graph.imageMemoryTrimmer.restore()
    }
}
