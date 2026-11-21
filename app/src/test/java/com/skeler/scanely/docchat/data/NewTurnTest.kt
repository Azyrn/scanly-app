package com.skeler.scanely.docchat.data

import com.skeler.scanely.R
import com.skeler.scanely.core.ai.custom.CustomProviderConfig
import com.skeler.scanely.core.ai.custom.EndpointChoice
import com.skeler.scanely.core.ai.custom.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who answers a new chat turn: with the user's own endpoint on, never Scanly's model or designer. */
class NewTurnTest {

    private fun own(url: String) = EndpointChoice.Own(
        CustomProviderConfig(url = url, apiKey = "sk-own", model = "my-model", protocol = ProviderProtocol.OPENAI)
    )

    @Test
    fun `an own endpoint runs the agent on the user's model, template requests included`() {
        for (eligible in listOf(true, false)) {
            val next = newTurn(own("https://api.groq.com/openai/v1/chat/completions"), agentEnabled = true, agentEligible = eligible)
            assertTrue(next is NewTurn.Agent)
            assertEquals("my-model", (next as NewTurn.Agent).ownModel?.model)
        }
    }

    @Test
    fun `an own endpoint is refused, never handed to the designer, where the agent cannot take it`() {
        assertEquals(
            NewTurn.Refused(R.string.chat_own_model_needs_agent),
            newTurn(own("https://api.groq.com/openai/v1/chat/completions"), agentEnabled = false, agentEligible = true)
        )
        assertEquals(
            NewTurn.Refused(R.string.chat_own_model_local),
            newTurn(own("http://192.168.1.20:11434/v1/chat/completions"), agentEnabled = true, agentEligible = true)
        )
        assertEquals(NewTurn.Refused(R.string.ai_err_custom_needs_pro), newTurn(EndpointChoice.NeedsPro, true, true))
    }

    @Test
    fun `without an own endpoint nothing changes`() {
        assertEquals(NewTurn.Agent(ownModel = null), newTurn(EndpointChoice.Scanly, agentEnabled = true, agentEligible = true))
        assertEquals(NewTurn.Designer, newTurn(EndpointChoice.Scanly, agentEnabled = true, agentEligible = false))
        assertEquals(NewTurn.Designer, newTurn(EndpointChoice.Scanly, agentEnabled = false, agentEligible = true))
    }
}
