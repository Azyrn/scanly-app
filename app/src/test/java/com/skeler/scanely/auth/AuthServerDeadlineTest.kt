package com.skeler.scanely.auth

import androidx.credentials.CredentialManager
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.skeler.scanely.auth.data.repository.FirebaseAuthRepository
import com.skeler.scanely.core.network.LocalWorker
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.core.network.ScanlyPublicApi
import com.skeler.scanely.testing.EnglishStrings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.TimeUnit

/**
 * N10: sign-up's confirmation mail and account deletion are Worker calls made on the scan client,
 * whose read timeout is five minutes (a big PDF scan needs it). A Worker that does not answer kept
 * the account screen spinning that long after the account already existed. They now get the same
 * thirty-second deadline as every Firebase call on that screen; a deletion that runs out of it says
 * the server could not be reached.
 */
class AuthServerDeadlineTest {
    private val worker = LocalWorker()

    @After fun tearDown() = worker.close()

    /** The production scan client's read timeout. */
    private val api = worker.api(OkHttpClient.Builder().readTimeout(SCAN_READ_SECONDS, TimeUnit.SECONDS).build())

    private fun repository(): FirebaseAuthRepository {
        val user = mock(FirebaseUser::class.java)
        `when`(user.uid).thenReturn("reader")
        val result = mock(AuthResult::class.java)
        `when`(result.user).thenReturn(user)
        val auth = mock(FirebaseAuth::class.java)
        `when`(auth.createUserWithEmailAndPassword("a@example.com", "pw123456"))
            .thenReturn(TaskCompletionSource<AuthResult>().apply { setResult(result) }.task)
        return FirebaseAuthRepository(
            EnglishStrings.context(), auth, mock(CredentialManager::class.java), mock(ScanlyPublicApi::class.java),
            api, mock(NetworkObserver::class.java) { true }
        )
    }

    @Test(timeout = TEST_LIMIT_MS)
    fun `sign-up does not wait minutes on a confirmation mail the Worker never answers`() = runBlocking {
        worker.route("/v1/me/verify-email", delayMs = STALL_MS) { 200 to """{"status":"sent"}""" }
        val result = repository().signUpWithEmail("a@example.com", "pw123456")
        assertTrue("the account exists either way: $result", result.isSuccess)
    }

    @Test(timeout = TEST_LIMIT_MS)
    fun `account deletion the Worker never answers fails with a message instead of spinning`() = runBlocking {
        worker.route("/v1/me", delayMs = STALL_MS) { 200 to "{}" }
        val result = repository().deleteAccount(EnglishStrings.context())
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue(message, message.contains("Couldn't reach the Scanly server"))
    }

    private companion object {
        const val SCAN_READ_SECONDS = 300L
        const val STALL_MS = 200_000L
        const val TEST_LIMIT_MS = 60_000L
    }
}
