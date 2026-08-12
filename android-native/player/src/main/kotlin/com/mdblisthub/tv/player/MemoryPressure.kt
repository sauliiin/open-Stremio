package com.mdblisthub.tv.player

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
 * with memory. `onTrimMemory` is the opposite kind of signal: it is sent by the
 * system, only when it is genuinely reclaiming, and it is the same notice that
 * precedes this process being killed. Acting on it is not a heuristic.
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
     * Null until the first trim, rather than a sentinel timestamp: any "long
     * ago" constant either overflows the subtraction below or, if it is zero,
     * reads as pressure during the first [TTL_MS] after boot — which is
     * precisely when a set-top box that starts straight into this app runs.
     */
    @Volatile
    private var signalledAtMs: Long? = null

    private var registered = false

    private val callbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            // RUNNING_LOW and above mean the system is reclaiming while this
            // app is in the foreground, which is the only case worth reacting
            // to: RUNNING_MODERATE is routine, and the backgrounded levels
            // describe a process that is not playing anything.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) signal()
        }

        @Deprecated("Kept for API levels that still call it instead of onTrimMemory.")
        override fun onLowMemory() = signal()

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    fun install(context: Context) {
        synchronized(this) {
            if (registered) return
            registered = true
            context.applicationContext.registerComponentCallbacks(callbacks)
        }
    }

    private fun signal() {
        signalledAtMs = SystemClock.elapsedRealtime()
    }

    val isTight: Boolean
        get() = signalledAtMs?.let { SystemClock.elapsedRealtime() - it < TTL_MS } == true
}
