package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdfeditor.PdfFeature

/** The grid's groups, in screen order. */
enum class ToolSection { ORGANIZE, CONVERT, EDIT, IMAGE }

/**
 * Every tool on the PDF tools grid, in grid order.
 *
 * [opensEditor] tools hand the picked document to the existing editor; every other tool runs
 * through the shared pick → options → result flow and is backed by a `ToolSpec` bound into a Hilt
 * map. The grid shows an entry only once its spec exists, so a tool that isn't built yet never
 * appears as a dead tile. Pro gating comes from [feature], so a tool can never disagree with the
 * editor about it.
 */
enum class PdfTool(
    val section: ToolSection,
    val feature: PdfFeature?,
    val opensEditor: Boolean = false
) {
    MERGE(ToolSection.ORGANIZE, PdfFeature.MERGE),
    SPLIT(ToolSection.ORGANIZE, null),
    ORGANIZE(ToolSection.ORGANIZE, null, opensEditor = true),
    ROTATE(ToolSection.ORGANIZE, PdfFeature.ROTATE_PAGES),
    COMPRESS(ToolSection.ORGANIZE, PdfFeature.COMPRESS),
    REPAIR(ToolSection.ORGANIZE, PdfFeature.REPAIR),

    PDF_TO_WORD(ToolSection.CONVERT, PdfFeature.OFFICE_CONVERSION),
    PDF_TO_POWERPOINT(ToolSection.CONVERT, PdfFeature.OFFICE_CONVERSION),
    PDF_TO_EXCEL(ToolSection.CONVERT, PdfFeature.OFFICE_CONVERSION),
    WORD_TO_PDF(ToolSection.CONVERT, PdfFeature.OFFICE_CONVERSION),
    POWERPOINT_TO_PDF(ToolSection.CONVERT, PdfFeature.OFFICE_CONVERSION),
    EXCEL_TO_PDF(ToolSection.CONVERT, PdfFeature.OFFICE_CONVERSION),
    PDF_TO_JPG(ToolSection.CONVERT, null),
    IMAGE_TO_PDF(ToolSection.CONVERT, null),
    EXTRACT_TEXT(ToolSection.CONVERT, null),

    EDIT(ToolSection.EDIT, null, opensEditor = true),
    SIGN(ToolSection.EDIT, PdfFeature.SIGNATURE, opensEditor = true),
    WATERMARK(ToolSection.EDIT, PdfFeature.WATERMARK),
    PAGE_NUMBERS(ToolSection.EDIT, null),
    PROTECT(ToolSection.EDIT, PdfFeature.ENCRYPT),
    UNLOCK(ToolSection.EDIT, PdfFeature.DECRYPT),

    COMPRESS_IMAGE(ToolSection.IMAGE, null),
    CONVERT_TO_JPG(ToolSection.IMAGE, null),
    CONVERT_FROM_JPG(ToolSection.IMAGE, null),
    RESIZE_IMAGE(ToolSection.IMAGE, null);

    val requiresPro: Boolean get() = feature?.requiresPro == true
}
