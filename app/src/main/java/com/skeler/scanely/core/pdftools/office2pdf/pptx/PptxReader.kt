package com.skeler.scanely.core.pdftools.office2pdf.pptx

import java.io.Closeable
import java.io.File
import java.io.InputStream

/** PowerPoint's own slide-size limits: 1 to 56 inches. */
private const val MIN_SLIDE_PT = 72f
private const val MAX_SLIDE_PT = 4032f
private const val DEFAULT_SLIDE_WIDTH_PT = 720f
private const val DEFAULT_SLIDE_HEIGHT_PT = 540f

/**
 * Reads a .pptx into [PptxSlide]s, one slide at a time so a long deck never sits in memory whole.
 *
 * Everything the slide shows is resolved here — placeholder positions and text styles inherited
 * from the layout and master, theme colours, group transforms — and everything it can't show is
 * counted in [issues] rather than failing the deck. A part that isn't valid XML, or that exceeds
 * the size caps, throws [PptxFormatException].
 *
 * The XML parser is injected because the on-device pull parser doesn't exist on a plain JVM; the
 * tests hand in their own and exercise everything else unchanged.
 */
internal class PptxReader(
    file: File,
    parseXml: (InputStream) -> XNode = ::parsePullXml
) : Closeable {

    val issues = PptxIssues()

    private val pkg = PptxPackage(file, parseXml)
    private val shapes = PptxShapeReader(pkg, issues)
    private var defaultTextStyle: XNode? = null

    override fun close() {
        pkg.close()
    }

    fun readPresentation(): PptxPresentation {
        val main = pkg.relsOf("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target
            ?: "ppt/presentation.xml"
        val root = pkg.parsePart(main) ?: throw PptxFormatException("no presentation part")
        if (root.name != "presentation") throw PptxFormatException("main part is not a presentation")
        defaultTextStyle = root.child("defaultTextStyle")

        val size = root.child("sldSz")
        val width = (emu(size?.attr("cx")) ?: DEFAULT_SLIDE_WIDTH_PT).coerceIn(MIN_SLIDE_PT, MAX_SLIDE_PT)
        val height = (emu(size?.attr("cy")) ?: DEFAULT_SLIDE_HEIGHT_PT).coerceIn(MIN_SLIDE_PT, MAX_SLIDE_PT)

        val rels = pkg.relsOf(main)
        val slides = root.child("sldIdLst")?.children("sldId").orEmpty().mapNotNull { id ->
            id.attr(REL_PREFIX + "id")?.let { rels[it] }?.target?.takeIf { pkg.hasEntry(it) }
        }.map { SlideRef(it, pkg.isHidden(it)) }
        return PptxPresentation(width, height, slides)
    }

    /** Opens a media entry for decoding; the stream fails past [MAX_MEDIA_BYTES]. */
    fun openMedia(part: String): InputStream? = pkg.openEntry(part, MAX_MEDIA_BYTES)

    fun readSlide(part: String): PptxSlide {
        val root = pkg.parsePart(part) ?: throw PptxFormatException("missing slide part")
        if (root.name != "sld") throw PptxFormatException("not a slide")
        val rels = pkg.relsOf(part)
        val layout = rels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.let { pkg.layoutOf(it.target) }
        val master = layout?.master
        val colors = master?.context?.colors ?: Colors.DEFAULT
        val defaults = parseListStyle(defaultTextStyle, colors)
        val context = PartContext(rels, colors)

        val elements = ArrayList<SlideElement>()
        if (root.attr("showMasterSp") != "0" && layout != null) readDecoration(layout, defaults, elements)
        shapeTree(root)?.let {
            shapes.walkTree(it, Scope(context, layout, master, defaults, slide = true), elements)
        }
        return PptxSlide(backgroundOf(root, context, layout), elements)
    }

    /**
     * The master's shapes (unless the layout hides them) and then the layout's own, drawn under the
     * slide's. Their placeholders are skipped: those only lend position and style to the slide's.
     */
    private fun readDecoration(layout: LayoutPart, defaults: ListStyle?, out: MutableList<SlideElement>) {
        val master = layout.master
        if (master != null && layout.root.attr("showMasterSp") != "0") {
            shapeTree(master.root)?.let {
                shapes.walkTree(it, Scope(master.context, null, null, defaults, slide = false), out)
            }
        }
        shapeTree(layout.root)?.let {
            shapes.walkTree(it, Scope(layout.context, null, null, defaults, slide = false), out)
        }
    }

    /** The slide's own background, else its layout's, else its master's. */
    private fun backgroundOf(root: XNode, context: PartContext, layout: LayoutPart?): Fill? =
        shapes.fills.backgroundOf(root, context)
            ?: layout?.let { shapes.fills.backgroundOf(it.root, it.context) }
            ?: layout?.master?.let { shapes.fills.backgroundOf(it.root, it.context) }
}
