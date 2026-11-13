package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.model.SkipSegment
import com.mdblisthub.tv.core.model.SkipSegmentKind
import com.mdblisthub.tv.core.network.IntroDbApi
import com.mdblisthub.tv.core.network.dto.IntroDbSegmentDto
import com.mdblisthub.tv.core.network.dto.IntroDbSegmentsDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where an episode's intro, recap and credits are, from IntroDB.
 *
 * One small GET per episode, asked beside the source cascade rather than in
 * front of it, and allowed to fail silently: no answer only means no "Skip"
 * button, never a slower or broken start.
 */
class SkipSegmentsRepository(private val api: IntroDbApi) {

    /**
     * Per process, keyed by episode. Going back into an episode — out to the
     * mini-player and back, or a restart from the detail screen — must not ask
     * again. Only real answers are kept; a failure is retried next time.
     */
    private val cache = object : LinkedHashMap<String, List<SkipSegment>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SkipSegment>>) =
            size > CACHE_ENTRIES
    }

    suspend fun segmentsFor(imdbId: String, season: Int, episode: Int): List<SkipSegment> {
        val key = "$imdbId:$season:$episode"
        synchronized(cache) { cache[key] }?.let { return it }

        val dto = try {
            withTimeoutOrNull(TIMEOUT_MS) { api.segments(imdbId, season, episode) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val segments = dto.toSkipSegments()
        synchronized(cache) { cache[key] = segments }
        return segments
    }

    private companion object {
        const val TIMEOUT_MS = 6_000L
        const val CACHE_ENTRIES = 64
    }
}

/** Below this the community has not agreed on where the segment is. */
internal const val MIN_SKIP_CONFIDENCE = 0.5

/** Shorter than this is a timing slip, not a segment worth a button. */
internal const val MIN_SKIP_LENGTH_MS = 5_000L

/**
 * An intro or recap longer than this is a mis-submission (someone timed the
 * cold open along with it). Credits are not capped: an hour-long finale can
 * really end on ten minutes of them.
 */
internal const val MAX_OPENING_LENGTH_MS = 5 * 60_000L

internal fun IntroDbSegmentsDto.toSkipSegments(): List<SkipSegment> = listOfNotNull(
    recap?.toSkipSegment(SkipSegmentKind.RECAP),
    intro?.toSkipSegment(SkipSegmentKind.INTRO),
    outro?.toSkipSegment(SkipSegmentKind.OUTRO),
).sortedBy { it.startMs }

private fun IntroDbSegmentDto.toSkipSegment(kind: SkipSegmentKind): SkipSegment? {
    val start = startMs ?: return null
    val end = endMs ?: return null
    if (start < 0) return null
    val confidence = confidence
    if (confidence != null && confidence < MIN_SKIP_CONFIDENCE) return null
    val length = end - start
    if (length < MIN_SKIP_LENGTH_MS) return null
    if (kind != SkipSegmentKind.OUTRO && length > MAX_OPENING_LENGTH_MS) return null
    return SkipSegment(kind, start, end)
}
