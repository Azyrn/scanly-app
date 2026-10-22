package com.skeler.scanely.ui.viewmodel

import com.skeler.scanely.core.license.RestoreOutcome
import com.skeler.scanely.core.network.LicenseRecoveryResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreMessageTest {

    @Test
    fun `a restored key names the tier it turned out to be`() {
        assertEquals("Your Pro key is back.", restoreMessage(RestoreOutcome.Restored("pro")))
    }

    /** The activation call can succeed without the entitlement having been read back. */
    @Test
    fun `a restored key with no tier still confirms the restore`() {
        assertEquals(
            "Your key is back on this device.",
            restoreMessage(RestoreOutcome.Restored(null))
        )
    }

    /**
     * The likeliest cause of an empty result is the wrong account, not a lost subscription, and
     * the message has to send the user somewhere rather than just deny them.
     */
    @Test
    fun `finding nothing points at the email address rather than declaring it gone`() {
        val message = restoreMessage(RestoreOutcome.NothingFound).orEmpty()
        assertTrue(message.contains("email address"))
        assertFalse(message.contains("nothing to restore"))
    }

    @Test
    fun `the outcomes the sheet already explains say nothing twice`() {
        assertNull(restoreMessage(RestoreOutcome.NeedsEmailConfirmation))
        assertNull(restoreMessage(RestoreOutcome.Failed("No connection.")))
    }
}

/**
 * The wire contract with the Worker's `/v1/license/mine`. Both fields are optional on the way in:
 * a response that lost a field must read as "nothing found", never as a crash on the one screen a
 * user reaches for when they have already lost their key.
 */
class LicenseRecoveryResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a full response is read as the server sends it`() {
        val response = json.decodeFromString<LicenseRecoveryResponse>(
            """
            {"licenses":[{"key":"SCANLY-AAAAA-BBBBB-CCCCC-DDDDD","tier":"pro",
             "status":"active","grantsAccess":true,"currentPeriodEnd":123}],
             "emailUnverified":false}
            """.trimIndent()
        )

        assertEquals(1, response.licenses.size)
        assertEquals("SCANLY-AAAAA-BBBBB-CCCCC-DDDDD", response.licenses[0].key)
        assertTrue(response.licenses[0].grantsAccess)
        assertEquals(123L, response.licenses[0].currentPeriodEnd)
        assertFalse(response.emailUnverified)
    }

    @Test
    fun `an empty result is not an error`() {
        val response = json.decodeFromString<LicenseRecoveryResponse>(
            """{"licenses":[],"emailUnverified":true}"""
        )

        assertTrue(response.licenses.isEmpty())
        assertTrue(response.emailUnverified)
    }

    @Test
    fun `missing fields fall back rather than throwing`() {
        val response = json.decodeFromString<LicenseRecoveryResponse>("{}")

        assertTrue(response.licenses.isEmpty())
        assertFalse(response.emailUnverified)
    }

    @Test
    fun `an unknown field added by a newer server does not break an older app`() {
        val response = json.decodeFromString<LicenseRecoveryResponse>(
            """{"licenses":[],"emailUnverified":false,"somethingNew":1}"""
        )

        assertTrue(response.licenses.isEmpty())
    }
}
