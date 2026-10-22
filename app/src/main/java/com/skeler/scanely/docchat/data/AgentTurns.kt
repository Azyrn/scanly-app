package com.skeler.scanely.docchat.data

import android.content.Context
import android.util.Base64
import com.skeler.scanely.R
import com.skeler.scanely.core.ai.AiResult
import com.skeler.scanely.core.ai.FatalAiException
import com.skeler.scanely.core.ai.FeatureSwitches
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat turns the document agent answers (worker/src/agent-turn.ts), for [DocChatRunner]: which
 * turns those are, and running one from start or resume to its reply and files.
 */
@Singleton
class AgentTurns @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: ChatRepository,
    private val files: ChatFiles,
    private val agent: AgentClient,
    private val approvals: AgentApprovals,
    private val features: FeatureSwitches
) {
    private val lock = Mutex()
    private val liveLogChars = context.resources.getInteger(R.integer.agent_log_live_chars)
    private val keptLogChars = context.resources.getInteger(R.integer.agent_log_kept_chars)

    /**
     * Runs [request] as an agent turn if it is one: a turn already with the agent (resumed), or a
     * new one while the server offers the agent, whatever is attached. False leaves it to the
     * designer: the agent is off, or the app is talking to an older server.
     */
    suspend fun run(replyId: String, request: RequestEntity, stored: WorkingBody?, startedAt: Long): Boolean {
        val agentFile = request.priorDesignId?.let { agentFileName(request.chatId, it) }
        val agentTurn = stored?.agentTurnId != null || (features.agentEnabled.value && request.agentEligible())
        if (agentTurn) runAgentTurn(replyId, request, stored, startedAt, agentFile)
        return agentTurn
    }

    /**
     * A turn the document agent answers: it talks, asks, or builds files. The reply becomes the
     * agent's text (or its question, with suggested answers); each file it delivered is its own
     * card below it. Billing is the server's: a turn that only talks or asks bills nothing.
     */
    private suspend fun runAgentTurn(
        replyId: String,
        request: RequestEntity,
        stored: WorkingBody?,
        startedAt: Long,
        agentFile: String?
    ) {
        val reply = AgentReply(
            replyId,
            stored?.takeIf { it.agentTurnId != null }
                ?: WorkingBody(startedAt = startedAt, steps = listOf(StepRecord(DesignStep.THINKING, startedAt)))
        )
        repository.update(replyId, reply.working)
        try {
            val (turnId, deadline) = reply.working.agentTurnId?.let { it to DEFAULT_AGENT_DEADLINE_S }
                ?: startAgent(reply, request, agentFile)
            val status = reply.follow(request.chatId, turnId, deadline)
            if (complete(replyId, request.chatId, status)) AgentTurnWorker.stopWatching(context, turnId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FatalAiException) {
            fail(replyId, e.result)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            val failed = context.getString(R.string.chat_agent_failed)
            whileWorking(replyId) { working ->
                repository.update(
                    replyId,
                    ErrorBody(
                        failed,
                        retryable = true,
                        steps = working.finishedSteps(),
                        log = working.keptLog(keptLogChars),
                        agentTurnId = working.agentTurnId
                    )
                )
            }
        }
    }

    /**
     * Writes a finished turn into its reply: every file as its own card, then the agent's words.
     * The chat on screen and the background watcher ([AgentTurnWorker]) may both get here; only
     * the first, while the reply is still working, writes. True if this call wrote it.
     */
    suspend fun complete(replyId: String, chatId: String, status: AgentStatusDto): Boolean =
        whileWorking(replyId) { working ->
            // Any file the stream did not announce (it dropped, or the app was away) lands now.
            status.files.forEach { deliverAgentFile(chatId, it) }
            recordCredits(chatId, status)
            // The server's log is the whole of it when the background watcher finishes the turn.
            val log = if (status.rows.isNotEmpty()) AgentLog(status.rows, status.tasks) else working.log
            repository.update(
                replyId,
                AiTextBody(
                    agentReplyText(status),
                    status.ask?.choices.orEmpty(),
                    working.finishedSteps(),
                    log.kept(keptLogChars).takeUnless { it.isEmpty },
                    working.agentTurnId
                )
            )
            repository.deleteRequest(replyId)
        }

    /** The turn's settled credits, on its first file's card (the server records them the same way). */
    private suspend fun recordCredits(chatId: String, status: AgentStatusDto) {
        val first = status.files.firstOrNull() ?: return
        if (status.pagesBilled <= 0) return
        val message = repository.messages(chatId)
            .firstOrNull { (it.body as? FileBody)?.agentFileId == first.id } ?: return
        val body = message.body as FileBody
        if (body.credits != status.pagesBilled) repository.update(message.id, body.copy(credits = status.pagesBilled))
    }

    /** A failed turn's reply, with the server's own error and "not charged"; as [complete], once. */
    suspend fun fail(replyId: String, error: AiResult.Error): Boolean =
        whileWorking(replyId) { working ->
            repository.update(
                replyId,
                error.toReplyBody(working.finishedSteps(), working.keptLog(keptLogChars), working.agentTurnId)
            )
        }

    /** Runs [write] under the lock if [replyId] is still a working reply; true if it ran. */
    private suspend fun whileWorking(replyId: String, write: suspend (WorkingBody) -> Unit): Boolean = lock.withLock {
        val working = repository.message(replyId)?.body as? WorkingBody ?: return@withLock false
        write(working)
        true
    }

    private suspend fun delivered(chatId: String, file: AgentFileDto) = lock.withLock { deliverAgentFile(chatId, file) }

    /** Starts the turn on the server and keeps its id on the reply, so a resumed chat follows it. */
    private suspend fun startAgent(reply: AgentReply, request: RequestEntity, agentFile: String?): Pair<String, Int> {
        val about = agentFile?.let { context.getString(R.string.chat_agent_about_file, it) }
        val accepted = agent.start(
            AgentTurnRequest(
                chatId = request.chatId,
                message = about?.let { "${request.prompt}\n\n$it" } ?: request.prompt,
                history = agentHistory(request.chatId, request.turnId),
                locale = Locale.getDefault().toLanguageTag(),
                attachments = scanText(request, repository.message(request.turnId)?.body as? UserBody),
                attachmentIds = uploadAttachments(request)
            )
        )
        reply.save(reply.working.copy(agentTurnId = accepted.turnId))
        // Delivers into this reply if the app is closed before the turn ends.
        AgentTurnWorker.watch(context, accepted.turnId, reply.replyId, request.chatId, accepted.deadlineSeconds)
        return accepted.turnId to accepted.deadlineSeconds
    }

    /**
     * Uploads the turn's picture or PDF (and a photo to place) once, as the bytes they are, and
     * returns the server's ids for them. The ids are kept on the user's message, so a retry sends
     * nothing twice and later turns can name the same file. Nothing here reads the file's text:
     * the assistant looks at it itself.
     */
    private suspend fun uploadAttachments(request: RequestEntity): List<String> {
        val message = repository.message(request.turnId) ?: return emptyList()
        val body = message.body as? UserBody ?: return emptyList()
        var changed = false
        val refs = body.attachments.map { ref ->
            if (ref.id != null || ref.kind == AttachmentKind.SCAN) return@map ref
            val encoded = files.readAttachment(request.turnId, ref.kind) ?: return@map ref
            val extension = encoded.agentExtension() ?: return@map ref
            val name = "${ref.name.substringBeforeLast('.').ifBlank { "file" }}.$extension"
            changed = true
            ref.copy(id = agent.upload(request.chatId, name, Base64.decode(encoded, Base64.DEFAULT)))
        }
        if (changed) repository.update(message.id, body.copy(attachments = refs))
        return refs.mapNotNull { it.id }
    }

    /** The reply's words: the question asked, what the agent said, or a plain line when it said nothing. */
    private fun agentReplyText(status: AgentStatusDto): String =
        status.ask?.question
            ?: status.text.takeIf { it.isNotBlank() }
            ?: context.getString(
                if (status.files.isNotEmpty()) R.string.chat_agent_done else R.string.chat_agent_failed
            )

    /** One agent turn's working reply as it changes: its steps, on this device's clock. */
    private inner class AgentReply(val replyId: String, var working: WorkingBody) {
        private val seen = mutableSetOf<String>()
        private var offset: Long? = null

        /** Follows [turnId] to its end; a web turn's question to the person shows in its chat meanwhile. */
        suspend fun follow(chatId: String, turnId: String, deadline: Int): AgentStatusDto =
            try {
                agent.follow(
                    turnId,
                    deadline,
                    ::onStep,
                    ::onRows,
                    ::onTasks,
                    onApproval = { approvals.ask(chatId, turnId, it) },
                    onApprovalClosed = approvals::closed
                ) { delivered(chatId, it) }
            } finally {
                approvals.turnEnded(turnId)
            }

        suspend fun save(body: WorkingBody) {
            working = body
            // A reply the background watcher already finished is not turned back into steps.
            whileWorking(replyId) { repository.update(replyId, body) }
        }

        suspend fun onStep(id: String, at: Long) {
            val step = agentStepOf(id) ?: return
            // The stream and the status route repeat steps; and the step on show is not news,
            // except a new round of thinking.
            val repeat = working.steps.lastOrNull()?.step == step && step != DesignStep.THINKING
            if (!seen.add("$id@$at") || repeat) return
            val now = System.currentTimeMillis()
            val skew = offset ?: (now - at).also { offset = it }
            save(working.copy(steps = working.steps + StepRecord(step, (at + skew).coerceAtMost(now)), note = null))
        }

        /** What the agent did: [rows] in place of those with the same ids, or of all of them when they are all of it. */
        suspend fun onRows(rows: List<AgentRow>, replace: Boolean) {
            save(working.copy(log = working.log.merged(rows, replace).kept(liveLogChars)))
        }

        suspend fun onTasks(tasks: List<AgentTask>) {
            save(working.copy(log = working.log.copy(tasks = tasks)))
        }
    }

    /** A delivered file as its own card, once: the bytes now if they come, or on first open. */
    private suspend fun deliverAgentFile(chatId: String, file: AgentFileDto) {
        val format = DocFormat.entries.firstOrNull { it.extension == file.format } ?: return
        if (repository.messages(chatId).any { (it.body as? FileBody)?.agentFileId == file.id }) return
        val pages = fetchAgentFile(file, format)
        repository.append(
            chatId,
            Role.AI,
            FileBody(
                designId = file.id,
                format = format,
                name = file.name,
                pages = pages,
                credits = 0,
                agentFileId = file.id
            ),
            now = System.currentTimeMillis()
        )
    }

    /**
     * The file's bytes now if they come, and its page 1: rendered here for a PDF, from the server for
     * an Office file (the phone cannot render one). Returns the page count, 0 when unknown.
     */
    private suspend fun fetchAgentFile(file: AgentFileDto, format: DocFormat): Int {
        var pages = file.pages ?: 0
        try {
            agent.download(file.id)?.let { bytes ->
                val local = files.write(file.id, format, bytes)
                if (format == DocFormat.PDF) {
                    pages = files.pageCount(local)
                    files.makeThumbnail(file.id, local)
                }
            }
            if (format != DocFormat.PDF && file.thumbnail) {
                agent.thumbnail(file.id)?.let { saveChatThumbnail(context.filesDir, file.id, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // The card still lands; opening it fetches the file again (ChatFileRestorer).
        }
        return pages
    }

    /** The chat so far, as plain text for the agent: what was said, and which files it made. */
    private suspend fun agentHistory(chatId: String, beforeTurnId: String): List<AgentHistoryItem> =
        repository.messages(chatId).takeWhile { it.id != beforeTurnId }.mapNotNull { message ->
            when (val body = message.body) {
                is UserBody -> AgentHistoryItem(
                    "user",
                    body.attachments.takeIf { it.isNotEmpty() }
                        ?.let { refs -> "${body.text}\n[Attached: ${refs.joinToString { it.label() }}]" } ?: body.text
                )
                is AiTextBody -> AgentHistoryItem("assistant", body.text)
                is FileBody -> AgentHistoryItem(
                    "assistant",
                    context.getString(R.string.chat_agent_delivered, body.name)
                )
                else -> null
            }
        }.filter { it.text.isNotBlank() }.takeLast(AGENT_HISTORY_ITEMS)

    /** The name of the agent file [designId] names in this chat, or null when it is a designer file. */
    private suspend fun agentFileName(chatId: String, designId: String): String? =
        repository.messages(chatId).firstNotNullOfOrNull { message ->
            (message.body as? FileBody)?.takeIf { it.designId == designId && it.agentFileId != null }?.name
        }

    private companion object {
        const val AGENT_HISTORY_ITEMS = 20
        const val DEFAULT_AGENT_DEADLINE_S = 600
    }
}

/**
 * Whether a turn goes to the document agent while the server offers it: every turn does (words,
 * pictures, PDFs, scans, changes to an earlier file). Only a template request, which the app no
 * longer makes but an old chat may still hold, stays with the Typst designer.
 */
internal fun RequestEntity.agentEligible(): Boolean = templateId == null

/** A past scan's text ("From my scans"), which travels as a small text file named after the scan. */
private fun scanText(request: RequestEntity, message: UserBody?): List<AgentAttachmentDto> {
    val text = request.sourceText?.takeIf { it.isNotBlank() } ?: return emptyList()
    val label = message?.attachments?.firstOrNull { it.kind == AttachmentKind.SCAN }?.name
        ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
    val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return listOf(AgentAttachmentDto("${label ?: "scan"}.txt", encoded))
}

/** How a message's attachment reads in the history the agent gets: its name, and the id it can be looked at by. */
private fun AttachmentRef.label(): String = id?.let { "$name (attachment $it)" } ?: name

/** A base64 file that starts as a PDF does ("%PDF-"). */
internal fun String.isPdfBase64(): Boolean = startsWith("JVBERi0")

/** The extension the agent takes this base64 file under, told by its first bytes: PDF, PNG or JPEG. */
internal fun String.agentExtension(): String? = when {
    isPdfBase64() -> "pdf"
    startsWith("iVBORw0KGgo") -> "png"
    startsWith("/9j/") -> "jpg"
    else -> null
}

/** The agent's step ids (worker/src/agent-turn.ts AgentStep) as the chat's steps. */
internal fun agentStepOf(id: String): DesignStep? = when (id) {
    "thinking" -> DesignStep.THINKING
    "reading" -> DesignStep.READING_GUIDE
    "coding" -> DesignStep.WRITING_CODE
    "previewing" -> DesignStep.PREVIEWING
    "checking" -> DesignStep.CHECKING
    "designing" -> DesignStep.BUILDING_PDF
    "delivering" -> DesignStep.DELIVERING
    else -> null
}

/** What a working reply's log keeps on the finished (synced) reply, or null when there is none. */
private fun WorkingBody.keptLog(maxChars: Int): AgentLog? = log.kept(maxChars).takeUnless { it.isEmpty }
