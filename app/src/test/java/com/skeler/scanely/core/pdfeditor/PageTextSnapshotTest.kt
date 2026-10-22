package com.skeler.scanely.core.pdfeditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.skeler.scanely.core.pdfeditor.engine.PageTextSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextSnapshotTest {

    /**
     * Lays out [text] as evenly spaced glyphs on one line, giving whitespace no box at all — which
     * is what pdfium reports for a space at a line break and, on plenty of documents, for ordinary
     * spaces between words too.
     */
    private fun line(
        text: String,
        top: Float,
        charWidth: Float = 5f,
        height: Float = 8f,
        startX: Float = 0f,
        sizePt: Float = 10f
    ): Triple<List<Rect>, FloatArray, String> {
        val boxes = text.mapIndexed { index, char ->
            if (char.isWhitespace()) {
                Rect.Zero
            } else {
                val left = startX + index * charWidth
                Rect(left, top, left + charWidth, top + height)
            }
        }
        return Triple(boxes, FloatArray(text.length) { sizePt }, text)
    }

    private fun snapshotOf(vararg lines: Triple<List<Rect>, FloatArray, String>): PageTextSnapshot {
        val boxes = lines.flatMap { it.first }
        val sizes = lines.flatMap { it.second.toList() }.toFloatArray()
        val text = lines.joinToString("") { it.third }
        return PageTextSnapshot(boxes, sizes, text)
    }

    @Test
    fun `a line selection spans the whole line, not up to the first space`() {
        val snapshot = snapshotOf(line("total due now", top = 100f))

        val index = snapshot.charIndexAt(Offset(2f, 104f))
        assertNotNull(index)

        val range = snapshot.lineAt(index!!)
        assertEquals(0, range.first)
        assertEquals("total due now".lastIndex, range.last)
    }

    @Test
    fun `a line selection stops at the end of its own line`() {
        val snapshot = snapshotOf(
            line("first line", top = 100f),
            line("second line", top = 120f)
        )

        val index = snapshot.charIndexAt(Offset(2f, 104f))!!
        assertEquals("first line".lastIndex, snapshot.lineAt(index).last)
    }

    @Test
    fun `dragging from A to B selects what it crossed, rounded out to whole words`() {
        val snapshot = snapshotOf(line("total due now", top = 100f))

        // From the "d" of "due" to the "n" of "now" — a drag no fingertip could aim more finely.
        val selection = snapshot.selectionBetween(Offset(31f, 104f), Offset(51f, 104f))

        assertNotNull(selection)
        assertEquals("due now", selection!!.text)
    }

    @Test
    fun `a drag that starts mid-word still takes the whole word`() {
        val snapshot = snapshotOf(line("total due now", top = 100f))

        // Starting on the "o" of "total" and ending on the "u" of "due".
        val selection = snapshot.selectionBetween(Offset(6f, 104f), Offset(36f, 104f))

        assertEquals("total due", selection!!.text)
    }

    @Test
    fun `a selection reports the baseline the glyphs actually sit on`() {
        val snapshot = snapshotOf(line("hello", top = 100f, height = 8f))

        val selection = snapshot.selectionOf(0..4)!!
        assertEquals(108f, selection.baselinePt, 0.01f)
    }

    @Test
    fun `a descender does not drag the baseline down with it`() {
        val boxes = listOf(
            Rect(0f, 100f, 5f, 108f),
            Rect(5f, 100f, 10f, 108f),
            // A "y", hanging two points below the line.
            Rect(10f, 100f, 15f, 110f),
            Rect(15f, 100f, 20f, 108f)
        )
        val snapshot = PageTextSnapshot(boxes, FloatArray(4) { 10f }, "away")

        assertEquals(108f, snapshot.selectionOf(0..3)!!.baselinePt, 0.01f)
    }

    @Test
    fun `positionless characters never win a hit test`() {
        val snapshot = snapshotOf(line("a b", top = 100f))

        // The space is Rect.Zero, which sits at the page's top-left corner. A point up there must
        // match nothing rather than matching the space.
        assertNull(snapshot.charIndexAt(Offset(0.5f, 0.5f)))
    }

    @Test
    fun `a selection over an empty page yields nothing`() {
        val snapshot = PageTextSnapshot(emptyList(), FloatArray(0), "")

        assertTrue(snapshot.isEmpty)
        assertNull(snapshot.lineSelectionAt(Offset(10f, 10f)))
        assertNull(snapshot.selectionOf(0..3))
    }

    @Test
    fun `a multi-line selection reports one quad per line`() {
        val snapshot = snapshotOf(
            line("first", top = 100f),
            line("second", top = 120f)
        )

        val selection = snapshot.selectionOf(0..snapshot.charCount - 1)!!
        assertEquals(2, selection.quads.size)
    }
}
