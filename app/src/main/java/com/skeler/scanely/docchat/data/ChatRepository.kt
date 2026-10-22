package com.skeler.scanely.docchat.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class Role { USER, AI }

/**
 * A decoded message. Decoding runs off the main thread, in [ChatRepository.observeMessages].
 * Immutable, so a list row is skipped when its message is equal to the one it last drew.
 */
@Immutable
data class ChatMessage(
    val id: String,
    val chatId: String,
    val seq: Long,
    val role: Role,
    val body: MessageBody,
    val createdAt: Long
)

/** The local record of every chat. Sync with the server sits on top of this (ChatSync). */
// One function per thing a chat can have done to it; splitting would only scatter the record.
@Suppress("TooManyFunctions")
@Singleton
class ChatRepository @Inject constructor(
    private val dao: ChatDao,
    private val files: ChatFiles
) {
    fun observeChats(query: String): Flow<List<ChatEntity>> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) dao.observeChats() else dao.searchChats("%${trimmed.escapeLike()}%")
    }

    fun observeChat(id: String): Flow<ChatEntity?> = dao.observeChat(id).distinctUntilChanged()

    /**
     * Decoded off the main thread, and only what changed: a step update on the working reply
     * re-decodes that one message, not the two hundred above it, and every unchanged message is the
     * same instance as before, so its row is skipped without even an equality check.
     */
    fun observeMessages(chatId: String): Flow<List<ChatMessage>> {
        val decoded = HashMap<String, Pair<MessageEntity, ChatMessage>>()
        return dao.observeMessages(chatId)
            .distinctUntilChanged()
            .map { rows ->
                val messages = rows.map { row ->
                    decoded[row.id]?.takeIf { it.first == row }?.second
                        ?: row.toMessage().also { decoded[row.id] = row to it }
                }
                if (decoded.size > rows.size) decoded.keys.retainAll(rows.mapTo(HashSet()) { it.id })
                messages
            }
            .flowOn(Dispatchers.Default)
    }

    suspend fun messages(chatId: String): List<ChatMessage> = dao.messages(chatId).map { it.toMessage() }

    suspend fun message(id: String): ChatMessage? = dao.message(id)?.toMessage()

    suspend fun chat(id: String): ChatEntity? = dao.chat(id)

    suspend fun createChat(id: String, title: String, now: Long = System.currentTimeMillis()) {
        if (dao.chat(id) != null) return
        dao.upsertChat(ChatEntity(id = id, title = title, createdAt = now, updatedAt = now))
    }

    suspend fun append(
        chatId: String,
        role: Role,
        body: MessageBody,
        id: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis()
    ): ChatMessage {
        val row = dao.append(chatId) { seq ->
            MessageEntity(
                id = id,
                chatId = chatId,
                seq = seq,
                role = role.name.lowercase(),
                kind = body.kind,
                body = body.encode(),
                searchText = body.searchText,
                createdAt = now,
                updatedAt = now
            )
        }
        touch(chatId, body, now)
        return row.toMessage()
    }

    /** Rewrites a message's body in place: a working reply becoming its file, a question its answer. */
    suspend fun update(id: String, body: MessageBody, now: Long = System.currentTimeMillis()) {
        val row = dao.message(id) ?: return
        dao.upsertMessage(
            row.copy(
                kind = body.kind,
                body = body.encode(),
                searchText = body.searchText,
                updatedAt = now,
                synced = false
            )
        )
        touch(row.chatId, body, now)
    }

    /** The server no longer keeps [designId]: its file cards in [chatId] offer a rebuild instead. */
    suspend fun markExpired(chatId: String, designId: String) {
        dao.messages(chatId).forEach { row ->
            val body = decodeBody(row.body) as? FileBody ?: return@forEach
            if (body.designId == designId && !body.expired) update(row.id, body.copy(expired = true))
        }
    }

    suspend fun rename(chatId: String, title: String, now: Long = System.currentTimeMillis()) {
        val chat = dao.chat(chatId) ?: return
        dao.upsertChat(chat.copy(title = title.trim(), titleEdited = true, updatedAt = now, synced = false))
    }

    /**
     * Hidden at once and removed with its files; the row stays as a tombstone until the server
     * has the deletion, so a sync cannot bring the chat back.
     */
    suspend fun delete(chatId: String, now: Long = System.currentTimeMillis()) {
        val chat = dao.chat(chatId) ?: return
        val messages = dao.messages(chatId)
        val designs = messages.mapNotNull { (decodeBody(it.body) as? FileBody)?.designId }
        messages.filter { it.role == "user" }.forEach { files.deleteAttachments(it.id) }
        files.deleteDesigns(designs)
        dao.upsertChat(chat.copy(deleted = true, synced = false, updatedAt = now))
    }

    suspend fun request(replyId: String): RequestEntity? = dao.request(replyId)

    suspend fun saveRequest(request: RequestEntity) = dao.upsertRequest(request)

    suspend fun deleteRequest(replyId: String) = dao.deleteRequest(replyId)

    suspend fun messagesOfKind(kind: String): List<ChatMessage> = dao.messagesOfKind(kind).map { it.toMessage() }

    private suspend fun touch(chatId: String, body: MessageBody, now: Long) {
        val chat = dao.chat(chatId) ?: return
        dao.upsertChat(
            chat.copy(
                preview = body.preview?.take(PREVIEW_CHARS) ?: chat.preview,
                format = (body as? FileBody)?.format?.extension ?: chat.format,
                updatedAt = now,
                synced = false
            )
        )
    }

    private companion object {
        const val PREVIEW_CHARS = 140
    }
}

internal fun MessageEntity.toMessage() = ChatMessage(
    id = id,
    chatId = chatId,
    seq = seq,
    role = if (role == "user") Role.USER else Role.AI,
    body = decodeBody(body),
    createdAt = createdAt
)

/** The line the history list shows for a chat whose newest message is this one. */
private val MessageBody.preview: String?
    get() = when (this) {
        is UserBody -> text
        is FileBody -> name
        is AiTextBody -> text
        is AskBody -> reason
        is ErrorBody -> message
        else -> null
    }

private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
