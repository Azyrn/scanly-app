package com.skeler.scanely.ui.pdfeditor

import com.skeler.scanely.core.pdfeditor.engine.PageGeometry
import com.skeler.scanely.core.pdfeditor.model.PdfEdit

/** One page as the grid and the canvas show it: staged order, with rotations and marks folded in. */
data class StudioPage(
    val originalIndex: Int,
    val rotation: Int,
    val isCropped: Boolean,
    val markingCount: Int,
    val widthPt: Float,
    val heightPt: Float
) {
    val aspectRatio: Float
        get() = if (rotation % 180 == 0) widthPt / heightPt else heightPt / widthPt
}

/**
 * Folds the staged edit stack into the page list the user is looking at.
 *
 * This mirrors `PdfEditorEngine.survivingOrder` deliberately: the grid must show exactly what a
 * save would produce, and the two would drift apart if the screen kept its own idea of the order.
 */
fun deriveStudioPages(
    pageCount: Int,
    edits: List<PdfEdit>,
    geometries: List<PageGeometry>
): List<StudioPage> {
    var order = List(pageCount) { it }
    edits.filterIsInstance<PdfEdit.Structural>().forEach { edit ->
        order = when (edit) {
            is PdfEdit.DeletePages -> order.filterNot { it in edit.pageIndices }
            is PdfEdit.ReorderPages -> edit.order.filter { it in order }
            else -> order
        }
    }

    val rotations = edits.filterIsInstance<PdfEdit.RotatePage>()
        .groupBy { it.pageIndex }
        .mapValues { (_, list) -> ((list.sumOf { it.degrees } % 360) + 360) % 360 }
    val cropped = edits.filterIsInstance<PdfEdit.CropPage>().map { it.pageIndex }.toSet()
    val markings = edits.filterIsInstance<PdfEdit.Marking>()
        .groupingBy { it.pageIndex }
        .eachCount()

    return order.map { index ->
        val geometry = geometries.getOrNull(index)
        StudioPage(
            originalIndex = index,
            rotation = rotations[index] ?: 0,
            isCropped = index in cropped,
            markingCount = markings[index] ?: 0,
            widthPt = geometry?.widthPt ?: DEFAULT_PAGE_WIDTH_PT,
            heightPt = geometry?.heightPt ?: DEFAULT_PAGE_HEIGHT_PT
        )
    }
}

private const val DEFAULT_PAGE_WIDTH_PT = 595f
private const val DEFAULT_PAGE_HEIGHT_PT = 842f
