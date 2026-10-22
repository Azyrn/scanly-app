package com.skeler.scanely.auth

import android.util.Log
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseAuth
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.skeler.scanely.auth.data.repository.FirebaseAuthRepository
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.core.network.ScanlyApi
import com.skeler.scanely.core.network.ScanlyPublicApi
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import retrofit2.Retrofit

/**
 * The screenshot: a phone on Wi-Fi, "Forgot password?", and "No internet connection." — while the
 * real failure was the release build calling http://127.0.0.1:8787/, which on a phone is the phone
 * itself, and being refused.
 *
 * The transport is real: OkHttp through Retrofit to a port nothing listens on, so the exception is
 * the ConnectException the phone got, not a stand-in. Only connectivity is stubbed — whether the
 * device has a validated network is the input here, not the thing under test.
 */
class PasswordResetErrorMessageTest {

    private fun unreachableBackend(): ScanlyPublicApi {
        // Bind, note the port, release it: connecting now is refused, as on the phone.
        val port = ServerSocket(0).use { it.localPort }
        return Retrofit.Builder()
            .baseUrl("http://127.0.0.1:$port/")
            .client(OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build())
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ScanlyPublicApi::class.java)
    }

    private fun resetMessage(online: Boolean): String = runBlocking {
        mockStatic(Log::class.java).use {
            val network = mock(NetworkObserver::class.java)
            `when`(network.isCurrentlyOnline()).thenReturn(online)
            val repository = FirebaseAuthRepository(
                mock(FirebaseAuth::class.java),
                mock(CredentialManager::class.java),
                unreachableBackend(),
                mock(ScanlyApi::class.java),
                network
            )
            val result = repository.sendPasswordReset("reader@example.com")
            assertTrue("a refused connection must not succeed", result.isFailure)
            result.exceptionOrNull()!!.message.orEmpty()
        }
    }

    @Test fun `server refused while the phone is online is not reported as no internet`() {
        val message = resetMessage(online = true)
        assertFalse(
            "told the user they are offline: \"$message\"",
            message.contains("internet", ignoreCase = true)
        )
        assertTrue(
            "does not say the server is the problem: \"$message\"",
            message.contains("server", ignoreCase = true)
        )
    }

    @Test fun `the same failure with no network is reported as no internet`() {
        assertEquals("No internet connection.", resetMessage(online = false))
    }
}
