package com.skeler.scanely.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's Baseline Profile: `scripts/gw :app:generateReleaseBaselineProfile` with the
 * phone connected (rooted or API 33+), the dev Worker reachable, and the test account pushed.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Cold start to Home: the Startup Profile, which also orders the dex for startup. */
    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        waitForHome()
    }

    /** Opening Build Documents from Home and using the chat: hot code, not startup. */
    @Test
    fun buildDocuments() = rule.collect(packageName = PACKAGE, stableIterations = 3, maxIterations = 8) {
        pressHome()
        startActivityAndWait()
        signInIfNeeded()
        buildDocumentsJourney()
    }
}
