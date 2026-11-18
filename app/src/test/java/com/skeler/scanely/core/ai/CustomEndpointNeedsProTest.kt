package com.skeler.scanely.core.ai

import com.skeler.scanely.core.ai.custom.CustomProviderResolver
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.billing.ProEntitlementGate
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.settings.data.SettingsKeys
import com.skeler.scanely.settings.domain.repository.SettingsRepository
import com.skeler.scanely.testing.EnglishStrings
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock

/**
 * N6: a user who switched their own AI endpoint on chose to pay their provider, not Scanly. When
 * Pro (which the endpoint needs) could not be confirmed within the gate's wait — a slow /v1/me on a
 * cold start, say — the request silently went to Scanly and spent the user's credits. It is now
 * refused with a message that says why and how to choose Scanly instead.
 */
class CustomEndpointNeedsProTest {
    private var scanlyCalls = 0

    private val settings = mock(SettingsRepository::class.java) { call: InvocationOnMock ->
        val key = call.arguments.firstOrNull() as? SettingsKeys
        when (call.method.name) {
            "getBoolean" -> flowOf(key == SettingsKeys.CUSTOM_PROVIDER_ENABLED || key == SettingsKeys.CUSTOM_PROVIDER_VERIFIED)
            "getString" -> flowOf(
                when (key) {
                    SettingsKeys.CUSTOM_PROVIDER_URL -> "https://llm.example.com/v1"
                    SettingsKeys.CUSTOM_PROVIDER_KEY -> "sk-test"
                    SettingsKeys.CUSTOM_PROVIDER_MODEL -> "model-1"
                    else -> key?.let { it.default as? String }.orEmpty()
                }
            )
            else -> null
        }
    }

    /** Pro not confirmed in time. */
    private val gate = mock(ProEntitlementGate::class.java) { call: InvocationOnMock ->
        if (call.method.name == "awaitPro") false else null
    }

    private val executor = mock(BackendExecutor::class.java) { call: InvocationOnMock ->
        if (call.method.name != "run") return@mock null
        scanlyCalls++
        AiOutcome.Success("translated by Scanly, on the user's credits")
    }

    private val service = GenerativeAiService(
        EnglishStrings.context(),
        mock(PayloadFactory::class.java),
        mock(BackendClient::class.java),
        executor,
        mock(NetworkObserver::class.java) { true },
        CustomProviderResolver(settings, gate),
        mock(com.skeler.scanely.core.ai.custom.CustomScanner::class.java),
        mock(ExtractionCatalog::class.java),
        mock(DesignStore::class.java),
        mock(EntitlementRepository::class.java)
    )

    @Test fun `own endpoint on and Pro unconfirmed - the request is refused, never billed to Scanly credits`() = runBlocking {
        val result = service.translateText("Bonjour", "English")
        assertEquals("no Scanly request", 0, scanlyCalls)
        assertTrue("refused with a reason: $result", result is AiResult.Error)
        val message = (result as AiResult.Error).message
        assertTrue(message, message.contains("Pro") && message.contains("Settings"))
    }
}
