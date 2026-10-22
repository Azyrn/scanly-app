package com.skeler.scanely.docchat.data

import android.content.Context
import com.skeler.scanely.R
import com.skeler.scanely.core.ai.AiResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What a delivered reply became, for the notification that announces it. */
sealed interface Delivered {
    val chatId: String
    val title: String

    data class File(override val chatId: String, override val title: String) : Delivered
    data class Failure(override val chatId: String, override val title: String, val error: AiResult.Error) : Delivered
}

/**
 * Turns a working reply into what its design produced — the one place that happens, whichever
 * got there first: the chat on screen ([DocChatRunner]) or the background watcher with the app
 * closed (DesignJobWorker). A reply that is no longer working was delivered already, and is left
 * alone, so the two never write it twice.
 */
@Singleton
class ChatJobDelivery @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: ChatRepository,
    private val files: ChatFiles
) {
    private val mutex = Mutex()

    /** [pdf] is moved into the chat's files. Null if the reply was already delivered. */
    suspend fun complete(replyId: String, designId: String, pagesBilled: Int, pdf: File): Delivered? = mutex.withLock {
        val message = repository.message(replyId) ?: return@withLock null
        val working = message.body as? WorkingBody ?: return@withLock null
        val file = files.adopt(pdf, designId, DocFormat.PDF)
        val pages = files.pageCount(file)
        files.makeThumbnail(designId, file)
        val title = repository.chat(message.chatId)?.title.orEmpty()
        repository.update(
            replyId,
            FileBody(
                designId,
                DocFormat.PDF,
                chatFileName(title, context.getString(R.string.chat_file_default_name)),
                pages,
                pagesBilled,
                steps = working.finishedSteps()
            )
        )
        repository.deleteRequest(replyId)
        Delivered.File(message.chatId, title)
    }

    /** As [complete], with the bytes the job route answered. */
    suspend fun complete(replyId: String, designId: String, pagesBilled: Int, pdf: ByteArray): Delivered? {
        val staged = File(context.cacheDir, "chat-job-$designId.pdf").apply { writeBytes(pdf) }
        return try {
            complete(replyId, designId, pagesBilled, staged)
        } finally {
            staged.delete()
        }
    }

    suspend fun fail(replyId: String, error: AiResult.Error): Delivered? = mutex.withLock {
        val message = repository.message(replyId) ?: return@withLock null
        val working = message.body as? WorkingBody ?: return@withLock null
        repository.update(replyId, error.toReplyBody(working.steps))
        Delivered.Failure(message.chatId, repository.chat(message.chatId)?.title.orEmpty(), error)
    }
}

/** A file's name from its chat's title: no path or reserved characters, never empty. */
internal fun chatFileName(title: String, fallback: String): String {
    val base = title.trimEnd('…').replace(UNSAFE_NAME, " ").trim().ifEmpty { fallback }
    return "${base.take(FILE_NAME_CHARS)}.pdf"
}

private const val FILE_NAME_CHARS = 60
private val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|\\n\\r\\t]+")

/** The steps a finished reply keeps, ending in Done. */
internal fun WorkingBody.finishedSteps(now: Long = System.currentTimeMillis()): List<StepRecord> =
    if (steps.lastOrNull()?.step == DesignStep.DONE) steps else steps + StepRecord(DesignStep.DONE, now)
