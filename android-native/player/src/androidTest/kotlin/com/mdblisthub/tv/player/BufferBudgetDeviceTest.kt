package com.mdblisthub.tv.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the buffer policy actually decides on the box it is running on.
 *
 * The JVM tests pin the arithmetic; this pins the inputs. `maxMemory()` is
 * `dalvik.vm.heapsize` as raised by `android:largeHeap`, and
 * `ActivityManager.MemoryInfo.threshold` is whatever this kernel's low-memory
 * killer is configured with — neither has a meaningful value off-device, and
 * both are what separate a box that stutters from one that does not.
 */
@RunWith(AndroidJUnit4::class)
class BufferBudgetDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** ~3.1MB/s, the high-bitrate remux the whole policy is sized against. */
    private val remuxBytesPerSecond = HeapBudget.ASSUMED_BYTES_PER_SECOND

    /** ~5MB/s — a 40Mbps encode, where a small budget stops being enough. */
    private val highBitrateBytesPerSecond = 5L * 1024 * 1024

    @Test
    fun reportsWhatThisDeviceActuallyGets() {
        val budget = HeapBudget.targetBufferBytes(context)
        val capacityMs = budget * 1_000L / remuxBytesPerSecond
        Log.i(
            TAG,
            "heap ${Runtime.getRuntime().maxMemory() / MB}MB, " +
                "ram ${HeapBudget.totalRamBytes(context)?.div(MB)}MB " +
                "(${HeapBudget.spareRamBytes(context)?.div(MB)}MB spare) " +
                "-> budget ${budget / MB}MB = ${capacityMs}ms at 25Mbps; " +
                "resume ${HeapBudget.rebufferStartMs(budget, remuxBytesPerSecond)}ms, " +
                "back buffer ${HeapBudget.backBufferMs(budget, remuxBytesPerSecond)}ms",
        )

        // The budget must be something the heap can actually back. This is the
        // invariant the tier floor used to break by overriding the ceiling.
        assertTrue(
            "budget ${budget / MB}MB exceeds a third of the ${Runtime.getRuntime().maxMemory() / MB}MB heap",
            budget <= Runtime.getRuntime().maxMemory() / 3,
        )
        assertTrue("budget must be positive", budget > 0)
    }

    /**
     * The regression, measured on device: at a bitrate this budget cannot hold
     * ten seconds of, the resume threshold has to come down — otherwise the
     * player is asked to rebuild a cushion larger than its whole allowance
     * before it may show a frame, and the picture stays stopped until the
     * viewer presses skip.
     */
    @Test
    fun theResumeThresholdComesDownWhenTheBudgetCannotHoldTheFullCushion() {
        val budget = HeapBudget.targetBufferBytes(context)
        val ceiling = HeapBudget.bufferForPlaybackAfterRebufferMs().toLong()

        for (bytesPerSecond in listOf(remuxBytesPerSecond, highBitrateBytesPerSecond)) {
            val capacityMs = budget * 1_000L / bytesPerSecond
            val resumeMs = HeapBudget.rebufferStartMs(budget, bytesPerSecond)
            Log.i(TAG, "at ${bytesPerSecond * 8 / 1_000_000}Mbps: ${capacityMs}ms held, resume at ${resumeMs}ms")

            assertTrue("resume threshold must never exceed the ceiling", resumeMs <= ceiling)
            assertTrue(
                "resume threshold must never exceed the whole budget",
                resumeMs <= capacityMs || resumeMs == HeapBudget.MIN_REBUFFER_START_MS,
            )
        }
    }

    /**
     * The same thing one level up, through the object the engine actually
     * asks. A real [DefaultLoadControl] with a real allocator, so nothing here
     * depends on having read Media3's source correctly.
     */
    @Test
    fun theLoadControlResumesOnWhatTheDeviceCanRebuild() {
        val budget = HeapBudget.targetBufferBytes(context)
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = AdaptiveLoadControl(
            delegate = DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    120_000,
                    600_000,
                    HeapBudget.bufferForPlaybackMs(),
                    HeapBudget.bufferForPlaybackAfterRebufferMs(),
                )
                .setTargetBufferBytes(budget)
                .setBackBuffer(0, true)
                .build(),
            allocator = allocator,
            targetBufferBytes = budget,
        )

        // `DefaultLoadControl` keeps per-player state and reads it back from
        // `shouldStartPlayback`, so a player it has never been told about is a
        // null lookup rather than a default.
        loadControl.onPrepared(PlayerId.UNSET)

        // Seeded from the pessimistic assumption before any sample is taken.
        val seeded = HeapBudget.rebufferStartMs(budget, HeapBudget.ASSUMED_BYTES_PER_SECOND)

        // Just under what the delegate's flat constant demands: the one case
        // the override exists for.
        val justBelowCeiling = HeapBudget.bufferForPlaybackAfterRebufferMs() * 1_000L - 1
        val expectedToResume = seeded * 1_000L <= justBelowCeiling

        assertEquals(
            "a rebuffer must resume at what this device can rebuild (${seeded}ms)",
            expectedToResume,
            loadControl.shouldStartPlayback(parameters(justBelowCeiling, rebuffering = true)),
        )

        // Startup is untouched: the first frame still waits for the full
        // `bufferForPlaybackMs`, so nothing about opening a film changes.
        assertEquals(
            "the first frame must not resume early",
            false,
            loadControl.shouldStartPlayback(
                parameters(HeapBudget.bufferForPlaybackMs() * 1_000L - 1, rebuffering = false),
            ),
        )
    }

    /** Nothing is given to the back buffer before throughput has been measured. */
    @Test
    fun theBackBufferStartsAtZero() {
        val budget = HeapBudget.targetBufferBytes(context)
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = AdaptiveLoadControl(
            delegate = DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setTargetBufferBytes(budget)
                .build(),
            allocator = allocator,
            targetBufferBytes = budget,
        )
        assertEquals(0L, loadControl.getBackBufferDurationUs(PlayerId.UNSET))
    }

    /**
     * A one-period timeline over an `https` item, not `Timeline.EMPTY`.
     *
     * `DefaultLoadControl.shouldStartPlayback` asks whether this is local
     * playback, which means reaching into the timeline for the period and its
     * media item — an empty timeline throws, and a `file://` one would be
     * answering a different question than the one this app has, since every
     * source that stutters here arrives over the network.
     */
    private fun parameters(bufferedDurationUs: Long, rebuffering: Boolean): LoadControl.Parameters {
        val timeline = SinglePeriodTimeline(
            /* durationUs = */ 90L * 60 * 1_000_000,
            /* isSeekable = */ true,
            /* isDynamic = */ false,
            /* useLiveConfiguration = */ false,
            /* manifest = */ null,
            MediaItem.fromUri("https://example.invalid/film.mkv"),
        )
        return LoadControl.Parameters(
            PlayerId.UNSET,
            timeline,
            MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            /* playbackPositionUs = */ 0L,
            bufferedDurationUs,
            /* playbackSpeed = */ 1f,
            /* playWhenReady = */ true,
            rebuffering,
            /* targetLiveOffsetUs = */ C.TIME_UNSET,
            /* lastRebufferRealtimeMs = */ C.TIME_UNSET,
        )
    }

    private companion object {
        const val TAG = "BufferBudgetDeviceTest"
        const val MB = 1024 * 1024
    }
}
