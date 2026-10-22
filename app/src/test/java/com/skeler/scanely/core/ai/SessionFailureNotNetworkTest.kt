package com.skeler.scanely.core.ai

import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.skeler.scanely.core.network.LocalWorker
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.core.network.TokenUnavailableException
import com.skeler.scanely.core.network.proceedWithToken
import com.skeler.scanely.core.network.tokenFailure
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`

/**
 * A session the server rejects is not a bad connection. Told "your connection looks unstable",
 * the user checks their Wi-Fi, which is fine, and is never sent to sign in again.
 */
class SessionFailureNotNetworkTest {

    private val worker = LocalWorker()

    @After fun tearDown() = worker.close()

    /** The real interceptor chain: a stale token, a 401, and a forced refresh that yields nothing. */
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain -> chain.proceedWithToken { refresh -> if (refresh) "" else "stale" } }
        .build()

    @Test
    fun `a rejected session ends the run with its own message and no retries`() = runBlocking {
        worker.route("/v1/ocr") { 401 to """{"error":{"code":"unauthenticated","message":"Sign in."}}""" }

        val outcome = mockStatic(SystemClock::class.java).use {
            mockStatic(Log::class.java).use {
                val network = mock(NetworkObserver::class.java)
                `when`(network.isCurrentlyOnline()).thenReturn(true)
                BackendExecutor(network).run(emit = {}) {
                    client.newCall(Request.Builder().url(worker.baseUrl + "v1/ocr").build()).execute()
                }
            }
        }

        assertTrue("not reported as a network problem: $outcome", outcome is AiOutcome.Fatal)
        val error = (outcome as AiOutcome.Fatal).error
        assertEquals("Your session expired. Sign in again.", error.message)
        assertEquals("retrying cannot fix a session", 1, worker.count("/v1/ocr"))
    }

    /** Firebase's exception constructors validate their arguments with the framework's TextUtils. */
    private fun <T> withTextUtils(block: () -> T): T = mockStatic(TextUtils::class.java).use { utils ->
        utils.`when`<Boolean> { TextUtils.isEmpty(any()) }
            .thenAnswer { (it.arguments[0] as CharSequence?).isNullOrEmpty() }
        block()
    }

    @Test
    fun `a token refresh that failed for lack of network is still a network failure`() {
        val failure = withTextUtils {
            tokenFailure("Could not verify your session.", FirebaseNetworkException("offline"))
        }

        assertFalse(failure is TokenUnavailableException)
    }

    @Test
    fun `a token refresh the account refused is a session failure`() {
        val refused = withTextUtils {
            FirebaseAuthInvalidUserException("ERROR_USER_DISABLED", "The account is disabled.")
        }

        assertTrue(tokenFailure("Could not verify your session.", refused) is TokenUnavailableException)
    }
}
