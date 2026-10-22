package com.skeler.scanely.settings.presentation.viewmodel

import com.skeler.scanely.core.ai.custom.ConnectionFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val COMPLETE = ProviderCredentials(
    url = "https://api.groq.com/openai/v1/chat/completions",
    apiKey = "gsk_secret",
    model = "llama-3.3-70b-versatile"
)

class CustomProviderUiStateTest {

    @Test
    fun `an untested endpoint cannot be saved`() {
        val state = CustomProviderUiState(isPro = true, credentials = COMPLETE)
        assertFalse(state.isVerified)
        assertFalse(state.canSave)
    }

    @Test
    fun `a passing test unlocks saving`() {
        val state = CustomProviderUiState(isPro = true, credentials = COMPLETE, verifiedFor = COMPLETE)
        assertTrue(state.isVerified)
        assertTrue(state.canSave)
    }

    @Test
    fun `editing any field after a passing test un-verifies the provider`() {
        val verified = CustomProviderUiState(isPro = true, credentials = COMPLETE, verifiedFor = COMPLETE)

        val editedModel = verified.copy(credentials = COMPLETE.copy(model = "some-other-model"))
        assertFalse("changing the model must re-require a test", editedModel.isVerified)
        assertFalse(editedModel.canSave)

        val editedKey = verified.copy(credentials = COMPLETE.copy(apiKey = "gsk_other"))
        assertFalse("changing the key must re-require a test", editedKey.isVerified)

        val editedUrl = verified.copy(
            credentials = COMPLETE.copy(url = "https://api.openai.com/v1/chat/completions")
        )
        assertFalse("changing the URL must re-require a test", editedUrl.isVerified)
    }

    @Test
    fun `a failed test leaves the provider unverified and unsaveable`() {
        val state = CustomProviderUiState(
            isPro = true,
            credentials = COMPLETE,
            failure = ConnectionFailure.InvalidKey(401)
        )
        assertFalse(state.isVerified)
        assertFalse(state.canSave)
    }

    @Test
    fun `an incomplete configuration cannot be tested`() {
        assertFalse(CustomProviderUiState(isPro = true, credentials = ProviderCredentials()).canTest)
        assertFalse(
            CustomProviderUiState(isPro = true, credentials = COMPLETE.copy(apiKey = "  ")).canTest
        )
        assertFalse(
            CustomProviderUiState(isPro = true, credentials = COMPLETE.copy(model = "")).canTest
        )
    }

    @Test
    fun `a complete configuration can be tested`() {
        assertTrue(CustomProviderUiState(isPro = true, credentials = COMPLETE).canTest)
    }

    @Test
    fun `neither testing nor saving is offered while a test is in flight`() {
        val testing = CustomProviderUiState(
            isPro = true,
            credentials = COMPLETE,
            verifiedFor = COMPLETE,
            isTesting = true
        )
        assertFalse(testing.canTest)
        assertFalse(testing.canSave)
    }

    @Test
    fun `a free user can read the form but not act on it`() {
        val free = CustomProviderUiState(isPro = false, credentials = COMPLETE)
        assertFalse("a free user must not edit the fields", free.canEdit)
        assertFalse("a free user must not run a connection test", free.canTest)
        assertFalse("a free user must not save an endpoint", free.canSave)
    }

    @Test
    fun `a free user cannot save even a configuration that already passed a test`() {
        // The case that matters: Pro lapses while a verified endpoint is still stored.
        val lapsed = CustomProviderUiState(
            isPro = false,
            credentials = COMPLETE,
            verifiedFor = COMPLETE
        )
        assertTrue("verification itself is unchanged", lapsed.isVerified)
        assertFalse("but it must not be saveable without Pro", lapsed.canSave)
        assertFalse(lapsed.canTest)
    }

    @Test
    fun `upgrading to pro unlocks the same configuration`() {
        val free = CustomProviderUiState(isPro = false, credentials = COMPLETE, verifiedFor = COMPLETE)
        val upgraded = free.copy(isPro = true)
        assertTrue(upgraded.canEdit)
        assertTrue(upgraded.canTest)
        assertTrue(upgraded.canSave)
    }

    @Test
    fun `verification does not survive being carried onto different credentials`() {
        val state = CustomProviderUiState(
            isPro = true,
            credentials = COMPLETE.copy(url = "https://api.openai.com/v1/chat/completions"),
            verifiedFor = COMPLETE
        )
        assertFalse(state.isVerified)
    }
}
