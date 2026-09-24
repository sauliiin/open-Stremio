package com.mdblisthub.tv.player

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * A [DefaultLoadControl] that reacts to the heap it is actually running on.
 *
 * Two things here cannot be expressed by `DefaultLoadControl` alone, which is
 * the whole reason this wrapper exists:
 *
 * 1. **The back buffer is a constructor constant there.** `LoadControl`
 *    declares `getBackBufferDurationUs` as a default method, so a wrapping
 *    implementation can vary it per call — which is what lets the back buffer
 *    stay a fixed *share* of the byte budget instead of a fixed number of
 *    seconds that happens to be enormous at high bitrate.
 * 2. **Nothing consults free memory.** The byte budget is fixed when the
 *    player is built, so a device that was comfortable at the start of a film
 *    has no way to give ground later.
 *
 * Every member is forwarded to [delegate] by hand rather than through Kotlin's
 * `by` interface delegation — deliberately, after that approach crashed real
 * playback. `by` only synthesises a forwarding override for members the
 * interface declares *abstract*; `LoadControl` has exactly one of those
 * (`getAllocator`), and everything else — `onPrepared`, `onTracksSelected`,
 * `retainBackBufferFromKeyframe`, `shouldStartPlayback`,
 * `shouldContinuePreloading` — is a Java 8 `default` method. For those, `by`
 * generates nothing, so a call landed on `LoadControl`'s own default body
 * instead of on `delegate`'s real implementation — which for
 * `retainBackBufferFromKeyframe` throws `IllegalStateException("...not
 * implemented")` unconditionally, and for the rest would have silently
 * skipped `DefaultLoadControl`'s actual buffer bookkeeping. Compiling clean
 * proved nothing here; only pressing play did.
 *
 * What this deliberately does *not* do is fight the delegate over
 * `Allocator.setTargetBufferSize`. `DefaultLoadControl` re-applies its own
 * target whenever tracks are selected, so a wrapper that also wrote there
 * would produce a value that silently reverts. Instead the budget is chosen
 * once, at construction, from measured headroom — and the only runtime lever
 * used is refusing to grow the buffer further when the heap gets tight, which
 * the delegate cannot override.
 */
