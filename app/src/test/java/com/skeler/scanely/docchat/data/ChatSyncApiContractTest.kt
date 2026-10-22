package com.skeler.scanely.docchat.data

import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

/** Every route here is one worker/src/index.ts serves (chats.ts, handleDesignPdf, routes/agent.ts). */
class ChatSyncApiContractTest {
    @Test
    fun `chat sync routes match the worker router`() {
        val expected = mapOf(
            "chats" to "GET v1/chats",
            "putChat" to "PUT v1/chats/{id}",
            "deleteChat" to "DELETE v1/chats/{id}",
            "messages" to "GET v1/chats/{id}/messages",
            "putMessages" to "PUT v1/chats/{id}/messages",
            "designPdf" to "GET v1/design/{id}/pdf",
            "startAgentTurn" to "POST v1/agent/turns",
            "uploadAgentAttachment" to "POST v1/agent/attachments",
            "agentTurnEvents" to "GET v1/agent/turns/{id}/events",
            "answerAgentApproval" to "POST v1/agent/turns/{id}/approvals/{approval}",
            "agentTurnOutput" to "GET v1/agent/turns/{id}/outputs/{row}",
            "agentTurn" to "GET v1/agent/turns/{id}",
            "agentFile" to "GET v1/agent/files/{id}",
            "agentFileThumbnail" to "GET v1/agent/files/{id}/thumbnail",
            "agentFilePdf" to "GET v1/agent/files/{id}/pdf"
        )
        val actual = ChatSyncApi::class.java.declaredMethods.associate { method ->
            method.name to (
                method.getAnnotation(GET::class.java)?.let { "GET ${it.value}" }
                    ?: method.getAnnotation(PUT::class.java)?.let { "PUT ${it.value}" }
                    ?: method.getAnnotation(POST::class.java)?.let { "POST ${it.value}" }
                    ?: method.getAnnotation(DELETE::class.java)?.let { "DELETE ${it.value}" }
                )
        }
        assertEquals(expected, actual)
    }
}
