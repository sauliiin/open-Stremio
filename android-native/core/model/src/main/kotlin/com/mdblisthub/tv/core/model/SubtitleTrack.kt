package com.mdblisthub.tv.core.model

/**
 * One line of dialogue, in the file's own timeline.
 *
 * The user's synchronization offset is deliberately *not* baked in here. It is
 * applied at lookup time instead, which is the whole reason adjusting it is
 * free: shifting the timeline means changing one Long, not re-reading a file.
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * A side-loaded subtitle file, parsed once and held in memory.
 *
 * This is what replaced handing the URL to Media3 as part of the `MediaItem`.
 * Side-loading made the subtitle part of the *video* item, so changing
 * anything about it — a 100ms nudge included — meant rebuilding that item and
 * re-preparing the player: the picture stopped, the connection reopened and
 * the position had to be sought again, which is the opposite of what someone
 * nudging a subtitle needs to see. Owning the cues means the engine never
 * learns the subtitle exists, and synchronizing is a pure UI operation over a
 * film that never stops playing.
 *
 * A two-hour film is on the order of 1500 cues, so the whole thing is a few
 * hundred KB — nothing next to the video buffer it no longer disturbs.
 */
class SubtitleTrack(cues: List<SubtitleCue>) {

    /** Sorted by start, because [cueAt] binary-searches it on every frame. */
    val cues: List<SubtitleCue> = cues.sortedBy(SubtitleCue::startMs)

    val isEmpty: Boolean get() = cues.isEmpty()

    /**
     * The line that should be on screen at [timeMs], or null for a gap.
     *
     * Called at display frequency, hence the binary search rather than a scan.
     * Overlapping cues are legal (ASS uses them for signs over dialogue); the
     * one that started most recently wins, which is the sane choice when only
     * one line can be shown.
     */
    fun cueAt(timeMs: Long): SubtitleCue? {
        if (cues.isEmpty()) return null

        // Index of the last cue that has already started.
        var low = 0
        var high = cues.size - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (cues[mid].startMs <= timeMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidate < 0) return null

        // Walk back over cues that started earlier but may still be on screen;
        // a handful is enough, since subtitles are not stacked hundreds deep.
        var index = candidate
        var back = 0
        while (index >= 0 && back < OVERLAP_LOOKBACK) {
            val cue = cues[index]
            if (cue.endMs > timeMs) return cue
            index--
            back++
        }
        return null
    }

    private companion object {
        const val OVERLAP_LOOKBACK = 8
    }
}
