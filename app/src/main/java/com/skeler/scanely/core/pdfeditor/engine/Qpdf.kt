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
@Suppress("TooManyFunctions")
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

    /** A copy of [inPath] at [outPath] without the ToUnicode maps of glyph-id fonts; maps removed, or < 0. */
    @JvmStatic external fun nativeWithoutToUnicode(inPath: String, outPath: String, password: String): Int

    /**
     * A tagged PDF's structure tree: type names, each node's type and parent, and (page, mcid, node)
     * triples; null when the PDF has none. See [structure].
     */
    @JvmStatic external fun nativeStructure(path: String, password: String): Array<Any>?

    /** What a tagged PDF says its content is ([PdfStructure]); null for an untagged or unreadable one. */
    fun structure(path: String, password: String): PdfStructure? {
        val raw = runCatching { nativeStructure(path, password) }.getOrNull() ?: return null
        val names = (raw[0] as Array<*>).map { it as String }
        val typeOf = raw[1] as IntArray
        val marks = raw[STRUCTURE_MARKS] as IntArray
        val pages = HashMap<Int, HashMap<Int, Int>>()
        for (i in marks.indices step MARK_FIELDS) {
            val mcid = marks[i + 1]
            if (mcid in 0 until MAX_MCID) pages.getOrPut(marks[i]) { HashMap() }[mcid] = marks[i + 2]
        }
        val pageNodes = arrayOfNulls<IntArray>((pages.keys.maxOrNull() ?: -1) + 1)
        pages.forEach { (page, ids) -> pageNodes[page] = IntArray(ids.keys.max() + 1) { ids[it] ?: -1 } }
        return PdfStructure(Array(typeOf.size) { names[typeOf[it]] }, raw[2] as IntArray, pageNodes)
    }

    private const val STRUCTURE_MARKS = 3
    private const val MARK_FIELDS = 3

    /** A page's marked-content ids are small and dense; a wild one in a damaged file must not size an array. */
    private const val MAX_MCID = 1_000_000

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
