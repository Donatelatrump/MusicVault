package com.musicvault.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Outputs a real ART profile for review; never overwrites the shipped profile automatically. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val baseline = BaselineProfileRule()
    @Test fun startupAndHomeScrolling() = baseline.collect(
        packageName = StartupBenchmark.TARGET_PACKAGE,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        repeat(3) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 4, 20)
            device.waitForIdle()
        }
    }
}
