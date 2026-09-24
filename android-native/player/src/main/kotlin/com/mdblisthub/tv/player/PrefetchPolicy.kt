package com.mdblisthub.tv.player

/**
 * Who owns the link right now — the player, or the worker filling the disk
 * cache ahead of it.
 *
 * Split out of [MediaPrefetcher] for the same reason [StallTracker] was split
 * out of the stall watch: the rule is a decision over a *series* of samples,
 * and a series is what cannot be checked by watching a film play. The failure
 * it guards against — a second HTTP download holding a slow link while the
 * player drains toward empty — looks, from the sofa, exactly like the mirror
 * being slow.
 *
 * **The player gets the link whenever it wants bytes, in flight or not.** An
 * earlier version of this let a download already started survive while the
 * player was loading, as long as the player's buffer was not actually
 * shrinking — on the reasoning that cancelling on every `isLoading` flicker
 * would stop any 16MB chunk from ever completing, so the disk cushion would
 * never be built.
 *
 * That reasoning was never measured, and it was wrong in the direction that
 * matters. On a device whose byte budget holds well under `minBufferMs` of
 * film — which is every device, at the bitrates this app plays —
 * `isLoading` is true for most of a film, with the buffer growing the whole
 * time. "Not shrinking" therefore described the normal case rather than an
 * exception, and the second connection ran alongside the player almost
 * continuously. A 2.5GB television on 5GHz Wi-Fi started stopping to buffer
 * where it had not before.
 *
 * So the lever is one, not two: while the player's own `LoadControl` wants
 * bytes, this worker neither starts a chunk nor keeps one. Prefetching uses
 * what is left over and nothing else. The thrash that worried me is real but
 * cheap — `SimpleCache` keeps every completed span, so an abandoned chunk
 * costs the bytes already written and nothing else, and the next idle pass
 * resumes from where it stopped.
 */
internal class PrefetchPolicy {

    /** What the caller must do to the download in flight, if anything. */
    enum class Effect { NONE, CANCEL_IN_FLIGHT }

    /** False while the player's own loader still wants bytes. */
    @Volatile
    var mayStartChunk: Boolean = false
        private set

    /**
     * A periodic sample of what the player's loader is doing.
     *
     * A backstop to [onLoadingChanged], which carries the same fact within
     * milliseconds: an event only fires on a *change*, so a prefetch run
     * beginning while the player happens not to be loading would otherwise sit
     * out the whole film waiting for a transition that already happened.
     */
    fun onSample(loading: Boolean): Effect = claim(playerWantsBytes = loading)

    /**
     * The player's loader started or stopped wanting bytes.
     *
     * Pushed from `onIsLoadingChanged` rather than read off the position
     * ticker, which runs every four seconds once the OSD is hidden — the
     * normal state of a film being watched. A player that started wanting
     * bytes again was, before this existed, sharing the link for up to four
     * seconds before this loop was even told.
     */
    fun onLoadingChanged(loading: Boolean): Effect = claim(playerWantsBytes = loading)

    /**
     * The picture has stopped — `STATE_BUFFERING`.
     *
     * Redundant with [onLoadingChanged] in most stalls, and kept anyway: a
     * stopped picture is the one state where being a poll late is a stall the
     * viewer sees, and it costs a boolean to be certain rather than nearly
     * certain.
     */
    fun onPlayerStalled(): Effect = claim(playerWantsBytes = true)

    /**
     * The viewer moved the playhead, so the bytes in flight were fetched for
     * somewhere they have left.
     */
    fun onSeek(): Effect = Effect.CANCEL_IN_FLIGHT

    /** Between sources there is nothing to work ahead of. */
    fun reset() {
        mayStartChunk = false
    }

    private fun claim(playerWantsBytes: Boolean): Effect {
        mayStartChunk = !playerWantsBytes
        return if (playerWantsBytes) Effect.CANCEL_IN_FLIGHT else Effect.NONE
    }
}
