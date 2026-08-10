package com.mdblisthub.tv.core.data.work

/**
 * Hands the video buffer the heap the artwork cache is sitting on.
 *
 * The two are budgeted independently — the image cache takes 25% of the heap,
 * the player's allocator up to another 40% of what is free — but they are
 * never both needed at once. While a film plays, the home screen is not on
 * screen and every decoded poster in memory is doing nothing except making the
 * buffer smaller.
 *
 * Reclaiming it also fixes a measurement trap. The player is built at exactly
 * the moment the home screen leaves, so sampling free heap then reads the
 * worst instant of the whole session — the buffer would be sized against
 * memory that is about to be released anyway. Trimming first, measuring
 * second, is what makes the number honest.
 *
 * Declared here and implemented in the app, for the same reason as
 * [ImageWarmer]: this module knows *when* the trade is worth making, not how
 * the image cache is built.
 */
interface ImageMemoryTrimmer {

    /** Shrinks the in-memory artwork cache to a fraction of its normal size. */
    fun trimForPlayback()

    /** Puts the original budget back, once playback is over. */
    fun restore()

    companion object {
        /** Does nothing, for a graph whose app has not attached one yet. */
        val NoOp = object : ImageMemoryTrimmer {
            override fun trimForPlayback() = Unit
            override fun restore() = Unit
        }
    }
}
