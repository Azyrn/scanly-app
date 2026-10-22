package com.skeler.scanely.docchat.presentation

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.skeler.scanely.docchat.data.ChatMessage
import com.skeler.scanely.docchat.data.FileBody
import com.skeler.scanely.ui.viewmodel.DesignSource

/** A file attached to the next message, read and normalised on the phone. The assistant reads it itself. */
@Immutable
data class AttachedDocument(
    val name: String,
    val pages: Int = 1,
    /** Reading the file itself: its type, size and pages. */
    val isReading: Boolean = true,
    /** Where it was picked from, for the composer's thumbnail. */
    val uri: String? = null
)

@Immutable
data class AttachedPhoto(val name: String, val isPreparing: Boolean = true, val uri: String? = null)

/** An image picked from Photos or Files whose role the user has not chosen yet. */
@Immutable
data class PendingImage(val uri: String, val name: String = "")

/** Why the last pick was refused, shown as a chip in the composer until dismissed or replaced. */
@Immutable
data class AttachError(@param:StringRes val text: Int, val args: List<Any> = emptyList())

@Immutable
data class ComposerUi(
    val document: AttachedDocument? = null,
    val photo: AttachedPhoto? = null,
    val scan: DesignSource? = null,
    /** The designer only: the next message starts a new document (an expired file's request, sent again). */
    val startNew: Boolean = false,
    /** The file a revision is based on, when the user picked one other than the newest. */
    val reviseDesignId: String? = null,
    /** Rebuild it, or place it on the page: asked with chips, and again on send if still open. */
    val pendingImage: PendingImage? = null,
    /** Send was tapped with [pendingImage] still open: the composer asks before anything is sent. */
    val askRole: Boolean = false,
    val attachError: AttachError? = null
) {
    val hasAttachment: Boolean get() = document != null || photo != null || scan != null || pendingImage != null
    val isPreparing: Boolean
        get() = document?.isReading == true || photo?.isPreparing == true
}

/** The designer only: the file a new message would revise. Null while the agent answers. */
@Immutable
data class RevisionTarget(val designId: String, val name: String, val version: Int, val isAgentFile: Boolean = false)

@Immutable
data class DocChatUiState(
    val chatId: String? = null,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    /** Ids of replies being worked on by this process right now. */
    val active: Set<String> = emptySet(),
    val composer: ComposerUi = ComposerUi(),
    val revision: RevisionTarget? = null,
    val isLoading: Boolean = false,
    /** A turn of this chat is still working; the send button becomes Stop. */
    val workingReplyId: String? = null,
    val recentScans: List<DesignSource> = emptyList(),
    val isExporting: Boolean = false,
    /** Design ids with a page-1 thumbnail on this phone; the rest show their format icon. */
    val thumbnails: Set<String> = emptySet(),
    /** The server offers the document agent (FeatureSwitches.agentEnabled). */
    val agentAvailable: Boolean = false
) {
    /** The next message goes to the agent (AgentTurns.agentEligible), whatever is attached. */
    val nextGoesToAgent: Boolean get() = agentAvailable

    val isEmpty: Boolean get() = messages.isEmpty() && !isLoading
    val hasManyFiles: Boolean get() = messages.count { it.body is FileBody } > 1

    /** Whether [text] (the composer's words, kept apart from this state) can be sent now. */
    fun canSend(text: String): Boolean = workingReplyId == null && !composer.isPreparing &&
        text.isNotBlank()
}

/** One-off things the screen shows once: a snackbar line, or the paywall with the server's reason. */
sealed interface DocChatEvent {
    data class Message(@param:StringRes val text: Int, val args: List<Any> = emptyList()) : DocChatEvent
    data class Raw(val text: String) : DocChatEvent
    data class Upgrade(val reason: String?, val isFeatureLock: Boolean) : DocChatEvent
}
