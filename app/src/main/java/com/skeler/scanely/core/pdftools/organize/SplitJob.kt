package com.skeler.scanely.core.pdftools.organize

import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.Qpdf
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolOutput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "SplitJob"

/** What Split makes of one document. Ranges are already validated against its page count. */
sealed interface SplitMode {
    /** One file per range, in the order typed. */
    data class ByRanges(val ranges: List<PageRange>) : SplitMode

    /** A file for every [pages] pages; the last one takes what is left. */
    data class EveryN(val pages: Int) : SplitMode

    /** One file holding the named pages, in the order typed. */
    data class Extract(val ranges: List<PageRange>) : SplitMode

    /** One file without the named pages; naming every page is refused. */
    data class Delete(val ranges: List<PageRange>) : SplitMode

    /** Consecutive pages grouped into files of at most [maxBytes] each, where a page allows it. */
    data class MaxSize(val maxBytes: Long) : SplitMode
}

/**
 * Splits one PDF into new ones. Every mode is qpdf page selection on the original, so pages keep
 * their content byte for byte, and a locked document's outputs stay locked with the same password
 * — splitting is not a way around a password.
 *
 * Every file this job creates is tracked from the moment it is named, and all of them are deleted
 * unless the whole run succeeds: a half-split document is never offered as a result.
 */
