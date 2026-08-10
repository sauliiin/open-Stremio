package com.mdblisthub.tv.player

import android.os.SystemClock
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

    @Volatile
    private var backBufferUs: Long = HeapBudget.MAX_BACK_BUFFER_MS * 1_000L

    @Volatile
    private var memoryCritical = false

    private var lastSampleMs = 0L

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

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    // ------------------------------------------------------- actually adaptive

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = backBufferUs

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        sampleIfDue(parameters.bufferedDurationUs)
        // The delegate still owns the normal decision — this only ever
        // subtracts from it. Growing the buffer is what allocates, so refusing
        // here is the one lever that reliably stops heap pressure getting
        // worse, and it costs nothing when memory is fine.
        if (memoryCritical) return false
        return delegate.shouldContinueLoading(parameters)
    }

    /**
     * Re-derives the back buffer from throughput measured off the allocator
     * itself, rather than from a track's declared bitrate.
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

        memoryCritical = HeapBudget.isCritical()

        val allocated = allocator.totalBytesAllocated.toLong()
        val bytesPerSecond = if (bufferedDurationUs > MIN_MEASURABLE_BUFFER_US && allocated > 0) {
            allocated * 1_000_000L / bufferedDurationUs
        } else {
            HeapBudget.ASSUMED_BYTES_PER_SECOND
        }

        backBufferUs = HeapBudget.backBufferMs(targetBufferBytes, bytesPerSecond) * 1_000L
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 2_000L

        /** Under a second of buffer, the throughput division is mostly noise. */
        const val MIN_MEASURABLE_BUFFER_US = 1_000_000L
    }
}
