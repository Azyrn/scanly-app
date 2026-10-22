package com.skeler.scanely.core.ai

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a designed PDF lands first, in private storage; the chat then moves it into its own files
 * (ChatFiles). Kept small: anything still here is only waiting to be collected.
 *
 * Every revision is a new design with its own id, so this directory would grow for the life of the
 * install; each write prunes it back to the most recent [KEEP].
 */
@Singleton
class DesignStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val directory: File get() = File(context.filesDir, DIRECTORY)

    suspend fun write(designId: String, pdf: ByteArray): Uri = withContext(Dispatchers.IO) {
        val target = directory
        target.mkdirs()

        val file = File(target, "$designId.pdf")
        file.writeBytes(pdf)

        prune(target)
        Uri.fromFile(file)
    }

    /**
     * Resolves a design URI to the file behind it, and only ever one of ours.
     *
     * The scoping is the point. `export` hands whatever this returns to the share sheet and copies
     * it into Downloads, so an unchecked `Uri.path` would make any URI that reached the design
     * state a file this app will read out and publish on the user's behalf. Today that URI is
     * always one `write` produced; this is what keeps that true of every future caller.
     */
    fun fileFor(pdf: Uri): File? {
        if (pdf.scheme != null && pdf.scheme != "file") return null
        val file = pdf.path?.let(::File)?.takeIf { it.isFile } ?: return null

        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val resolved = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return resolved.takeIf { it.parentFile == root }
    }

    private fun prune(target: File) {
        val files = target.listFiles().orEmpty()
        if (files.size <= KEEP) return
        files.sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach { it.delete() }
    }

    private companion object {
        const val DIRECTORY = "designs"
        const val KEEP = 8
    }
}
