package com.skeler.scanely.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MarkdownPdfTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val markdown = """
        # Quarterly Report

        Revenue is **up**, costs are *flat*.

        | Region | Revenue | Growth |
        | --- | --- | --- |
        | North | 1,200 | 12% |
        | South | 900 | 4% |

        - [x] Ship the export
        - [ ] Write it up

        > Numbers are provisional.

        ```
        total = revenue - costs
        ```
    """.trimIndent()

    @Test
    fun markdownPdfDrawsTheRenderedPage() {
        val pixels = render(MarkdownPdf.render(markdown))

        assertEquals(1, pixels.size)
        // A rendered page is neither blank nor a wall of text: it has the table's rules on it.
        assertTrue(inked(pixels.first()) > 0.01f)
        assertTrue(horizontalRules(pixels.first()) >= 3)
    }

    @Test
    fun longMarkdownFlowsOntoMoreThanOnePage() {
        val rows = (1..80).joinToString("\n") { "| Row $it | value $it |" }
        val pixels = render(MarkdownPdf.render("| A | B |\n| --- | --- |\n$rows"))

        assertTrue(pixels.size > 1)
        // The header is carried onto every page of the grid.
        pixels.forEach { assertTrue(horizontalRules(it) >= 2) }
    }

    @Test
    fun emptyMarkdownStillProducesAPage() {
        assertEquals(1, render(MarkdownPdf.render("")).size)
    }

    /**
     * A document shaped the way the compose system prompt asks for one. Composition has no OCR
     * behind it, so this renderer is the only thing standing between a prompt and the PDF.
     */
    @Test
    fun aComposedDocumentRendersToARealPage() {
        val invoice = """
            # Invoice

            **From:** Skeler Design Studio
            **To:** Acme Ltd
            **Due:** 30 days from issue

            | Description | Days | Rate | Amount |
            | --- | --- | --- | --- |
            | Design consulting | 3 | ${'$'}400 | ${'$'}1,200 |
            | **Total** | | | **${'$'}1,200** |

            ## Payment details

            Transfer to account `[Account number]`, quoting invoice `[Invoice number]`.
        """.trimIndent()

        val pixels = render(MarkdownPdf.render(invoice))

        assertEquals(1, pixels.size)
        assertTrue(inked(pixels.first()) > 0.01f)
        // The line-item table survived as a drawn grid, not as literal pipe characters.
        assertTrue(horizontalRules(pixels.first()) >= 3)
    }

    /**
     * The invoice shape that used to break `Amount` onto a second line: one long column beside
     * three short ones. The header row must stay as tall as a header row whose last column cannot
     * wrap under any sizing, which is only true if no cell wrapped.
     */
    @Test
    fun aNarrowColumnKeepsItsHeaderOnOneLine() {
        fun table(last: String) = """
            | Description | Quantity | Rate | $last |
            | --- | --- | --- | --- |
            | Creative design consultation and execution | 3 days | ${'$'}400/day | ${'$'}1,200 |
        """.trimIndent()

        val control = headerRowHeight(render(MarkdownPdf.render(table("Amt"))).first())
        val actual = headerRowHeight(render(MarkdownPdf.render(table("Amount"))).first())

        assertTrue("header row wrapped: $actual vs $control", actual <= control * 1.2f)
    }

    /** A scan's PDF must keep looking like the page it came from: no trace of colour. */
    @Test
    fun aDocumentRendersWithoutColour() {
        val source = "# Invoice\n\n| Item | Cost |\n| --- | --- |\n| Pen | 2 |"

        assertEquals(0, accentPixels(render(MarkdownPdf.render(source)).first()))
    }

    /** Bluish pixels — what a coloured heading or rule would leave. */
    private fun accentPixels(page: Bitmap): Int {
        var hits = 0
        for (y in 0 until page.height) {
            for (x in 0 until page.width) {
                val pixel = page.getPixel(x, y)
                if (Color.blue(pixel) - Color.red(pixel) > 40 && Color.blue(pixel) > 80) hits++
            }
        }
        return hits
    }

    /** Distance between the first two horizontal rules — one header row, however tall it drew. */
    private fun headerRowHeight(page: Bitmap): Float {
        val rules = (0 until page.height).filter { y ->
            var dark = 0
            for (x in 0 until page.width) {
                if (isDark(page.getPixel(x, y))) dark++
            }
            dark > page.width * 0.6f
        }
        // Rules are drawn a pixel or two thick, so collapse each band to its first row.
        val edges = rules.filterIndexed { index, y -> index == 0 || y - rules[index - 1] > 2 }
        return (edges[1] - edges[0]).toFloat()
    }

    /** Rasterizes every page so the assertions look at what a PDF viewer would show. */
    private fun render(document: android.graphics.pdf.PdfDocument): List<Bitmap> {
        val file = File.createTempFile("markdown", ".pdf", context.cacheDir)
        try {
            file.outputStream().use { document.writeTo(it) }
            document.close()

            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    return (0 until renderer.pageCount).map { index ->
                        renderer.openPage(index).use { page ->
                            Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                                .also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun inked(page: Bitmap): Float {
        var dark = 0
        for (y in 0 until page.height) {
            for (x in 0 until page.width) {
                if (isDark(page.getPixel(x, y))) dark++
            }
        }
        return dark.toFloat() / (page.width * page.height)
    }

    /** Rows that are mostly dark across the content width — a table rule or a divider. */
    private fun horizontalRules(page: Bitmap): Int {
        var rules = 0
        for (y in 0 until page.height) {
            var dark = 0
            for (x in 0 until page.width) {
                if (isDark(page.getPixel(x, y))) dark++
            }
            if (dark > page.width * 0.6f) rules++
        }
        return rules
    }

    private fun isDark(pixel: Int): Boolean =
        Color.red(pixel) < 200 && Color.green(pixel) < 200 && Color.blue(pixel) < 200
}
