package com.skeler.scanely.docchat.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One document chat. [preview] and [format] are denormalised from its newest message so the
 * history list is one indexed query, not a scan over every chat's messages.
 */
@Entity(tableName = "chats", indices = [Index("updatedAt")])
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Set once the user renames it; the automatic title never overwrites a chosen one. */
    val titleEdited: Boolean = false,
    val preview: String = "",
    /** Format of the newest file in the chat ("pdf"), or null before the first file. */
    val format: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** False until the server holds this version of the header. */
    val synced: Boolean = false,
    /** Deleted here, not yet on the server. Hidden from every list. */
    val deleted: Boolean = false,
    /** The server has messages for this chat that this phone has not fetched; fetched on open. */
    val needsMessages: Boolean = false
)

/**
 * A message. [body] is a [MessageBody] as JSON, so a new reply kind (DOCX, a tool result) is a
 * new serialised class rather than a schema migration. [searchText] is the plain words a search
 * matches, so a query never matches JSON keys.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["chatId", "seq"])],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val seq: Long,
    val role: String,
    val kind: String,
    val body: String,
    val searchText: String,
    val createdAt: Long,
    /** This phone's clock; the newest edit of a message wins on the server. */
    val updatedAt: Long = createdAt,
    val synced: Boolean = false
)

/**
 * What a turn sent, kept on this device only so Retry, an answered question and a turn that
 * outlived its screen can send exactly the same request again. File bytes live on disk
 * ([ChatFiles]); a row would hit the cursor window limit.
 */
@Entity(
    tableName = "requests",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
data class RequestEntity(
    /** The AI message this request answers into. */
    @PrimaryKey val replyId: String,
    val chatId: String,
    /** The user message that sent it; its attachments are stored under this id. */
    val turnId: String,
    val prompt: String,
    val sourceText: String?,
    val priorDesignId: String?,
    val templateId: String?,
    val ocrReceipt: String?,
    val hasDocument: Boolean,
    val hasPhoto: Boolean,
    /** Idempotency key: a retry of the same request inside the server's window replays, never re-bills. */
    val requestKey: String,
    val keyCreatedAt: Long,
    /** The answer to an ask, as JSON; null for a first attempt. */
    val answer: String? = null,
    /** How long reading the attached file's text took before sending; shown as its own step. */
    val ocrMillis: Long = 0
)

// One function per query, like a Retrofit interface: the count is the schema's surface, not a smell.
@Suppress("TooManyFunctions")
@Dao
interface ChatDao {
    @Query("SELECT * FROM chats WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query(
        """
        SELECT * FROM chats WHERE deleted = 0 AND (
            title LIKE :pattern ESCAPE '\' OR id IN (
                SELECT chatId FROM messages WHERE searchText LIKE :pattern ESCAPE '\'
            )
        ) ORDER BY updatedAt DESC
        """
    )
    fun searchChats(pattern: String): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observeChat(id: String): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun chat(id: String): ChatEntity?

    @Query("SELECT * FROM chats")
    suspend fun allChats(): List<ChatEntity>

    @Upsert
    suspend fun upsertChat(chat: ChatEntity)

    @Upsert
    suspend fun upsertChats(chats: List<ChatEntity>)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteChat(id: String)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY seq")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY seq")
    suspend fun messages(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE kind = :kind")
    suspend fun messagesOfKind(kind: String): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE chatId = :chatId")
    suspend fun lastSeq(chatId: String): Long

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Upsert
    suspend fun upsertMessages(messages: List<MessageEntity>)

    /** A working reply is not sent: only what it becomes is worth a write on the server. */
    @Query("SELECT * FROM messages WHERE synced = 0 AND kind != 'working' ORDER BY chatId, seq")
    suspend fun unsyncedMessages(): List<MessageEntity>

    @Query("SELECT * FROM chats WHERE synced = 0")
    suspend fun unsyncedChats(): List<ChatEntity>

    /** What sync is waiting to send; a change here schedules it. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM chats WHERE synced = 0) + " +
            "(SELECT COUNT(*) FROM messages WHERE synced = 0 AND kind != 'working')"
    )
    fun observeUnsynced(): Flow<Int>

    /** Only if nothing changed it since it was sent. */
    @Query("UPDATE chats SET synced = 1 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markChatSynced(id: String, updatedAt: Long)

    @Query("UPDATE messages SET synced = 1 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markMessageSynced(id: String, updatedAt: Long)

    @Query("UPDATE chats SET needsMessages = 0 WHERE id = :id")
    suspend fun markMessagesFetched(id: String)

    @Query("DELETE FROM chats")
    suspend fun clearChats()

    @Query("SELECT * FROM requests WHERE replyId = :replyId")
    suspend fun request(replyId: String): RequestEntity?

    @Upsert
    suspend fun upsertRequest(request: RequestEntity)

    @Query("DELETE FROM requests WHERE replyId = :replyId")
    suspend fun deleteRequest(replyId: String)

    /** Appends at the end of the chat; [build] gets the sequence number to use. */
    @Transaction
    suspend fun append(chatId: String, build: (seq: Long) -> MessageEntity): MessageEntity {
        val message = build(lastSeq(chatId) + 1)
        upsertMessage(message)
        return message
    }
}

@Database(
    entities = [ChatEntity::class, MessageEntity::class, RequestEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
