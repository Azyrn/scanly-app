package com.skeler.scanely.core.billing

import com.skeler.scanely.auth.domain.model.AuthUser
import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.di.GenerativeAiModule
import com.skeler.scanely.core.di.NetworkModule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.ServerSocket
import java.net.Socket

/**
 * The paywall spinner while `/v1/me` hangs. The Worker is a socket that accepts the connection and
 * never answers — a stall, not a refusal, so only a timeout can end it.
 *
 * The client is built by the same module functions the app's DI graph calls, from the real base
 * client, pointed at the socket. The auth interceptor is a pass-through: a token is not the input.
 */
class EntitlementStallTest {

    private val stalled = ServerSocket(0)
    private val held = mutableListOf<Socket>()

    init {
        Thread {
            while (!stalled.isClosed) runCatching { held += stalled.accept() }
        }.apply { isDaemon = true }.start()
    }

    @After fun tearDown() {
        stalled.close()
        held.forEach { runCatching { it.close() } }
    }

    // Upper bound for the whole test: the old client waited 300 s for a first byte.
    @Test(timeout = 60_000)
    fun `a stalled plan check gives up and says so instead of spinning`() {
        val user = mock(AuthUser::class.java)
        `when`(user.uid).thenReturn("reader")
        val auth = mock(AuthRepository::class.java)
        `when`(auth.currentUser).thenReturn(MutableStateFlow(user))
        `when`(auth.isSignedIn).thenReturn(true)

        val api = GenerativeAiModule.backendControlApi(
            NetworkModule.provideBaseHttpClient(),
            { chain -> chain.proceed(chain.request()) },
            "http://127.0.0.1:${stalled.localPort}/"
        )

        val started = System.nanoTime()
        val repository = EntitlementRepository(api, auth) // signing in starts the first fetch
        while (repository.status.value.error == null) Thread.sleep(50)
        val seconds = (System.nanoTime() - started) / 1e9

        assertTrue("spinner ran ${seconds}s", seconds < 25)
        assertEquals(
            "Couldn't reach Scanly. Check your connection and try again.",
            repository.status.value.error
        )
    }
}
