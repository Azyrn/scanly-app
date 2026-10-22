package com.skeler.scanely.core.license

import com.skeler.scanely.auth.domain.model.AuthUser
import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.network.LocalWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * A key the server no longer recognises: the app drops it and should say why, in the server's words.
 *
 * The Worker's answers are the real shapes from worker/src/errors.ts and paddle.ts, served over a
 * socket, so the error body arrives as Retrofit really buffers it: readable once. The key store and
 * device id are stubbed — they are DataStore, not what is under test.
 */
class LicenseRevokedMessageTest {

    private val worker = LocalWorker()

    @After fun tearDown() = worker.close()

    private fun messageFor(status: Int, code: String, message: String): String = runBlocking {
        worker.route("/v1/me") { LocalWorker.me() }
        worker.route("/v1/license/status") {
            status to """{"error":{"code":"$code","message":"$message"}}"""
        }

        val user = mock(AuthUser::class.java)
        `when`(user.uid).thenReturn("reader")
        val auth = mock(AuthRepository::class.java)
        `when`(auth.currentUser).thenReturn(MutableStateFlow(user))
        `when`(auth.isSignedIn).thenReturn(true)
        val store = mock(LicenseStore::class.java)
        `when`(store.key).thenReturn(flowOf("SCAN-LY00-KEY0-0001"))
        val deviceId = mock(DeviceId::class.java)
        `when`(deviceId.get()).thenReturn("device-1")
        `when`(deviceId.displayName).thenReturn("Test phone")

        val api = worker.api()
        // Signing in triggers the status check on its own, as it does at app start.
        val repository = LicenseRepository(api, store, deviceId, auth, EntitlementRepository(api, auth))

        verify(store, timeout(10_000)).clear()
        worker.awaitQuiet(quietMs = 500)
        repository.state.value.error.orEmpty()
    }

    @Test fun `a key the server does not recognise shows the server's reason`() {
        assertEquals(
            "That license key was not recognized.",
            messageFor(404, "license_invalid", "That license key was not recognized.")
        )
    }

    @Test fun `a lapsed subscription shows the server's reason`() {
        assertEquals(
            "This subscription is no longer active.",
            messageFor(402, "license_inactive", "This subscription is no longer active.")
        )
    }
}
