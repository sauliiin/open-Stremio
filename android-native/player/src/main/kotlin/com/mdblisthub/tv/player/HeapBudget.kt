package com.mdblisthub.tv.player

import android.app.ActivityManager
import android.content.Context

/**
 * How many bytes the video buffer is allowed, measured rather than assumed.
 *
 * Two independent limits apply, and the budget is the **smaller** of them.
 * Getting this wrong in either direction is what makes a buffer either stutter
 * or get the app killed:
 *
 * 1. **Heap ceiling.** `DefaultAllocator` takes its `byte[]` from the Java
 *    heap, so `Runtime.maxMemory()` — which is `dalvik.vm.heapsize`, raised
 *    here by `android:largeHeap` — is a hard wall. Crossing it is an
 *    `OutOfMemoryError`, no matter how much RAM the device has spare.
 * 2. **Device free RAM.** Staying under the heap ceiling does not make an
 *    allocation safe. A box with 200MB genuinely free will start killing
 *    background processes — and eventually this one — long before a 250MB heap
 *    ceiling is reached, because that ceiling is a *policy* number and free RAM
 *    is a *physical* one. `ActivityManager.MemoryInfo.threshold` is the level
 *    the platform itself treats as the danger line.
 *
 * Sizing off the heap alone (what this did first) is why a device with plenty
 * of headroom on paper and very little RAM in practice could be handed a
 * buffer it had no way to back.
 */
internal object HeapBudget {

    /**
     * Share of the *currently free* heap headroom the buffer may claim.
     *
     * Still not all of it: Compose, Coil and the decoders keep allocating while
     * a film runs, and a buffer that claims everything free at the moment it
     * was measured turns the next artwork decode into an OOM.
     *
     * But the reading this multiplies is already pessimistic twice over — it
     * counts uncollected garbage as used, and it is taken while the browsing
     * screens' bitmaps are still resident — so the old 0.55 was cautious about
     * a number that was itself cautious. That compounding is what left
     * high-bitrate files stuttering on boxes with room to spare: the byte
     * budget, not the 120s buffer duration, is what caps the forward buffer,
     * and at 25Mbps every 32MB of it is only ten seconds of film.
     */
    private const val HEAP_SHARE = 0.72

    /**
     * Share of genuinely spare device RAM the buffer may claim.
     *
     * Lower than the heap share on purpose. Heap headroom is ours alone; free
     * RAM is shared with every other process on the box, and taking a large
     * slice of it is how an app gets itself killed while backgrounded. Just over
     * half of what the platform itself calls spare — i.e. what is left *above*
     * the low-memory killer's own threshold — is the most that can be justified
     * against that risk.
     */
    private const val RAM_SHARE = 0.55

    /** What a healthy device should get, when both limits allow it. */
    private const val PREFERRED_MIN_BYTES = 128L * 1024 * 1024

    /**
     * The point below which playback stutters regardless, so there is no
     * reason to go under it — if the device cannot afford even this, a smaller
     * buffer would not save it either.
     *
     * Raised from 24MB after devices with 1.5–2.5GB of total RAM kept
     * rebuffering on high-bitrate remuxes: at ~25Mbps, 24MB is only ~8s of
     * forward buffer, well under what a normal network hiccup drains.
     *
     * Deliberately pushed almost up to [PREFERRED_MIN_BYTES] rather than to
     * some smaller compromise. Set this close enough to `PREFERRED_MIN_BYTES`
     * and `targetBufferBytes`'s floor collapses to effectively
     * `min(PREFERRED_MIN_BYTES, maxMemory() / 3)` on tight devices — i.e. the
     * *heap* ceiling the rest of this file already treats as the real risk
     * line (a third of the heap, leaving two thirds for Compose/Coil/decoders)
     * becomes the only thing still standing between this floor and an OOM,
     * instead of a second, more conservative floor underneath it. That is the
     * trade: this stops yielding to a low *free-RAM* reading (the
     * low-memory-killer's own signal) on the tightest boxes, and leans on the
     * heap math alone. Remeasure on an actual 1.5GB device if OOMs or process
     * kills show up where they did not before — this is intentionally right
     * at that edge, not short of it.
     */
    private const val ABSOLUTE_MIN_BYTES = 112L * 1024 * 1024

    /**
     * Above this the extra buffer buys nothing a viewer can perceive.
     *
     * "Perceive" is bitrate-relative, which is why this is not lower: 320MB is
     * a comfortable four minutes of a 10Mbps encode but under two of a 40Mbps
     * remux, and the remux is the file that stutters. A device only ever
     * reaches this ceiling if both measured limits above already allowed it.
     */
    private const val MAX_TARGET_BYTES = 448L * 1024 * 1024

    /**
     * Fraction of the byte budget the back buffer may hold.
     *
     * A fixed number of *seconds* cannot stay in proportion to a byte budget:
     * at 20Mbps, 30s is ~75MB, which on a small box exceeds the entire budget
     * on its own and starves the forward buffer — the one that decides whether
     * playback stutters. Pinning the back buffer to a share of the same pot
     * means it can never do that, at any bitrate.
     */
    const val BACK_BUFFER_SHARE = 0.20

    /** The ceiling on rewind-for-free, once the share above allows that much. */
    const val MAX_BACK_BUFFER_MS = 10_000L

    /** Enough to keep the picture moving while the pressure passes. */
    const val MIN_BACK_BUFFER_MS = 2_000L

    /**
     * Used only until real throughput is observed — see [AdaptiveLoadControl].
     * Deliberately a high estimate (~25Mbps), because guessing low here is what
     * produces an oversized back buffer on exactly the high-bitrate release
     * that cannot afford one.
     */
    const val ASSUMED_BYTES_PER_SECOND = 3_100_000L

    /**
     * Free heap right now.
     *
     * `totalMemory() - freeMemory()` counts garbage that has not been collected
     * yet as used, so this under-reports the real headroom. That is the safe
     * direction to be wrong in, and it is emphatically not worth "fixing" with
     * a `System.gc()` before measuring — that trades a small over-estimate for
     * a visible pause.
     */
    fun headroomBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    }

    /**
     * RAM the device can spare beyond the level at which the platform starts
     * reclaiming, or null when it cannot be read.
     */
    fun spareRamBytes(context: Context): Long? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            manager.getMemoryInfo(info)
            // `threshold` is where the low-memory killer starts working. What
            // is above it is the only part that is really free to use.
            (info.availMem - info.threshold).coerceAtLeast(0L)
        }.getOrNull()
    }

    fun targetBufferBytes(context: Context): Int {
        val heapAllowance = (headroomBytes() * HEAP_SHARE).toLong()
        val ramAllowance = spareRamBytes(context)
            ?.let { (it * RAM_SHARE).toLong() }
            ?: Long.MAX_VALUE

        // The device has to satisfy both limits, so the budget is whichever is
        // tighter — a 1GB-free box is bounded by its heap, a 200MB-free one by
        // its RAM, and each gets the buffer it can actually back.
        val allowance = minOf(heapAllowance, ramAllowance)

        // The preferred floor is itself capped by both limits: pushing a
        // buffer up to 96MB on a device that just told us it cannot spare that
        // would defeat the point of measuring at all.
        val floor = minOf(
            PREFERRED_MIN_BYTES,
            Runtime.getRuntime().maxMemory() / 3,
            allowance.coerceAtLeast(ABSOLUTE_MIN_BYTES),
        ).coerceAtLeast(ABSOLUTE_MIN_BYTES)

        return allowance.coerceIn(floor, MAX_TARGET_BYTES).toInt()
    }

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
