package com.skeler.scanely.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start, then Home → Build Documents → chat → drawer → typing, measured with no compilation
 * (a fresh sideloaded install) and with the Baseline Profile required (a Play install).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BuildDocumentsBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun withoutProfile() = measure(CompilationMode.None())

    @Test
    fun withBaselineProfile() = measure(CompilationMode.Partial(BaselineProfileMode.Require))

    @OptIn(ExperimentalMetricApi::class)
    private fun measure(compilation: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            if (!signedIn) {
                pressHome()
                startActivityAndWait()
                signInIfNeeded()
                signedIn = true
            }
            pressHome()
        }
    ) {
        startActivityAndWait()
        buildDocumentsJourney()
    }

    private companion object {
        const val ITERATIONS = 10
        var signedIn = false
    }
}
