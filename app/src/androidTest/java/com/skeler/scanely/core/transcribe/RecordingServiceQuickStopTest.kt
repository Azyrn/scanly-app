package com.skeler.scanely.core.transcribe

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C8: a recording stopped right after it started used to stop the service before it reached
 * startForeground, and Android killed the app (ForegroundServiceDidNotStartInTimeException) a few
 * seconds later. If that still happens this process dies during the wait and the test fails.
 * Needs RECORD_AUDIO granted (`pm grant`), as a recording would.
 */
@RunWith(AndroidJUnit4::class)
class RecordingServiceQuickStopTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun startThenImmediateStopDoesNotCrashAndLeavesNoService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(STARTS) {
            instrumentation.runOnMainSync {
                RecordingService.start(context)
                RecordingService.stop(context)
            }
        }
        // Past Android's deadline for entering the foreground (about 10 s).
        Thread.sleep(WAIT_MS)
        assertFalse("RecordingService still running after its stop", serviceRunning())
    }

    @Suppress("DEPRECATION") // Still reports this app's own services.
    private fun serviceRunning(): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == RecordingService::class.java.name }

    private companion object {
        const val STARTS = 10
        const val WAIT_MS = 15_000L
    }
}
