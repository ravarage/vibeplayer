package com.vibeplayer.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Cold start numbers with no AOT profile vs the generated baseline profile. */
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun grant() = grantStorage()

    private fun browser(mode: CompilationMode) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitForBrowser()
    }

    private fun openVideo(mode: CompilationMode) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        openVideoByIntent()
    }

    @Test fun browserColdNone() = browser(CompilationMode.None())

    @Test fun browserColdBaselineProfile() =
        browser(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test fun openVideoColdNone() = openVideo(CompilationMode.None())

    @Test fun openVideoColdBaselineProfile() =
        openVideo(CompilationMode.Partial(BaselineProfileMode.Require))
}
