package com.skeler.scanely.docchat.presentation

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.skeler.scanely.core.ai.AiResult
import com.skeler.scanely.core.ai.FeatureSwitches
import com.skeler.scanely.docchat.data.AgentApprovalDto
import com.skeler.scanely.docchat.data.AgentApprovals
import com.skeler.scanely.docchat.data.AgentClient
import com.skeler.scanely.docchat.data.AskAnswer
import com.skeler.scanely.docchat.data.ChatFileRestorer
import com.skeler.scanely.docchat.data.ChatFiles
import com.skeler.scanely.docchat.data.ChatMessage
import com.skeler.scanely.docchat.data.ChatRepository
import com.skeler.scanely.docchat.data.ChatSync
import com.skeler.scanely.docchat.data.DocChatRunner
import com.skeler.scanely.docchat.data.ErrorBody
import com.skeler.scanely.docchat.data.FileBody
import com.skeler.scanely.docchat.data.RowOutput
import com.skeler.scanely.docchat.data.TurnDraft
import com.skeler.scanely.docchat.data.UserBody
import com.skeler.scanely.docchat.data.WorkingBody
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.ui.viewmodel.DesignOperations
import com.skeler.scanely.ui.viewmodel.DesignSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * One chat on screen. Everything the chat says lives in [ChatRepository] and every request runs in
 * [DocChatRunner]; this holds only what has not been sent yet — the composer — and turns the rest
 * into [DocChatUiState].
 */
