package com.skeler.scanely.core.ai

import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.network.NetworkObserver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendExecutorTest {
    private val executor = BackendExecutor(NetworkObserver(
        InstrumentationRegistry.getInstrumentation().targetContext))

    @Test fun exhaustedRetriesKeepServerReasonInsteadOfInventingBusy() = runBlocking {
        var attempts = 0
        val messages = mutableListOf<String>()
        val outcome = executor.run(emit = {
            if (it is AiEvent.Stage) it.message?.let(messages::add)
        }) {
            attempts++
            throw TransientAiException("The document designer is temporarily unavailable.")
        }
        assertEquals(3, attempts)
        assertTrue(outcome is AiOutcome.Exhausted)
        outcome as AiOutcome.Exhausted
        assertEquals("The document designer is temporarily unavailable.", outcome.message)
        assertFalse(outcome.networkProblem)
        assertTrue(messages.any { it.startsWith("Temporary service issue.") })
        assertFalse(messages.any { it.contains("is busy") })
    }

    @Test fun exhaustedUserQuotaIsNeverRetried() = runBlocking {
        var attempts = 0
        val error = AiResult.Error("No free designs left", code = "format_not_in_plan")
        val outcome = executor.run(emit = {}) {
            attempts++
            throw FatalAiException(error)
        }
        assertEquals(1, attempts)
        assertEquals(AiOutcome.Fatal(error), outcome)
    }
}
