package com.skeler.scanely.docchat.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.skeler.scanely.auth.domain.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChatSync"

/**
 * Keeps the chat cache and the Worker's copy in step. The server is the record: a reinstall or a
 * second phone pulls the history from it, and this phone's own changes are pushed as they settle.
 *
 * - Push: chats and finished messages written here (`synced = 0`), a few seconds after the last
 *   change, newest edit winning on the server. Working replies are never sent.
 * - Pull: chat headers changed since the server's cursor; a chat's messages are fetched when it is
 *   opened ([fetchMessages]), so a long history costs one request, not one per chat.
 * - The cache belongs to one account. Another account signing in (or signing out) clears it, so
 *   one person's documents never show in another's history on a shared phone.
 */
@OptIn(FlowPreview::class)
@Singleton
class ChatSync @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ChatSyncApi,
    private val dao: ChatDao,
    private val files: ChatFiles,
    private val auth: AuthRepository,
    private val legacyImport: LegacyDesignImport
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        scope.launch {
            auth.currentUser.map { it?.uid }.distinctUntilChanged().collect { uid -> onAccount(uid) }
        }
        scope.launch {
            dao.observeUnsynced().filter { it > 0 }.debounce(PUSH_DELAY_MS).collect { sync(pull = false) }
        }
    }

    /** Push what is pending and pull what changed. False when it could not reach the server. */
    suspend fun sync(pull: Boolean = true): Boolean = mutex.withLock {
        if (auth.currentUser.value == null) return@withLock false
        if (pull) _refreshing.value = true
        try {
            push()
            if (pull) pull()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed: ${e.javaClass.simpleName}")
            false
        } finally {
            _refreshing.value = false
        }
    }

    fun syncSoon() {
        scope.launch { sync() }
    }

    /** A chat's messages from the server, when its header said there are some this phone lacks. */
    suspend fun fetchMessages(chatId: String) = mutex.withLock {
        val chat = dao.chat(chatId) ?: return@withLock
        if (!chat.needsMessages) return@withLock
        try {
            mergeMessages(chatId, api.messages(chatId).messages)
            dao.markMessagesFetched(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fetching messages failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun push() {
        for (chat in dao.unsyncedChats()) {
            if (chat.deleted) {
                api.deleteChat(chat.id)
                dao.deleteChat(chat.id)
                continue
            }
            api.putChat(chat.id, RemoteChatWrite(chat.title, chat.preview, chat.format, chat.createdAt, chat.updatedAt))
            dao.markChatSynced(chat.id, chat.updatedAt)
        }
        dao.unsyncedMessages().groupBy { it.chatId }.forEach { (chatId, messages) ->
            if (dao.chat(chatId)?.deleted != false) return@forEach
            messages.chunked(BATCH).forEach { batch ->
                api.putMessages(chatId, RemoteMessagesWrite(batch.map { it.toRemote() }))
                batch.forEach { dao.markMessageSynced(it.id, it.updatedAt) }
            }
        }
    }

    private suspend fun pull() {
        var cursor = prefs.getLong(KEY_CURSOR, 0)
        do {
            val page = api.chats(cursor)
            page.chats.forEach { remote -> mergeChat(remote) }
            cursor = page.cursor
            prefs.edit { putLong(KEY_CURSOR, cursor) }
        } while (page.more && page.chats.isNotEmpty())
    }

    private suspend fun mergeChat(remote: RemoteChat) {
        val local = dao.chat(remote.id)
        if (remote.deleted) {
            if (local != null) {
                files.deleteDesigns(dao.messages(remote.id).mapNotNull { (decodeBody(it.body) as? FileBody)?.designId })
                dao.deleteChat(remote.id)
            }
            return
        }
        if (!takesRemoteChat(local, remote)) return
        dao.upsertChat(
            ChatEntity(
                id = remote.id,
                title = remote.title,
                titleEdited = local?.titleEdited ?: true,
                preview = remote.preview,
                format = remote.format,
                createdAt = remote.createdAt,
                updatedAt = remote.updatedAt,
                synced = true,
                needsMessages = true
            )
        )
    }

    private suspend fun mergeMessages(chatId: String, remote: List<RemoteMessage>) {
        val rows = remote.mapNotNull { message ->
            if (takesRemoteMessage(dao.message(message.id), message, chatId)) message.toEntity(chatId) else null
        }
        dao.upsertMessages(rows)
    }

    /** The cache is one account's. Clearing it is safe: the server holds everything that was sent. */
    private suspend fun onAccount(uid: String?) = mutex.withLock {
        val owner = prefs.getString(KEY_OWNER, null)
        if (owner == uid) return@withLock
        if (owner != null || uid == null) {
            dao.clearChats()
            files.clearAll()
        }
        prefs.edit {
            putString(KEY_OWNER, uid)
            putLong(KEY_CURSOR, 0)
        }
        if (uid != null) {
            // The old designer's last workspace becomes this account's first chat, once.
            legacyImport.run()
            scope.launch { sync() }
        }
    }

    private companion object {
        const val PREFS = "doc_chat_sync"
        const val KEY_OWNER = "owner"
        const val KEY_CURSOR = "cursor"
        const val BATCH = 50
        const val PUSH_DELAY_MS = 3_000L
    }
}

/** A local edit not yet sent is kept only if it is newer; it is pushed on the next sync. */
internal fun takesRemoteChat(local: ChatEntity?, remote: RemoteChat): Boolean =
    local == null || local.synced || remote.updatedAt >= local.updatedAt

/** As [takesRemoteChat]; and a message id is never moved from the chat it belongs to here. */
internal fun takesRemoteMessage(local: MessageEntity?, remote: RemoteMessage, chatId: String): Boolean =
    local == null || (local.chatId == chatId && (local.synced || remote.updatedAt > local.updatedAt))

private fun MessageEntity.toRemote() = RemoteMessage(id, seq, role, kind, body, createdAt, updatedAt)

private fun RemoteMessage.toEntity(chatId: String): MessageEntity {
    val decoded = decodeBody(body)
    return MessageEntity(
        id = id,
        chatId = chatId,
        seq = seq,
        role = if (role == "user") "user" else "ai",
        kind = decoded.kind,
        body = body,
        searchText = decoded.searchText,
        createdAt = createdAt,
        updatedAt = updatedAt,
        synced = true
    )
}
