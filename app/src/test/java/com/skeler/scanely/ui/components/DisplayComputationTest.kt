package com.skeler.scanely.ui.components

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Test

class DisplayComputationTest {
    @Test fun `document parsing runs off the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val parserThread = computeForDisplay { Thread.currentThread() }
        assertNotSame(caller, parserThread)
    }
}
