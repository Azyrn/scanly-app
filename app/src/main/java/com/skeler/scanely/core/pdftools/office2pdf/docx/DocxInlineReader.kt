package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser

/** Paragraph-level wrappers whose children are read as if they were the paragraph's own. */
private val INLINE_CONTAINERS = setOf(
    "hyperlink",
    "ins",
    "smartTag",
    "customXml",
    "bdo",
    "dir",
    "fldSimple",
    "moveTo"
)

/** Run children that stand for one fixed character. */
private val FIXED_TEXT = mapOf(
    "tab" to "\t",
    "ptab" to "\t",
    "cr" to "\n",
    "noBreakHyphen" to "\u2011",
    "softHyphen" to "\u00AD"
)

private val VML_ELEMENTS = setOf("pict", "object")

/**
 * Run-level content: text, breaks, pictures, and everything that can't be drawn, counted into
 * [DocxNotes] instead. Deleted revisions and field instructions are dropped; inserted revisions
 * and field results are kept, which is what a printed copy of the final document shows.
 */
internal class DocxInlineReader(private val session: ReadSession) {

    /** One child of a paragraph (or of a wrapper inside it). */
    fun read(parser: XmlPullParser, name: String, context: InlineContext) {
        when {
            !parser.isW() -> readForeign(parser, name, context)
            name == "r" -> readRun(parser, context)
            name in INLINE_CONTAINERS -> parser.children { read(parser, it, context) }
            name == "sdt" -> session.readSdt(parser) { parser.children { read(parser, it, context) } }
            else -> parser.skip()
        }
    }

    private fun readForeign(parser: XmlPullParser, name: String, context: InlineContext) {
        when (name) {
            "AlternateContent" -> readAlternate(parser, context)
            "oMath", "oMathPara" -> {
                context.notes.equations++
                parser.skip()
            }
            else -> parser.skip()
        }
    }

    private fun readRun(parser: XmlPullParser, outer: InlineContext) {
        var direct = RunProps()
        var style: RunStyle? = null
        val context = outer.copy(
            runStyle = {
                style ?: direct.over(session.styles.character(direct[RunKey.STYLE]).over(outer.runBase()))
                    .toStyle()
                    .also { style = it }
            }
        )
        parser.children { name ->
            when {
                !parser.isW() -> readForeign(parser, name, context)
                name == "rPr" -> {
                    direct = parseRunProps(parser, session.theme)
                    style = null
                }
                else -> readRunChild(parser, name, context)
            }
        }
    }

    private fun readRunChild(parser: XmlPullParser, name: String, context: InlineContext) {
        val fixed = FIXED_TEXT[name]
        when {
            name == "t" -> text(context, parser.readText())
            fixed != null -> {
                text(context, fixed)
                parser.skip()
            }
            name == "br" -> readBreak(parser, context)
            name == "sym" -> readSymbol(parser, context)
            name == "drawing" -> readDrawing(parser, context)
            name in VML_ELEMENTS -> readVml(parser, context, isObject = name == "object")
            else -> readMarker(parser, name)
        }
    }

    /** Elements that only change state: field delimiters and note references. */
    private fun readMarker(parser: XmlPullParser, name: String) {
        when (name) {
            "fldChar" -> session.fields.mark(parser.attr("fldCharType"))
            "footnoteReference", "endnoteReference" -> session.notes.footnotes = true
        }
        parser.skip()
    }

    /** A column break has no columns to move to here, so it ends the line like a text wrap. */
    private fun readBreak(parser: XmlPullParser, context: InlineContext) {
        val type = parser.attr("type")
        parser.skip()
        when {
            session.fields.inInstruction -> Unit
            type == "page" -> context.pageBreak()
            else -> text(context, "\n")
        }
    }

    private fun readSymbol(parser: XmlPullParser, context: InlineContext) {
        val symbol = symbolOf(parser.attr("char"))
        parser.skip()
        if (symbol == null) return
        text(context, symbol)
        session.notes.fontsReplaced = true
    }

    private fun text(context: InlineContext, value: String) {
        if (value.isEmpty() || session.fields.inInstruction) return
        val style = context.runStyle?.invoke() ?: context.runBase().toStyle()
        if (style.hidden) return
        context.emit(DocxText(if (style.caps) value.uppercase() else value, style))
    }

    private fun readDrawing(parser: XmlPullParser, context: InlineContext) {
        if (session.fields.inInstruction) {
            parser.skip()
            return
        }
        parser.children { name ->
            if (name == "inline" || name == "anchor") readGraphic(parser, context) else parser.skip()
        }
    }

    /**
     * One DrawingML object. Only pictures can be drawn; a floating (anchored) picture is drawn
     * in line at its anchor paragraph, since nothing here can wrap text around it.
     */
    private fun readGraphic(parser: XmlPullParser, context: InlineContext) {
        val graphic = scanGraphic(parser)
        val entry = graphic.embed?.let(::imageEntry)
        when {
            "chart" in graphic.uri -> context.notes.charts++
            "diagram" in graphic.uri -> context.notes.diagrams++
            "picture" !in graphic.uri -> context.notes.shapes++
            entry != null -> context.emit(DocxImage(entry, graphic.widthEmu, graphic.heightEmu))
            else -> context.notes.unreadableImages++
        }
    }

