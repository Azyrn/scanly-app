package com.skeler.scanely.core.pdftools.office2pdf.pptx

/*
 * Fills, outlines and frames: the DrawingML spPr pieces every kind of shape shares.
 */

private const val MIN_LINE_WIDTH = 0.25f
private const val DEFAULT_LINE_WIDTH = 0.75f

/** The fill elements of an spPr, bgPr or tcPr; the first one present decides the fill. */
private val FILL_ELEMENTS = setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")

/** Reads fills, which can point at images in the package and count what had to be simplified. */
internal class FillReader(private val pkg: PptxPackage, private val issues: PptxIssues) {

    fun backgroundOf(root: XNode, ctx: PartContext): Fill? {
        val bg = root.path("cSld", "bg") ?: return null
        bg.child("bgPr")?.let { return fillOf(it, ctx, null)?.takeUnless { f -> f == Fill.None } }
        bg.child("bgRef")?.let { ref -> return colorOf(ref, ctx.colors)?.let { Fill.Solid(it) } }
        return null
    }

    /** Null when [container] says nothing about fill, [Fill.None] when it explicitly says "none". */
    fun fillOf(container: XNode?, ctx: PartContext, groupFill: Fill?): Fill? {
        container ?: return null
        val c = container.children.firstOrNull { it.name in FILL_ELEMENTS } ?: return null
        return fillFrom(c, ctx, groupFill)
    }

    private fun fillFrom(c: XNode, ctx: PartContext, groupFill: Fill?): Fill = when (c.name) {
        "noFill" -> Fill.None
        "solidFill" -> solidOrNone(colorOf(c, ctx.colors))
        "gradFill" -> gradientOf(c, ctx.colors)
        "blipFill" -> imageFill(c, ctx)
        "pattFill" -> patternFill(c, ctx)
        else -> groupFill ?: Fill.None // grpFill
    }

    /** A picture fill whose image is missing from the package draws nothing. */
    private fun imageFill(c: XNode, ctx: PartContext): Fill = blipPart(c, ctx)?.let { Fill.Image(it) } ?: Fill.None

    /** Patterns are drawn as their foreground colour. */
    private fun patternFill(c: XNode, ctx: PartContext): Fill {
        issues.shapesSimplified = true
        return solidOrNone(colorOf(c.child("fgClr"), ctx.colors))
    }

    /**
     * A shape's own fill, else its style's fillRef colour. The style's fill index is a theme fill
     * slot; only its colour is used, and index 0 means "no fill".
     */
    fun shapeFill(props: List<XNode>, style: XNode?, ctx: PartContext, groupFill: Fill?): Fill? =
        props.firstNotNullOfOrNull { fillOf(it, ctx, groupFill) }
            ?: style?.child("fillRef")?.takeIf { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 }
                ?.let { colorOf(it, ctx.colors) }?.let { Fill.Solid(it) }

    private fun gradientOf(node: XNode, colors: Colors): Fill {
        val stops = node.child("gsLst")?.children("gs").orEmpty().mapNotNull { gs ->
            colorOf(gs, colors)?.let { GradientStop((percent(gs.attr("pos")) ?: 0f).coerceIn(0f, 1f), it) }
        }.sortedBy { it.position }
        if (node.child("path") != null) issues.shapesSimplified = true
        return when (stops.size) {
            0 -> Fill.None
            1 -> Fill.Solid(stops[0].color)
            else -> Fill.Gradient(stops, (node.child("lin")?.attr("ang")?.toIntOrNull() ?: 0) / ANGLE_SCALE)
        }
    }

    fun blipPart(blipFill: XNode, ctx: PartContext): String? {
        val id = blipFill.child("blip")?.attr(REL_PREFIX + "embed") ?: return null
        return ctx.rels[id]?.target?.takeIf { pkg.hasEntry(it) }
    }
}

private fun solidOrNone(color: Int?): Fill = color?.let { Fill.Solid(it) } ?: Fill.None

/**
 * A shape's outline: its own `ln` (in [holder]) wins, then the style's lnRef. An `ln` with
 * only a width still takes its colour from the style, which is how most themed shapes are written.
 */
internal fun strokeOf(holder: XNode?, style: XNode?, colors: Colors, lineName: String = "ln"): Stroke? {
    val ref = style?.child("lnRef")
    val refIndex = ref?.attr("idx")?.toIntOrNull() ?: 0
    val refColor = if (refIndex > 0) colorOf(ref, colors) else null
    val refWidth = themeLineWidth(refIndex, colors)
    val ln = holder?.child(lineName)
        ?: return refColor?.let { Stroke(it, refWidth.coerceAtLeast(MIN_LINE_WIDTH)) }
    if (ln.child("noFill") != null) return null
    val color = lineColor(ln, colors) ?: refColor ?: return null
    val width = emu(ln.attr("w")) ?: refWidth
    return Stroke(color, width.coerceAtLeast(MIN_LINE_WIDTH), dashed = isDashed(ln))
}

private fun isDashed(ln: XNode): Boolean {
    val dash = ln.child("prstDash")?.attr("val")
    return dash != null && dash != "solid"
}

/** The theme's line width for a 1-based lnRef index; index 0 (no reference) gets the default. */
private fun themeLineWidth(refIndex: Int, colors: Colors): Float = if (refIndex > 0) {
    colors.theme.lineWidths.getOrNull(refIndex - 1) ?: DEFAULT_LINE_WIDTH
} else {
    DEFAULT_LINE_WIDTH
}

/** A gradient outline is drawn in its first stop's colour. */
private fun lineColor(ln: XNode, colors: Colors): Int? =
    colorOf(ln.child("solidFill"), colors)
        ?: ln.path("gradFill", "gsLst", "gs")?.let { colorOf(it, colors) }

internal fun frameOf(xfrm: XNode?): Frame? {
    xfrm ?: return null
    val off = xfrm.child("off") ?: return null
    val ext = xfrm.child("ext") ?: return null
    return Frame(
        x = emu(off.attr("x")) ?: 0f,
        y = emu(off.attr("y")) ?: 0f,
        width = (emu(ext.attr("cx")) ?: 0f).coerceAtLeast(0f),
        height = (emu(ext.attr("cy")) ?: 0f).coerceAtLeast(0f),
        rotation = (xfrm.attr("rot")?.toIntOrNull() ?: 0) / ANGLE_SCALE,
        flipH = isOn(xfrm.attr("flipH")),
        flipV = isOn(xfrm.attr("flipV"))
    )
}

/** An xsd:boolean attribute as written by PowerPoint ("1") or other generators ("true"). */
private fun isOn(value: String?): Boolean = value == "1" || value == "true"