@Singleton
class SplitJob @Inject constructor(
    private val qpdf: QpdfEngine,
    private val workspace: ToolWorkspace
) {

    suspend fun split(
        document: ToolDocument,
        mode: SplitMode,
        run: File,
        onProgress: (Float?) -> Unit
    ): ToolResult {
        validate(document, mode)
        val created = mutableListOf<File>()
        var delivered = false
        try {
            val result = when (mode) {
                is SplitMode.ByRanges -> byRanges(document, mode.ranges, run, created, onProgress)
                is SplitMode.EveryN -> everyN(document, mode.pages, run, created, onProgress)
                is SplitMode.Extract -> single(document, mode.ranges, SUFFIX_EXTRACTED, run, created, onProgress)
                is SplitMode.Delete -> {
                    val removed = PageRanges.pagesOf(mode.ranges)
                    val kept = PageRanges.compact((1..document.pageCount).filterNot { it in removed })
                    single(document, kept, SUFFIX_DELETED, run, created, onProgress)
                }
                is SplitMode.MaxSize -> bySize(document, mode.maxBytes, run, created, onProgress)
            }
            delivered = true
            return result
        } finally {
            if (!delivered) created.forEach { it.deleteRecursively() }
        }
    }

    /** The options screen validates too; this is the guard for callers that skipped it. */
    private fun validate(document: ToolDocument, mode: SplitMode) {
        val ranges = rangesOf(mode)
        require(ranges.all { it.last <= document.pageCount }) { "range past the last page" }
        if (mode !is SplitMode.EveryN && mode !is SplitMode.MaxSize) {
            require(ranges.isNotEmpty()) { "no pages named" }
        }
        if (mode is SplitMode.Delete && PageRanges.pagesOf(ranges).size >= document.pageCount) {
            // Nothing would be left; refusing is kinder than an empty document.
            throw ToolException(ToolFailure.Failed("delete would remove every page"))
        }
    }

    /** The ranges [mode] names; checks the modes that name none instead. */
    private fun rangesOf(mode: SplitMode): List<PageRange> = when (mode) {
        is SplitMode.ByRanges -> mode.ranges
        is SplitMode.Extract -> mode.ranges
        is SplitMode.Delete -> mode.ranges
        is SplitMode.EveryN -> {
            require(mode.pages >= 1) { "pages per file must be at least 1" }
            emptyList()
        }
        is SplitMode.MaxSize -> {
            require(mode.maxBytes > 0) { "size limit must be positive" }
            emptyList()
        }
    }

    private suspend fun byRanges(
        document: ToolDocument,
        ranges: List<PageRange>,
        run: File,
        created: MutableList<File>,
        onProgress: (Float?) -> Unit
    ): ToolResult {
        workspace.requireSpace(estimate(document, ranges.sumOf { it.size }))
        val base = baseName(document.displayName)
        val outputs = ranges.mapIndexed { index, range ->
            coroutineContext.ensureActive()
            onProgress(index.toFloat() / ranges.size)
            val label = if (range.first == range.last) "_page_${range.first}" else "_pages_${range.spec}"
            val file = uniqueFile(run, base + label)
            created += file
            select(document, PageRanges.spec(listOf(range)), file)
        }
        onProgress(1f)
        return ToolResult(outputs, document.sizeBytes)
    }

    private suspend fun single(
        document: ToolDocument,
        ranges: List<PageRange>,
        suffix: String,
        run: File,
        created: MutableList<File>,
        onProgress: (Float?) -> Unit
    ): ToolResult {
        workspace.requireSpace(estimate(document, ranges.sumOf { it.size }))
        onProgress(null)
        val file = uniqueFile(run, baseName(document.displayName) + suffix)
        created += file
        val output = select(document, PageRanges.spec(ranges), file)
        onProgress(1f)
        return ToolResult(listOf(output), document.sizeBytes)
    }

    /**
     * qpdf's own `--split-pages` writes every part in one pass over the document, far cheaper than
     * one page selection per part on a long file. It names parts after their page numbers, so it
     * writes into a private folder and the parts are renamed into the run in page order.
     */
    private suspend fun everyN(
        document: ToolDocument,
        pagesPerFile: Int,
        run: File,
        created: MutableList<File>,
        onProgress: (Float?) -> Unit
    ): ToolResult {
        workspace.requireSpace(estimate(document, document.pageCount))
        val staging = File(run, ".split-${System.nanoTime()}").apply { mkdirs() }
        created += staging
        try {
            onProgress(null)
            val pattern = File(staging, "%d$PDF_EXTENSION").absolutePath
            val result = withContext(Dispatchers.IO) {
                Qpdf.run(
                    *passwordArgs(document.password),
                    "--split-pages=$pagesPerFile",
                    document.file.absolutePath,
                    pattern
                )
            }
            coroutineContext.ensureActive()
            result.requireOk("split")

            // Zero-padded page numbers of equal width, so name order is page order.
            val parts = staging.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
            val expected = (document.pageCount + pagesPerFile - 1) / pagesPerFile
            if (parts.size != expected) {
                Log.w(TAG, "split wrote ${parts.size} parts, expected $expected")
                throw ToolException(ToolFailure.Failed("split wrote ${parts.size} of $expected parts"))
            }
            val base = baseName(document.displayName)
            val outputs = parts.mapIndexed { index, part ->
                coroutineContext.ensureActive()
                onProgress(index.toFloat() / parts.size)
                val target = File(run, base + SUFFIX_PART + partNumber(index, parts.size) + PDF_EXTENSION)
                created += target
                if (!part.renameTo(target)) throw ToolException(ToolFailure.Failed("could not move a split part"))
                target.toPdfOutput("split")
            }
            onProgress(1f)
            return ToolResult(outputs, document.sizeBytes)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Greedy grouping: each part takes as many following pages as fit under [maxBytes]. Size
     * isn't additive — pages share fonts and images — so the only honest measure is to write a
     * candidate and look. Growth doubles until a candidate is too big, then halves the gap, so a
     * part costs a handful of trial writes rather than one per page.
     *
     * A page that alone is over the limit becomes a part of its own; the limit can't be met
     * without changing the page, which Split never does, and the result says so.
     */
    private suspend fun bySize(
        document: ToolDocument,
        maxBytes: Long,
        run: File,
        created: MutableList<File>,
        onProgress: (Float?) -> Unit
    ): ToolResult {
        // Every part plus one live candidate at a time.
        workspace.requireSpace(estimate(document, document.pageCount) + maxBytes)
        val pageCount = document.pageCount
        val averagePage = maxOf(1L, document.sizeBytes / pageCount)
        val parts = mutableListOf<File>()
        var oversized = 0
        var start = 1
        var attempt = 0

        while (start <= pageCount) {
            coroutineContext.ensureActive()
            onProgress((start - 1).toFloat() / pageCount)
            val firstEnd = (start - 1 + (maxBytes / averagePage).coerceIn(1L, pageCount.toLong()).toInt())
                .coerceAtMost(pageCount)
            val part = largestPart(document, start, firstEnd, maxBytes) {
                File(run, ".candidate-${attempt++}$PDF_EXTENSION").also { created += it }
            }
            parts += part.file
            if (part.oversized) oversized++
            start = part.end + 1
        }

        val outputs = moveParts(document, parts, run, created)
        onProgress(1f)
        val notes = if (oversized > 0) {
            listOf(ToolNote(R.plurals.pdf_tool_split_oversized_pages, oversized, plural = true))
        } else {
            emptyList()
        }
        return ToolResult(outputs, document.sizeBytes, notes)
    }

    /**
     * The longest run of pages from [start] that writes to at most [maxBytes], searched from
     * [firstEnd]; a lone page over the limit is the answer when nothing fits.
     */
    private suspend fun largestPart(
        document: ToolDocument,
        start: Int,
        firstEnd: Int,
        maxBytes: Long,
        newCandidate: () -> File
    ): PartSearch {
        val search = PartSearch(start, document.pageCount)
        var end = firstEnd
        while (true) {
            coroutineContext.ensureActive()
            val candidate = newCandidate()
            select(document, PageRange(start, end).spec, candidate)
            val fits = candidate.length() <= maxBytes
            // A lone page over the limit is kept too: no grouping can ever make it fit.
            if (fits || end == start) search.accept(candidate, end, fits) else search.reject(candidate, end)
            val loneOversized = !fits && end == start
            if (loneOversized || search.settled) break
            end = search.nextEnd()
        }
        return search
    }

    /**
     * One part's search state: the largest end known to fit (and its file), the smallest known not
     * to. Growth doubles until a candidate is too big, then the gap halves.
     */
    private class PartSearch(private val start: Int, private val pageCount: Int) {
        var end = start - 1
            private set
        private var fitFile: File? = null
        private var tooBigEnd = pageCount + 1
        var oversized = false
            private set

        val file: File get() = requireNotNull(fitFile)

        val settled: Boolean get() = end == pageCount || end + 1 == tooBigEnd

        fun accept(candidate: File, candidateEnd: Int, fits: Boolean) {
            fitFile?.delete()
            fitFile = candidate
            end = candidateEnd
            if (!fits) oversized = true
        }

        fun reject(candidate: File, candidateEnd: Int) {
            candidate.delete()
            tooBigEnd = candidateEnd
        }

        fun nextEnd(): Int = if (tooBigEnd > pageCount) {
            minOf(pageCount, start + 2 * (end - start + 1) - 1)
        } else {
            (end + tooBigEnd) / 2
        }
    }

    /** Renames the finished parts into the run, numbered in page order. */
    private suspend fun moveParts(
        document: ToolDocument,
        parts: List<File>,
        run: File,
        created: MutableList<File>
    ): List<ToolOutput> {
        val base = baseName(document.displayName)
        return parts.mapIndexed { index, part ->
            coroutineContext.ensureActive()
            val target = File(run, base + SUFFIX_PART + partNumber(index, parts.size) + PDF_EXTENSION)
            created += target
            if (!part.renameTo(target)) throw ToolException(ToolFailure.Failed("could not move a split part"))
            target.toPdfOutput("split")
        }
    }

    private suspend fun select(document: ToolDocument, spec: String, file: File): ToolOutput {
        qpdf.selectPages(document.file, file, spec, document.password).requireOk("split")
        coroutineContext.ensureActive()
        return file.toPdfOutput("split")
    }

    /** Pages × the average page, with headroom; shared fonts and images make it a guess either way. */
    private fun estimate(document: ToolDocument, pages: Int): Long =
        (document.sizeBytes.toDouble() / document.pageCount * pages * SPACE_FACTOR).toLong()

    /** The same range typed twice must not overwrite the first file. */
    private fun uniqueFile(run: File, base: String): File {
        var file = File(run, base + PDF_EXTENSION)
        var copy = 2
        while (file.exists()) file = File(run, "${base}_${copy++}$PDF_EXTENSION")
        return file
    }

    private fun passwordArgs(password: String): Array<String> =
        if (password.isEmpty()) emptyArray() else arrayOf("--password=$password")

    private companion object {
        const val SUFFIX_PART = "_part-"
        const val SUFFIX_EXTRACTED = "_extracted"
        const val SUFFIX_DELETED = "_pages_deleted"
    }
}
