package com.skeler.scanely.docchat.data

import kotlinx.serialization.Serializable
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * The Worker's chat history (worker/src/chats.ts) and the one design route the chat needs beyond
 * the designer's: a kept design's PDF, recompiled. Its own interface, so ScanlyApi stays the
 * designer's and scanner's.
 */
interface ChatSyncApi {
    @GET("v1/chats")
    suspend fun chats(@Query("since") since: Long): RemoteChats

    @PUT("v1/chats/{id}")
    suspend fun putChat(@Path("id") id: String, @Body chat: RemoteChatWrite): RemoteOk

    @DELETE("v1/chats/{id}")
    suspend fun deleteChat(@Path("id") id: String): RemoteOk

    @GET("v1/chats/{id}/messages")
    suspend fun messages(@Path("id") id: String): RemoteMessages

    @PUT("v1/chats/{id}/messages")
    suspend fun putMessages(@Path("id") id: String, @Body body: RemoteMessagesWrite): RemoteOk

    /** Not billed; `design_expired` (410) once the design is no longer kept. */
    @GET("v1/design/{id}/pdf")
    suspend fun designPdf(@Path("id") id: String): Response<ResponseBody>

    /**
     * The document agent (worker/src/routes/agent.ts): 202 [AgentTurnAccepted], then the turn is
     * followed with [agentTurnEvents] and collected from [agentTurn]. A turn that only talks or
     * asks bills nothing; the server decides and says so.
     */
    @POST("v1/agent/turns")
    @Headers("X-Scanly-Caps: design-agent-v1")
    suspend fun startAgentTurn(@Body request: AgentTurnRequest): AgentTurnAccepted

    /** Stores one picture or PDF for a chat, as raw bytes; a turn then names it by the id returned. */
    @POST("v1/agent/attachments")
    @Headers("X-Scanly-Caps: design-agent-v1")
    suspend fun uploadAgentAttachment(
        @Query("chatId") chatId: String,
        @Query("name") name: String,
        @Body file: RequestBody
    ): AgentAttachmentStored

    /** The person's answer to a web turn's question ([AgentApprovalDto]); 409 `approval_closed` once it has closed. */
    @POST("v1/agent/turns/{id}/approvals/{approval}")
    suspend fun answerAgentApproval(
        @Path("id") id: String,
        @Path("approval") approval: String,
        @Body answer: AgentApprovalAnswer
    ): Response<ResponseBody>

    @Streaming
    @GET("v1/agent/turns/{id}/events")
    suspend fun agentTurnEvents(@Path("id") id: String): Response<ResponseBody>

    @GET("v1/agent/turns/{id}/outputs/{row}")
    suspend fun agentTurnOutput(@Path("id") id: String, @Path("row") row: String): Response<ResponseBody>

    /** 202 while it runs, 200 with the result, or the error envelope (notCharged) when it failed. */
    @GET("v1/agent/turns/{id}")
    suspend fun agentTurn(@Path("id") id: String): Response<ResponseBody>

    @Streaming
    @GET("v1/agent/files/{id}")
    suspend fun agentFile(@Path("id") id: String): Response<ResponseBody>

    /** The file as a PDF: an Office file converted by the server, so its pages can be saved as images. */
    @GET("v1/agent/files/{id}/pdf")
    suspend fun agentFilePdf(@Path("id") id: String): Response<ResponseBody>

    /** An Office file's page 1 (PNG), when the server made one ([AgentFileDto.thumbnail]). */
    @GET("v1/agent/files/{id}/thumbnail")
    suspend fun agentFileThumbnail(@Path("id") id: String): Response<ResponseBody>
}

@Serializable
data class AgentHistoryItem(val role: String, val text: String)

@Serializable
data class AgentTurnRequest(
    val chatId: String,
    val message: String,
    val history: List<AgentHistoryItem>,
    val locale: String? = null,
    /** A past scan's text for the agent's workspace (worker/src/agent-inputs.ts), base64. */
    val attachments: List<AgentAttachmentDto> = emptyList(),
    /** Pictures and PDFs this message brings, by the ids [ChatSyncApi.uploadAgentAttachment] gave. */
    val attachmentIds: List<String> = emptyList()
)

