package com.skeler.scanely.core.pdfeditor

import java.io.File

/**
 * Tracks a saved PDF on its way to Downloads. One per [PdfEditorSession], so a signature written
 * from the tools sheet and an edit saved from the studio share it: either one's failed export is
 * what the studio's Save tries again.
 *
 * Saving applies the edits and adopts the result as the working document, which clears them. If
 * the write to Downloads then failed, nothing was staged, Save went disabled, and the edited
 * document could not be exported again. The file is remembered until it gets there.
 */
internal class ExportRetry {

    /** A saved file that has not reached Downloads yet. */
    var unexported: File? = null
        private set

    /** Runs [write] for [file]; null when it failed, in which case [file] stays [unexported]. */
    suspend fun <T : Any> export(file: File, write: suspend (File) -> T?): T? {
        val result = runCatching { write(file) }.getOrNull()
        unexported = if (result == null) file else null
        return result
    }

    /** The document it belonged to is closed; there is nothing left to export. */
    fun clear() {
        unexported = null
    }
}

/** Whether Save has something to do: staged edits, or a saved file still to export. */
internal fun canSave(isDirty: Boolean, retry: ExportRetry): Boolean = isDirty || retry.unexported != null

/** What signing tells the user once the signed file has been written, or not, to Downloads. */
internal data class SignedExport<T>(val saved: T?, val message: String)

/**
 * Writes a freshly signed [file] to Downloads through [retry]. The signed file is adopted as the
 * working document straight after, which leaves nothing staged: without [retry] remembering it, a
 * failed write left Save disabled and signing again — a second signature — as the only way out.
 */
internal suspend fun <T : Any> exportSigned(
    retry: ExportRetry,
    file: File,
    write: suspend (File) -> T?
): SignedExport<T> {
    val saved = retry.export(file, write)
    return SignedExport(
        saved,
        if (saved == null) {
            "Signed, but couldn't write to Downloads. Tap Save to try again."
        } else {
            "Signed and saved to Downloads/Scanly"
        }
    )
}
