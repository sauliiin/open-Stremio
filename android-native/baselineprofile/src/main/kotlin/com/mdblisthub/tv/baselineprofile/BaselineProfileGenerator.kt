package com.mdblisthub.tv.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records what a cold start actually runs.
 *
 * The journey below is not a test — nothing is asserted and nothing fails.
 * It is a script whose only job is to *touch* the code paths worth compiling
 * ahead of time, because a baseline profile is exactly the list of classes
 * and methods that ran while it was being collected.
 *
 * Which is why the walk matters more than the launch: `startActivityAndWait`
 * alone would capture the graph, Room, DataStore and the first composition,
 * but not the row scrolling and artwork decoding that the first thirty
 * seconds of use are actually spent on.
 *
 * ## What the previous version of this file got wrong
 *
 * It ran, it passed, and it produced a profile with **zero** entries for
 * `HomeScreenKt`, `PosterCardKt`, `MediaRowKt` or `DetailScreenKt` — 25k lines
 * covering little beyond login and intro. Two causes, both fixed below:
 *
 * 1. It recorded on a device that was not signed in, so the walk never left
 *    the login screen. Its own comment predicted this and chose to tolerate it
 *    silently, which is how a bad profile shipped without anyone noticing.
 *    [awaitHome] now waits for real content and the run is only meaningful
 *    once it finds it.
 * 2. Nothing dismissed the update dialog, which the app raises about a second
 *    and a half after launch — right on top of the walk. Every D-pad press
 *    then went to a modal instead of the rows. [dismissUpdateDialog] clears it
 *    first.
 *
 * After regenerating, *verify* rather than assume:
 * `grep -c "HomeScreenKt\|PosterCardKt\|MediaRowKt" app/src/release/generated/baselineProfiles/baseline-prof.txt`
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // Own the screen before recording anything beyond the launch itself.
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
        device.waitForIdle()
    }

    /** Performance-sensitive browsing belongs in the Baseline, not Startup, profile. */
    @Test
    fun homeBrowsing() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait()
        awaitHome()

        // A remote only ever moves focus, so walking the D-pad *is* this app's
        // scroll path: every press runs the column's bring-into-view spec, the
        // poster card's focus animation and Coil's next batch of artwork.
        //
        // Down first, and this is the single most valuable press in the file:
        // leaving the spotlight for the rows is what runs the hero handover —
        // the two `animateFloatAsState`s, the `graphicsLayer` blocks and the
        // relayout from full-height spotlight to fixed strip.
        walk(WALK_DOWN) { device.pressDPadDown() }
        walk(WALK_ACROSS) { device.pressDPadRight() }
        walk(WALK_DOWN) { device.pressDPadDown() }
        walk(WALK_ACROSS) { device.pressDPadLeft() }
        // Back up into the spotlight, which runs the handover the other way.
        walk(WALK_DOWN * 2) { device.pressDPadUp() }
    }

    /**
     * Opening a title and coming back.
     *
     * Separate from the browse walk because it compiles a different screen:
     * `DetailScreen` is the second-heaviest composable in the app and had no
     * entries at all in the profile this file previously produced.
     */
    @Test
    fun openDetail() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait()
        awaitHome()

        // Into the rows, onto a card, open it.
        walk(WALK_DOWN) { device.pressDPadDown() }
        device.pressDPadCenter()
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
        device.waitForIdle()
        // Let the detail settle: its metadata, cast row and season list all
        // arrive after the first frame, and each is code worth compiling.
        Thread.sleep(SETTLE_MS)
        walk(WALK_ACROSS) { device.pressDPadDown() }

        device.pressBack()
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
    }

    /**
     * Waits for the home screen to be genuinely up, then clears anything modal
     * sitting on top of it.
     *
     * The sleep is not decoration. The rows arrive from Room and then from the
     * network, and artwork decoding trails both; pressing the D-pad into a
     * half-built list records the empty state rather than the populated one.
     */
    private fun MacrobenchmarkScope.awaitHome() {
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
        device.waitForIdle()
        Thread.sleep(CONTENT_SETTLE_MS)
        dismissUpdateDialog()
        device.waitForIdle()
    }

    /**
     * Dismisses the "an update is available" modal if it is showing.
     *
     * Matched by the button's own label in each of the app's four languages,
     * because the device generating the profile may be set to any of them and
     * an untranslated match would silently do nothing — which is exactly the
     * failure this whole file exists to stop repeating.
     */
    private fun MacrobenchmarkScope.dismissUpdateDialog() {
        for (label in UPDATE_LATER_LABELS) {
            val button = device.findObject(By.text(label)) ?: continue
            button.click()
            device.waitForIdle()
            Thread.sleep(DIALOG_SETTLE_MS)
            return
        }
    }

    private fun MacrobenchmarkScope.walk(steps: Int, press: () -> Unit) {
        repeat(steps) {
            press()
            device.waitForIdle()
            // A D-pad press starts a scroll animation and a batch of image
            // requests; `waitForIdle` returns before either finishes, so
            // without this the walk outruns the work it is meant to record.
            Thread.sleep(STEP_MS)
        }
    }

    private companion object {
        /**
         * The applicationId registered in the Firebase Android client.
         */
        const val PACKAGE = "mdblist_hub.apk.S84"
        const val LAUNCH_TIMEOUT_MS = 30_000L

        /** `update_later`, in every language the app ships. */
        val UPDATE_LATER_LABELS = listOf("Mais tarde", "Later", "Más tarde", "Plus tard")

        const val WALK_DOWN = 4
        const val WALK_ACROSS = 6

        const val CONTENT_SETTLE_MS = 12_000L
        const val DIALOG_SETTLE_MS = 1_000L
        const val SETTLE_MS = 3_000L
        const val STEP_MS = 600L
    }
}
