package com.skeler.scanely.core.pdftools.stamp

import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.util.Log
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.pdfeditor.engine.PageGeometry
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.Qpdf
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolOutput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.isOutOfSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

private const val TAG = "StampPdf"

/** Whether a stamp is drawn on top of the page's content or beneath it. */
enum class StampLayer { OVER, UNDER }

/** A stamp page's size in whole points: PdfDocument only takes integers. */
data class StampSize(val widthPt: Int, val heightPt: Int) {
    companion object {
        fun of(geometry: PageGeometry) = StampSize(
            geometry.widthPt.roundToInt().coerceAtLeast(1),
            geometry.heightPt.roundToInt().coerceAtLeast(1)
        )
    }
}

/**
 * One stamping pass: the stamp PDF's pages ([sizes], in order) and which of them each document page
 * receives. [targets] pairs a 1-based document page with a 1-based stamp page, in ascending
 * document order; pages not listed are left alone. [draw] paints stamp page `index` (0-based) on a
 * top-left-origin canvas the size of [sizes]`[index]`.
 */
class StampPlan(
    val sizes: List<StampSize>,
    val targets: List<Pair<Int, Int>>,
    val draw: (canvas: Canvas, index: Int, size: StampSize) -> Unit
)

/**
 * Stamps a document by drawing the marks into a separate PDF with Android's PdfDocument and letting
 * qpdf lay its pages over (or under) the document's pages. The original content streams are never
 * rewritten, so text, vectors and images stay byte-for-byte what they were.
 *
 * Rotated pages need nothing here, and that is deliberate: pdfium reports a page's size as
 * displayed (/Rotate already applied), the stamp is drawn upright at that size, and qpdf places
 * each overlay with its inverse-transformation option on — it undoes the destination's /Rotate so
 * the stamp lands upright on the page as the reader sees it. qpdf centres the overlay in the trim
 * box and only ever shrinks it, so a stamp drawn at the visible size maps 1:1.
 */
