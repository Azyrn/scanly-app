package com.skeler.scanely.docchat.data

import android.content.Context
import com.skeler.scanely.R
import com.skeler.scanely.core.ai.AiResult
import com.skeler.scanely.core.ai.AiStage
import com.skeler.scanely.core.ai.DesignAsk
import com.skeler.scanely.core.ai.DesignEvent
import com.skeler.scanely.core.ai.DesignIntakeAnswer
import com.skeler.scanely.core.ai.DesignJobWorker
import com.skeler.scanely.core.ai.DesignResult
import com.skeler.scanely.core.ai.DesignStore
import com.skeler.scanely.ui.viewmodel.ChatDesignRequest
import com.skeler.scanely.ui.viewmodel.DesignOperations
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the composer sends: the words, and whatever was attached to them. */
data class TurnDraft(
    val text: String,
    /** A photo (normalised JPEG) or PDF to rebuild, base64. The assistant reads it itself. */
    val document: String? = null,
    val documentName: String? = null,
    val documentPages: Int = 1,
    /** A picture to place, base64 JPEG. */
    val photo: String? = null,
    val photoName: String? = null,
    /** A past scan's text and label. */
    val scanText: String? = null,
    val scanLabel: String? = null,
    /** The file this message revises; null starts a new document. */
    val reviseDesignId: String? = null
)

@Serializable
internal data class SavedAnswer(
    val force: Boolean = false,
    val intakeToken: String? = null,
    val fields: Map<String, String> = emptyMap()
)

/**
 * Runs every turn of every chat, outside any screen: a design keeps going when the user opens
 * History or leaves, and its reply lands in the chat either way. All state is in [ChatRepository];
 * screens only observe it. [active] is what is running in this process right now.
 *
 * Billing rules are the designer's, unchanged: one idempotency key per user action (a retry
 * inside the server's window replays instead of billing twice), and a fresh key for an answered
 * question, which the server released.
 */
