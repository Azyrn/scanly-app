package com.skeler.scanely.auth

import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialUnknownException
import com.google.firebase.auth.FirebaseAuth
import com.skeler.scanely.auth.data.repository.FirebaseAuthRepository
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.core.network.ScanlyApi
import com.skeler.scanely.core.network.ScanlyPublicApi
import com.skeler.scanely.testing.EnglishStrings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify

/**
 * clearCredentialState fails with ClearCredentialException (a provider error, no provider on the
 * phone), a sibling of GetCredentialException rather than a subclass. Escaping signOut it crashed
 * Settings > Sign out from viewModelScope, and made a completed account deletion report failure.
 */
class SignOutCredentialClearTest {

    private fun repository(firebaseAuth: FirebaseAuth): FirebaseAuthRepository {
        val credentials = mock(CredentialManager::class.java) { invocation ->
            if (invocation.method.name == "clearCredentialState") throw ClearCredentialUnknownException()
            null
        }
        return FirebaseAuthRepository(
            EnglishStrings.context(),
            firebaseAuth,
            credentials,
            mock(ScanlyPublicApi::class.java),
            mock(ScanlyApi::class.java),
            mock(NetworkObserver::class.java)
        )
    }

    @Test fun `a failed credential clear does not escape sign-out`() = runBlocking {
        mockStatic(Log::class.java).use {
            val firebaseAuth = mock(FirebaseAuth::class.java)
            repository(firebaseAuth).signOut(EnglishStrings.context())
            verify(firebaseAuth).signOut()
        }
    }

    @Test fun `account deletion succeeds when only the credential clear fails`() = runBlocking {
        mockStatic(Log::class.java).use {
            val result = repository(mock(FirebaseAuth::class.java)).deleteAccount(EnglishStrings.context())
            assertTrue("deletion reported as failed: ${result.exceptionOrNull()}", result.isSuccess)
        }
    }
}