    /**
     * Legacy VML: a picture (or an OLE object's preview picture) is drawn; a text box or shape
     * is left out and counted. Horizontal rules are dropped silently — they carry no content.
     */
    private fun readVml(parser: XmlPullParser, context: InlineContext, isObject: Boolean) {
        val vml = scanVml(parser)
        val entry = vml.relId?.let(::imageEntry)
        when {
            session.fields.inInstruction -> Unit
            entry != null -> context.emit(
                DocxImage(entry, (vml.widthPt * EMU_PER_POINT).toLong(), (vml.heightPt * EMU_PER_POINT).toLong())
            )
            isObject -> context.notes.objects++
            !vml.rule -> context.notes.shapes++
        }
    }

    /**
     * mc:AlternateContent offers a modern Choice and a Fallback every consumer understands. The
     * Fallback wins when present; both are read into scratch buffers so the loser leaves no trace,
     * not even in the notes.
     */
    private fun readAlternate(parser: XmlPullParser, context: InlineContext) {
        var choice: Pair<List<DocxInline>, DocxNotes>? = null
        var fallback: Pair<List<DocxInline>, DocxNotes>? = null
        parser.children { name ->
            when {
                name == "Fallback" -> fallback = collect(parser, context)
                name == "Choice" && choice == null -> choice = collect(parser, context)
                else -> parser.skip()
            }
        }
        val (inlines, notes) = fallback ?: choice ?: return
        inlines.forEach(context.emit)
        context.notes.add(notes)
    }

    private fun collect(parser: XmlPullParser, context: InlineContext): Pair<List<DocxInline>, DocxNotes> {
        val inlines = ArrayList<DocxInline>()
        val notes = DocxNotes()
        val scratch = context.copy(notes = notes, emit = { inlines += it }, pageBreak = {})
        parser.children { name ->
            when {
                !parser.isW() -> readForeign(parser, name, scratch)
                context.runStyle != null && name != "r" -> readRunChild(parser, name, scratch)
                else -> read(parser, name, scratch)
            }
        }
        return inlines to notes
    }

    /** A picture part this zip actually holds; linked (external) pictures can't be fetched offline. */
    private fun imageEntry(relId: String): String? = session.rels[relId]
        ?.takeUnless { it.external }
        ?.target
        ?.takeIf { session.zip.getEntry(it) != null }
}

private class GraphicInfo(val uri: String, val embed: String?, val widthEmu: Long, val heightEmu: Long)

private fun scanGraphic(parser: XmlPullParser): GraphicInfo {
    var width = 0L
    var height = 0L
    var uri = ""
    var embed: String? = null
    parser.descendants { name ->
        when {
            name == "extent" && width == 0L -> {
                width = parser.attr("cx")?.toLongOrNull() ?: 0L
                height = parser.attr("cy")?.toLongOrNull() ?: 0L
            }
            name == "graphicData" && uri.isEmpty() -> uri = parser.attr("uri").orEmpty()
            name == "blip" && embed == null -> embed = parser.relAttr("embed")
        }
    }
    return GraphicInfo(uri, embed, width, height)
}

private class VmlInfo(val relId: String?, val widthPt: Float, val heightPt: Float, val rule: Boolean)

private fun scanVml(parser: XmlPullParser): VmlInfo {
    var relId: String? = null
    var size: Pair<Float, Float>? = null
    var rule = false
    parser.descendants { name ->
        if (name == "imagedata" && relId == null) relId = parser.relAttr("id") ?: parser.attr("relid")
        if (size == null) size = parser.attr("style")?.let(::cssSize)
        if (parser.attr("hr") == "t") rule = true
    }
    val (width, height) = size ?: (0f to 0f)
    return VmlInfo(relId, width, height, rule)
}

/** w:sym names a code in a symbol font; Word stores it either raw or in the F000 private range. */
private fun symbolOf(hex: String?): String? {
    val code = hex?.toIntOrNull(HEX_RADIX)?.takeIf { it <= Char.MAX_VALUE.code } ?: return null
    val privateUse = if (code < SYMBOL_PAGE_SIZE) code + SYMBOL_FONT_BASE else code
    return symbolText(privateUse.toChar().toString())
}

private const val HEX_RADIX = 16
private const val SYMBOL_PAGE_SIZE = 0x100
private const val SYMBOL_FONT_BASE = 0xF000

/** VML sizes come as CSS: "width:120pt;height:80.5pt". Returns points, or null without both. */
private fun cssSize(style: String): Pair<Float, Float>? {
    val properties = style.split(';').mapNotNull { declaration ->
        val parts = declaration.split(':', limit = 2)
        if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim().lowercase() else null
    }.toMap()
    val width = properties["width"]?.let(::cssLength)?.takeIf { it > 0f }
    val height = properties["height"]?.let(::cssLength)?.takeIf { it > 0f }
    return if (width != null && height != null) width to height else null
}

private fun cssLength(value: String): Float? {
    val unit = CSS_UNITS.keys.firstOrNull { value.endsWith(it) }
    val number = (if (unit == null) value else value.dropLast(unit.length)).toFloatOrNull()
    return number?.times(CSS_UNITS[unit] ?: PX_IN_POINTS)
}

/** CSS without a unit means pixels at 96 dpi. */
private const val PX_IN_POINTS = 0.75f

private val CSS_UNITS = mapOf(
    "pt" to 1f,
    "in" to 72f,
    "cm" to 28.3465f,
    "mm" to 2.83465f,
    "px" to PX_IN_POINTS,
    "pc" to 12f
)
