package com.skeler.scanely.auth

import android.util.Log
import androidx.credentials.CredentialManager
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.skeler.scanely.auth.data.repository.FirebaseAuthRepository
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.core.network.ScanlyApi
import com.skeler.scanely.core.network.ScanlyPublicApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`

/**
 * A Firebase call Play Services never completes. Without a deadline, the auth screen's button stays
 * disabled behind a spinner with no way out. The task is real and simply never finishes; Firebase
 * itself is stubbed only to hand it over.
 */
class AuthWaitDeadlineTest {

    @Test(timeout = 90_000)
    fun `a Firebase call that never completes fails with a message instead of spinning`() = runBlocking {
        mockStatic(Log::class.java).use {
            val user = mock(FirebaseUser::class.java)
            `when`(user.uid).thenReturn("reader")
            `when`(user.sendEmailVerification()).thenReturn(TaskCompletionSource<Void>().task)
            val auth = mock(FirebaseAuth::class.java)
            `when`(auth.currentUser).thenReturn(user)
            val repository = FirebaseAuthRepository(
                auth,
                mock(CredentialManager::class.java),
                mock(ScanlyPublicApi::class.java),
                mock(ScanlyApi::class.java),
                mock(NetworkObserver::class.java)
            )

            val result = repository.sendEmailVerification()

            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()!!.message.orEmpty()
            assertTrue(message, message.contains("taking too long"))
        }
    }
}