@Singleton
// Each public function is one user action on a turn; the private ones are the turn's lifecycle.
@Suppress("TooManyFunctions")
class DocChatRunner @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: ChatRepository,
    private val files: ChatFiles,
    private val operations: DesignOperations,
    private val designStore: DesignStore,
    private val delivery: ChatJobDelivery,
    private val agentTurns: AgentTurns
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()
    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()
    private val foreground = MutableStateFlow(true)

    fun setForeground(visible: Boolean) {
        foreground.value = visible
    }

    /** Starts a turn: the user's message, the request behind it, and a working reply. */
    suspend fun send(chatId: String, draft: TurnDraft): String {
        val now = System.currentTimeMillis()
        repository.createChat(chatId, chatTitle(draft), now)
        val turn = repository.append(chatId, Role.USER, draft.toUserBody(), now = now)
        draft.document?.let { files.writeAttachment(turn.id, AttachmentKind.DOCUMENT, it) }
        draft.photo?.let { files.writeAttachment(turn.id, AttachmentKind.PHOTO, it) }
        val reply = repository.append(chatId, Role.AI, WorkingBody(startedAt = now), now = now)
        repository.saveRequest(
            RequestEntity(
                replyId = reply.id,
                chatId = chatId,
                turnId = turn.id,
                prompt = draft.text.trim(),
                sourceText = draft.scanText?.takeIf { draft.document == null }?.trim(),
                priorDesignId = draft.reviseDesignId,
                templateId = null,
                ocrReceipt = null,
                hasDocument = draft.document != null,
                hasPhoto = draft.photo != null,
                requestKey = UUID.randomUUID().toString(),
                keyCreatedAt = now
            )
        )
        launch(reply.id)
        return reply.id
    }

    /** Sends a failed or stopped turn again, under the same key while the server still holds it. */
    fun retry(replyId: String) {
        scope.launch {
            val request = repository.request(replyId) ?: return@launch
            val now = System.currentTimeMillis()
            if (now - request.keyCreatedAt >= KEY_WINDOW_MS) {
                repository.saveRequest(request.copy(requestKey = UUID.randomUUID().toString(), keyCreatedAt = now))
            }
            repository.update(replyId, WorkingBody(startedAt = now))
            launch(replyId)
        }
    }

    fun answerAsk(replyId: String, answer: AskAnswer) {
        scope.launch {
            val message = repository.message(replyId) ?: return@launch
            val ask = message.body as? AskBody ?: return@launch
            if (ask.answer != null) return@launch
            repository.update(replyId, ask.copy(answer = answer))
            val request = repository.request(replyId) ?: return@launch
            when (answer) {
                AskAnswer.CONTINUE -> follow(request, SavedAnswer(force = true, intakeToken = ask.intakeToken))
                // Old chats only: the app no longer offers templates, so this answer is never given now.
                AskAnswer.USE_TEMPLATE, AskAnswer.DISMISS -> Unit
            }
        }
    }

    fun submitFields(replyId: String, values: Map<String, String>) {
        scope.launch {
            val message = repository.message(replyId) ?: return@launch
            val ask = message.body as? FieldsBody ?: return@launch
            if (ask.submitted != null) return@launch
            val filled = values.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
            if (ask.fields.any { it.id !in filled }) return@launch
            repository.update(replyId, ask.copy(submitted = filled))
            val request = repository.request(replyId) ?: return@launch
            val previous = request.answer?.let { messageJson.decodeFromString(SavedAnswer.serializer(), it) }
                ?: SavedAnswer()
            follow(request, previous.copy(intakeToken = ask.intakeToken, fields = previous.fields + filled))
        }
    }

    /**
     * Stops waiting. Nothing the server already started is cancelled — a background job's file
     * still arrives in the chat; an inline request can be collected by Retry under the same key.
     */
    fun stop(replyId: String) {
        jobs.remove(replyId)?.cancel()
        publishActive()
        scope.launch {
            val working = repository.message(replyId)?.body as? WorkingBody ?: return@launch
            if (working.jobId != null || working.agentTurnId != null) {
                repository.update(replyId, working.copy(stopped = true, background = true))
            } else {
                repository.update(
                    replyId,
                    ErrorBody(context.getString(R.string.chat_stopped_waiting), retryable = true, code = STOPPED)
                )
            }
        }
    }

    /** A chat opened with a reply still working and nothing running it here: pick it back up. */
    fun resume(chatId: String) {
        scope.launch {
            // A stopped wait on a job is followed again too: its file still belongs in the chat.
            repository.messages(chatId)
                .filter { message ->
                    val working = message.body as? WorkingBody ?: return@filter false
                    message.id !in jobs && (!working.stopped || working.jobId != null || working.agentTurnId != null)
                }
                .forEach { launch(it.id) }
        }
    }

    /** An answered question continues the same chat as a new reply, under a new key. */
    private suspend fun follow(request: RequestEntity, answer: SavedAnswer?) {
        val now = System.currentTimeMillis()
        val reply = repository.append(request.chatId, Role.AI, WorkingBody(startedAt = now), now = now)
        repository.saveRequest(
            request.copy(
                replyId = reply.id,
                requestKey = UUID.randomUUID().toString(),
                keyCreatedAt = now,
                answer = answer?.let { messageJson.encodeToString(SavedAnswer.serializer(), it) }
            )
        )
        launch(reply.id)
    }

    private fun launch(replyId: String) {
        synchronized(jobs) {
            if (jobs[replyId]?.isActive == true) return
            val job = scope.launch { execute(replyId) }
            jobs[replyId] = job
            job.invokeOnCompletion {
                synchronized(jobs) { if (jobs[replyId] === job) jobs.remove(replyId) }
                publishActive()
            }
        }
        publishActive()
    }

    private fun publishActive() {
        _active.value = synchronized(jobs) { jobs.keys.toSet() }
    }

    /** One turn's inputs, read once, and its working reply as it changes. */
    private class Turn(
        val replyId: String,
        val request: RequestEntity,
        val document: String?,
        val photo: String?,
        val answer: DesignIntakeAnswer?,
        var working: WorkingBody
    ) {
        /** Server clock to this device's: set from the first step, when it is received. */
        var offset: Long? = null

        /** Polling repeats the whole list; a step is recorded once. */
        val seen = mutableSetOf<String>()
    }

    private suspend fun execute(replyId: String) {
        val request = repository.request(replyId) ?: run {
            // Its request is not on this phone (an older install, cleared data): it cannot be resent.
            repository.update(replyId, ErrorBody(context.getString(R.string.chat_interrupted), retryable = false))
            return
        }
        val stored = repository.message(replyId)?.body as? WorkingBody
        val startedAt = stored?.startedAt ?: System.currentTimeMillis()
        if (agentTurns.run(replyId, request, stored, startedAt)) {
            refreshQuota()
        } else {
            runDesignTurn(replyId, request, startedAt)
        }
    }

    /** A turn the Typst designer answers, as every chat turn was before the agent. */
    private suspend fun runDesignTurn(replyId: String, request: RequestEntity, startedAt: Long) {
        val turn = Turn(
            replyId = replyId,
            request = request,
            document = files.attachment(request, AttachmentKind.DOCUMENT),
            photo = files.attachment(request, AttachmentKind.PHOTO),
            answer = request.answer
                ?.let { messageJson.decodeFromString(SavedAnswer.serializer(), it) }
                ?.let { DesignIntakeAnswer(it.force, it.intakeToken, it.fields) },
            working = WorkingBody(startedAt = startedAt, steps = listOf(StepRecord(DesignStep.PLANNING, startedAt)))
        )
        repository.update(replyId, turn.working)
        try {
            if (runOnce(turn, mayRecover = true)) return
            // Android cut background networking; the same key goes again once the app is back.
            repository.update(replyId, turn.working.copy(note = context.getString(R.string.chat_reconnecting)))
            foreground.first { it }
            runOnce(turn, mayRecover = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            repository.update(replyId, ErrorBody(context.getString(R.string.chat_design_failed), retryable = true))
        }
    }

    /** Sends the turn once. False when it should be sent again once the app is in the foreground. */
    private suspend fun runOnce(turn: Turn, mayRecover: Boolean): Boolean {
        val request = turn.request
        var recover = false
        operations.designForChat(
            ChatDesignRequest(
                request.prompt, request.sourceText, request.priorDesignId, turn.photo, turn.document,
                request.requestKey, request.templateId, turn.answer, request.ocrReceipt
            )
        ).collect { event ->
            when (event) {
                is DesignEvent.Stage -> turn.update(turn.working.onStage(event, coarse = turn.offset == null))
                is DesignEvent.Step -> turn.onServerStep(event)
                is DesignEvent.Background -> {
                    // A rebuild takes minutes; a text design is followed here and only noted if the app leaves.
                    turn.update(turn.working.copy(jobId = event.jobId, background = request.hasDocument))
                    // Delivers into this reply if the app is closed before the job ends.
                    DesignJobWorker.watchChat(context, event.jobId, turn.replyId)
                }
                is DesignEvent.Finished -> {
                    val failure = event.result as? DesignResult.Failure
                    recover = mayRecover && failure?.canRecoverOnForeground == true && !foreground.value
                    if (!recover) finish(turn, event.result)
                }
            }
        }
        return !recover
    }

    private suspend fun Turn.onServerStep(event: DesignEvent.Step) {
        val step = designStepOf(event.id) ?: return
        if (!seen.add("${event.id}@${event.at}")) return
        val now = System.currentTimeMillis()
        val skew = offset ?: (now - event.at).also { offset = it }
        val at = (event.at + skew).coerceAtMost(now)
        update(working.copy(steps = working.steps + StepRecord(step, at), note = null))
    }

    private suspend fun Turn.update(body: WorkingBody) {
        working = body
        repository.update(replyId, body)
    }

    private suspend fun finish(turn: Turn, result: DesignResult) {
        val replyId = turn.replyId
        // Seen here, on screen: the background watcher has nothing to add.
        if (foreground.value) turn.working.jobId?.let { DesignJobWorker.stopWatchingChat(context, it) }
        when (result) {
            is DesignResult.Success -> {
                val source = designStore.fileFor(result.pdf)
                if (source == null) {
                    delivery.fail(replyId, AiResult.Error(context.getString(R.string.chat_design_failed)))
                } else {
                    delivery.complete(replyId, result.designId, result.pagesBilled, source)
                }
            }
            is DesignResult.Failure -> {
                delivery.fail(replyId, result.error)
                // A revision of a design the server let go: its card says so instead of offering edits.
                val prior = turn.request.priorDesignId
                if (result.error.code == DESIGN_EXPIRED && prior != null) {
                    repository.markExpired(turn.request.chatId, prior)
                }
            }
        }
        refreshQuota()
    }

    private suspend fun refreshQuota() {
        try {
            operations.refreshQuota()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The reply is in the chat; a stale balance corrects itself on the next refresh.
        }
    }

    private fun chatTitle(draft: TurnDraft): String {
        val words = draft.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val title = words ?: draft.documentName ?: draft.scanLabel
        return title?.let { if (it.length <= TITLE_CHARS) it else it.take(TITLE_CHARS).trimEnd() + "…" }
            ?: context.getString(R.string.chat_untitled)
    }

    private companion object {
        const val STOPPED = "stopped"
        const val DESIGN_EXPIRED = "design_expired"
        const val TITLE_CHARS = 48

        /**
         * Just under the server's 24-hour idempotency window: a retry inside it is the same user
         * action and replays an already-finished request instead of building and billing it again.
         */
        const val KEY_WINDOW_MS = 23 * 60 * 60 * 1000L
    }
}

