package com.skeler.scanely.docchat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a message says, stored as JSON in [MessageEntity.body] and synced to the server as is.
 * Every class is additive-only: an older app reading a newer body falls back to
 * [UnknownBody] rather than failing the whole chat.
 */
@Serializable
sealed interface MessageBody

/** File formats a reply can carry. Only PDF is produced today; the rest render already. */
@Serializable
enum class DocFormat(val extension: String, val mimeType: String) {
    @SerialName("pdf")
    PDF("pdf", "application/pdf"),

    @SerialName("docx")
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),

    @SerialName("pptx")
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),

    @SerialName("xlsx")
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
}

@Serializable
enum class AttachmentKind {
    /** A photo or PDF the design rebuilds. */
    @SerialName("document")
    DOCUMENT,

    /** A picture the design places (a portrait, a logo). */
    @SerialName("photo")
    PHOTO,

    /** The text of a past scan from History. */
    @SerialName("scan")
    SCAN
}

@Serializable
data class AttachmentRef(
    val kind: AttachmentKind,
    val name: String,
    val pages: Int = 1,
    /** The server's id for it once uploaded (worker/src/agent-attachments.ts): how later turns name this file. */
    val id: String? = null
)

@Serializable
@SerialName("user")
data class UserBody(
    val text: String,
    val templateId: String? = null,
    val attachments: List<AttachmentRef> = emptyList()
) : MessageBody

@Serializable
@SerialName("text")
data class AiTextBody(
    val text: String,
    /** The agent's suggested answers to a question it asked; tapping one sends it. */
    val choices: List<String> = emptyList(),
    /** How the document agent's turn went: its steps, and what it did (kept within the synced size). */
    val steps: List<StepRecord> = emptyList(),
    val log: AgentLog? = null,
    /** The agent's turn, for fetching a row's whole output while the server keeps it. */
    val agentTurnId: String? = null
) : MessageBody

/** The designer thinks the attachment is not the document asked for (409). Nothing was billed. */
@Serializable
@SerialName("ask")
data class AskBody(
    val reason: String,
    val suggestedTemplate: String? = null,
    val intakeToken: String? = null,
    /** How the user answered; the choices lock once set. */
    val answer: AskAnswer? = null
) : MessageBody

@Serializable
enum class AskAnswer {
    @SerialName("template")
    USE_TEMPLATE,

    @SerialName("continue")
    CONTINUE,

    @SerialName("dismiss")
    DISMISS
}

@Serializable
data class FieldRef(val id: String, val label: String)

/** Required details are missing (422); the form is answered inside the chat. Nothing was billed. */
@Serializable
@SerialName("fields")
data class FieldsBody(
    val fields: List<FieldRef>,
    val intakeToken: String? = null,
    val submitted: Map<String, String>? = null
) : MessageBody

@Serializable
@SerialName("file")
data class FileBody(
    val designId: String,
    val format: DocFormat = DocFormat.PDF,
    val name: String,
    val pages: Int,
    /** Credits the server billed for this file; 0 when it billed none (a replay) or did not say. */
    val credits: Int,
    /** The server no longer holds this design; the file can be opened if cached, not revised. */
    val expired: Boolean = false,
    /** How it was made, for Show thinking on the finished reply. */
    val steps: List<StepRecord> = emptyList(),
    /**
     * Set for a file the document agent delivered: fetched from /v1/agent/files/{id}, and [designId]
     * is this same id (the local cache key). A message about it goes to the agent, not the designer.
     */
    val agentFileId: String? = null
) : MessageBody

@Serializable
@SerialName("error")
data class ErrorBody(
    val message: String,
    /** Said by the server only: the hold was released and nothing was billed. */
    val notCharged: Boolean = false,
    val retryable: Boolean = true,
    val code: String? = null,
    val steps: List<StepRecord> = emptyList(),
    val log: AgentLog? = null,
    val agentTurnId: String? = null
) : MessageBody

/**
 * A step and when it started, on this device's clock. [ms] is set only for a step that did not run
 * up to the next one (the OCR read before sending, with the user's typing in between).
 */
@Serializable
data class StepRecord(val step: DesignStep, val at: Long, val ms: Long? = null)

/** The design steps the status line names. Server step ids are the serial names. */
@Serializable
enum class DesignStep {
    @SerialName("reading")
    READING_FILE,

    @SerialName("extracting")
    EXTRACTING_TEXT,

    @SerialName("planning")
    PLANNING,

    @SerialName("writing")
    WRITING,

    @SerialName("building")
    BUILDING_PDF,

    @SerialName("checking")
    CHECKING,

    @SerialName("fixing")
    FIXING_LAYOUT,

    @SerialName("done")
    DONE,

    // The document agent's steps (worker/src/agent-turn.ts AgentStep), mapped by agentStepOf.
    @SerialName("thinking")
    THINKING,

    @SerialName("reading_guide")
    READING_GUIDE,

    @SerialName("coding")
    WRITING_CODE,

    @SerialName("previewing")
    PREVIEWING,

    @SerialName("delivering")
    DELIVERING
}

/** A reply still being made. Replaced in place by the file, question or error it becomes. */
@Serializable
@SerialName("working")
data class WorkingBody(
    val startedAt: Long,
    val steps: List<StepRecord> = emptyList(),
    /** The server's background job, once it has answered with one. */
    val jobId: String? = null,
    val background: Boolean = false,
    /** The user stopped waiting; a background job's result still arrives here. */
    val stopped: Boolean = false,
    /** A plain status from the client (a retry count, "reconnecting"), shown instead of the step. */
    val note: String? = null,
    /** The document agent's turn, once the server has accepted it; followed again on resume. */
    val agentTurnId: String? = null,
    /** What the document agent has done so far. */
    val log: AgentLog = AgentLog()
) : MessageBody

/** A body this version cannot read, kept so the chat still opens. */
@Serializable
@SerialName("unknown")
data object UnknownBody : MessageBody

internal val messageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "type"
}

internal fun MessageBody.encode(): String = messageJson.encodeToString(MessageBody.serializer(), this)

internal fun decodeBody(json: String): MessageBody =
    runCatching { messageJson.decodeFromString(MessageBody.serializer(), json) }.getOrDefault(UnknownBody)

internal val MessageBody.kind: String
    get() = when (this) {
        is UserBody -> "user"
        is AiTextBody -> "text"
        is AskBody -> "ask"
        is FieldsBody -> "fields"
        is FileBody -> "file"
        is ErrorBody -> "error"
        is WorkingBody -> "working"
        UnknownBody -> "unknown"
    }

internal val MessageBody.searchText: String
    get() = when (this) {
        is UserBody -> listOf(text, *attachments.map { it.name }.toTypedArray()).joinToString(" ")
        is AiTextBody -> text
        is FileBody -> name
        is AskBody -> reason
        else -> ""
    }
