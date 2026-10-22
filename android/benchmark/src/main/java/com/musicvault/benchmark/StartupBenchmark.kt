package com.musicvault.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Use an isolated ARM device with completed onboarding and a seeded offline library. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Test fun coldStartupWithoutAheadOfTimeCompilation() = startup(CompilationMode.None())
    @Test fun coldStartupWithBundledBaselineProfile() = startup(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require, warmupIterations = 0))
    private fun startup(compilation: CompilationMode) = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
        device.waitForIdle()
    }
    @Test fun homeScrollFrameTiming() = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require, warmupIterations = 0),
        iterations = 5,
        setupBlock = { pressHome(); startActivityAndWait(); device.waitForIdle() }
    ) {
        repeat(4) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 4, 20)
            device.waitForIdle()
        }
    }
    companion object { const val TARGET_PACKAGE = "com.musicvault.app.debug" }
}
