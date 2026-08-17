package com.mdblisthub.tv.player

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock

/**
 * Whether the *platform* has said memory is tight — not whether a heap reading
 * looks low.
 *
 * That distinction is the entire point of this file. [AdaptiveLoadControl] used
 * to throttle on `totalMemory() - freeMemory()`, which counts uncollected
 * garbage as used and therefore dips below any threshold routinely and
 * transiently; every dip stalled a film for a reason that had nothing to do
 * with memory. The signals here are the opposite kind: they come from the
 * system, only when it is genuinely reclaiming, and they are the same notice
 * that precedes this process being killed. Acting on them is not a heuristic.
 *
 * **Two sources, because one of them stopped arriving.** `onTrimMemory` was the
 * original and only signal, keyed off `TRIM_MEMORY_RUNNING_LOW`. That level is
 * deprecated, and modern Android no longer delivers the `RUNNING_*` levels to a
 * foreground process the way it used to — which quietly turned this class into a
 * flag that never raised, on exactly the devices that need it. Since the buffer
 * floor in [HeapBudget] is deliberately set close to the OOM line, a safety
 * valve that never opens is worse than no valve at all, so [isTight] now also
 * polls `ActivityManager.MemoryInfo.lowMemory` — the live reading the platform
 * still maintains, and the same one the low-memory killer works from.
 *
 * Registered against the application, once per process, and never unregistered
 * — this is a singleton either way, and the callback outliving one player is
 * exactly what makes the flag survive between films.
 */
internal object MemoryPressure {

    /**
     * How long one trim notice keeps the buffer restrained.
     *
     * The platform announces pressure but never announces its end, so the flag
     * has to expire on its own or a single transient trim would degrade the
     * rest of a two-hour film. Pressure that persists is re-announced well
     * inside this window.
     */
    private const val TTL_MS = 45_000L

    /**
     * How long a [ActivityManager.MemoryInfo] reading is reused.
     *
     * [isTight] is read from `shouldContinueLoading`, which Media3 calls every
     * few milliseconds while loading. `getMemoryInfo` is a binder round trip to
     * `system_server` — cheap once, absurd at that rate — and the condition it
     * reports changes over seconds, not milliseconds.
     */
    private const val POLL_INTERVAL_MS = 2_000L

    /**
     * Null until the first trim, rather than a sentinel timestamp: any "long
     * ago" constant either overflows the subtraction below or, if it is zero,
     * reads as pressure during the first [TTL_MS] after boot — which is
     * precisely when a set-top box that starts straight into this app runs.
     */
    @Volatile
    private var signalledAtMs: Long? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastPollMs = 0L

    @Volatile
    private var lastPollWasLow = false

    private var registered = false

    private val callbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            // Compared against the literal rather than the constant, which is
            // deprecated: the *values* are frozen platform ABI and still arrive
            // on the devices that send them, so the threshold stays honest
            // while the deprecated symbol leaves the build. 10 is
            // TRIM_MEMORY_RUNNING_LOW — RUNNING_MODERATE (5) is routine and
            // deliberately below the line, and everything above 10 (the
            // critical and backgrounded levels) is more urgent, not less.
            if (level >= TRIM_MEMORY_RUNNING_LOW_LEVEL) signal()
        }

        @Deprecated("Kept for API levels that still call it instead of onTrimMemory.")
        override fun onLowMemory() = signal()

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    private const val TRIM_MEMORY_RUNNING_LOW_LEVEL = 10

    fun install(context: Context) {
        synchronized(this) {
            if (registered) return
            registered = true
            val application = context.applicationContext
            appContext = application
            application.registerComponentCallbacks(callbacks)
        }
    }

    private fun signal() {
        signalledAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * True while either signal says the system is reclaiming.
     *
     * The trim notice wins outright when it is fresh, so a device that still
     * sends them keeps the original, cheaper behaviour and never reaches the
     * poll below.
     */
    val isTight: Boolean
        get() {
            val now = SystemClock.elapsedRealtime()
            if (signalledAtMs?.let { now - it < TTL_MS } == true) return true
            return pollLowMemory(now)
        }

    private fun pollLowMemory(now: Long): Boolean {
        val context = appContext ?: return false
        if (now - lastPollMs < POLL_INTERVAL_MS) return lastPollWasLow
        lastPollMs = now

        val manager = context.getSystemService(ActivityManager::class.java)
        lastPollWasLow = manager != null && runCatching {
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.lowMemory
        }.getOrDefault(false)
        return lastPollWasLow
    }
}