@OptIn(UnstableApi::class)
class AdaptiveLoadControl(
    private val delegate: DefaultLoadControl,
    private val allocator: DefaultAllocator,
    private val targetBufferBytes: Int,
) : LoadControl {

    /**
     * Zero until throughput has been measured, not the maximum.
     *
     * The first seconds of playback are when the byte budget is emptiest and
     * the forward buffer matters most, and starting at [HeapBudget
     * .MAX_BACK_BUFFER_MS] meant a device whose whole budget is twenty seconds
     * of film spent half of the first ten on media already played — before a
     * single sample had been taken to find out whether it could afford to.
     * Zero is `DefaultLoadControl`'s own default and the safe direction: the
     * first [sampleIfDue] two seconds in raises it to whatever the surplus
     * really allows.
     */
    @Volatile
    private var backBufferUs: Long = 0L

    /**
     * The buffer this device can afford to rebuild before the picture comes
     * back — see [HeapBudget.rebufferStartMs].
     *
     * Seeded from the pessimistic bitrate assumption rather than left at the
     * delegate's flat constant, because the first rebuffer can arrive before
     * the first throughput sample does.
     */
    @Volatile
    private var rebufferStartUs: Long =
        HeapBudget.rebufferStartMs(targetBufferBytes, HeapBudget.ASSUMED_BYTES_PER_SECOND) * 1_000L

    /**
     * The last throughput actually measured, kept rather than discarded.
     *
     * The sampler can only measure while there is a second of buffer to divide
     * by, and the moments it cannot — right after a seek, in the middle of a
     * stall — are exactly the moments the numbers derived from it matter most.
     * Falling back to [HeapBudget.ASSUMED_BYTES_PER_SECOND] there would mean a
     * 5Mbps stream being treated as a 25Mbps one at the precise instant the
     * resume threshold is being decided.
     */
    @Volatile
    private var lastBytesPerSecond: Long = HeapBudget.ASSUMED_BYTES_PER_SECOND

    private var lastSampleMs = 0L

    /** What the last [Log] line said, so an unchanged verdict is not repeated. */
    private var loggedBackBufferMs = -1L
    private var loggedRebufferStartMs = -1L

    // ------------------------------------------------- forwarded, unchanged
    //
    // Every one of these matters: `DefaultLoadControl` holds per-player state
    // (loading flags, the shared allocator's target size) that it updates
    // exactly in these calls. Skipping any of them — as the `by` delegate
    // version silently did — desyncs that state from what the engine believes
    // is true.

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    // ------------------------------------------------------- actually adaptive

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = backBufferUs

    /**
     * Resumes on what the device can afford, not on one number for every
     * device.
     *
     * The delegate is asked first and its answer is final when it is yes, so
     * nothing here can make a device wait *longer* than `DefaultLoadControl`
     * would. All this adds is a second, lower threshold for the one case the
     * flat constant gets wrong: a box whose byte budget cannot hold
     * `bufferForPlaybackAfterRebufferMs` of the stream being played, which is
     * then asked to rebuild a cushion larger than its entire allowance before
     * it may show a frame. It never finishes, so the picture stays stopped
     * until the viewer presses skip — a seek being the one thing that either
     * recomputes the requirement somewhere the link can satisfy or serves it
     * from the disk cache outright.
     *
     * Restricted to `rebuffering`, so the first frame of a film still waits
     * for the full `bufferForPlaybackMs` and nothing about startup changes.
     */
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (delegate.shouldStartPlayback(parameters)) return true
        if (!parameters.rebuffering) return false
        return parameters.bufferedDurationUs >= rebufferStartUs
    }

    /**
     * Loading is vetoed for one reason only, and never below [PRESSURE_FLOOR_US]
     * of buffer.
     *
     * There used to be a heap-pressure veto here: when free memory looked low
     * this returned false, stopping the buffer from growing. That was wrong,
     * and badly so. `totalMemory() - freeMemory()` counts garbage that has not
     * been collected yet, so free heap dips below any fixed threshold routinely
     * and transiently — and each dip stopped loading *at any buffer level*,
     * drained what was left, and stalled playback mid-film for a reason that
     * had nothing to do with the source.
     *
     * What replaces it differs on both counts. The signal is [MemoryPressure],
     * which is the system announcing that it is reclaiming rather than a
     * reading being interpreted; and the veto has a floor, so the worst it can
     * do is hold the buffer at half a minute instead of the full budget. It
     * cannot starve the forward buffer, which is what made the old version a
     * cure worse than the disease. This exists because the budget in
     * `HeapBudget` is chosen once, from measurements taken before the film
     * started — the trim notice is the only chance to give ground afterwards.
     */
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        sampleIfDue(parameters.bufferedDurationUs)
        if (MemoryPressure.isTight && parameters.bufferedDurationUs > PRESSURE_FLOOR_US) {
            return false
        }
        return delegate.shouldContinueLoading(parameters)
    }

    /**
     * Re-derives the back buffer and the resume threshold from throughput
     * measured off the allocator itself, rather than from a track's declared
     * bitrate.
     *
     * `getTotalBytesAllocated() / bufferedDuration` is the effective bytes per
     * second of buffered media — real, current, and free to read. It runs
     * slightly high because the allocated total includes the back buffer as
     * well as the forward one, which biases the resulting back buffer
     * conservative; that is the harmless direction.
     */
    private fun sampleIfDue(bufferedDurationUs: Long) {
        val now = SystemClock.elapsedRealtime()
        // `shouldContinueLoading` is called every few milliseconds while
        // loading. Measuring the heap on each one would cost more than the
        // stutter it exists to prevent.
        if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
        lastSampleMs = now

        val allocated = allocator.totalBytesAllocated.toLong()
        if (bufferedDurationUs > MIN_MEASURABLE_BUFFER_US && allocated > 0) {
            lastBytesPerSecond = allocated * 1_000_000L / bufferedDurationUs
        }
        val bytesPerSecond = lastBytesPerSecond

        val backBufferMs = HeapBudget.backBufferMs(targetBufferBytes, bytesPerSecond)
        val rebufferStartMs = HeapBudget.rebufferStartMs(targetBufferBytes, bytesPerSecond)
        backBufferUs = backBufferMs * 1_000L
        rebufferStartUs = rebufferStartMs * 1_000L

        // Only when the verdict actually moves. This runs every two seconds for
        // the length of a film, and a line per sample would bury the one thing
        // worth reading — that on a constrained box the resume threshold lands
        // below `bufferForPlaybackAfterRebufferMs`, which is the whole reason
        // `shouldStartPlayback` is overridden.
        if (backBufferMs != loggedBackBufferMs || rebufferStartMs != loggedRebufferStartMs) {
            loggedBackBufferMs = backBufferMs
            loggedRebufferStartMs = rebufferStartMs
            Log.i(
                TAG,
                "budget ${targetBufferBytes / MB}MB " +
                    "@ ${bytesPerSecond * 8 / 1_000_000}Mbps " +
                    "= ${targetBufferBytes * 1_000L / bytesPerSecond.coerceAtLeast(1L)}ms of film; " +
                    "resume after rebuffer at ${rebufferStartMs}ms " +
                    "(ceiling ${HeapBudget.bufferForPlaybackAfterRebufferMs()}ms), " +
                    "back buffer ${backBufferMs}ms",
            )
        }
    }

    private companion object {
        const val TAG = "Playback"

        const val MB = 1024 * 1024

        const val SAMPLE_INTERVAL_MS = 2_000L

        /** Under a second of buffer, the throughput division is mostly noise. */
        const val MIN_MEASURABLE_BUFFER_US = 1_000_000L

        /**
         * The buffer a memory-pressure veto may never take playback below.
         *
         * Enough that a mirror can go quiet for half a minute without the
         * picture stopping — the same cushion the whole load control is sized
         * for, just without the headroom above it.
         */
        const val PRESSURE_FLOOR_US = 30L * 1_000_000L
    }
}
