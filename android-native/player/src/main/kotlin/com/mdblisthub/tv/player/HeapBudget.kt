package com.mdblisthub.tv.player

/**
 * How many bytes the video buffer is allowed, measured rather than assumed.
 *
 * The number that matters here is **heap headroom**, not free device RAM.
 * `DefaultAllocator` takes its `byte[]` from the Java heap, so a box with a
 * gigabyte of free RAM and a 128MB `dalvik.vm.heapsize` still dies at 128MB;
 * conversely a heap that has grown large but is mostly garbage has far more
 * room than `totalMemory()` suggests.
 *
 * `maxMemory()` alone — which is what this replaced — answers "how big can the
 * heap ever get", not "how much of it is available right now". Sizing off the
 * ceiling means the artwork cache, Compose and the buffer each budget against
 * the same number as though the other two did not exist.
 */
internal object HeapBudget {

    /**
     * Share of the *currently free* headroom the buffer may claim.
     *
     * Deliberately well under half: Compose, Coil and the decoders all still
     * need to allocate while a film runs, and a buffer that claims everything
     * free at the moment it was measured turns the next artwork decode into an
     * OOM.
     */
    private const val HEADROOM_SHARE = 0.40

    /** Below this, a stream stutters no matter how healthy the source is. */
    private const val MIN_TARGET_BYTES = 24L * 1024 * 1024

    /** Above this the extra buffer buys nothing a viewer can perceive. */
    private const val MAX_TARGET_BYTES = 160L * 1024 * 1024

    /**
     * Fraction of the byte budget the back buffer may hold.
     *
     * This ratio is the actual fix for the old configuration, and it is worth
     * being explicit about why. A fixed 30s back buffer against a byte cap is
     * self-defeating: at 20Mbps those 30 seconds are ~75MB, which on a small
     * box exceeds the entire budget on its own, so the forward buffer — the
     * one that decides whether playback stutters — is starved by the one that
     * only makes a rewind slightly cheaper. Pinning the back buffer to a share
     * of the same pot means it can never do that, at any bitrate.
     */
    const val BACK_BUFFER_SHARE = 0.20

    /** The ceiling on rewind-for-free, once the share above allows that much. */
    const val MAX_BACK_BUFFER_MS = 10_000L

    /** Enough to keep the picture moving while the pressure passes. */
    const val MIN_BACK_BUFFER_MS = 2_000L

    /**
     * Used only until real throughput is observed — see
     * [AdaptiveLoadControl]. Deliberately a high estimate (~25Mbps), because
     * guessing low here is what produces an oversized back buffer on exactly
     * the high-bitrate release that cannot afford one.
     */
    const val ASSUMED_BYTES_PER_SECOND = 3_100_000L

    /**
     * Free heap below which the buffer stops growing entirely.
     *
     * A hard floor rather than a proportion: at this point the question is no
     * longer how smooth playback is, it is whether the process survives the
     * next allocation.
     */
    private const val CRITICAL_HEADROOM_BYTES = 24L * 1024 * 1024

    /**
     * Free heap right now.
     *
     * `totalMemory() - freeMemory()` counts garbage that has not been
     * collected yet as used, so this under-reports the real headroom. That is
     * the safe direction to be wrong in, and it is emphatically not worth
     * "fixing" with a `System.gc()` before measuring — that trades a small
     * over-estimate for a visible pause.
     */
    fun headroomBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    }

    fun targetBufferBytes(): Int =
        (headroomBytes() * HEADROOM_SHARE)
            .toLong()
            .coerceIn(MIN_TARGET_BYTES, MAX_TARGET_BYTES)
            .toInt()

    fun isCritical(): Boolean = headroomBytes() < CRITICAL_HEADROOM_BYTES

    /**
     * How much back buffer [targetBytes] affords at the given throughput,
     * clamped so it is never the reason the forward buffer runs dry.
     */
    fun backBufferMs(targetBytes: Int, bytesPerSecond: Long): Long {
        val usable = bytesPerSecond.coerceAtLeast(1L)
        val affordableMs = (targetBytes * BACK_BUFFER_SHARE / usable * 1000L).toLong()
        return affordableMs.coerceIn(MIN_BACK_BUFFER_MS, MAX_BACK_BUFFER_MS)
    }
}
