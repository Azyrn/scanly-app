package com.skeler.scanely.core.transcribe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * startForegroundService obliges the service to call startForeground within seconds. Stopping it
 * before its onStartCommand ran (recording stopped right after starting) destroyed it without ever
 * entering the foreground, and Android killed the app with ForegroundServiceDidNotStartInTime.
 */
class ForegroundStartGateTest {

    @Test
    fun `stop with no start pending stops at once`() {
        val gate = ForegroundStartGate()
        gate.onStartRequested()
        assertFalse(gate.onForeground())

        assertTrue(gate.onStopRequested())
    }

    @Test
    fun `stop before the service entered the foreground waits for it`() {
        val gate = ForegroundStartGate()
        gate.onStartRequested()

        assertFalse("must not stop before startForeground", gate.onStopRequested())
        assertTrue("service stops itself once in the foreground", gate.onForeground())
    }

    @Test
    fun `a new start cancels a deferred stop`() {
        val gate = ForegroundStartGate()
        gate.onStartRequested()
        assertFalse(gate.onStopRequested())
        gate.onStartRequested()

        assertFalse(gate.onForeground())
        assertFalse(gate.onForeground())
    }

    @Test
    fun `deferred stop waits for every pending start`() {
        val gate = ForegroundStartGate()
        gate.onStartRequested()
        gate.onStartRequested()
        assertFalse(gate.onStopRequested())

        assertFalse("a second start is still due", gate.onForeground())
        assertTrue(gate.onForeground())
        assertTrue(gate.onStopRequested())
    }
}
