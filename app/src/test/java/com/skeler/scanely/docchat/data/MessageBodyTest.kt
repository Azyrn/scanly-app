package com.skeler.scanely.docchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBodyTest {
    @Test
    fun `every body survives a round trip`() {
        val bodies = listOf(
            UserBody("An invoice", "invoice", listOf(AttachmentRef(AttachmentKind.DOCUMENT, "scan.pdf", 2))),
            AiTextBody("Stopped waiting."),
            AskBody("a receipt", "invoice", "token", AskAnswer.CONTINUE),
            FieldsBody(listOf(FieldRef("client", "Client name")), "token", mapOf("client" to "Acme")),
            FileBody("d-1", DocFormat.PDF, "Invoice.pdf", pages = 2, credits = 3),
            ErrorBody("Failed.", notCharged = true, retryable = true, code = "design_failed"),
            WorkingBody(10L, listOf(StepRecord(DesignStep.PLANNING, 10L)), jobId = "j", background = true),
            // The document agent's bodies.
            AiTextBody("Which company is it for?", choices = listOf("Mine", "A client")),
            FileBody("f-1", DocFormat.DOCX, "report.docx", pages = 0, credits = 0, agentFileId = "f-1"),
            WorkingBody(10L, listOf(StepRecord(DesignStep.WRITING_CODE, 11L)), agentTurnId = "t-1")
        )
        bodies.forEach { assertEquals(it, decodeBody(it.encode())) }
    }

    @Test
    fun `a body saved before the agent existed still reads the same`() {
        assertEquals(AiTextBody("Hi"), decodeBody("""{"type":"text","text":"Hi"}"""))
        val file = decodeBody("""{"type":"file","designId":"d","name":"a.pdf","pages":1,"credits":1}""") as FileBody
        assertEquals(null, file.agentFileId)
    }

    @Test
    fun `a body from a newer app opens as unknown instead of failing the chat`() {
        assertEquals(UnknownBody, decodeBody("""{"type":"spreadsheet","rows":3}"""))
        assertEquals(UnknownBody, decodeBody("not json"))
    }

    @Test
    fun `a newer field on a known body is ignored`() {
        val body = decodeBody("""{"type":"file","designId":"d","name":"a.pdf","pages":1,"credits":1,"thumbnail":"x"}""")
        assertEquals(FileBody("d", DocFormat.PDF, "a.pdf", 1, 1), body)
    }

    @Test
    fun `steps use the server's step ids`() {
        val json = WorkingBody(1L, listOf(StepRecord(DesignStep.FIXING_LAYOUT, 2L))).encode()
        assertTrue(json, json.contains("\"fixing\""))
        val fromServer = decodeBody("""{"type":"working","startedAt":1,"steps":[{"step":"building","at":5}]}""")
        assertEquals(DesignStep.BUILDING_PDF, (fromServer as WorkingBody).steps.single().step)
    }

    @Test
    fun `search text is the words, never the json`() {
        val body = UserBody("Navy headings", attachments = listOf(AttachmentRef(AttachmentKind.PHOTO, "me.jpg")))
        assertEquals("Navy headings me.jpg", body.searchText)
        assertEquals("", WorkingBody(1L).searchText)
    }
}
