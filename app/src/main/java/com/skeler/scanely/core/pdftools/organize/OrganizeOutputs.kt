package com.skeler.scanely.core.pdftools.organize

import android.util.Log
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolOutput
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.isOutOfSpace
import java.io.File
import java.io.IOException

private const val TAG = "OrganizeJobs"

internal const val PDF_EXTENSION = ".pdf"

/** Space for an output and qpdf's own temporaries, which can briefly outgrow the output. */
internal const val SPACE_FACTOR = 1.2

/**
 * Turns a qpdf answer into the failure the user can act on, the same mapping PdfToolEngine uses:
 * a full disk only arrives as text, so the message goes through the ENOSPC check.
 */
internal fun QpdfResult.requireOk(label: String) {
    val message = when (this) {
        QpdfResult.Ok -> return
        QpdfResult.WrongPassword -> throw ToolException(ToolFailure.WrongPassword)
        is QpdfResult.Failed -> message
        is QpdfResult.BadUsage -> message
    }
    // qpdf messages name the file path and the failing object, never contents or passwords.
    Log.w(TAG, "$label failed: $message")
    throw if (IOException(message).isOutOfSpace()) {
        ToolException(ToolFailure.LowStorage)
    } else {
        ToolException(ToolFailure.Failed(message))
    }
}

/** A success that wrote nothing is still a failure; nothing empty is ever offered to share. */
internal fun File.toPdfOutput(label: String): ToolOutput {
    if (!isFile || length() == 0L) {
        Log.w(TAG, "$label reported success but wrote no output")
        throw ToolException(ToolFailure.Failed("$label wrote no output"))
    }
    return ToolOutput(this, name, ScanExporter.PDF_MIME_TYPE, length())
}

/** The display name without its extension, sanitised before any suffix goes on so ".pdf" survives the cap. */
internal fun baseName(displayName: String): String =
    ToolWorkspace.safeFileName(displayName.substringBeforeLast('.').ifBlank { displayName })

/** "01", "02" … wide enough for [count], so the parts sort in order in any file manager. */
internal fun partNumber(index: Int, count: Int): String =
    (index + 1).toString().padStart(maxOf(MIN_PART_DIGITS, count.toString().length), '0')

private const val MIN_PART_DIGITS = 2
