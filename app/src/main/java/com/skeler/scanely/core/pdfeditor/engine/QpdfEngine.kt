package com.skeler.scanely.core.pdfeditor.engine

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What a recipient may still do with an encrypted document. */
data class PdfPermissions(
    val printing: Boolean = true,
    val highResPrinting: Boolean = true,
    val modifying: Boolean = false,
    val extracting: Boolean = false,
    val annotating: Boolean = true,
    val fillingForms: Boolean = true,
    val assembling: Boolean = false,
    /** Extraction for screen readers. Disabling it is hostile; kept separate so it stays opt-out. */
    val accessibility: Boolean = true
)

/**
 * What one compression pass may do.
 *
 * [jpegQuality] zero leaves images untouched, the only pixel-identical setting. Higher re-encodes
 * as JPEG, but only images at least [minImageEdge] on a side: re-encoding a 32-pixel icon costs
 * quality and saves nothing, and qpdf keeps the smaller of the two encodings anyway.
 *
 * No DPI knob, deliberately — qpdf can re-encode an image but cannot resample it.
 */
data class CompressionSettings(
    val jpegQuality: Int,
    val minImageEdge: Int,
    val flateLevel: Int = MAX_FLATE_LEVEL
) {
    val touchesImages: Boolean get() = jpegQuality > 0

    companion object {
        const val MAX_FLATE_LEVEL = 9
        const val MIN_QUALITY = 10
        const val MAX_QUALITY = 95

        /** Low enough to catch a scanned page's images without chasing icons and rules. */
        const val DEFAULT_MIN_EDGE = 200
    }
}

enum class EncryptionState {
    /** Readable, not encrypted. */
    PLAIN,

    /** Encrypted, and the password given (possibly empty, for owner-only restrictions) opened it. */
    OPENED,

    /** Encrypted, and the password given didn't open it. */
    NEEDS_PASSWORD,

    /** Not readable at all: damaged, or not a PDF. */
    UNREADABLE
}

enum class CompressionLevel(val settings: CompressionSettings) {
    /** Re-packs structure only. Images are untouched, so the result is visually identical. */
    LOSSLESS(CompressionSettings(jpegQuality = 0, minImageEdge = 0)),
    BALANCED(CompressionSettings(jpegQuality = 75, minImageEdge = 400)),
    SMALLEST(CompressionSettings(jpegQuality = 45, minImageEdge = 250))
}

/**
 * Structural PDF surgery. pdfium renders and reads, qpdf rewrites — this is the rewrite half.
 *
 * Every operation is out-of-place: it reads [input] and writes a new [output]. Nothing mutates a
 * file the user still has open, which is what makes the staged edit stack in `EditSession`
 * safely discardable.
 */
@Singleton
class QpdfEngine @Inject constructor() {

    suspend fun merge(inputs: List<File>, output: File): QpdfResult = onIo {
        require(inputs.isNotEmpty()) { "merge needs at least one input" }
        Qpdf.run(
            "--empty",
            "--pages",
            *inputs.map { it.absolutePath }.toTypedArray(),
            "--",
            output.absolutePath
        )
    }

    /**
     * [spec] is qpdf page-range syntax — "1,3,5-9", "1-z", "z-1" to reverse. Deletion, extraction
     * and reordering are all the same operation: name the pages you want to keep, in order.
     */
    suspend fun selectPages(
        input: File,
        output: File,
        spec: String,
        password: String = ""
    ): QpdfResult = onIo {
        Qpdf.run(
            *passwordArgs(password),
            input.absolutePath,
            "--pages",
            ".",
            spec,
            "--",
            output.absolutePath
        )
    }

    suspend fun rotatePages(
        input: File,
        output: File,
        degrees: Int,
        spec: String,
        password: String = ""
    ): QpdfResult = onIo {
        Qpdf.run(
            *passwordArgs(password),
            "--rotate=${if (degrees >= 0) "+" else ""}$degrees:$spec",
            input.absolutePath,
            output.absolutePath
        )
    }

    /**
     * Stamps [stamp] over [input]. This is how annotations, signatures and retyped text reach the
     * page: they are rendered to a transparent overlay PDF with Android's own PdfDocument, then
     * composited here, leaving the original page objects untouched.
     */
    suspend fun overlay(
        input: File,
        stamp: File,
        output: File,
        pageSpec: String = "1-z",
        password: String = ""
    ): QpdfResult = onIo {
        Qpdf.run(
            *passwordArgs(password),
            input.absolutePath,
            "--overlay",
            stamp.absolutePath,
            "--to=$pageSpec",
            "--",
            output.absolutePath
        )
    }

