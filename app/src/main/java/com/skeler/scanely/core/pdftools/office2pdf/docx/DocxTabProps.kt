package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser

/** w:tabs: a few stops in a real document; a crafted one cannot make more than [MAX_TABS] count. */
internal fun parseTabs(parser: XmlPullParser): TabStops {
    val stops = ArrayList<TabStop>()
    parser.children { name ->
        val pos = parser.intAttr("pos")?.takeIf { parser.isW() && name == "tab" }
        if (pos != null && stops.size < MAX_TABS) {
            stops += TabStop(pos, TAB_KINDS[parser.attr("val")] ?: TabKind.START)
        }
        parser.skip()
    }
    return TabStops(stops)
}

private const val MAX_TABS = 64
private val TAB_KINDS = mapOf(
    "clear" to TabKind.CLEAR,
    "center" to TabKind.CENTER,
    "right" to TabKind.END,
    "end" to TabKind.END,
    "decimal" to TabKind.END
)
