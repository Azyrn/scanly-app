package com.skeler.scanely.core.di

import com.skeler.scanely.core.network.BackendAuthInterceptor
import com.skeler.scanely.docchat.data.ChatModule
import dagger.Lazy
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * The Retrofit services are injected on the main thread before the first frame (App.onCreate's
 * FailureReporter, MainActivity's licence and entitlement repositories, Home's view models).
 * Making one must not build an OkHttpClient: that waits for the first request.
 */
class LazyHttpClientTest {

    private val untouched = Lazy<OkHttpClient> { throw AssertionError("OkHttpClient built when the API was made") }
    private val auth = mock(BackendAuthInterceptor::class.java)

    @Test
    fun `making the backend APIs builds no client`() {
        GenerativeAiModule.provideScanlyApi(untouched, auth)
        GenerativeAiModule.provideBackendControlApi(untouched, auth)
        GenerativeAiModule.provideScanlyPublicApi(untouched)
    }

    @Test
    fun `making the custom-provider and chat APIs builds no client`() {
        CustomProviderModule.provideCustomProviderApi(untouched)
        CustomProviderModule.provideProbeApi(untouched)
        ChatModule.provideChatSyncApi(untouched, auth)
        ChatModule.provideAgentEventsApi(untouched, auth)
    }

    @Test
    fun `the client is built once, on the first request`() {
        var built = 0
        val factory = lazyCallFactory {
            built++
            OkHttpClient()
        }
        assertEquals(0, built)

        repeat(2) { factory.newCall(Request.Builder().url("https://example.invalid/").build()) }

        assertEquals(1, built)
    }
}
