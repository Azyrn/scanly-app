package com.skeler.scanely.core.pdftools.office2pdf.pptx

/*
 * Placeholder inheritance: a slide shape that is a placeholder takes whatever it leaves unset
 * (position, geometry, text styles) from the matching layout placeholder, then the master's.
 */

/** [slide] is false while drawing master/layout decoration, whose placeholders are never drawn. */
internal class Scope(
    val context: PartContext,
    val layout: LayoutPart?,
    val master: MasterPart?,
    val defaults: ListStyle?,
    val slide: Boolean
)

/** The placeholders a slide shape inherits from, nearest first. */
internal class Inherited(val layout: XNode?, val master: XNode?, val type: String?)

internal fun inherited(node: XNode, scope: Scope): Inherited? {
    if (!scope.slide) return null
    val ph = placeholderOf(node) ?: return null
    val layoutPh = scope.layout?.let { findPlaceholder(it.placeholders, ph.attr("type"), ph.attr("idx")) }
    val type = ph.attr("type") ?: layoutPh?.let { placeholderOf(it)?.attr("type") }
    val masterPh = scope.master?.let { findPlaceholder(it.placeholders, type, null) }
    return Inherited(layoutPh, masterPh, type)
}

/** The master text style a placeholder of [type] starts from. */
internal fun masterTextStyle(master: MasterPart?, type: String?): ListStyle? = when (type) {
    "title", "ctrTitle" -> master?.titleStyle
    "dt", "ftr", "sldNum", "hdr" -> master?.otherStyle
    else -> master?.bodyStyle
}

internal fun placeholderOf(node: XNode): XNode? =
    node.children.firstOrNull { it.name.startsWith("nv") }?.child("nvPr")?.child("ph")

/** Placeholder types that share one master slot: subtitles, objects, tables and media all sit in "body". */
private fun normalizedType(type: String?): String = when (type) {
    null, "obj", "body", "subTitle", "tbl", "chart", "dgm", "media", "clipArt", "pic" -> "body"
    "ctrTitle", "title" -> "title"
    else -> type
}

private fun findPlaceholder(candidates: List<XNode>, type: String?, idx: String?): XNode? {
    if (idx != null) {
        candidates.firstOrNull { placeholderOf(it)?.attr("idx") == idx }?.let { return it }
    }
    val wanted = normalizedType(type)
    return candidates.firstOrNull { normalizedType(placeholderOf(it)?.attr("type")) == wanted }
}