// One function per thing the user can do on the screen; the composer, turns and files share its state.
@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocChatViewModel @Inject internal constructor(
    private val repository: ChatRepository,
    private val runner: DocChatRunner,
    private val operations: DesignOperations,
    private val exporter: DocChatExporter,
    private val restorer: ChatFileRestorer,
    private val sync: ChatSync,
    private val files: ChatFiles,
    private val agent: AgentClient,
    private val approvals: AgentApprovals,
    features: FeatureSwitches,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val chatId = MutableStateFlow(
        savedState.get<String>(KEY_CHAT) ?: runCatching { savedState.toRoute<Destination.DocChat>().chatId }.getOrNull()
    )
    private val composer = MutableStateFlow(ComposerUi())

    /**
     * The words being typed, apart from the screen's state: a keystroke then recomposes only the
     * field, send and the caption, not the whole chat (14.5% janky frames while typing at 120Hz).
     */
    private val text = MutableStateFlow(savedState.get<String>(KEY_TEXT).orEmpty())
    val composerText: StateFlow<String> = text.asStateFlow()
    private val scans = MutableStateFlow<List<DesignSource>>(emptyList())
    private val exporting = MutableStateFlow(false)
    private val events = Channel<DocChatEvent>(Channel.BUFFERED)
    val oneOffEvents: Flow<DocChatEvent> = events.receiveAsFlow()

    private val attachments = ComposerAttachments(viewModelScope, operations, composer)

    /** Replies seen working in this session, so only a fresh refusal opens the paywall. */
    private val seenWorking = mutableSetOf<String>()

    private val chat = chatId.flatMapLatest { id -> id?.let(repository::observeChat) ?: flowOf(null) }
    private val messages = chatId.flatMapLatest { id ->
        id?.let { repository.observeMessages(it).onEach(::watchForUpgrade) } ?: flowOf(emptyList())
    }

    /** Design ids whose page-1 thumbnail is on this phone; checked off the main thread. */
    private val thumbnails = combine(messages, restorer.restored) { messages, _ ->
        withContext(Dispatchers.IO) {
            messages.mapNotNull { (it.body as? FileBody)?.designId }.filter { files.thumbnail(it) != null }.toSet()
        }
    }

    private val base = combine(
        combine(chatId, chat, messages, ::Triple),
        runner.active,
        composer,
        scans,
        exporting
    ) { (id, chat, messages), active, composer, scans, exporting ->
        val working = messages.lastOrNull { it.body is WorkingBody && !(it.body as WorkingBody).stopped }
        DocChatUiState(
            chatId = id,
            title = chat?.title.orEmpty(),
            messages = messages,
            active = active,
            composer = composer,
            revision = revisionTarget(messages, composer),
            isLoading = id != null && chat == null,
            workingReplyId = working?.id,
            recentScans = scans,
            isExporting = exporting
        )
    }

    val state: StateFlow<DocChatUiState> = combine(base, thumbnails, features.agentEnabled) { state, thumbs, agent ->
        // The agent sees the chat's files and decides what a message is about; only the designer needs a target.
        state.copy(thumbnails = thumbs, agentAvailable = agent, revision = state.revision.takeUnless { agent })
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DocChatUiState(chatId = chatId.value))

    init {
        chatId.value?.let(::opened)
        sync.syncSoon()
        // Deleted in History (here or on another phone) while open: start a new chat instead.
        viewModelScope.launch { chat.collect { if (it?.deleted == true) newChat() } }
        viewModelScope.launch {
            scans.value = runCatching { operations.sources() }.getOrDefault(emptyList())
        }
    }

    fun setForeground(visible: Boolean) = runner.setForeground(visible)

    fun updateText(text: String) {
        savedState[KEY_TEXT] = text
        this.text.value = text
    }

    /** An example from the empty state: its wording fills the field; nothing is sent. */
    fun useExample(text: String) = updateText(text)

    /** Revise a chosen version rather than the newest. */
    fun reviseFrom(designId: String) = composer.update { it.copy(reviseDesignId = designId, startNew = false) }

    /** An expired file's request, back in the composer as a new document. */

    /** A row of the agent's log in full ([AgentClient.output]). */
    suspend fun rowOutput(turnId: String, row: String): RowOutput = agent.output(turnId, row)

    /** The question a web turn of this chat is waiting on, if any. */
    val approval: StateFlow<AgentApprovalDto?> = combine(chatId, approvals.pending) { id, pending ->
        pending?.takeIf { it.chatId == id }?.approval
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun answerApproval(approvalId: String, allow: Boolean) {
        viewModelScope.launch {
            // A failure leaves the question to time out on the server, which denies it.
            runCatching { approvals.answer(approvalId, allow) }
        }
    }

    /** Its time ran out on this screen: the server has denied it (or is about to). */
    fun approvalExpired(approvalId: String) = approvals.closed(approvalId)

    fun rebuild(fileMessageId: String) {
        val messages = state.value.messages
        val at = messages.indexOfFirst { it.id == fileMessageId }
        val turn = messages.take(at.coerceAtLeast(0)).lastOrNull { it.body is UserBody }?.body as? UserBody ?: return
        updateText(turn.text)
        composer.update { it.copy(startNew = true, reviseDesignId = null) }
    }

    /**
     * A picture from Photos or Files: a page to rebuild, or a portrait or logo to place. Nothing is
     * read until the user says which, so a placed picture never costs an OCR pass. A [rebuildPrompt]
     * means they already said (the "Rebuild from a photo" starter).
     */
    fun attachImage(uri: Uri, rebuildPrompt: String? = null) {
        if (rebuildPrompt != null) {
            if (text.value.isBlank()) updateText(rebuildPrompt)
            return attachDocument(uri)
        }
        composer.update { it.copy(pendingImage = PendingImage(uri.toString()), askRole = false, attachError = null) }
        viewModelScope.launch {
            val name = operations.displayName(uri)
            composer.update { current ->
                current.copy(
                    pendingImage = current.pendingImage?.takeIf { it.uri == uri.toString() }?.copy(name = name)
                        ?: current.pendingImage
                )
            }
        }
    }

    /**
     * The pending picture's role. [prompt] fills the field only when it is empty; after the send-time
     * question ([andSend]) the message goes as soon as the picture is ready.
     */
    fun setImageRole(rebuild: Boolean, prompt: String?, andSend: Boolean = false) {
        val pending = composer.value.pendingImage ?: return
        val uri = pending.uri.toUri()
        composer.update { it.copy(pendingImage = null, askRole = false) }
        if (prompt != null && text.value.isBlank()) updateText(prompt)
        if (rebuild) attachDocument(uri) else attachPhoto(uri)
        if (andSend) {
            val job = attachments.job(rebuild)
            viewModelScope.launch {
                job?.join()
                send()
            }
        }
    }

    fun removePendingImage() = composer.update { it.copy(pendingImage = null, askRole = false) }

    fun dismissAttachError() = composer.update { it.copy(attachError = null) }

    fun attachDocument(uri: Uri) = attachments.attachDocument(uri)

    fun removeDocument() = attachments.removeDocument()

    fun attachPhoto(uri: Uri) = attachments.attachPhoto(uri)

    fun removePhoto() = attachments.removePhoto()

    fun attachScan(source: DesignSource?) {
        if (source != null) removeDocument()
        composer.update { it.copy(scan = source) }
    }

    fun send() {
        val current = state.value
        val words = text.value
        if (!current.canSend(words)) return
        val draft = composer.value
        if (draft.pendingImage != null) {
            composer.update { it.copy(askRole = true) }
            return
        }
        val id = current.chatId ?: UUID.randomUUID().toString().also {
            savedState[KEY_CHAT] = it
            chatId.value = it
        }
        val newContent = draft.document != null || draft.scan != null
        val taken = attachments.take(draft)
        val turn = TurnDraft(
            text = words,
            document = taken.document,
            documentName = draft.document?.name,
            documentPages = draft.document?.pages ?: 1,
            photo = taken.photo,
            photoName = draft.photo?.name,
            scanText = draft.scan?.text,
            scanLabel = draft.scan?.label,
            reviseDesignId = current.revision?.designId.takeUnless { newContent }
        )
        updateText("")
        composer.value = ComposerUi()
        viewModelScope.launch { runner.send(id, turn) }
    }

    /** An answer the agent suggested: sent as is, in this chat, about the file being worked on. */
    fun sendChoice(choice: String) {
        val current = state.value
        val id = current.chatId ?: return
        if (current.workingReplyId != null || choice.isBlank()) return
        viewModelScope.launch {
            runner.send(id, TurnDraft(text = choice, reviseDesignId = current.revision?.designId))
        }
    }

    fun stop() {
        state.value.workingReplyId?.let(runner::stop)
    }

    fun retry(replyId: String) = runner.retry(replyId)

    fun answerAsk(replyId: String, answer: AskAnswer) = runner.answerAsk(replyId, answer)

    fun submitFields(replyId: String, values: Map<String, String>) = runner.submitFields(replyId, values)

    /** "+": an empty chat. The current one is already saved; nothing is lost. */
    fun newChat() {
        if (chatId.value == null) return
        removeDocument()
        removePhoto()
        savedState[KEY_CHAT] = null
        updateText("")
        composer.value = ComposerUi()
        chatId.value = null
    }

    fun openChat(id: String) {
        if (id == chatId.value) return
        savedState[KEY_CHAT] = id
        composer.value = ComposerUi()
        updateText("")
        chatId.value = id
        opened(id)
    }

    /**
     * A chat on screen: its unfinished replies picked back up, its messages fetched if the server
     * has newer ones, and its newest file fetched if this phone lacks it — only the newest, since
     * each fetch is a request on the user's allowance; older cards fetch theirs when tapped.
     */
    private fun opened(id: String) {
        runner.resume(id)
        viewModelScope.launch {
            sync.fetchMessages(id)
            val newest = repository.messages(id).lastOrNull { it.body is FileBody } ?: return@launch
            restorer.ensure(newest.id, newest.body as FileBody)
        }
    }

    fun rename(title: String) {
        val id = chatId.value ?: return
        if (title.isBlank()) return
        viewModelScope.launch { repository.rename(id, title.take(TITLE_LIMIT)) }
    }

    fun open(context: Context, messageId: String, file: FileBody) = export { exporter.open(context, messageId, file) }

    fun share(context: Context, messageId: String, file: FileBody) =
        export { exporter.share(context, messageId, file) }

    fun download(context: Context, messageId: String, file: FileBody) =
        export { exporter.download(context, messageId, file) }

    fun saveImages(context: Context, messageId: String, file: FileBody) =
        export { exporter.saveImages(context, messageId, file) }

    /** A file card with no thumbnail here: fetch it, one at a time, newest first (see [ChatFileRestorer]). */
    fun restore(messageId: String, file: FileBody) {
        viewModelScope.launch { restorer.ensure(messageId, file) }
    }

    private fun export(action: suspend () -> DocChatEvent?) {
        if (exporting.value) return
        exporting.value = true
        viewModelScope.launch {
            try {
                action()?.let { events.send(it) }
            } finally {
                exporting.value = false
            }
        }
    }

    private suspend fun watchForUpgrade(messages: List<ChatMessage>) {
        val refused = messages.filter { message ->
            val body = message.body
            if (body is WorkingBody) seenWorking += message.id
            val wasWorking = body !is WorkingBody && seenWorking.remove(message.id)
            wasWorking && body is ErrorBody && AiResult.Error(body.message, body.code).isUpgradePath
        }
        refused.forEach { message ->
            val body = message.body as ErrorBody
            events.send(DocChatEvent.Upgrade(body.message, body.code == "format_not_in_plan"))
        }
    }

    private fun revisionTarget(messages: List<ChatMessage>, composer: ComposerUi): RevisionTarget? {
        if (composer.startNew) return null
        val files = messages.mapNotNull { message -> (message.body as? FileBody)?.takeIf { !it.expired } }
        val chosen = composer.reviseDesignId?.let { id -> files.firstOrNull { it.designId == id } }
        val target = chosen ?: files.lastOrNull()
        return target?.let {
            RevisionTarget(it.designId, it.name, files.indexOf(it) + 1, isAgentFile = it.agentFileId != null)
        }
    }

    private companion object {
        const val KEY_CHAT = "chatId"
        const val KEY_TEXT = "composerText"
        const val STOP_TIMEOUT_MS = 5_000L
        const val TITLE_LIMIT = 80
    }
}
