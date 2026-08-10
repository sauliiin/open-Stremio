package com.mdblisthub.tv.baselineprofile

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

        // A remote only ever moves focus, so walking the D-pad *is* this app's
        // scroll path: every press runs the column's bring-into-view spec, the
        // poster card's focus animation and Coil's next batch of artwork.
        //
        // Deliberately unguarded and unasserted. On a device that is not
        // signed in the app stops at the login screen and these presses do
        // nothing at all — which costs the profile the row-scrolling entries
        // but still leaves every startup entry above intact, and is far better
        // than failing the generation outright over it.
        repeat(WALK_STEPS) {
            device.pressDPadDown()
            device.waitForIdle()
        }
        repeat(WALK_STEPS) {
            device.pressDPadRight()
            device.waitForIdle()
        }
    }

    private companion object {
        /**
         * The applicationId registered in the Firebase Android client.
         */
        const val PACKAGE = "mdblist_hub.apk.S84"
        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val WALK_STEPS = 5
    }
}
