package com.mdblisthub.tv.core.model

/** Which part of an episode a [SkipSegment] covers — it decides the button's label. */
enum class SkipSegmentKind { INTRO, RECAP, OUTRO }

/**
 * A stretch of an episode the viewer can jump over: the opening titles, the
 * "previously on" recap, or the end credits.
 *
 * Times are into the episode as released, which is what the community timed —
 * a release cut differently (an extra studio logo up front, say) lands every
 * segment a few seconds off, which is why these are offered as a button and
 * never applied on their own.
 */
data class SkipSegment(
    val kind: SkipSegmentKind,
    val startMs: Long,
    val endMs: Long,
)
