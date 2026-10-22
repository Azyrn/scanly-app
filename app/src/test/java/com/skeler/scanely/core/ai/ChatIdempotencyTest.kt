package com.skeler.scanely.core.ai

import android.os.SystemClock
import android.util.Log
import com.skeler.scanely.core.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import retrofit2.http.Header

class ChatIdempotencyTest {
    @Test fun chatRouteCarriesAnOperationKey() {
        val method = ScanlyApi::class.java.declaredMethods.single { it.name == "translate" }
        assertTrue(method.parameterAnnotations.flatten().filterIsInstance<Header>()
            .any { it.value == "X-Idempotency-Key" })
    }

    @Test fun transportRetriesReuseTheSameKeyForTheChatRoute() = runBlocking {
        mockStatic(SystemClock::class.java).use {
            mockStatic(Log::class.java).use {
                val network = mock(NetworkObserver::class.java)
                `when`(network.isCurrentlyOnline()).thenReturn(true)
                val api = mock(ScanlyApi::class.java)
                val client = BackendClient(api)
                val stream = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n"
                val translate = TranslateRequest("Bonjour", "English")
                `when`(api.translate(translate, "translate-key")).thenReturn(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".toResponseBody(),
                    stream.toResponseBody())
                assertEquals(AiOutcome.Success("Hello"), BackendExecutor(network).run(emit = {}) {
                    client.translate("Bonjour", "English", "translate-key", emit = {})
                })
                verify(api, times(2)).translate(translate, "translate-key")
                Unit
            }
        }
    }
}
