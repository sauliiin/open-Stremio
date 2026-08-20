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

// The human-readable name for each mode lives in the app's string resources,
// not here: this module has no business knowing which language the box is set
// to, and hard-coded labels were exactly why the player screen stayed
// Portuguese after the interface gained an English option.

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

    /**
     * The user asked to pick a source up front instead of letting the cascade
     * choose. Candidates are still collected in the background and offered as
     * [PlaybackState.availableSources] as they arrive, but none is opened
     * automatically — playback only starts once [PlaybackController.playManual]
     * is called with one of them.
     */
    SELECTING,
}

/**
 * One selectable audio or subtitle track.
 *
 * `id` is this app's own index into the type-filtered track list, not
 * anything ExoPlayer assigns — ExoPlayer identifies tracks by
 * (group, index) pairs, which do not survive being flattened into the Int
 * the pickers are built around.
 *
 * [label] and [language] are whatever the container declared, both nullable
 * and neither invented here. A track with no label and no language code needs
 * a name like "Track 2", and inventing that in this module would mean writing
 * it in one fixed language — so the UI composes it instead. [mimeType] and
 * [channelCount] ride along for the same reason: what tells two same-labelled
 * tracks apart is their codec and channel layout, but "AC3 5.1" is a phrase
 * assembled next to the word "Estéreo", which only the UI can translate.
 */
data class TrackInfo(
    val id: Int,
    val label: String? = null,
    val language: String? = null,
    /** Container sample MIME — `audio/ac3`, `audio/vnd.dts` — or null. */
    val mimeType: String? = null,
    /** Declared channel count, or null when the container did not say. */
    val channelCount: Int? = null,
    /**
     * Whether a decoder for it was found when the track list was read.
     *
     * A warning, not a veto. Unplayable tracks are listed *and* selectable:
     * dropping them silently made a file whose only Portuguese audio is DTS-HD
     * look like a file with no Portuguese at all, and refusing the tap left no
     * way to find out that a receiver downstream would have decoded it
     * perfectly — `isTrackSupported` only ever answers for this box.
     *
     * Selecting one that really cannot be decoded costs a silent film, not a
     * restart: see [PlaybackController.recoverFromDecoderFailure].
     */
    val playable: Boolean = true,
)

/**
 * "No track of this type is selected" — what [PlaybackState.currentSubtitleId]
 * and [PlaybackState.currentAudioId] hold when nothing is on.
 *
 * Named rather than a bare -1 because it is now something callers *pass*:
 * turning the container's captions off is `selectSubtitleTrack(NO_TRACK)`,
 * and `selectSubtitleTrack(-1)` at a call site reads like a bug.
 */
const val NO_TRACK = -1

/**
 * How far the subtitle offset reaches in either direction.
 *
 * Public, and deliberately not duplicated in the UI: the bar that draws this
 * range and the controller that clamps to it have to be the same number, or the
 * bar's ends stop somewhere other than where it appears to end.
 *
 * Ten seconds — what this was — covers a subtitle matched to the wrong release.
 * A minute covers one matched to the wrong *cut*: an extended edition, or a
 * master with the recap trimmed off the front, which is the case that used to
 * be unfixable here.
 */
const val MAX_SUBTITLE_OFFSET_MS = 60_000L

/**
 * Where the playhead is, kept apart from [PlaybackState] on purpose.
 *
 * This used to be three fields on `PlaybackState` — position, duration and the
 * subtitle line — and that is a `StateFlow` emitting on a timer. Every screen
 * reading the state read those too, so the whole player recomposed on each
 * tick, and on each line of dialogue: `activeSubtitleCue` changing every two
 * or three seconds during a conversation was in fact more frequent than the
 * idle tick it was blamed on.
 *
 * Split out, `PlaybackState` only changes on real events — a phase
 * transition, a track list, a candidate arriving. Since it is a data class
 * behind a `MutableStateFlow`, a `copy()` that changes nothing now compares
 * equal and is dropped rather than delivered, so a film that is simply
 * playing stops recomposing the screen at all.
 */
data class PlaybackPosition(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    /** 0f–1f, for the seek bar. */
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

data class PlaybackState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    /** 1-based position within a single pass over the candidates. */
    val attempt: Int = 0,
    val candidateCount: Int = 0,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val currentAudioId: Int = NO_TRACK,
    /**
     * True when [currentAudioId] is a track this device turned out not to be
     * able to decode, so the film is playing without sound.
     *
     * Not an error: the user asked for that track, the alternative was the
     * cascade restarting the film on another mirror, and picking a different
     * track undoes it. The UI owes them a line saying so — silence with no
     * explanation is indistinguishable from a broken player.
     */
    val audioSilenced: Boolean = false,
    /** The container's own subtitle track, or [NO_TRACK]. Never on at the same
     *  time as [externalSubtitle] — see [PlaybackController.selectSubtitleTrack]. */
    val currentSubtitleId: Int = NO_TRACK,
    val externalSubtitle: SubtitleOption? = null,
    /** Signed subtitle shift: negative shows cues earlier, positive later. */
    val subtitleOffsetMs: Long = 0,
    val scaleType: VideoScaleType = VideoScaleType.FIT,
    /** Why it stopped, as a value the UI renders — see [PlaybackFailure]. */
    val error: PlaybackFailure? = null,
    /**
     * Every candidate the cascade collected so far. Offered up once
     * [PlaybackPhase.FAILED] is reached so the user can pick one by hand
     * instead of being stuck on the error, or continuously while
     * [PlaybackPhase.SELECTING] is active because the user asked to choose
     * up front. Empty on every other phase — nothing in this app shows a
     * list of sources while the automatic attempt still has a chance to work.
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
}
