package com.skeler.scanely.core.pdftools

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.skeler.scanely.core.common.UriNames
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "ToolWorkspace"

/** Room left over after a job's own estimate, so the rest of the phone isn't starved to zero. */
private const val SAFETY_MARGIN_BYTES = 32L * 1024 * 1024

private const val COPY_BUFFER_BYTES = 256 * 1024

/** A picked file, copied into a run's private directory. */
data class ImportedFile(val file: File, val displayName: String, val sizeBytes: Long)

/**
 * Private scratch space for tool runs: one directory per run under the cache, so a run's inputs,
 * intermediates and outputs leave together and an abandoned run can never leak into the next.
 *
 * The user's files are only ever read. Every tool works on a copy, which is what lets cancelling
 * at any moment be harmless.
 */
@Singleton
class ToolWorkspace internal constructor(
    private val context: Context,
    private val freeBytes: (File) -> Long
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context, { dir -> StatFs(dir.path).availableBytes })

    private val root = File(context.cacheDir, "pdf-tools")

    init {
        // Runs never survive the process that started them, so anything here is a leftover.
        runCatching { root.deleteRecursively() }
    }

    fun newRun(): File = File(root, "run-${System.nanoTime()}").apply { mkdirs() }

    fun release(run: File) {
        runCatching { run.deleteRecursively() }
    }

    /** Throws [ToolFailure.LowStorage] unless [bytes] more fit with the safety margin to spare. */
    fun requireSpace(bytes: Long) {
        root.mkdirs()
        if (freeBytes(root) < bytes + SAFETY_MARGIN_BYTES) {
            throw ToolException(ToolFailure.LowStorage)
        }
    }

    /**
     * Copies [uri] into [run], reporting progress as a 0..1 fraction when the provider declares a
     * size and null when it doesn't. Cancellation is checked between chunks, and a cancelled or
     * failed copy leaves no partial file behind.
     */
    suspend fun import(
        uri: Uri,
        run: File,
        onProgress: (Float?) -> Unit
    ): ImportedFile = withContext(Dispatchers.IO) {
        val name = UriNames.displayName(context, uri) ?: uri.lastPathSegment ?: DEFAULT_NAME
        val declared = declaredSize(uri)
        requireSpace(declared ?: 0L)

        val target = File(run, "in-${System.nanoTime()}-${safeFileName(name)}")
        var imported = false
        try {
            val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                ?: throw ToolException(ToolFailure.Unreadable)
            val copied = input.use { source -> copyWithProgress(source, target, declared, onProgress) }
            imported = true
            ImportedFile(target, name, copied)
        } catch (e: IOException) {
            Log.w(TAG, "Import of $uri failed", e)
            throw ToolException(if (e.isOutOfSpace()) ToolFailure.LowStorage else ToolFailure.Unreadable, e)
        } finally {
            // Failed or cancelled, the partial copy goes.
            if (!imported) target.delete()
        }
    }

    /** Streams [source] into [target], checking cancellation between chunks; returns the bytes copied. */
    private suspend fun copyWithProgress(
        source: InputStream,
        target: File,
        declared: Long?,
        onProgress: (Float?) -> Unit
    ): Long {
        var copied = 0L
        target.outputStream().use { sink ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                coroutineContext.ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                copied += read
                onProgress(progressOf(copied, declared))
            }
        }
        return copied
    }

    private fun progressOf(copied: Long, declared: Long?): Float? =
        if (declared != null && declared > 0) (copied.toFloat() / declared).coerceAtMost(1f) else null

    private fun declaredSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                    cursor.getLong(column)
                } else {
                    null
                }
            }
    }.getOrNull()?.takeIf { it >= 0 }

    companion object {
        private const val DEFAULT_NAME = "document.pdf"

        /** Keeps a display name usable as a file name: no separators, nothing a filesystem rejects. */
        fun safeFileName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(MAX_NAME_LENGTH).ifBlank { DEFAULT_NAME }

        private const val MAX_NAME_LENGTH = 120
    }
}

/** ENOSPC surfaces as an IOException whose cause (or message) names it; both are checked. */
internal fun Throwable.isOutOfSpace(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if ((current as? ErrnoException)?.errno == OsConstants.ENOSPC) return true
        if (current.message?.contains("ENOSPC") == true ||
            current.message?.contains("No space left", ignoreCase = true) == true
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
