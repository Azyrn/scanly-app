package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser
import kotlin.math.abs

/**
 * w:sectPr: page size and margins, the header and footer parts the section names, its first-page
 * and page-numbering settings; multi-column layouts are noted.
 */
internal class SectionReader(private val notes: DocxNotes) {
    fun read(parser: XmlPullParser): PageSetup {
        var setup = PageSetup()
        parser.children { name ->
            if (parser.isW()) setup = apply(name, parser, setup)
            parser.skip()
        }
        return setup
    }

    private fun apply(name: String, parser: XmlPullParser, setup: PageSetup): PageSetup = when (name) {
        "pgSz" -> pageSize(parser, setup)
        "pgMar" -> distances(parser, margins(parser, setup))
        "type" -> setup.copy(continuous = parser.attr("val") == "continuous")
        "cols" -> setup.also { noteColumns(parser) }
        else -> pages(name, parser, setup)
    }

    /** What decides each page's header and number: title page, page numbering, header and footer parts. */
    private fun pages(name: String, parser: XmlPullParser, setup: PageSetup): PageSetup = when (name) {
        "titlePg" -> setup.copy(titlePage = parser.toggle())
        "pgNumType" -> setup.copy(pageStart = parser.intAttr("start") ?: setup.pageStart)
        "headerReference", "footerReference" -> withReference(name == "headerReference", parser, setup)
        else -> setup
    }

    private fun withReference(header: Boolean, parser: XmlPullParser, setup: PageSetup): PageSetup {
        val (kind, id) = reference(parser) ?: return setup
        return if (header) {
            setup.copy(headers = setup.headers + (kind to id))
        } else {
            setup.copy(footers = setup.footers + (kind to id))
        }
    }

    private fun noteColumns(parser: XmlPullParser) {
        if ((parser.intAttr("num") ?: 1) > 1) notes.columns = true
    }

    private fun reference(parser: XmlPullParser): Pair<HeaderKind, String>? {
        val id = parser.relAttr("id") ?: return null
        val kind = when (parser.attr("type")) {
            "first" -> HeaderKind.FIRST
            "even" -> HeaderKind.EVEN
            else -> HeaderKind.DEFAULT
        }
        return kind to id
    }

    private fun pageSize(parser: XmlPullParser, setup: PageSetup): PageSetup {
        val width = parser.intAttr("w") ?: setup.widthTwips
        val height = parser.intAttr("h") ?: setup.heightTwips
        val landscape = parser.attr("orient") == "landscape"
        // Word writes landscape as swapped sizes plus the flag; some generators set only the flag.
        return if (landscape && width < height) {
            setup.copy(widthTwips = height, heightTwips = width)
        } else {
            setup.copy(widthTwips = width, heightTwips = height)
        }
    }

    /** The four margins; a negative top/bottom one counts by its size (see [distances]). */
    private fun margins(parser: XmlPullParser, setup: PageSetup): PageSetup = setup.copy(
        marginTop = parser.intAttr("top")?.let(::abs) ?: setup.marginTop,
        marginBottom = parser.intAttr("bottom")?.let(::abs) ?: setup.marginBottom,
        marginLeft = parser.intAttr("left") ?: parser.intAttr("start") ?: setup.marginLeft,
        marginRight = parser.intAttr("right") ?: parser.intAttr("end") ?: setup.marginRight
    )

    /** Header and footer distances; a negative top/bottom margin keeps the body where it is under a taller one. */
    private fun distances(parser: XmlPullParser, setup: PageSetup): PageSetup = setup.copy(
        headerTwips = parser.intAttr("header")?.let(::abs) ?: setup.headerTwips,
        footerTwips = parser.intAttr("footer")?.let(::abs) ?: setup.footerTwips,
        fixedTop = parser.intAttr("top")?.let { it < 0 } ?: setup.fixedTop,
        fixedBottom = parser.intAttr("bottom")?.let { it < 0 } ?: setup.fixedBottom
    )
}