/**
 * A failed design as the reply the chat shows: a question (409 / 422, nothing billed), or an
 * error that keeps the server's own "not charged". The paywall's refusals are not retried.
 */
internal fun AiResult.Error.toReplyBody(
    steps: List<StepRecord> = emptyList(),
    log: AgentLog? = null,
    agentTurnId: String? = null
): MessageBody = when (val asked = ask) {
    is DesignAsk.Mismatch -> AskBody(asked.reason, asked.suggestedTemplate, asked.intakeToken)
    is DesignAsk.MissingFields -> FieldsBody(asked.fields.map { FieldRef(it.id, it.label) }, asked.intakeToken)
    null -> ErrorBody(
        message,
        notCharged,
        retryable = !isUpgradePath,
        code = code,
        steps = steps,
        log = log,
        agentTurnId = agentTurnId
    )
}

/** The Worker's step ids (design-pipeline.ts DesignStepId) as the chat's steps. */
internal fun designStepOf(id: String): DesignStep? = when (id) {
    "planning" -> DesignStep.PLANNING
    "writing" -> DesignStep.WRITING
    "building" -> DesignStep.BUILDING_PDF
    "checking" -> DesignStep.CHECKING
    "fixing" -> DesignStep.FIXING_LAYOUT
    "done" -> DesignStep.DONE
    else -> null
}

