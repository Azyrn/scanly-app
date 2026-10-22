package com.skeler.scanely.core.pdftools.pdf2office

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.PageGeometry
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.PdfTextLayer
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolOutput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.isOutOfSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "OfficePageSource"

/** What one PDF page turned out to be. */
internal sealed interface OfficePage {
    /** [dense] pages were over the analysis cap and came back as plain lines. */
    data class Text(val layout: PageLayout, val dense: Boolean) : OfficePage

    /** No text layer: a photographed or scanned page, which only an image can carry over. */
    data object Scanned : OfficePage

    /** pdfium couldn't open the page at all. */
    data object Unreadable : OfficePage
}

data class RenderedPage(val widthPx: Int, val heightPx: Int)

/**
 * The PDF side of the three office converters: page sizes, each page's text as a [PageLayout],
 * and page renders for scanned pages. One page at a time, so memory stays flat however long the
 * document is.
 */
@Singleton
class OfficePageSource @Inject constructor(private val reader: PdfPageReader) {

    /** Falls back to A4 for every page when pdfium can't measure one — sizes only steer layout. */
    internal suspend fun geometries(document: ToolDocument): List<PageGeometry> =
        reader.pageGeometries(document.file, document.password)?.takeIf { it.isNotEmpty() }
            ?: List(document.pageCount.coerceAtLeast(1)) { PageGeometry(A4_WIDTH_PT, A4_HEIGHT_PT) }

    internal suspend fun read(document: ToolDocument, index: Int): OfficePage {
        val raw = reader.withTextLayer(document.file, index, document.password, ::rawPage)
            ?: return OfficePage.Unreadable

        val (layout, dense) = when (raw) {
            is RawPage.Chars -> LayoutAnalyzer.analyze(raw.chars) to false
            is RawPage.Plain -> LayoutAnalyzer.plainLines(raw.text) to true
        }
        return if (layout.isEmpty) OfficePage.Scanned else OfficePage.Text(layout, dense)
    }

    /**
     * Renders the page at about [RENDER_DPI] into [sink] as JPEG. Null when the page can't be
     * rendered, including when the phone has no memory for the bitmap — that loses one page,
     * not the whole conversion.
     */
    internal suspend fun renderJpeg(
        document: ToolDocument,
        index: Int,
        geometry: PageGeometry,
        sink: OutputStream
    ): RenderedPage? {
        val longest = max(geometry.widthPt, geometry.heightPt).coerceAtLeast(1f)
        val scale = min(RENDER_DPI / POINTS_PER_INCH, MAX_RENDER_SIDE_PX / longest)
        val widthPx = (geometry.widthPt.coerceAtLeast(1f) * scale).roundToInt().coerceAtLeast(1)
        val rendered = try {
            reader.renderPage(document.file, index, widthPx, document.password)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "No memory to render page $index", e)
            null
        }
        val bitmap = rendered ?: return null
        return try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, sink)) {
                Log.w(TAG, "JPEG encode failed for page $index")
                null
            } else {
                RenderedPage(bitmap.width, bitmap.height)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Copies what the layout needs out of [layer], which closes when the read returns. */
    private fun rawPage(layer: PdfTextLayer): RawPage {
        val count = layer.charCount
        return when {
            count <= 0 -> RawPage.Chars(emptyList())
            count > LayoutAnalyzer.MAX_CHARS_PER_PAGE ->
                RawPage.Plain(layer.textIn(0 until min(count, LayoutAnalyzer.MAX_PLAIN_TEXT_CHARS)))
            else -> RawPage.Chars(chars(layer, count))
        }
    }

    private fun chars(layer: PdfTextLayer, count: Int): List<LayoutChar> {
        val text = layer.textIn(0 until count)
        return List(count) { charIndex ->
            val box = layer.charBox(charIndex)
            LayoutChar(
                char = text.getOrElse(charIndex) { ' ' },
                left = box?.left ?: 0f,
                top = box?.top ?: 0f,
                right = box?.right ?: 0f,
                bottom = box?.bottom ?: 0f,
                fontSize = layer.fontSizeAt(charIndex)
            )
        }
    }

    private sealed interface RawPage {
        class Chars(val chars: List<LayoutChar>) : RawPage
        class Plain(val text: String) : RawPage
    }

    companion object {
        private const val A4_WIDTH_PT = 595f
        private const val A4_HEIGHT_PT = 842f
        private const val RENDER_DPI = 150f
        private const val POINTS_PER_INCH = 72f
        private const val MAX_RENDER_SIDE_PX = 2_000f
        private const val JPEG_QUALITY = 82

        /** "report.pdf" → "report.docx", sanitised before the extension goes on so it can't be cut off. */
        internal fun outputName(displayName: String, extension: String): String {
            val base = displayName.substringBeforeLast('.').ifBlank { displayName }
            return ToolWorkspace.safeFileName(base) + extension
        }
    }
}

/** What a conversion had to do differently, counted per page and turned into result notes. */
internal class PageTally {
    var scanned = 0
    var dense = 0
    var unreadable = 0

    fun notes(@StringRes simplified: Int?, @PluralsRes scannedNote: Int): List<ToolNote> = buildList {
        simplified?.let { add(ToolNote(it)) }
        if (scanned > 0) add(ToolNote(scannedNote, scanned, plural = true))
        if (dense > 0) add(ToolNote(R.plurals.pdf2office_dense_pages, dense, plural = true))
        if (unreadable > 0) add(ToolNote(R.plurals.pdf2office_unreadable_pages, unreadable, plural = true))
    }
}

/**
 * The shared shape of the three converters: check space, let [write] fill one output file (with a
 * private scratch directory beside it), and turn the answer into a [ToolResult]. The output and the
 * scratch directory are deleted on every path that doesn't deliver — failure, a full disk, Cancel.
 */
internal suspend fun produceOfficeFile(
    workspace: ToolWorkspace,
    input: ToolJobInput,
    extension: String,
    mimeType: String,
    label: String,
    write: suspend (document: ToolDocument, output: File, scratch: File) -> List<ToolNote>
): ToolResult = withContext(Dispatchers.IO) {
    val document = input.documents.firstOrNull()
        ?: throw ToolException(ToolFailure.Failed("$label got no document"))
    // The output rarely outgrows the PDF; scanned-page renders are the exception, staged in scratch.
    workspace.requireSpace(document.sizeBytes * 2)
    val name = OfficePageSource.outputName(document.displayName, extension)
    val output = File(input.run, name)
    val scratch = File(input.run, ".$label-${System.nanoTime()}").apply { mkdirs() }
    var delivered = false
    try {
        val notes = write(document, output, scratch)
        ensureActive()
        if (!output.isFile || output.length() == 0L) {
            throw ToolException(ToolFailure.Failed("$label wrote no output"))
        }
        input.onProgress(1f)
        delivered = true
        ToolResult(listOf(ToolOutput(output, name, mimeType, output.length())), document.sizeBytes, notes)
    } catch (e: IOException) {
        Log.w(TAG, "$label failed", e)
        throw ToolException(if (e.isOutOfSpace()) ToolFailure.LowStorage else ToolFailure.Failed(e.message), e)
    } finally {
        scratch.deleteRecursively()
        if (!delivered) output.delete()
    }
}
