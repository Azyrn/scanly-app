package com.skeler.scanely.docchat.data

import com.skeler.scanely.core.ai.AiResult
import com.skeler.scanely.core.ai.DesignAsk
import com.skeler.scanely.core.ai.DesignField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyBodyTest {
    @Test
    fun `a 409 becomes a question with the server's suggestion and token`() {
        val error = AiResult.Error("x", "design_mismatch", ask = DesignAsk.Mismatch("a receipt", "invoice", "t1"))
        assertEquals(AskBody("a receipt", "invoice", "t1"), error.toReplyBody())
    }

    @Test
    fun `a 422 becomes an inline form`() {
        val ask = DesignAsk.MissingFields(listOf(DesignField("client", "Client name")), "t2")
        val error = AiResult.Error("x", "design_missing_fields", ask = ask)
        assertEquals(FieldsBody(listOf(FieldRef("client", "Client name")), "t2"), error.toReplyBody())
    }

    @Test
    fun `not charged is shown only when the server said so`() {
        val said = AiResult.Error("Failed.", "design_failed", notCharged = true).toReplyBody() as ErrorBody
        val silent = AiResult.Error("Failed.", "provider_error").toReplyBody() as ErrorBody
        assertTrue(said.notCharged)
        assertFalse(silent.notCharged)
        assertTrue(said.retryable)
    }

    @Test
    fun `a paywall refusal is answered with plans, not a retry`() {
        val body = AiResult.Error("No credits left.", "quota_exceeded").toReplyBody() as ErrorBody
        assertFalse(body.retryable)
        assertEquals("quota_exceeded", body.code)
    }
}
