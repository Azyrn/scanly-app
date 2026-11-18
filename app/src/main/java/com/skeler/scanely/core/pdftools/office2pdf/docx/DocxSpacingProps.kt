package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser

/** Auto spacing is Word's HTML-import rule: a fixed 14 pt whatever the number says. */
internal fun parseSpacing(parser: XmlPullParser, values: MutableMap<ParaKey, Any>) {
    val autoBefore = isOn(parser.attr("beforeAutospacing"))
    val autoAfter = isOn(parser.attr("afterAutospacing"))
    values.putNotNull(ParaKey.BEFORE, if (autoBefore) AUTO_SPACING else parser.intAttr("before"))
    values.putNotNull(ParaKey.AFTER, if (autoAfter) AUTO_SPACING else parser.intAttr("after"))
    // A level that sets a space says whether it is automatic; one that sets none leaves both as inherited.
    if (autoBefore || parser.attr("before") != null) values[ParaKey.AUTO_BEFORE] = autoBefore
    if (autoAfter || parser.attr("after") != null) values[ParaKey.AUTO_AFTER] = autoAfter
    values.putNotNull(ParaKey.LINE, parser.intAttr("line"))
    values.putNotNull(ParaKey.LINE_RULE, LINE_RULES[parser.attr("lineRule")])
}

/** A hanging indent is stored as a negative first-line indent, and wins when both are given. */
internal fun parseIndent(parser: XmlPullParser, values: MutableMap<ParaKey, Any>) {
    values.putNotNull(ParaKey.LEFT, parser.intAttr("left") ?: parser.intAttr("start"))
    values.putNotNull(ParaKey.RIGHT, parser.intAttr("right") ?: parser.intAttr("end"))
    values.putNotNull(ParaKey.FIRST_LINE, parser.intAttr("firstLine"))
    values.putNotNull(ParaKey.FIRST_LINE, parser.intAttr("hanging")?.unaryMinus())
}

private fun isOn(value: String?): Boolean = value == "1" || value == "true"

private fun <K> MutableMap<K, Any>.putNotNull(key: K, value: Any?) {
    if (value != null) put(key, value)
}

private val LINE_RULES = mapOf("auto" to LineRule.AUTO, "exact" to LineRule.EXACT, "atLeast" to LineRule.AT_LEAST)

private const val AUTO_SPACING = 280
