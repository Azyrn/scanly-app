package com.skeler.scanely.docchat.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.skeler.scanely.core.ai.DesignJobWorker
import com.skeler.scanely.core.ai.FatalAiException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * Follows a document-agent turn while the app is not on screen, and writes its end into the chat
 * — the files and the agent's words, or the error with the server's "not charged" — so it is there
 * when the user comes back, even if the app was closed; the notification opens that chat.
 *
 * The chat on screen follows the same turn live and cancels this watcher when it got the end
 * first; either way [AgentTurns.complete] and [AgentTurns.fail] write a reply only once.
 */
class AgentTurnWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun agentClient(): AgentClient
        fun agentTurns(): AgentTurns
        fun chatRepository(): ChatRepository
    }

    override suspend fun doWork(): Result {
        val turnId = inputData.getString(KEY_TURN_ID)
        val replyId = inputData.getString(KEY_REPLY_ID)
        val chatId = inputData.getString(KEY_CHAT_ID)
        if (turnId == null || replyId == null || chatId == null) return Result.failure()
        val deadline = inputData.getInt(KEY_DEADLINE_S, DEFAULT_DEADLINE_S)
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        deliver(dependencies, turnId, replyId, chatId, deadline)?.let {
            DesignJobWorker.notifyDelivered(applicationContext, it)
        }
        return Result.success()
    }

    /** The turn's end, written into the reply; null if the chat on screen wrote it first, or it never ended. */
    private suspend fun deliver(
        dependencies: Dependencies,
        turnId: String,
        replyId: String,
        chatId: String,
        deadline: Int
    ): Delivered? {
        val turns = dependencies.agentTurns()
        return try {
            val status = dependencies.agentClient().await(turnId, deadline, POLL_MS)
            if (turns.complete(replyId, chatId, status)) Delivered.File(chatId, title(dependencies, chatId)) else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: FatalAiException) {
            if (turns.fail(replyId, e.result)) {
                Delivered.Failure(chatId, title(dependencies, chatId), e.result)
            } else {
                null
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // No end worth writing down; the chat picks the turn back up when it is opened.
            null
        }
    }

    private suspend fun title(dependencies: Dependencies, chatId: String): String =
        dependencies.chatRepository().chat(chatId)?.title.orEmpty()

    companion object {
        private const val KEY_TURN_ID = "turnId"
        private const val KEY_REPLY_ID = "replyId"
        private const val KEY_CHAT_ID = "chatId"
        private const val KEY_DEADLINE_S = "deadlineSeconds"
        private const val DEFAULT_DEADLINE_S = 600
        private const val POLL_MS = 10_000L

        private fun workName(turnId: String) = "agent-turn-$turnId"

        /** Watch [turnId] for the chat reply [replyId]. A turn already watched keeps its watcher. */
        fun watch(context: Context, turnId: String, replyId: String, chatId: String, deadlineSeconds: Int) {
            val request = OneTimeWorkRequestBuilder<AgentTurnWorker>()
                .setInputData(
                    workDataOf(
                        KEY_TURN_ID to turnId,
                        KEY_REPLY_ID to replyId,
                        KEY_CHAT_ID to chatId,
                        KEY_DEADLINE_S to deadlineSeconds
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(turnId), ExistingWorkPolicy.KEEP, request)
        }

        fun stopWatching(context: Context, turnId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(turnId))
        }
    }
}
