package com.skeler.scanely.docchat.data

import com.skeler.scanely.core.ai.AiResult
import com.skeler.scanely.core.ai.FatalAiException
import com.skeler.scanely.core.ai.TransientAiException
import com.skeler.scanely.core.ai.aiDebug
import com.skeler.scanely.core.ai.backendFailure
import com.skeler.scanely.core.ai.toAiException
import com.skeler.scanely.core.network.TolerantJson
import com.skeler.scanely.core.network.backendError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The document agent's turn routes. A turn is started once, then followed: live from the events
 * stream, and collected from the status route, which is also the fallback when the stream drops.
 * Failures arrive as the server's error envelope, mapped exactly as the designer's are, so a
 * failed turn keeps the server's own "not charged".
 */
@Singleton
class AgentClient @Inject constructor(
    private val api: ChatSyncApi,
    /** For the live events only: a client that keeps the stream open ([AgentEventsApi]). */
    @param:AgentEventsApi private val events: ChatSyncApi
) {

    /** Starts a turn; its id. Refusals (no credits, daily limit, switched off) are final. */
    suspend fun start(request: AgentTurnRequest): AgentTurnAccepted = try {
        api.startAgentTurn(request)
    } catch (e: HttpException) {
        throw e.toAiException()
    }

    /** Stores one picture or PDF for [chatId]; the id later turns name it by. Refusals are the server's, final. */
    suspend fun upload(chatId: String, name: String, bytes: ByteArray): String = try {
        api.uploadAgentAttachment(chatId, name, bytes.toRequestBody(OCTET_STREAM)).id
    } catch (e: HttpException) {
        throw e.toAiException()
    }

    /**
     * Follows [turnId] to its end: the result, or a [FatalAiException] carrying the server's error.
     * [onRows] gets the rows of what the agent did that changed, with `replace` true when they are
     * everything the server kept (a stream's first batch, a status poll); [onTasks] its whole task list.
     */
    suspend fun follow(
        turnId: String,
        deadlineSeconds: Int,
        onStep: suspend (id: String, at: Long) -> Unit,
        onRows: suspend (rows: List<AgentRow>, replace: Boolean) -> Unit = { _, _ -> },
        onTasks: suspend (List<AgentTask>) -> Unit = {},
        onApproval: suspend (AgentApprovalDto) -> Unit = {},
        onApprovalClosed: suspend (String) -> Unit = {},
        onFile: suspend (AgentFileDto) -> Unit
    ): AgentStatusDto {
        try {
            val stream = events.agentTurnEvents(turnId)
            if (stream.isSuccessful) {
                stream.body()?.let { body ->
                    readEvents(body, EventHandlers(onStep, onFile, onRows, onTasks, onApproval, onApprovalClosed))
                }
            } else {
                stream.errorBody()?.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // The status route below has everything the stream would have said.
        }
        return poll(turnId, deadlineSeconds, onStep, onRows, onTasks, POLL_MS)
    }

    /**
     * The person's answer to [turnId]'s question [approval]: true if it reached the turn, false once the
     * question has closed (answered, timed out, or the turn ended). Anything else is the server's error.
     */
    suspend fun answerApproval(turnId: String, approval: String, allow: Boolean): Boolean {
        val response = api.answerAgentApproval(turnId, approval, AgentApprovalAnswer(if (allow) "allow" else "deny"))
        return when {
            response.isSuccessful -> true.also { response.body()?.close() }
            response.code() == HTTP_CONFLICT -> false.also { response.errorBody()?.close() }
            else -> throw failureOf(response)
        }
    }

    /** Waits for [turnId]'s end without the stream (the background watcher); as [follow]'s result. */
    suspend fun await(turnId: String, deadlineSeconds: Int, pollMs: Long): AgentStatusDto =
        poll(turnId, deadlineSeconds, { _, _ -> }, { _, _ -> }, {}, pollMs)

    /**
     * A row's whole output: [RowOutput.Text], [RowOutput.Expired] once the server no longer keeps the
     * turn (410 output_expired), or [RowOutput.Unavailable] when it could not be fetched now.
     */
    suspend fun output(turnId: String, row: String): RowOutput = try {
        outputOf(api.agentTurnOutput(turnId, row))
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
        RowOutput.Unavailable
    }

    /** A delivered file's bytes, or null when the server no longer has it. */
    suspend fun download(fileId: String): ByteArray? {
        val response = api.agentFile(fileId)
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return null
        }
        return response.body()?.use { it.bytes() }
    }

    /** A delivered file as a PDF (an Office file converted by the server), or null. */
    suspend fun pdf(fileId: String): ByteArray? {
        val response = api.agentFilePdf(fileId)
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return null
        }
        val bytes = response.body()?.use { it.bytes() } ?: return null
        val magic = String(bytes.copyOf(PDF_MAGIC.length), Charsets.US_ASCII)
        return bytes.takeIf { magic == PDF_MAGIC }
    }

    /** An Office file's page-1 PNG from the server, or null. */
    suspend fun thumbnail(fileId: String): ByteArray? {
        val response = api.agentFileThumbnail(fileId)
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return null
        }
        return response.body()?.use { it.bytes() }
    }

    private class EventHandlers(
        val onStep: suspend (String, Long) -> Unit,
        val onFile: suspend (AgentFileDto) -> Unit,
        val onRows: suspend (List<AgentRow>, Boolean) -> Unit,
        val onTasks: suspend (List<AgentTask>) -> Unit,
        val onApproval: suspend (AgentApprovalDto) -> Unit,
        val onApprovalClosed: suspend (String) -> Unit
    ) {
        /** The server opens a stream with everything it kept; later batches are the rows that changed. */
        var rowsSeen = false

        /** One event; false once the turn has ended (the status route then gives the result, once). */
        suspend fun on(event: AgentEventDto): Boolean {
            when (event.type) {
                "step", "file" -> progress(event)
                "rows" -> rows(event.rows)
                "tasks" -> onTasks(event.items)
                "approval", "approval_closed" -> approval(event)
                "done", "failed" -> return false
                // A kind of event this version does not know: the stream goes on.
                else -> Unit
            }
            return true
        }

        private suspend fun progress(event: AgentEventDto) {
            event.step?.let { step -> onStep(step, event.at) }
            event.file?.let { file -> onFile(file) }
        }

        private suspend fun rows(rows: List<AgentRow>) {
            // Debug builds only, ids only: when each row reached the phone (row latency checks).
            aiDebug { "agent rows ${rows.joinToString(",") { "${it.row}:${it.state}" }}" }
            onRows(rows, !rowsSeen)
            rowsSeen = true
        }

        /** A web turn's question opened, or closed (answered, timed out, the turn ended). */
        private suspend fun approval(event: AgentEventDto) {
            val opened = event.approval
            val closed = event.approvalId
            if (event.type == "approval" && opened != null) onApproval(opened)
            if (event.type == "approval_closed" && closed != null) onApprovalClosed(closed)
        }
    }

    private suspend fun readEvents(body: ResponseBody, handlers: EventHandlers) {
        body.use {
            val source = it.source()
            var open = true
            while (open) {
                currentCoroutineContext().ensureActive()
                val line = runInterruptible(Dispatchers.IO) { source.readUtf8Line() }
                open = line != null && handle(line, handlers)
            }
        }
    }

    /** One stream line; false once the turn has ended (the status route then gives the result, once). */
    private suspend fun handle(line: String, handlers: EventHandlers): Boolean {
        val event = eventOf(line) ?: return true
        return handlers.on(event)
    }

    private fun eventOf(line: String): AgentEventDto? = if (!line.startsWith(DATA_PREFIX)) {
        null
    } else {
        runCatching {
            TolerantJson.decodeFromString(AgentEventDto.serializer(), line.substring(DATA_PREFIX.length).trim())
        }.getOrNull()
    }

    private suspend fun poll(
        turnId: String,
        deadlineSeconds: Int,
        onStep: suspend (String, Long) -> Unit,
        onRows: suspend (List<AgentRow>, Boolean) -> Unit,
        onTasks: suspend (List<AgentTask>) -> Unit,
        pollMs: Long
    ): AgentStatusDto =
        withTimeoutOrNull((deadlineSeconds + DEADLINE_SLACK_S) * MS) {
            var finished: AgentStatusDto? = null
            while (finished == null) {
                val response = api.agentTurn(turnId)
                if (!response.isSuccessful) throw failureOf(response)
                val status = response.body()?.use { body ->
                    TolerantJson.decodeFromString(AgentStatusDto.serializer(), body.string())
                } ?: throw TransientAiException("The assistant sent an empty reply.")
                status.steps.forEach { onStep(it.step, it.at) }
                reportLog(status, onRows, onTasks)
                if (response.code() == HTTP_ACCEPTED) delay(pollMs) else finished = status
            }
            finished
        } ?: throw TransientAiException("The assistant is still working. Open this chat again in a moment.")

    /** The turn ran once; its failure is final, and the server said what it billed. */
    private fun failureOf(response: retrofit2.Response<ResponseBody>): FatalAiException {
        val error = HttpException(response).backendError()
        val failure = backendFailure(response.code(), error)
        return failure as? FatalAiException ?: FatalAiException(
            AiResult.Error(
                failure.message ?: "The assistant failed. Try again.",
                code = error?.code,
                notCharged = error?.notCharged == true
            )
        )
    }

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        const val DATA_PREFIX = "data:"
        const val PDF_MAGIC = "%PDF-"
        const val HTTP_ACCEPTED = 202
        const val HTTP_CONFLICT = 409
        const val POLL_MS = 3_000L
        const val DEADLINE_SLACK_S = 60
        const val MS = 1000L
    }
}

/** What fetching a row's whole output gave. */
sealed interface RowOutput {
    /** [cut]: characters past what even the server kept. */
    data class Text(val text: String, val cut: Int) : RowOutput
    data object Expired : RowOutput
    data object Unavailable : RowOutput
}

private fun outputOf(response: retrofit2.Response<ResponseBody>): RowOutput = when {
    response.isSuccessful -> response.body()?.use { body ->
        val output = TolerantJson.decodeFromString(RowOutputDto.serializer(), body.string())
        RowOutput.Text(output.text, output.cut)
    } ?: RowOutput.Unavailable
    response.code() == HTTP_GONE -> RowOutput.Expired.also { response.errorBody()?.close() }
    else -> RowOutput.Unavailable.also { response.errorBody()?.close() }
}

/** What the status says the agent did: all of it, in place of what is shown. */
private suspend fun reportLog(
    status: AgentStatusDto,
    onRows: suspend (List<AgentRow>, Boolean) -> Unit,
    onTasks: suspend (List<AgentTask>) -> Unit
) {
    if (status.rows.isNotEmpty()) onRows(status.rows, true)
    if (status.tasks.isNotEmpty()) onTasks(status.tasks)
}

private const val HTTP_GONE = 410