@Serializable
data class AgentAttachmentStored(val id: String, val name: String = "", val kind: String = "", val size: Long = 0)

@Serializable
data class AgentAttachmentDto(val name: String, val data: String)

@Serializable
data class AgentTurnAccepted(val turnId: String, val deadlineSeconds: Int = 600)

@Serializable
data class AgentFileDto(
    val id: String,
    val name: String,
    val format: String,
    val size: Long = 0,
    /** Office files: the page count and whether a page-1 thumbnail is on the server (PDFs are counted here). */
    val pages: Int? = null,
    val thumbnail: Boolean = false
)

@Serializable
data class AgentAskDto(val question: String, val choices: List<String> = emptyList())

@Serializable
data class AgentStepDto(val step: String, val at: Long = 0)

/**
 * A web turn's question (worker/src/agent-turn.ts AgentApproval): may the assistant open [url], which is
 * not in the person's messages or its search results? [why] is `page_link` (found on a page it opened) or
 * `new_url`; [warn] is `query` (the link carries data) or `long`. Unanswered within [expiresInMs], it is denied.
 */
@Serializable
data class AgentApprovalDto(
    val id: String,
    val host: String,
    val url: String,
    val why: String = "new_url",
    val warn: String? = null,
    val expiresInMs: Long = 0
)

@Serializable
data class AgentApprovalAnswer(val decision: String)

/** The turn route's 200/202 body. */
@Serializable
data class AgentStatusDto(
    val state: String,
    val text: String = "",
    val files: List<AgentFileDto> = emptyList(),
    val ask: AgentAskDto? = null,
    val pagesBilled: Int = 0,
    val steps: List<AgentStepDto> = emptyList(),
    /** What the agent has done so far, as the server keeps it (agent-log.ts). */
    val rows: List<AgentRow> = emptyList(),
    val tasks: List<AgentTask> = emptyList()
)

/** One server-sent event: `step`, `rows`, `tasks`, `file`, `approval`, `approval_closed`, then `done` or `failed`. */
@Serializable
data class AgentEventDto(
    val type: String,
    val step: String? = null,
    val at: Long = 0,
    val file: AgentFileDto? = null,
    val text: String = "",
    val files: List<AgentFileDto> = emptyList(),
    val ask: AgentAskDto? = null,
    val pagesBilled: Int = 0,
    val code: String? = null,
    val message: String? = null,
    val notCharged: Boolean = false,
    /** A `rows` event's rows that changed (the first on a stream is everything the server kept). */
    val rows: List<AgentRow> = emptyList(),
    /** A `tasks` event's whole task list. */
    val items: List<AgentTask> = emptyList(),
    /** An `approval` event's question. */
    val approval: AgentApprovalDto? = null,
    /** An `approval_closed` event's question, and how it closed (allowed, denied, expired, ended). */
    val approvalId: String? = null,
    val decision: String? = null
)

@Serializable
data class RemoteChat(
    val id: String,
    val title: String = "",
    val preview: String = "",
    val format: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val serverAt: Long = 0,
    val deleted: Boolean = false
)

@Serializable
data class RemoteChats(val chats: List<RemoteChat> = emptyList(), val cursor: Long = 0, val more: Boolean = false)

@Serializable
data class RemoteChatWrite(
    val title: String,
    val preview: String,
    val format: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class RemoteMessage(
    val id: String,
    val seq: Long,
    val role: String,
    val kind: String,
    /** The app's own [MessageBody] JSON, verbatim. */
    val body: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class RemoteMessages(val messages: List<RemoteMessage> = emptyList())

@Serializable
data class RemoteMessagesWrite(val messages: List<RemoteMessage>)

@Serializable
data class RemoteOk(val ok: Boolean = false, val deleted: Boolean = false)
