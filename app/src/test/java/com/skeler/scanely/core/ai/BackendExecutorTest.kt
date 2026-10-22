package com.skeler.scanely.core.ai

import android.os.SystemClock
import android.util.Log
import com.skeler.scanely.core.network.NetworkObserver
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class BackendExecutorTest {
    @Test fun `an explicitly single attempt call is never replayed after IO failure`() = runBlocking {
        mockStatic(SystemClock::class.java).use {
            mockStatic(Log::class.java).use {
                val network = mock(NetworkObserver::class.java)
                `when`(network.isCurrentlyOnline()).thenReturn(true)
                var calls = 0
                val outcome = BackendExecutor(network).run(emit = {}, retrySafe = false) {
                    calls++
                    throw IOException("response lost after billing")
                }
                assertTrue(outcome is AiOutcome.Exhausted)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun `idempotent requests still retry transient transport errors`() = runBlocking {
        mockStatic(SystemClock::class.java).use {
            mockStatic(Log::class.java).use {
                val network = mock(NetworkObserver::class.java)
                `when`(network.isCurrentlyOnline()).thenReturn(true)
                var calls = 0
                val outcome = BackendExecutor(network).run(emit = {}) {
                    if (++calls == 1) throw IOException("offline briefly")
                    "result"
                }
                assertEquals(AiOutcome.Success("result"), outcome)
                assertEquals(2, calls)
            }
        }
    }
}
