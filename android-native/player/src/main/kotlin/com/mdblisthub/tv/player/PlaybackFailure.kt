package com.mdblisthub.tv.player

/**
 * Why playback stopped, as a value rather than as a sentence.
 *
 * This module used to hand the UI finished Portuguese strings, which made the
 * player the one screen the language setting could never reach — an engine has
 * no business knowing which language the box is set to. Each case here carries
 * only the facts the message needs; `PlayerScreen` turns it into words.
 */
sealed interface PlaybackFailure {

    /** Every installed addon answered, and none of them with a playable link. */
    data object NoCandidates : PlaybackFailure

    /** The whole queue was walked twice. [count] is how long the queue was. */
    data class AllCandidatesFailed(val count: Int) : PlaybackFailure

    /**
     * Several sources in a row served a placeholder clip instead of the film,
     * which in practice means the debrid provider is rate limiting rather than
     * that the mirrors are individually dead.
     */
    data object DecoyStreak : PlaybackFailure

    /** The source the user picked by hand has no URL to open. */
    data object ManualNoLink : PlaybackFailure

    /** It opened, then went quiet without ever delivering enough to play. */
    data object ManualUnresponsive : PlaybackFailure

    /** It refused to open at all. */
    data object ManualFailed : PlaybackFailure

    /** It opened, and what came back was a removal notice, not the film. */
    data object ManualDecoy : PlaybackFailure
}