@Singleton
class StampRunner @Inject constructor(
    private val pageReader: PdfPageReader,
    private val workspace: ToolWorkspace
) {

    /**
     * Runs one stamping pass on [document] into [ToolJobInput.run]. [plan] is built from the
     * document's displayed page sizes. Progress covers drawing the stamp; qpdf's single native call
     * reports no progress, so the flow shows "working" for it. Nothing is left in the run directory
     * on failure or cancellation.
     */
    suspend fun stamp(
        document: ToolDocument,
        input: ToolJobInput,
        suffix: String,
        label: String,
        layer: StampLayer,
        plan: (List<PageGeometry>) -> StampPlan
    ): ToolResult {
        val stampPlan = plan(geometries(document, label))
        require(stampPlan.sizes.isNotEmpty() && stampPlan.targets.isNotEmpty()) { "$label: nothing to stamp" }

        // The output is the input plus the stamp; qpdf's temporaries can briefly double it.
        workspace.requireSpace(document.sizeBytes * 2 + STAMP_BASE_BYTES + stampPlan.sizes.size * STAMP_PAGE_BYTES)

        val base = document.displayName.substringBeforeLast('.').ifBlank { document.displayName }
        val fileName = ToolWorkspace.safeFileName(base) + suffix + PDF_EXTENSION
        val output = File(input.run, fileName)
        val stamp = File(input.run, ".stamp-${System.nanoTime()}.pdf")
        var delivered = false
        try {
            writeStamp(stampPlan, stamp, input.onProgress)
            coroutineContext.ensureActive()
            input.onProgress(null)
            applyStamp(document, stamp, output, layer, stampPlan, label)
            delivered = true
            return ToolResult(
                listOf(ToolOutput(output, fileName, ScanExporter.PDF_MIME_TYPE, output.length())),
                document.sizeBytes
            )
        } finally {
            stamp.delete()
            if (!delivered) output.delete()
        }
    }

    private suspend fun geometries(document: ToolDocument, label: String): List<PageGeometry> {
        val geometries = pageReader.pageGeometries(document.file, document.password)
        coroutineContext.ensureActive()
        if (geometries.isNullOrEmpty()) {
            Log.w(TAG, "$label: pdfium could not lay out the pages")
            throw ToolException(ToolFailure.Damaged)
        }
        return geometries
    }

    private suspend fun applyStamp(
        document: ToolDocument,
        stamp: File,
        output: File,
        layer: StampLayer,
        plan: StampPlan,
        label: String
    ) {
        val result = withContext(Dispatchers.IO) {
            Qpdf.run(*qpdfArgs(document, stamp, output, layer, plan))
        }
        coroutineContext.ensureActive()
        failureOf(result, label)?.let { throw it }
        if (!output.isFile || output.length() == 0L) {
            Log.w(TAG, "$label reported success but wrote no output")
            throw ToolException(ToolFailure.Failed("$label wrote no output"))
        }
    }

    /**
     * PdfDocument keeps each finished page as a small recorded picture and only serialises on
     * writeTo, so a stamp of a few text runs costs a few kilobytes per page even at thousands of
     * pages; nothing here holds a rendered bitmap.
     */
    private suspend fun writeStamp(plan: StampPlan, stamp: File, onProgress: (Float?) -> Unit) {
        withContext(Dispatchers.Default) {
            val pdf = PdfDocument()
            try {
                plan.sizes.forEachIndexed { index, size ->
                    ensureActive()
                    val info = PdfDocument.PageInfo.Builder(size.widthPt, size.heightPt, index + 1).create()
                    val page = pdf.startPage(info)
                    plan.draw(page.canvas, index, size)
                    pdf.finishPage(page)
                    onProgress((index + 1).toFloat() / plan.sizes.size * DRAW_PROGRESS_SHARE)
                }
                withContext(Dispatchers.IO) {
                    try {
                        stamp.outputStream().buffered().use { pdf.writeTo(it) }
                    } catch (e: IOException) {
                        Log.w(TAG, "Writing the stamp failed", e)
                        throw ToolException(
                            if (e.isOutOfSpace()) ToolFailure.LowStorage else ToolFailure.Failed("stamp write"),
                            e
                        )
                    }
                }
            } finally {
                pdf.close()
            }
        }
    }

    private fun qpdfArgs(
        document: ToolDocument,
        stamp: File,
        output: File,
        layer: StampLayer,
        plan: StampPlan
    ): Array<String> {
        val to = pageRangeSpec(plan.targets.map { it.first })
        // One stamp page for every target: --repeat reuses it without listing it N times.
        val pick = if (plan.sizes.size == 1 && plan.targets.all { it.second == 1 }) {
            arrayOf("--from=", "--repeat=1")
        } else {
            arrayOf("--from=" + pageRangeSpec(plan.targets.map { it.second }))
        }
        val password = if (document.password.isEmpty()) emptyArray() else arrayOf("--password=${document.password}")
        return arrayOf(
            *password,
            document.file.absolutePath,
            if (layer == StampLayer.OVER) "--overlay" else "--underlay",
            stamp.absolutePath,
            "--to=$to",
            *pick,
            "--",
            output.absolutePath
        )
    }

    /** The exception a non-Ok [result] maps to, or null when qpdf succeeded. */
    private fun failureOf(result: QpdfResult, label: String): ToolException? = when (result) {
        QpdfResult.Ok -> null
        QpdfResult.WrongPassword -> ToolException(ToolFailure.WrongPassword)
        is QpdfResult.Failed -> engineFailure(label, result.message)
        is QpdfResult.BadUsage -> engineFailure(label, result.message)
    }

    private fun engineFailure(label: String, message: String): ToolException {
        // qpdf messages name the file path and the failing object, never contents or passwords.
        Log.w(TAG, "$label failed: $message")
        return if (IOException(message).isOutOfSpace()) {
            ToolException(ToolFailure.LowStorage)
        } else {
            ToolException(ToolFailure.Failed(message))
        }
    }

    companion object {
        /**
         * qpdf page-range text for [pages] (1-based) in the given order, folding ascending runs
         * ("1,2,3,5" → "1-3,5") so a 2000-page document doesn't become a 10 KB argument. Repeats
         * stay listed one by one; qpdf accepts a page more than once.
         */
        fun pageRangeSpec(pages: List<Int>): String {
            require(pages.isNotEmpty()) { "empty page range" }
            val parts = mutableListOf<String>()
            var start = pages.first()
            var previous = start
            for (page in pages.drop(1)) {
                if (page == previous + 1) {
                    previous = page
                    continue
                }
                parts += if (start == previous) "$start" else "$start-$previous"
                start = page
                previous = page
            }
            parts += if (start == previous) "$start" else "$start-$previous"
            return parts.joinToString(",")
        }
    }
}

private const val PDF_EXTENSION = ".pdf"

/** Drawing the stamp is the part with a known length; qpdf's pass after it is the rest. */
private const val DRAW_PROGRESS_SHARE = 0.6f

/** Room for the stamp PDF itself: an embedded font, maybe an image, and per-page records. */
private const val STAMP_BASE_BYTES = 16L * 1024 * 1024
private const val STAMP_PAGE_BYTES = 4L * 1024
