package com.skeler.scanely.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame times of [navigationJourney], measured with no compilation (a fresh sideloaded install)
 * and with the Baseline Profile required (a Play install). Needs no account: Gradle installs the app
 * fresh, so it runs signed out.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NavigationBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun withoutProfile() = measure(CompilationMode.None())

    @Test
    fun withBaselineProfile() = measure(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun measure(compilation: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        startupMode = null,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForHome()
        }
    ) {
        navigationJourney()
    }

    private companion object {
        const val ITERATIONS = 8
    }
}
