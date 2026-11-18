package com.skeler.scanely.core.pdftools.office2pdf.docx

import org.xmlpull.v1.XmlPullParser

/**
 * Run children that mark something rather than show text: field delimiters and instructions, footnote
 * references and the marks and separators inside footnotes. Anything else is skipped.
 */
internal class DocxMarkers(private val session: ReadSession) {

    fun read(parser: XmlPullParser, name: String, context: InlineContext) {
        when (name) {
            "instrText" -> session.fields.instruction(parser.readText())
            "footnoteReference" -> readNoteReference(parser, context)
            "footnoteRef" -> emitStyled(parser, context) { DocxNoteMark(it) }
            "separator", "continuationSeparator" -> emitStyled(parser, context) { DocxSeparator(it) }
            "fldChar" -> {
                session.fields.mark(parser.attr("fldCharType"))
                parser.skip()
            }
            "endnoteReference" -> {
                session.notes.footnotes = true
                parser.skip()
            }
            else -> parser.skip()
        }
    }

    /** w:fldSimple: the instruction is an attribute and the children ([readResult]) are the cached result. */
    fun simpleField(parser: XmlPullParser, readResult: () -> Unit) {
        session.fields.mark("begin")
        session.fields.instruction(parser.attr("instr").orEmpty())
        session.fields.mark("separate")
        readResult()
        session.fields.mark("end")
    }

    /** A footnote reference shows the next number, unless the run's own text is its mark (customMarkFollows). */
    private fun readNoteReference(parser: XmlPullParser, context: InlineContext) {
        val id = parser.attr("id")
        val custom = parser.attr("customMarkFollows")?.let { it == "1" || it == "true" } == true
        parser.skip()
        if (id == null || session.fields.inInstruction) return
        val style = context.runStyle?.invoke() ?: context.runBase().toStyle()
        val mark = if (custom) "" else (++session.footnoteCount).toString()
        context.emit(DocxNoteRef(id, mark, style))
    }

    private fun emitStyled(parser: XmlPullParser, context: InlineContext, inline: (RunStyle) -> DocxInline) {
        parser.skip()
        if (session.fields.inInstruction) return
        context.emit(inline(context.runStyle?.invoke() ?: context.runBase().toStyle()))
    }
}
