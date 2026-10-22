package com.skeler.scanely.docchat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import com.skeler.scanely.core.ocr.PdfRendererHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bytes behind a chat, in private storage: what a turn attached (so Retry sends the same
 * file), every file a reply produced, and a small page-1 thumbnail of each.
 *
 * Produced files are a cache, not the record: the server keeps the design and rebuilds the file
 * on request, so the oldest are pruned past [MAX_FILES_BYTES] and fetched again when opened.
 */
@Singleton
class ChatFiles @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val renderer: PdfRendererHelper
) {
    private val root get() = File(context.filesDir, ROOT)
    private val attachments get() = File(root, "attachments")
    private val files get() = File(root, FILES)
    private val thumbs get() = File(root, "thumbs")

    suspend fun writeAttachment(turnId: String, kind: AttachmentKind, base64: String) = withContext(Dispatchers.IO) {
        attachments.mkdirs()
        File(attachments, "$turnId.${kind.name.lowercase()}").writeText(base64)
    }

    suspend fun readAttachment(turnId: String, kind: AttachmentKind): String? = withContext(Dispatchers.IO) {
        File(attachments, "$turnId.${kind.name.lowercase()}").takeIf { it.isFile }?.readText()
    }

    suspend fun deleteAttachments(turnId: String) = withContext(Dispatchers.IO) {
        AttachmentKind.entries.forEach { File(attachments, "$turnId.${it.name.lowercase()}").delete() }
    }

    fun file(designId: String, format: DocFormat): File = File(files, "${safe(designId)}.${format.extension}")

    fun cachedFile(designId: String, format: DocFormat): File? = file(designId, format).takeIf { it.isFile }

    /** Takes a produced file into the chat cache. Moves within private storage when it can. */
    suspend fun adopt(source: File, designId: String, format: DocFormat): File = withContext(Dispatchers.IO) {
        files.mkdirs()
        val target = file(designId, format)
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        prune(keep = target)
        target
    }

    suspend fun write(designId: String, format: DocFormat, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        files.mkdirs()
        val target = file(designId, format)
        val partial = File(files, "${target.name}.part")
        partial.writeBytes(bytes)
        partial.renameTo(target)
        prune(keep = target)
        target
    }

    fun thumbnail(designId: String): File? = chatThumbnail(context.filesDir, designId).takeIf { it.isFile }

    /** Page 1 at list size, rendered once; a list row never rasterises a PDF itself. */
    suspend fun makeThumbnail(designId: String, pdf: File): File? = withContext(Dispatchers.IO) {
        thumbnail(designId)?.let { return@withContext it }
        val bitmap = try {
            renderer.renderPage(Uri.fromFile(pdf), 0, preview = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        writeThumbnail(chatThumbnail(context.filesDir, designId), bitmap)
    }

    suspend fun pageCount(pdf: File): Int = renderer.getPageCount(Uri.fromFile(pdf))

    /** A content URI another app can read, for Open. */
    fun shareUri(file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    suspend fun deleteDesigns(designIds: Collection<String>) = withContext(Dispatchers.IO) {
        designIds.forEach { id ->
            DocFormat.entries.forEach { file(id, it).delete() }
            chatThumbnail(context.filesDir, id).delete()
        }
    }

    /** Every chat file on this phone: another account's history must not stay behind. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        root.deleteRecursively()
    }

    private fun prune(keep: File) {
        val all = files.listFiles().orEmpty().filter { it.isFile }
        var total = all.sumOf { it.length() }
        if (total <= MAX_FILES_BYTES) return
        all.filter { it != keep }.sortedBy { it.lastModified() }.forEach { file ->
            if (total > MAX_FILES_BYTES) {
                total -= file.length()
                file.delete()
            }
        }
    }

    private companion object {
        const val FILES = "files"
        const val MAX_FILES_BYTES = 200L * 1024 * 1024
    }
}

private const val ROOT = "docchat"
private const val THUMB_WIDTH = 360
private const val THUMB_QUALITY = 82

/** Where a design's page-1 thumbnail lives; a list row loads it lazily (Coil) and never renders a PDF. */
fun chatThumbnail(filesDir: File, designId: String): File = File(filesDir, "$ROOT/thumbs/${safe(designId)}.jpg")

/**
 * Page 1 the server rendered (an Office file the phone cannot render), saved like a PDF's thumbnail.
 * Null when the bytes are not an image.
 */
suspend fun saveChatThumbnail(filesDir: File, designId: String, image: ByteArray): File? = withContext(Dispatchers.IO) {
    val target = chatThumbnail(filesDir, designId)
    if (target.isFile) return@withContext target
    val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return@withContext null
    writeThumbnail(target, bitmap)
}

/** Scales to list width and writes the JPEG; recycles [bitmap]. */
private fun writeThumbnail(target: File, bitmap: Bitmap): File = try {
    target.parentFile?.mkdirs()
    val scaled = if (bitmap.width > THUMB_WIDTH) {
        bitmap.scale(THUMB_WIDTH, bitmap.height * THUMB_WIDTH / bitmap.width)
    } else {
        bitmap
    }
    target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
    if (scaled !== bitmap) scaled.recycle()
    target
} finally {
    bitmap.recycle()
}

/** Ids come from the server; a path separator in one must never reach the file system. */
private fun safe(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' }
