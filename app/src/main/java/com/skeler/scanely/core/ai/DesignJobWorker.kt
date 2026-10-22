package com.skeler.scanely.core.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.skeler.scanely.MainActivity
import com.skeler.scanely.R
import com.skeler.scanely.core.network.ScanlyApi
import com.skeler.scanely.docchat.data.ChatJobDelivery
import com.skeler.scanely.docchat.data.Delivered
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * Follows a design the server builds as a background job while the app is not on screen.
 *
 * For a chat reply ([KEY_REPLY_ID]) it collects the result itself and writes it into the chat —
 * the file, or the error with the server's own "not charged" — so it is there when the user comes
 * back, even if the app was closed; the notification opens that chat. One watcher per job, so two
 * chats can each have one running. The chat on screen cancels its watcher when it got the result
 * first ([ChatJobDelivery] keeps either from writing a reply twice).
 */
class DesignJobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun scanlyApi(): ScanlyApi
        fun chatJobDelivery(): ChatJobDelivery
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val replyId = inputData.getString(KEY_REPLY_ID) ?: return Result.failure()
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val delivery = dependencies.chatJobDelivery()
        val delivered = try {
            collect(dependencies.scanlyApi(), jobId)?.let { pdf ->
                delivery.complete(replyId, pdf.designId, pdf.pagesBilled, pdf.bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FatalAiException) {
            delivery.fail(replyId, e.result)
        } catch (_: Exception) {
            // No answer worth writing down; the chat picks the job back up when it is opened.
            null
        }
        delivered?.let { notifyDelivered(applicationContext, it) }
        return Result.success()
    }

    private class JobPdf(val designId: String, val pagesBilled: Int, val bytes: ByteArray)

    /** The finished job's PDF, or a [FatalAiException] with the server's error. */
    private suspend fun collect(api: ScanlyApi, jobId: String): JobPdf? {
        val response = awaitDesignJob(DEADLINE_SECONDS, POLL_MS) { api.designJob(jobId) }
        val bytes = response.body()?.use { it.bytes() } ?: return null
        return JobPdf(
            designId = response.headers()[DESIGN_ID_HEADER] ?: jobId,
            pagesBilled = response.headers()[USAGE_PAGES_HEADER]?.toIntOrNull() ?: 0,
            bytes = bytes
        )
    }

    companion object {
        /** "Your document is ready" (or the failure, with the server's "not charged"), opening the chat. */
        fun notifyDelivered(context: Context, delivered: Delivered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            createChannel(context)
            val open = PendingIntent.getActivity(
                context,
                delivered.chatId.hashCode(),
                MainActivity.openChatIntent(context, delivered.chatId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val text = notificationText(context, delivered)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_action_pdf_file)
                .setContentTitle(delivered.title.ifEmpty { context.getString(R.string.ai_build_documents_title) })
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(delivered.chatId.hashCode(), notification)
            } catch (_: SecurityException) {
                // Permission withdrawn between the check and the post.
            }
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.chat_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = context.getString(R.string.chat_notification_channel_description) }
            )
        }

        private fun notificationText(context: Context, delivered: Delivered): String = when (delivered) {
            is Delivered.File -> context.getString(R.string.chat_notification_ready)
            is Delivered.Failure -> {
                val charged = if (delivered.error.notCharged) {
                    " " + context.getString(R.string.chat_notification_not_charged)
                } else {
                    ""
                }
                delivered.error.message + charged
            }
        }

        private const val KEY_JOB_ID = "jobId"
        private const val KEY_REPLY_ID = "replyId"
        private const val CHANNEL_ID = "designs"
        private const val POLL_MS = 10_000L
        private const val DESIGN_ID_HEADER = "X-Design-Id"
        private const val USAGE_PAGES_HEADER = "X-Usage-Pages"

        /** The server's job deadline; the worker gives up a minute after it. */
        private const val DEADLINE_SECONDS = 300

        private fun workName(jobId: String) = "design-job-$jobId"

        /** Watch [jobId] for the chat reply [replyId]. A job already watched keeps its watcher. */
        fun watchChat(context: Context, jobId: String, replyId: String) {
            val request = OneTimeWorkRequestBuilder<DesignJobWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to jobId, KEY_REPLY_ID to replyId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.KEEP, request)
        }

        fun stopWatchingChat(context: Context, jobId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(jobId))
        }
    }
}
