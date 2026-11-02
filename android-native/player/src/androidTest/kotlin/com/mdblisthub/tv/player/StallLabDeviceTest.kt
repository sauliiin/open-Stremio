package com.mdblisthub.tv.player

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.PlaybackHint
import com.mdblisthub.tv.core.network.HttpClients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asFlow
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * Plays a real file through the real [PlaybackController] against a host that
 * misbehaves on purpose — `player/stall-lab/fault_server.py`.
 *
 * "It only comes back when I press skip" cannot be reproduced by waiting for a
 * mirror to have a bad night, and a unit test cannot say whether the engine
 * really recovers. This can: the failure is written into the URL, and the
 * verdict is how long the playhead stood still with the viewer waiting.
 *
 * Skipped unless `lab_url` is passed, because it needs that server running —
 * see `player/PLAYBACK_STALLS.md`.
 */
@RunWith(AndroidJUnit4::class)
class StallLabDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    private val name = args.getString("lab_name") ?: "scenario"
    private val runtimeMinutes = intArg("lab_runtime_min") ?: 8

    private val frameThread = HandlerThread("lab-frames").apply { start() }
    private val frames = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 4).apply {
        setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(frameThread.looper))
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: PlaybackController

    /** Something to do to the player at a given moment, as a viewer would. */
    private class Step(val atSeconds: Int, val label: String, val run: () -> Unit)

    @Test
    fun playsThroughTheFault() {
        val url = args.getString("lab_url")
        assumeTrue("lab_url not given", url != null)

        // A previous scenario's spans would serve this one from disk.
        File(context.cacheDir, "media").deleteRecursively()

        // This process has none of the app's own heap: Compose, Coil, the artwork
        // bitmaps. Without standing in for them a memory figure reads better than it is.
        val ballast = List(intArg("lab_ballast_mb") ?: 0) { ByteArray(MB.toInt()) }

        openPlayer()
        play(listOfNotNull(url, args.getString("lab_fallback_url")), intArg("lab_start_ms")?.toLong())

        val report = watch(intArg("lab_seconds") ?: 120, scheduledSteps())
        Log.i(TAG, "RESULT $name $report ballast=${ballast.size}MB")

        instrumentation.runOnMainSync {
            controller.release()
            scope.cancel()
        }
        frames.close()
        frameThread.quitSafely()
    }

    private fun openPlayer() = instrumentation.runOnMainSync {
        controller = PlaybackController(context, scope, HttpClients.playback(HttpClients.metadata(context)))
        controller.player.setVideoSurface(frames.surface)
    }

    private fun play(urls: List<String>, startMs: Long? = null) = instrumentation.runOnMainSync {
        controller.play(
            candidates = urls.mapIndexed { index, link ->
                PlayableStream(
                    key = "lab$index",
                    addon = "lab",
                    title = name,
                    url = link,
                    filename = "$name-${link.hashCode()}.bin",
                )
            }.asFlow(),
            expectedRuntimeMinutes = runtimeMinutes,
            hint = startMs?.let { PlaybackHint(it, runtimeMinutes * 60_000L, null) },
            resumeAtPercent = startMs?.let { it * 100f / (runtimeMinutes * 60_000f) },
        )
    }

    private fun scheduledSteps(): List<Step> = buildList {
        intArg("lab_forward_at_s")?.let { at ->
            add(Step(at, "pressing forward") { onMain { controller.nudge(forward = true) } })
        }
        intArg("lab_pause_at_s")?.let { at ->
            add(Step(at, "pausing") { onMain { controller.pause() } })
            add(Step(at + (intArg("lab_pause_for_s") ?: 0), "resuming") { onMain { controller.resume() } })
        }
        intArg("lab_seek_storm_s")?.let { every ->
            // Seeded, so a failure here can be replayed exactly.
            val random = Random(SEEK_SEED)
            for (at in every until (intArg("lab_seconds") ?: 120) step every) {
                val share = random.nextDouble(0.0, 0.95)
                add(Step(at, "seeking to $share of the film") {
                    onMain {
                        val duration = controller.player.duration
                        if (duration != C.TIME_UNSET) controller.seekTo((duration * share).toLong())
                    }
                })
            }
        }
        intArg("lab_next_title_at_s")?.let { at ->
            val next = args.getString("lab_next_url").orEmpty()
            add(Step(at, "changing title") { play(listOf(next)) })
        }
        intArg("lab_reopen_at_s")?.let { at ->
            add(Step(at, "closing and reopening the screen") {
                onMain { controller.release() }
                openPlayer()
                play(listOfNotNull(args.getString("lab_url")), startMs = 30_000L)
            })
        }
    }.sortedBy { it.atSeconds }

    private fun watch(seconds: Int, steps: List<Step>): String {
        var pausedByTest = false
        var previous: Sample? = null
        var frozenMs = 0L
        var longestFrozenMs = 0L
        var totalFrozenMs = 0L
        var pictureFrozenMs = 0L
        var longestPictureFrozenMs = 0L
        var skippedMs = 0L
        var freezes = 0
        var firstFrameAt = -1L
        var failedAt = -1L
        var nextStep = 0
        val began = SystemClock.elapsedRealtime()
        var lastAt = began

        while (SystemClock.elapsedRealtime() - began < seconds * 1_000L) {
            SystemClock.sleep(POLL_MS)
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastAt
            lastAt = now

            while (nextStep < steps.size && (now - began) / 1_000L >= steps[nextStep].atSeconds) {
                val step = steps[nextStep++]
                Log.i(TAG, "$name ${step.label}")
                if (step.label == "pausing") pausedByTest = true
                if (step.label == "resuming") pausedByTest = false
                step.run()
                previous = null
            }

            val sample = sample()
            val last = previous
            if (firstFrameAt < 0 && sample.state == Player.STATE_READY && sample.positionMs > 0) {
                firstFrameAt = now - began
            }
            // An error on screen is an answer, not a freeze.
            if (failedAt < 0 && sample.phase == PlaybackPhase.FAILED.name) failedAt = now - began
            if (firstFrameAt >= 0 && last != null && !pausedByTest && sample.state != Player.STATE_ENDED && failedAt < 0) {
                val moved = sample.positionMs - last.positionMs
                // The bound is generous because a poll can run late; only a jump far
                // beyond real time is film the viewer did not get to see.
                if (moved > elapsed + JUMP_TOLERANCE_MS) skippedMs += moved - elapsed

                if (moved < elapsed / 4) {
                    if (frozenMs == 0L) freezes++
                    frozenMs += elapsed
                    totalFrozenMs += elapsed
                    longestFrozenMs = maxOf(longestFrozenMs, frozenMs)
                } else {
                    frozenMs = 0L
                }

                // Clock moving, picture not: the freeze no player state reports.
                if (moved >= elapsed / 4 && sample.rendered == last.rendered) {
                    pictureFrozenMs += elapsed
                    longestPictureFrozenMs = maxOf(longestPictureFrozenMs, pictureFrozenMs)
                } else {
                    pictureFrozenMs = 0L
                }
            }

            Log.i(
                TAG,
                "$name t=${(now - began) / 1000}s pos=${sample.positionMs} " +
                    "buf=${sample.bufferedMs} state=${sample.state} playing=${sample.playing} " +
                    "loading=${sample.loading} rendered=${sample.rendered} dropped=${sample.dropped} " +
                    "frozen=${frozenMs}ms heapMB=${heapUsedMb()}",
            )
            previous = sample
        }

        val end = sample()
        return "first_frame=${firstFrameAt}ms failed_at=${failedAt}ms final_pos=${end.positionMs}ms " +
            "freezes=$freezes longest_freeze=${longestFrozenMs}ms total_frozen=${totalFrozenMs}ms " +
            "longest_picture_freeze=${longestPictureFrozenMs}ms skipped=${skippedMs}ms " +
            "dropped=${end.dropped} phase=${end.phase} source=${end.source}"
    }

    private fun sample(): Sample {
        lateinit var sample: Sample
        instrumentation.runOnMainSync {
            val player = controller.player
            val state = controller.state.value
            val counters = player.videoDecoderCounters
            sample = Sample(
                positionMs = player.currentPosition,
                state = player.playbackState,
                playing = player.isPlaying,
                bufferedMs = player.bufferedPosition,
                loading = player.isLoading,
                rendered = counters?.renderedOutputBufferCount ?: 0,
                dropped = counters?.droppedBufferCount ?: 0,
                phase = state.phase.name,
                source = state.activeStream?.key,
            )
        }
        return sample
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun intArg(key: String): Int? = args.getString(key)?.toIntOrNull()

    private fun heapUsedMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / MB
    }

    private data class Sample(
        val positionMs: Long,
        val state: Int,
        val playing: Boolean,
        val bufferedMs: Long,
        val loading: Boolean,
        val rendered: Int,
        val dropped: Int,
        val phase: String,
        val source: String?,
    )

    private companion object {
        const val TAG = "StallLab"
        const val MB = 1024L * 1024L
        const val POLL_MS = 1_000L
        const val JUMP_TOLERANCE_MS = 1_500L
        const val SEEK_SEED = 42
    }
}
