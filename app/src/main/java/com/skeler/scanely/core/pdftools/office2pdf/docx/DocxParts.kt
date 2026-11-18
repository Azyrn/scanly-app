package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser

/** compatibilityMode of Word 2013 and later, whose justification also shrinks spaces. */
private const val WORD_2013_MODE = 15

/**
 * A section that names no header (or footer) of a kind keeps the previous section's of that kind
 * (ISO/IEC 29500-1 §17.10.5); the first section has nothing to inherit.
 */
internal fun inheritHeaders(sections: List<DocxSection>): List<DocxSection> {
    var headers = emptyMap<HeaderKind, String>()
    var footers = emptyMap<HeaderKind, String>()
    return sections.map { section ->
        headers = headers + section.page.headers
        footers = footers + section.page.footers
        section.copy(page = section.page.copy(headers = headers, footers = footers))
    }
}

/** settings.xml: w:evenAndOddHeaders, and the Word version the layout follows (w:compat compatibilityMode). */
internal fun parseSettings(parser: XmlPullParser): DocxSettings {
    var evenAndOdd = false
    var mode = 0
    parser.children { name ->
        when {
            !parser.isW() -> parser.skip()
            name == "evenAndOddHeaders" -> evenAndOdd = parser.toggle().also { parser.skip() }
            name == "compat" -> parser.children { child ->
                if (child == "compatSetting" && parser.attr("name") == "compatibilityMode") {
                    mode = parser.attr("val")?.toIntOrNull() ?: mode
                }
                parser.skip()
            }
            else -> parser.skip()
        }
    }
    return DocxSettings(evenAndOdd, mode >= WORD_2013_MODE)
}

internal class DocxSettings(val evenAndOddHeaders: Boolean = false, val shrinkSpaces: Boolean = false)
