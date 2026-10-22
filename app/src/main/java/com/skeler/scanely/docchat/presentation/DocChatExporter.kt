package com.skeler.scanely.docchat.presentation

import android.content.Context
import android.net.Uri
import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.docchat.data.AgentClient
import com.skeler.scanely.docchat.data.ChatFileRestorer
import com.skeler.scanely.docchat.data.ChatFiles
import com.skeler.scanely.docchat.data.DocFormat
import com.skeler.scanely.docchat.data.FileBody
import com.skeler.scanely.ui.viewmodel.DesignOperations
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject

private const val TAG = "DocChatExporter"

/**
 * Open, Share, Download and Save as images for a file card. Each returns what to tell the user.
 * A file this phone does not hold is fetched first ([ChatFileRestorer]).
 */
class DocChatExporter @Inject internal constructor(
    private val files: ChatFiles,
    private val restorer: ChatFileRestorer,
    private val operations: DesignOperations,
    private val agent: AgentClient
) {
    suspend fun open(context: Context, messageId: String, body: FileBody): DocChatEvent? {
        val file = restorer.ensure(messageId, body) ?: return missing(body)
        val opened = ScanExporter.openDocument(
            context,
            files.shareUri(file),
            body.format.mimeType,
            context.getString(R.string.chat_open_with)
        )
        return if (opened) null else DocChatEvent.Message(R.string.chat_no_viewer)
    }

    /**
     * Shared from the Downloads copy rather than private storage, as the designer always did: a
     * URI the receiving app can still read after this process is gone, and a copy the user keeps.
     */
    suspend fun share(context: Context, messageId: String, body: FileBody): DocChatEvent? {
        restorer.ensure(messageId, body) ?: return missing(body)
        val saved = saveToDownloads(context, body) ?: return DocChatEvent.Message(R.string.chat_save_failed)
        return if (ScanExporter.shareDocument(context, saved, body.format.mimeType)) {
            null
        } else {
            DocChatEvent.Message(R.string.chat_share_failed)
        }
    }

    suspend fun download(context: Context, messageId: String, body: FileBody): DocChatEvent {
        restorer.ensure(messageId, body) ?: return missing(body)
        return if (saveToDownloads(context, body) != null) {
            DocChatEvent.Message(R.string.chat_saved_to_downloads)
        } else {
            DocChatEvent.Message(R.string.chat_save_failed)
        }
    }

    /** Every page as its own JPEG in Pictures/Scanly, one page at a time. */
    suspend fun saveImages(context: Context, messageId: String, body: FileBody): DocChatEvent {
        val file = pagesFile(context, messageId, body)
            ?: return if (body.isOfficeAgentFile) DocChatEvent.Message(R.string.chat_images_failed) else missing(body)
        val appContext = context.applicationContext
        val base = body.name.substringBeforeLast('.')
        var saved = 0
        return try {
            val rendered = operations.forEachPage(Uri.fromFile(file)) { index, bitmap ->
                try {
                    val name = if (body.pages > 1) "${base}_${index + 1}" else base
                    saved += ScanExporter.saveImagesToGallery(appContext, listOf(bitmap), name)
                } finally {
                    bitmap.recycle()
                }
            }
            if (rendered && saved > 0) {
                DocChatEvent.Raw(context.resources.getQuantityString(R.plurals.chat_images_saved, saved, saved))
            } else {
                DocChatEvent.Message(R.string.chat_images_failed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Image export failed", e)
            DocChatEvent.Message(R.string.chat_images_failed)
        }
    }

    /** The PDF whose pages are saved. The phone renders only PDFs: an Office file's pages come from the server's PDF of it. */
    private suspend fun pagesFile(context: Context, messageId: String, body: FileBody): File? {
        val office = body.agentFileId.takeIf { body.isOfficeAgentFile }
        return if (office == null) restorer.ensure(messageId, body) else officePdf(context, office)
    }

    private val FileBody.isOfficeAgentFile: Boolean get() = format != DocFormat.PDF && agentFileId != null

    private suspend fun officePdf(context: Context, fileId: String): File? = try {
        agent.pdf(fileId)?.let { bytes ->
            File(context.cacheDir, "chat-pages").apply { mkdirs() }.resolve("$fileId.pdf").apply { writeBytes(bytes) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Office pages failed", e)
        null
    }

    private suspend fun saveToDownloads(context: Context, body: FileBody): Uri? {
        val file = local(body) ?: return null
        return try {
            // The file as it is: a Word, Excel or PowerPoint file keeps its own type and extension.
            val name = "${body.name.substringBeforeLast('.')}.${body.format.extension}"
            ScanExporter.exportFile(context, file, name, body.format.mimeType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            null
        }
    }

    private fun local(body: FileBody): File? = files.cachedFile(body.designId, body.format)

    private fun missing(body: FileBody) =
        DocChatEvent.Message(if (body.expired) R.string.chat_file_expired else R.string.chat_file_missing)
}