private suspend fun ChatFiles.attachment(request: RequestEntity, kind: AttachmentKind): String? {
    val attached = if (kind == AttachmentKind.DOCUMENT) request.hasDocument else request.hasPhoto
    return if (attached) readAttachment(request.turnId, kind) else null
}

private fun TurnDraft.toUserBody() = UserBody(
    text = text.trim(),
    attachments = listOfNotNull(
        documentName?.takeIf { document != null }?.let { AttachmentRef(AttachmentKind.DOCUMENT, it, documentPages) },
        photoName?.takeIf { photo != null }?.let { AttachmentRef(AttachmentKind.PHOTO, it) },
        scanLabel?.takeIf { scanText != null }?.let { AttachmentRef(AttachmentKind.SCAN, it) }
    )
)

/**
 * The client's own stages. Once the server reports steps they only carry a note (a retry count);
 * before that — and on the user's own endpoint, which has no server steps — waiting on the model
 * reads as writing.
 */
private fun WorkingBody.onStage(event: DesignEvent.Stage, coarse: Boolean): WorkingBody {
    val step = DesignStep.WRITING.takeIf { coarse && event.stage == AiStage.GENERATING && event.message == null }
    val steps = if (step == null || steps.lastOrNull()?.step == step) {
        steps
    } else {
        steps + StepRecord(step, System.currentTimeMillis())
    }
    return copy(steps = steps, note = event.message)
}