    suspend fun compress(
        input: File,
        output: File,
        settings: CompressionSettings,
        password: String = ""
    ): QpdfResult = onIo {
        val imageArgs = if (!settings.touchesImages) {
            emptyArray()
        } else {
            arrayOf(
                "--optimize-images",
                "--oi-min-width=${settings.minImageEdge}",
                "--oi-min-height=${settings.minImageEdge}",
                "--oi-min-area=${settings.minImageEdge * settings.minImageEdge}",
                "--jpeg-quality=${settings.jpegQuality}"
            )
        }
        Qpdf.run(
            *passwordArgs(password),
            "--object-streams=generate",
            "--compress-streams=y",
            "--recompress-flate",
            "--compression-level=${settings.flateLevel}",
            *imageArgs,
            input.absolutePath,
            output.absolutePath
        )
    }

    suspend fun encrypt(
        input: File,
        output: File,
        userPassword: String,
        ownerPassword: String,
        permissions: PdfPermissions = PdfPermissions(),
        openPassword: String = ""
    ): QpdfResult = onIo {
        Qpdf.run(
            *passwordArgs(openPassword),
            "--encrypt",
            "--user-password=$userPassword",
            "--owner-password=$ownerPassword",
            "--bits=256",
            *permissionArgs(permissions),
            "--",
            input.absolutePath,
            output.absolutePath
        )
    }

    suspend fun decrypt(input: File, output: File, password: String): QpdfResult = onIo {
        Qpdf.run(
            "--password=$password",
            "--decrypt",
            input.absolutePath,
            output.absolutePath
        )
    }

    /**
     * [boxes] are top-left-origin page-point rectangles keyed by page index; [pageHeights] supplies
     * the flip back to PDF user space.
     */
    suspend fun setCropBoxes(
        input: File,
        output: File,
        boxes: Map<Int, Rect>,
        pageHeights: Map<Int, Float>,
        password: String = ""
    ): QpdfResult = onIo {
        val indices = boxes.keys.toIntArray()
        val flat = FloatArray(indices.size * 4)
        indices.forEachIndexed { i, pageIndex ->
            val box = boxes.getValue(pageIndex)
            val height = pageHeights[pageIndex] ?: box.bottom
            flat[i * 4] = box.left
            flat[i * 4 + 1] = height - box.bottom
            flat[i * 4 + 2] = box.right
            flat[i * 4 + 3] = height - box.top
        }
        Qpdf.toResult(
            Qpdf.nativeSetCropBoxes(
                input.absolutePath,
                output.absolutePath,
                password,
                indices,
                flat
            )
        )
    }

    suspend fun stripMetadata(
        input: File,
        output: File,
        stripXmp: Boolean = true,
        password: String = ""
    ): QpdfResult = onIo {
        Qpdf.toResult(
            Qpdf.nativeStripMetadata(
                input.absolutePath,
                output.absolutePath,
                password,
                stripXmp
            )
        )
    }

    /** The four answers [isEncrypted] folds together, for callers that must tell them apart. */
    suspend fun encryptionState(file: File, password: String = ""): EncryptionState = onIo {
        when (Qpdf.nativeEncryptionState(file.absolutePath, password)) {
            0 -> EncryptionState.PLAIN
            1 -> EncryptionState.OPENED
            Qpdf.ERR_PASSWORD -> EncryptionState.NEEDS_PASSWORD
            else -> EncryptionState.UNREADABLE
        }
    }

    /**
     * True for an encrypted file, including one [password] doesn't open; false for a readable
     * unencrypted one; null when the file couldn't be read at all — damaged, or not a PDF.
     */
    suspend fun isEncrypted(file: File, password: String = ""): Boolean? = onIo {
        when (Qpdf.nativeEncryptionState(file.absolutePath, password)) {
            0 -> false
            1, Qpdf.ERR_PASSWORD -> true
            else -> null
        }
    }

    /** Moves the cross-reference table to the front so a shared file starts rendering sooner. */
    suspend fun linearize(input: File, output: File, password: String = ""): QpdfResult = onIo {
        Qpdf.run(
            *passwordArgs(password),
            "--linearize",
            input.absolutePath,
            output.absolutePath
        )
    }

    private fun passwordArgs(password: String): Array<String> =
        if (password.isEmpty()) emptyArray() else arrayOf("--password=$password")

    private fun permissionArgs(p: PdfPermissions): Array<String> = arrayOf(
        "--print=" + when {
            !p.printing -> "none"
            p.highResPrinting -> "full"
            else -> "low"
        },
        "--modify=" + if (p.modifying) "all" else "none",
        "--extract=" + yn(p.extracting),
        "--annotate=" + yn(p.annotating),
        "--form=" + yn(p.fillingForms),
        "--assemble=" + yn(p.assembling),
        "--accessibility=" + yn(p.accessibility)
    )

    private fun yn(value: Boolean): String = if (value) "y" else "n"

    private suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
