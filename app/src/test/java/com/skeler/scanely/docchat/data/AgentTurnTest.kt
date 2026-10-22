package com.skeler.scanely.docchat.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.skeler.scanely.core.ai.FatalAiException
import com.skeler.scanely.core.network.ApiJson
import com.skeler.scanely.core.network.LocalWorker
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit

/** The agent's routing rule, its steps, and following a turn against a local Worker. */
class AgentTurnTest {
    private val worker = LocalWorker()

    @After fun tearDown() = worker.close()

    private val api =
        Retrofit.Builder()
            .baseUrl(worker.baseUrl)
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ChatSyncApi::class.java)
    private val client = AgentClient(api, api)

    private fun request(
        hasDocument: Boolean = false,
        hasPhoto: Boolean = false,
        templateId: String? = null,
        sourceText: String? = null,
        priorDesignId: String? = null
    ) = RequestEntity(
        replyId = "r", chatId = "c", turnId = "t", prompt = "A report", sourceText = sourceText,
        priorDesignId = priorDesignId, templateId = templateId, ocrReceipt = null, hasDocument = hasDocument,
        hasPhoto = hasPhoto, requestKey = "k", keyCreatedAt = 0
    )

    @Test
    fun `every turn goes to the agent - words, pictures to rebuild, PDFs, scans and changes - except an old template request`() {
        assertTrue(request().agentEligible())
        assertTrue(request(priorDesignId = "f").agentEligible())
        assertTrue(request(priorDesignId = "d").agentEligible())
        assertTrue(request(hasDocument = true, sourceText = "ocr").agentEligible())
        assertTrue(request(hasDocument = true).agentEligible())
        assertTrue(request(hasPhoto = true).agentEligible())
        assertTrue(request(sourceText = "scan text").agentEligible())
        assertFalse(request(templateId = "invoice").agentEligible())
    }

    @Test
    fun `a picture to rebuild travels as the PNG or JPEG it is, and a format the agent does not take is left out`() {
        val encoder = java.util.Base64.getEncoder()
        assertEquals("pdf", encoder.encodeToString("%PDF-1.7 x".toByteArray()).agentExtension())
        assertEquals("png", encoder.encodeToString(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0)).agentExtension())
        assertEquals("jpg", encoder.encodeToString(byteArrayOf(-1, -40, -1, -32, 0, 16)).agentExtension())
        assertEquals(null, encoder.encodeToString("RIFF0000WEBP".toByteArray()).agentExtension())
    }

    @Test
    fun `a PDF is told from a picture by its first bytes`() {
        assertTrue(java.util.Base64.getEncoder().encodeToString("%PDF-1.7 x".toByteArray()).isPdfBase64())
        assertFalse(java.util.Base64.getEncoder().encodeToString(byteArrayOf(-1, -40, -1, 0)).isPdfBase64())
    }

    @Test
    fun `every agent step the worker reports has a label`() {
        val ids = listOf("thinking", "reading", "coding", "previewing", "checking", "designing", "delivering")
        ids.forEach { assertTrue(it, agentStepOf(it) != null) }
        assertEquals(null, agentStepOf("unknown"))
    }

    @Test
    fun `a turn is followed live, then collected, and files arrive once each`() = runBlocking {
        worker.route("/v1/agent/turns/t1/events") {
            200 to listOf(
                """{"type":"step","step":"thinking","at":1}""",
                """{"type":"step","step":"coding","at":2}""",
                """{"type":"file","file":{"id":"f1","name":"report.docx","format":"docx","size":10}}""",
                """{"type":"done","text":"Here it is.","files":[],"pagesBilled":3}"""
            ).joinToString("") { "data: $it\n\n" }
        }
        worker.route("/v1/agent/turns/t1") {
            200 to """{"state":"done","text":"Here it is.","files":[{"id":"f1","name":"report.docx","format":"docx","size":10}],"pagesBilled":3,"steps":[{"step":"thinking","at":1}]}"""
        }
        val steps = mutableListOf<String>()
        val files = mutableListOf<String>()
        val status = client.follow("t1", 60, { id, _ -> steps += id }) { files += it.id }
        assertEquals("Here it is.", status.text)
        assertEquals(3, status.pagesBilled)
        assertEquals(listOf("f1"), files)
        assertEquals(listOf("thinking", "coding", "thinking"), steps)
    }

    @Test
    fun `a web turn's question and its close come on the stream, and an event this version does not know ends nothing`() =
        runBlocking {
            val id = "a".repeat(32)
            worker.route("/v1/agent/turns/t3/events") {
                200 to listOf(
                    """{"type":"approval","approval":{"id":"$id","host":"site.example","url":"https://site.example/x?q=1",""" +
                        """"why":"new_url","warn":"query","expiresInMs":120000}}""",
                    """{"type":"something_later","x":1}""",
                    """{"type":"approval_closed","approvalId":"$id","decision":"denied"}""",
                    """{"type":"file","file":{"id":"f1","name":"report.pdf","format":"pdf","size":10}}""",
                    """{"type":"done","text":"Done.","files":[],"pagesBilled":0}"""
                ).joinToString("") { "data: $it\n\n" }
            }
            worker.route("/v1/agent/turns/t3") { 200 to """{"state":"done","text":"Done.","files":[]}""" }
            val asked = mutableListOf<AgentApprovalDto>()
            val closed = mutableListOf<String>()
            val files = mutableListOf<String>()
            client.follow(
                "t3",
                60,
                { _, _ -> },
                onApproval = { asked += it },
                onApprovalClosed = { closed += it }
            ) { files += it.id }
            assertEquals(
                listOf(AgentApprovalDto(id, "site.example", "https://site.example/x?q=1", "new_url", "query", 120_000)),
                asked
            )
            assertEquals(listOf(id), closed)
            assertEquals("the stream went on past the unknown event", listOf("f1"), files)
        }

    @Test
    fun `an answer reaches the turn, a closed question is a plain no, and anything else is the server's error`() =
        runBlocking {
            val path = "/v1/agent/turns/t4/approvals/" + "b".repeat(32)
            worker.route(path) { call ->
                when (call) {
                    1 -> 200 to """{"ok":true}"""
                    2 -> 409 to """{"error":{"code":"approval_closed","message":"That question has already closed."}}"""
                    else -> 502 to """{"error":{"code":"provider_error","message":"The assistant did not answer."}}"""
                }
            }
            assertTrue(client.answerApproval("t4", "b".repeat(32), allow = true))
            assertFalse(client.answerApproval("t4", "b".repeat(32), allow = false))
            try {
                client.answerApproval("t4", "b".repeat(32), allow = true)
                fail("expected the server's error")
            } catch (e: FatalAiException) {
                assertEquals("The assistant did not answer.", e.result.message)
            }
            assertEquals(3, worker.count(path))
        }

    @Test
    fun `a failed turn surfaces the server's error and its not-charged`() = runBlocking {
        worker.route("/v1/agent/turns/t2/events") { 500 to "{}" }
        worker.route("/v1/agent/turns/t2") {
            502 to """{"error":{"code":"provider_error","message":"The assistant failed.","notCharged":true}}"""
        }
        try {
            client.follow("t2", 60, { _, _ -> }) { }
            fail("expected a failure")
        } catch (e: FatalAiException) {
            assertEquals("The assistant failed.", e.result.message)
            assertTrue(e.result.notCharged)
        }
    }

    @Test
    fun `a refused start is final and keeps the server's reason`() = runBlocking {
        worker.route("/v1/agent/turns") {
            429 to """{"error":{"code":"rate_limited","message":"You reached today's limit of 30 assistant messages."}}"""
        }
        try {
            client.start(AgentTurnRequest("c", "hi", emptyList()))
            fail("expected a refusal")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("today's limit"))
        }
    }
}
