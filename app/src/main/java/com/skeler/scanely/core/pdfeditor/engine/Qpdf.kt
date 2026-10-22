package com.skeler.scanely.core.pdfeditor.engine

sealed interface QpdfResult {
    data object Ok : QpdfResult

    /** The file is encrypted and the supplied password (or its absence) didn't open it. */
    data object WrongPassword : QpdfResult

    data class Failed(val message: String) : QpdfResult

    /** Malformed argv — a bug in the caller, never something the user did. */
    data class BadUsage(val message: String) : QpdfResult
}

/**
 * Raw JNI surface over libqpdf. Callers should use [QpdfEngine] instead; this is internal so an
 * argv typo can't leak past the typed layer.
 */
internal object Qpdf {

    private const val OK = 0
    private const val ERR_USAGE = -1
    internal const val ERR_PASSWORD = -3

    init {
        System.loadLibrary("scanlypdf")
    }

    @JvmStatic external fun nativeRun(argv: Array<String>): Int

    @JvmStatic external fun nativeStripMetadata(
        inPath: String,
        outPath: String,
        password: String,
        stripXmp: Boolean
    ): Int

    @JvmStatic external fun nativeSetCropBoxes(
        inPath: String,
        outPath: String,
        password: String,
        pageIndices: IntArray,
        boxes: FloatArray
    ): Int

    @JvmStatic external fun nativeEncryptionState(path: String, password: String): Int

    /** JSON array of signature dictionaries; null when the document couldn't be opened. */
    @JvmStatic external fun nativeExtractSignatures(path: String, password: String): String?

    /**
     * Saves the images on pages [firstPage] until [endPage] into [outDir]: JPEGs as their own bytes,
     * plain RGB/grey images as raw 8-bit samples. Returns JSON
     * `{"images":[{"kind":"jpeg"|"raw","path",("w","h","comps")}],"ids":[...],"skipped":n}`;
     * null when the document couldn't be opened or a write failed ([nativeLastError] says why).
     * [seen] are ids from earlier calls, so an image repeated across pages is saved once.
     */
    @JvmStatic external fun nativeExtractImages(
        path: String,
        password: String,
        outDir: String,
        firstPage: Int,
        endPage: Int,
        seen: LongArray,
        maxPixels: Long
    ): String?

    @JvmStatic external fun nativeLastError(): String

    fun run(vararg args: String): QpdfResult = toResult(nativeRun(arrayOf(*args)))

    fun toResult(code: Int): QpdfResult = when (code) {
        OK -> QpdfResult.Ok
        ERR_PASSWORD -> QpdfResult.WrongPassword
        ERR_USAGE -> QpdfResult.BadUsage(nativeLastError())
        else -> QpdfResult.Failed(nativeLastError().ifBlank { "PDF operation failed." })
    }
}
