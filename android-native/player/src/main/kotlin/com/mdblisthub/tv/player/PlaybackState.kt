package com.mdblisthub.tv.player

import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.SubtitleOption

/**
 * The cycle the "esticar" button walks, in the order it walks it.
 *
 * Three modes, not the five the mpv engine had: these map one-to-one onto
 * what ExoPlayer's own resize modes do, and the two that went away
 * (forcing 16:9 or 4:3 regardless of the source) were mpv-specific knobs
 * that only ever produced a wrong-looking picture on correctly-tagged
 * content — which is nearly all of it.
 *
 * `FIT` is the default: letterboxed, nothing cropped. `ZOOM` is the one
 * people actually reach for on a widescreen release, cropping top and bottom
 * rather than pillarboxing to nothing. `STRETCH` distorts to fill and is
 * last on purpose.
 */
enum class VideoScaleType { FIT, ZOOM, STRETCH }

val SCALE_CYCLE = listOf(
    VideoScaleType.FIT,
    VideoScaleType.ZOOM,
    VideoScaleType.STRETCH,
)

/** A label worth showing for a couple of seconds after the button is pressed. */
fun VideoScaleType.label(): String = when (this) {
    VideoScaleType.FIT -> "Ajustar à tela"
    VideoScaleType.ZOOM -> "Preencher"
    VideoScaleType.STRETCH -> "Esticar"
}

/** Where playback is, as one value the UI can render without branching twice. */
enum class PlaybackPhase {
    IDLE,

    /**
     * A source is being tried. This is the phase the source cascade lives in,
     * and the one the screen covers with artwork — the user is told the film
     * is starting, not which of nine mirrors is currently being probed.
     */
    RESOLVING,

    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,

    /** Every candidate was tried, twice, and none produced a frame. */
    FAILED,
}

/**
 * One selectable audio or subtitle track.
 *
 * `id` is this app's own index into the type-filtered track list, not
 * anything ExoPlayer assigns — ExoPlayer identifies tracks by
 * (group, index) pairs, which do not survive being flattened into the Int
 * the pickers are built around.
 */
data class TrackInfo(val id: Int, val label: String)

data class PlaybackState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    /** 1-based position within a single pass over the candidates. */
    val attempt: Int = 0,
    val candidateCount: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val currentAudioId: Int = -1,
    val currentSubtitleId: Int = -1,
    val externalSubtitle: SubtitleOption? = null,
    /** Signed subtitle shift: negative shows cues earlier, positive later. */
    val subtitleOffsetMs: Long = 0,
    /** The line an external subtitle is showing right now, or null for a gap. */
    val activeSubtitleCue: String? = null,
    val scaleType: VideoScaleType = VideoScaleType.FIT,
    val error: String? = null,
    /**
     * Every candidate the cascade collected, offered up once [PlaybackPhase.FAILED]
     * is reached so the user can pick one by hand instead of being stuck on the
     * error. Empty on every other phase — nothing in this app shows a list of
     * sources while the automatic attempt still has a chance to work.
     */
    val availableSources: List<PlayableStream> = emptyList(),
    /**
     * The candidate the current attempt is playing — set the moment it is
     * handed to the player, before it is known to actually work. This is
     * what lets a subtitle be matched against the release that is playing:
     * see `SubtitleMatcher`.
     */
    val activeStream: PlayableStream? = null,
) {
    val isPlaying: Boolean get() = phase == PlaybackPhase.PLAYING
    val canShowVideo: Boolean
        get() = phase == PlaybackPhase.PLAYING || phase == PlaybackPhase.PAUSED ||
            phase == PlaybackPhase.BUFFERING

    /** 0f–1f, for the seek bar. */
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}
