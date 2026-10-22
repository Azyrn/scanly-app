package com.skeler.scanely.core.license

import com.skeler.scanely.auth.domain.model.AuthUser
import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.network.LocalWorker
import com.skeler.scanely.core.network.proceedWithToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Redeeming a key with a session the server rejects must say so. "Couldn't reach Scanly" sends a
 * user whose connection is fine to check it, and never to sign in again.
 *
 * The rejection is real: the local Worker answers 401 and the real interceptor chain's forced
 * refresh yields no token. The key store and device id are stubbed — DataStore, not under test.
 */
class LicenseSessionMessageTest {

    private val worker = LocalWorker()

    @After fun tearDown() = worker.close()

    @Test
    fun `activating with a rejected session names the session, not the connection`() = runBlocking {
        worker.route("/v1/me") { LocalWorker.me() }
        worker.route("/v1/license/activate") {
            401 to """{"error":{"code":"unauthenticated","message":"Sign in."}}"""
        }

        val user = mock(AuthUser::class.java)
        `when`(user.uid).thenReturn("reader")
        val auth = mock(AuthRepository::class.java)
        `when`(auth.currentUser).thenReturn(MutableStateFlow(user))
        `when`(auth.isSignedIn).thenReturn(true)
        val store = mock(LicenseStore::class.java)
        `when`(store.key).thenReturn(flowOf(null))
        val deviceId = mock(DeviceId::class.java)
        `when`(deviceId.get()).thenReturn("device-1")
        `when`(deviceId.displayName).thenReturn("Test phone")

        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceedWithToken { refresh -> if (refresh) "" else "stale" } }
            .build()
        val api = worker.api(client)
        val repository = LicenseRepository(api, store, deviceId, auth, EntitlementRepository(worker.api(), auth))

        assertFalse(repository.activate("SCAN-LY00-KEY0-0001"))
        assertEquals("Your session expired. Sign in again.", repository.state.value.error)
    }
}
