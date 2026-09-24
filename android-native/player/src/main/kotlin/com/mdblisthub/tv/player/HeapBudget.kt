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
     * The reading that describes the *box* rather than the moment.
     *
     * `totalMem` cannot change while the process lives, but reading it costs a
     * binder round trip to `ActivityManager`. `availMem` is deliberately *not*
     * cached below: that one moves minute to minute and sizing a buffer from a
     * stale copy of it is the mistake this whole file exists to avoid.
     */
    @Volatile
    private var cachedTotalRamBytes: Long? = null

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
     * Where the tiers divide, in `ActivityManager.MemoryInfo.totalMem`.
     *
     * `totalMem` is physical RAM as the kernel sees it. Devices below this
     * boundary use the 128MB floor; those at or above it use 256MB.
     */
    private const val MODEST_RAM_BYTES = 2_400L * 1024 * 1024

    private const val BUFFER_FOR_PLAYBACK_MS = 5_000
    private const val BUFFER_AFTER_REBUFFER_MS = 10_000

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
     * Fraction of the budget's *surplus* the back buffer may hold.
     *
     * A share of the whole pot cannot stay in proportion, because the pot is
     * not all discretionary: playback does not resume after a rebuffer until
     * [bufferForPlaybackAfterRebufferMs] is buffered, so that much of every
     * budget — and a working cushion above it, see [RESERVED_FORWARD_MS] — is
     * spoken for before anything is shared out. Taking twenty percent off the
     * top instead is how a device whose entire budget was twenty-five seconds
     * of a high-bitrate remux ended up handing five of them to film already
     * watched, leaving barely more than the resume threshold in front of the
     * playhead. That device stutters where a 4GB one does not, and this is one
     * of the reasons.
     *
     * Against the surplus the same twenty percent is proportionate at every
     * size: a budget that cannot cover the reserve gets no back buffer at all,
     * and one with minutes to spare still reaches [MAX_BACK_BUFFER_MS].
     */
    const val BACK_BUFFER_SHARE = 0.20

    /** The ceiling on rewind-for-free, once the share above allows that much. */
    const val MAX_BACK_BUFFER_MS = 10_000L

    /**
     * Forward buffer reserved before any of the budget is shared out.
     *
     * Twice [bufferForPlaybackAfterRebufferMs], because a cushion equal to the
     * resume threshold is not a cushion — it is a guarantee that the next
     * hiccup stops the picture again, which is the freeze/resume cycle this
     * whole file exists to avoid.
     *
     * Nothing is lost on the devices this excludes. Rewinding reads from
     * [MediaCache], which is on disk, costs no heap and holds minutes rather
     * than seconds; the RAM back buffer only ever saved the difference between
     * a disk read and a memory one, and a disk read is not what makes a
     * picture stop.
     */
    private const val RESERVED_FORWARD_MS = 2 * BUFFER_AFTER_REBUFFER_MS

    /**
     * Used only until real throughput is observed — see [AdaptiveLoadControl],
     * which keeps the last measurement rather than returning here once it has
     * one. Deliberately a high estimate (~25Mbps), because guessing low here is
     * what produces an oversized back buffer on exactly the high-bitrate
     * release that cannot afford one.
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
        cachedTotalRamBytes?.let { return it.takeIf { bytes -> bytes > 0 } }
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        val total = runCatching {
            manager.getMemoryInfo(info)
            info.totalMem
        }.getOrNull()?.takeIf { it > 0 }
        // Zero stands for "asked and got nothing", so an unreadable reading is
        // not re-asked on every playback either.
        cachedTotalRamBytes = total ?: 0L
        return total
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
     * Avoids declaring the first frame ready on a nearly empty buffer on a
     * device. This must not shrink just because the device has more RAM: RAM
     * determines the maximum byte budget, whereas this is the minimum time
     * cushion needed to survive a brief source slowdown.
     */
    fun bufferForPlaybackMs(): Int = BUFFER_FOR_PLAYBACK_MS

    /**
     * Rebuild a meaningful cushion before resuming after a real rebuffer.
     * This deliberately favours one slightly longer wait over repeated
     * freeze/resume cycles while the source is unstable.
     *
     * The *ceiling*, not the answer. `DefaultLoadControl` takes it as a flat
     * constant for every device, and [AdaptiveLoadControl] lowers it through
     * [rebufferStartMs] wherever the byte budget cannot hold it — see there
     * for why a device that cannot afford this number must not be asked for
     * it anyway.
     */
    fun bufferForPlaybackAfterRebufferMs(): Int = BUFFER_AFTER_REBUFFER_MS

    /**
     * Share of the byte budget a device may be asked to refill before the
     * picture comes back.
     *
     * A third, so that resuming leaves two thirds of the budget still to fill
     * — a cushion, rather than a resume straight back onto the edge of the
     * next stall.
     */
    private const val REBUFFER_START_SHARE = 0.33

    /**
     * The floor on that, and the number to raise first if stutter returns.
     *
     * Media3 defaults to 2s. Below about this a resume really does land back
     * in the stall it just left — but the reason it can be this low at all is
     * [MediaPrefetcher]: the RAM buffer refills from a cache file at eMMC
     * speed rather than from the radio, so "almost nothing in hand" stopped
     * being true when the deep cushion moved to disk.
     */
    const val MIN_REBUFFER_START_MS = 2_500L

    /**
     * How much buffer this device can actually be asked to rebuild before
     * playback resumes, given what its budget holds at this bitrate.
     *
     * [bufferForPlaybackAfterRebufferMs] is one number for every device, and
     * on a box whose entire budget is twenty seconds of a high-bitrate remux,
     * ten of them is half the pot — spent stopped, while the link that just
     * faltered is asked to deliver at a rate it has already demonstrated it
     * cannot. Until it finishes the picture stays frozen, and the thing that
     * ends that freeze is the viewer pressing skip: a seek recomputes the
     * requirement somewhere the link can satisfy, or serves it from the disk
     * cache outright. That is a resume threshold the device cannot pay,
     * presenting as a player that has stopped responding.
     *
     * A device with room for the full ten seconds is unaffected — the share
     * below lands well past the ceiling and is clamped straight back to it.
     * Only a budget too small to hold it is lowered, which is exactly the
     * population that was freezing.
     */
    fun rebufferStartMs(targetBytes: Int, bytesPerSecond: Long): Long {
        val usable = bytesPerSecond.coerceAtLeast(1L)
        val capacityMs = targetBytes * 1_000L / usable
        val affordableMs = (capacityMs * REBUFFER_START_SHARE).toLong()
        return affordableMs.coerceIn(MIN_REBUFFER_START_MS, BUFFER_AFTER_REBUFFER_MS.toLong())
    }

    /**
     * How much back buffer [targetBytes] affords at the given throughput,
     * taken from the surplus alone so it can never be the reason the forward
     * buffer runs dry.
     */
    fun backBufferMs(targetBytes: Int, bytesPerSecond: Long): Long {
        val usable = bytesPerSecond.coerceAtLeast(1L)
        // What the whole budget is worth in film at this bitrate, less the
        // part that is not discretionary — see [RESERVED_FORWARD_MS].
        val budgetMs = targetBytes * 1_000L / usable
        val surplusMs = budgetMs - RESERVED_FORWARD_MS
        if (surplusMs <= 0) return 0L
        val affordableMs = (surplusMs * BACK_BUFFER_SHARE).toLong()
        return affordableMs.coerceAtMost(MAX_BACK_BUFFER_MS)
    }
}
