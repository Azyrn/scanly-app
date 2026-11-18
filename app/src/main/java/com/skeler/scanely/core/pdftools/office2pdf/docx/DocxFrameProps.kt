package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser

/** w:framePr with Word's defaults (MS-OI29500 §2.1.43); a drop cap is not a frame here. */
internal fun parseFrame(parser: XmlPullParser, values: MutableMap<ParaKey, Any>) {
    if (parser.attr("dropCap").let { it != null && it != "none" }) return
    val height = parser.twips("h")
    values[ParaKey.FRAME] = FramePosition(
        xTwips = parser.twips("x"),
        yTwips = parser.twips("y"),
        xAlign = parser.attr("xAlign"),
        yAlign = parser.attr("yAlign"),
        hAnchor = parser.attr("hAnchor") ?: TEXT_ANCHOR,
        vAnchor = parser.attr("vAnchor") ?: TEXT_ANCHOR,
        widthTwips = parser.twips("w").coerceAtLeast(0),
        heightTwips = height.coerceIn(0, MAX_FRAME_HEIGHT),
        exactHeight = height != 0 && parser.attr("hRule") == "exact",
        wrapNone = parser.attr("wrap") == "none"
    )
}

private fun XmlPullParser.twips(name: String): Int = intAttr(name) ?: 0

/** Word's default frame anchor, both ways. */
private const val TEXT_ANCHOR = "text"

/** Word's own limit on a frame's height (MS-OI29500 §2.1.43). */
private const val MAX_FRAME_HEIGHT = 31_680
