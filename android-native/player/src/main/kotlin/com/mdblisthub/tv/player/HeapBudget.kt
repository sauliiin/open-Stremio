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

    /**
     * The RAM buffer floor, by how much RAM the box physically has.
     *
     * This used to be one flat 128MB constant applied to every device, with a
     * comment conceding it was "past the edge the measurements were defending".
     * It was — but the fault was in *how* it applied, not in the number. It was
     * a floor that overrode `maxMemory() / 3`, so a Fire TV Stick with a 256MB
     * heap was handed half of it for `byte[]` while Compose, Coil and the
     * decoders shared the rest, and the collector ran constantly. That garbage
     * collection was the stutter the flat floor had been raised to prevent.
     *
     * So these are generous, and safely so, because two things changed
     * underneath them. [MediaPrefetcher] moved the deep cushion onto disk, so
     * the RAM buffer is no longer the only thing between a network hiccup and a
     * stopped picture. And in [targetBufferBytes] the floor now yields to the
     * heap-third ceiling instead of overriding it — which means raising these
     * constants can no longer push any device past what its heap can back. On a
     * box with the heap to afford them they take effect; on one without, the
     * ceiling quietly wins and the tier constant is simply never reached.
     *
     * That is what makes doubling them a different decision from the flat 128MB
     * that came before, rather than a return to it.
     */
    private const val ROOMY_MIN_BYTES = 256L * 1024 * 1024
    private const val MODEST_MIN_BYTES = 128L * 1024 * 1024

    /**
     * The bottom tier, and the one to lower first if anything regresses.
     *
     * This is the one tier where the floor is not academic. In the ordinary
     * case the measured `allowance` already sits above the heap-third ceiling,
     * so the ceiling decides and the floor is never consulted — true for every
     * tier, including this one. But a Fire TV Stick is also the device most
     * likely to *hit* `spareRamBytes` pressure, since 1GB of physical RAM
     * leaves little headroom above the low-memory killer's threshold to begin
     * with. When that happens is exactly when this floor stops being inert and
     * starts forcing the buffer back up — during the one condition where doing
     * that is actively wrong.
     *
     * So this stays close to the heap-third ceiling (~85MB on a 256MB heap)
     * rather than doubling with the other two tiers: 56MB is ~22% of that
     * heap, comfortably under the ~33% ratio that was thrashing, while still
     * being ~18s of an 25Mbps remux — enough to bridge eMMC read latency, which
     * is the only job left for this buffer now that [MediaPrefetcher] holds
     * the deep cushion on disk.
     */
    private const val CONSTRAINED_MIN_BYTES = 56L * 1024 * 1024

    /**
     * Where the tiers divide, in `ActivityManager.MemoryInfo.totalMem`.
     *
     * `totalMem` is physical RAM as the kernel sees it, so a box marketed as
     * "1GB" reports nearer 0.9GiB and a "1.5GB" stick nearer 1.4GiB — the
     * thresholds sit in the gaps between those real readings rather than on
     * the round numbers from a spec sheet.
     */
    private const val CONSTRAINED_RAM_BYTES = 1_250L * 1024 * 1024
    private const val MODEST_RAM_BYTES = 2_400L * 1024 * 1024

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

    /**
     * Total physical RAM, or null when it cannot be read.
     *
     * Distinct from [spareRamBytes] on purpose: that one moves minute to
     * minute and answers "how much can be claimed right now", while this one
     * is a property of the box and answers "what class of box is this". Only
     * the second is a sane thing to size a *floor* from — a floor derived from
     * a momentary reading would be a different number every time a film
     * started.
     */
    fun totalRamBytes(context: Context): Long? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            manager.getMemoryInfo(info)
            info.totalMem
        }.getOrNull()?.takeIf { it > 0 }
    }

    /**
     * True on the boxes this whole change is about — a Fire TV Stick rather
     * than a television with a real SoC in it.
     *
     * `isLowRamDevice` is checked first and trusted outright: it is the
     * manufacturer declaring the device is memory-constrained, which is better
     * evidence than any threshold guessed here.
     */
    fun isConstrainedDevice(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        if (manager?.isLowRamDevice == true) return true
        val total = totalRamBytes(context) ?: return false
        return total < CONSTRAINED_RAM_BYTES
    }

    /**
     * The floor for this box, by tier.
     *
     * An unreadable `totalMem` lands on the middle tier rather than the top
     * one: the cost of being wrong downward is a slightly shallower RAM buffer
     * behind a disk window that covers for it, and the cost of being wrong
     * upward is the garbage collection this change exists to stop.
     */
    private fun minimumBufferBytes(context: Context): Long {
        // Covers the bottom tier outright, including the `isLowRamDevice`
        // declaration that no RAM threshold would catch on its own, so what
        // remains below is only the split between the middle and top tiers.
        if (isConstrainedDevice(context)) return CONSTRAINED_MIN_BYTES
        val total = totalRamBytes(context) ?: return MODEST_MIN_BYTES
        return if (total < MODEST_RAM_BYTES) MODEST_MIN_BYTES else ROOMY_MIN_BYTES
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

        // A third of the heap ceiling is the hard line the rest of this file
        // treats as the real risk, and nothing below is allowed past it — not
        // the measured allowance, and not the tier floor either. That last part
        // is the whole difference from the version this replaced, where the
        // ceiling sat *inside* a `minOf` whose result a trailing
        // `coerceAtLeast` then overrode: `maxMemory() / 3` could never lower
        // anything, and every device got the flat floor regardless of the heap
        // it had to fit in.
        val ceiling = minOf(MAX_TARGET_BYTES, Runtime.getRuntime().maxMemory() / 3)

        // The floor yields to that ceiling rather than overriding it, which is
        // what keeps a raised tier constant from quietly becoming the same bug
        // again. A floor exists to override the *measured* allowance — a
        // pessimistic, momentary reading that dips for reasons unrelated to
        // what the box can afford. It has no business overriding a structural
        // limit: a device whose heap cannot back its tier's number does not
        // become able to by being handed it anyway, it just OOMs.
        val floor = minOf(minimumBufferBytes(context), ceiling)

        return allowance.coerceIn(floor, ceiling).toInt()
    }

    /**
     * How much buffer the player must rebuild before it resumes from a stall.
     *
     * Media3 defaults to 2s and this app raised it to 8s, on the reasoning that
     * resuming with almost nothing in hand turns one stall into a run of them.
     * That reasoning holds on a box that can refill 8s quickly. On a Fire TV
     * Stick behind its own weak radio, pulling 8 seconds of a high-bitrate
     * remux can take longer than the outage did — and until it finishes, the
     * picture stays stopped. That is the freeze that only a seek clears: seek
     * backward and the bytes come off disk instantly, seek forward and the
     * requirement is recomputed somewhere the link can actually satisfy.
     *
     * So the constrained tier resumes sooner. It can afford to: with
     * [MediaPrefetcher] holding minutes of film on disk, "almost nothing in
     * hand" is no longer true — the RAM buffer refills from the cache file at
     * eMMC speed rather than from the network, so the run-of-stalls the 8s was
     * defending against cannot start.
     */
    fun bufferForPlaybackAfterRebufferMs(context: Context): Int =
        if (isConstrainedDevice(context)) 2_500 else 8_000

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
