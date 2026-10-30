package com.skeler.scanely.core.pdftools.pdf2office.exact

import java.io.File

/** A picture on the page: an image file and where it goes, in points from the page's top-left. */
class PagePicture(
    val file: File,
    val png: Boolean,
    val xPt: Float,
    val yPt: Float,
    val widthPt: Float,
    val heightPt: Float
)

/**
 * One page ready to write: its layout, its pictures, and a picture of everything else that isn't
 * text (rules, fills, vector art), or null when there is none.
 */
class ExactPage(
    val widthPt: Float,
    val heightPt: Float,
    val layout: ExactPageLayout,
    val background: File?,
    val pictures: List<PagePicture>
)
