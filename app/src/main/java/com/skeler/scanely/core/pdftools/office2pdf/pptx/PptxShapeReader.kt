package com.skeler.scanely.core.pdftools.office2pdf.pptx

import kotlin.math.cos
import kotlin.math.sin

private const val MAX_GROUP_DEPTH = 32
private const val FULL_TURN = 360f

private val PRESETS = mapOf(
    "rect" to Geometry.RECT, "flowChartProcess" to Geometry.RECT,
    "roundRect" to Geometry.ROUND_RECT, "flowChartAlternateProcess" to Geometry.ROUND_RECT,
    "ellipse" to Geometry.ELLIPSE, "flowChartConnector" to Geometry.ELLIPSE,
    "line" to Geometry.LINE, "straightConnector1" to Geometry.LINE,
    "triangle" to Geometry.TRIANGLE, "rtTriangle" to Geometry.RIGHT_TRIANGLE,
    "diamond" to Geometry.DIAMOND, "flowChartDecision" to Geometry.DIAMOND,
    "rightArrow" to Geometry.ARROW_RIGHT, "leftArrow" to Geometry.ARROW_LEFT,
    "upArrow" to Geometry.ARROW_UP, "downArrow" to Geometry.ARROW_DOWN
)

/** Bent and curved connectors still join the same two corners; drawn straight, with a note. */
private val CONNECTOR_PRESETS = setOf(
    "bentConnector2",
    "bentConnector3",
    "bentConnector4",
    "bentConnector5",
    "curvedConnector2",
    "curvedConnector3",
    "curvedConnector4",
    "curvedConnector5"
)

private val MEDIA_TAGS = setOf("videoFile", "audioFile", "quickTimeFile", "wavAudioFile", "media")

/**
 * Maps a group's child coordinate space onto its parent's: scale from chOff/chExt to off/ext,
 * then the group's own flips and rotation about its centre. Children come out flattened, so the
 * renderer only ever sees boxes on the slide.
 */
private class GroupTransform(
    private val parent: GroupTransform?,
    private val frame: Frame,
    private val childX: Float,
    private val childY: Float,
    private val childWidth: Float,
    private val childHeight: Float
) {
    fun apply(f: Frame): Frame {
        val sx = if (childWidth > 0) frame.width / childWidth else 1f
        val sy = if (childHeight > 0) frame.height / childHeight else 1f
        val w = f.width * sx
        val h = f.height * sy
        var cx = frame.x + (f.x - childX) * sx + w / 2
        var cy = frame.y + (f.y - childY) * sy + h / 2
        var rotation = f.rotation
        var flipH = f.flipH
        var flipV = f.flipV
        val gx = frame.x + frame.width / 2
        val gy = frame.y + frame.height / 2
        // A mirror conjugates the child's own rotation: R(θ) seen in a mirror is R(-θ) plus a flip.
        if (frame.flipH) {
            cx = 2 * gx - cx
            flipH = !flipH
            rotation = -rotation
        }
        if (frame.flipV) {
            cy = 2 * gy - cy
            flipV = !flipV
            rotation = -rotation
        }
        if (frame.rotation != 0f) {
            val rad = Math.toRadians(frame.rotation.toDouble())
            val dx = cx - gx
            val dy = cy - gy
            cx = gx + (dx * cos(rad) - dy * sin(rad)).toFloat()
            cy = gy + (dx * sin(rad) + dy * cos(rad)).toFloat()
            rotation += frame.rotation
        }
        val out = Frame(cx - w / 2, cy - h / 2, w, h, rotation % FULL_TURN, flipH, flipV)
        return parent?.apply(out) ?: out
    }
}

/**
 * Turns a shape tree into [SlideElement]s: shapes, pictures, tables and groups (flattened through
 * their transforms). What can't be shown is counted in [issues] rather than failing the slide.
 */
internal class PptxShapeReader(pkg: PptxPackage, private val issues: PptxIssues) {
    val fills = FillReader(pkg, issues)
    private val tables = PptxTableReader(fills, issues)

    fun walkTree(tree: XNode, scope: Scope, out: MutableList<SlideElement>) {
        walk(tree, scope, null, out, null, 0)
    }

    private fun walk(
        tree: XNode,
        scope: Scope,
        transform: GroupTransform?,
        out: MutableList<SlideElement>,
        groupFill: Fill?,
        depth: Int
    ) {
        if (depth > MAX_GROUP_DEPTH) {
            issues.shapesSimplified = true
            return
        }
        for (node in tree.children) {
            // Master and layout placeholders only lend their looks; they are never drawn themselves.
            if (scope.slide || placeholderOf(node) == null) readNode(node, scope, transform, out, groupFill, depth)
        }
    }

    private fun readNode(
        node: XNode,
        scope: Scope,
        transform: GroupTransform?,
        out: MutableList<SlideElement>,
        groupFill: Fill?,
        depth: Int
    ) {
        try {
            when (node.name) {
                "sp", "cxnSp" -> readShape(node, scope, transform, out, groupFill)
                "pic" -> readPicture(node, scope, transform, out, null)
                "graphicFrame" -> readGraphicFrame(node, scope, transform, out)
                "grpSp" -> readGroup(node, scope, transform, out, groupFill, depth)
                "AlternateContent" -> alternateOf(node)?.let { walk(it, scope, transform, out, groupFill, depth + 1) }
                "contentPart" -> issues.shapesSimplified = true
            }
        } catch (_: IllegalArgumentException) {
            // Covers NumberFormatException too: one odd shape is simplified away, not the deck.
            issues.shapesSimplified = true
        }
    }

    private fun readGroup(
        node: XNode,
        scope: Scope,
        transform: GroupTransform?,
        out: MutableList<SlideElement>,
        groupFill: Fill?,
        depth: Int
    ) {
        val props = node.child("grpSpPr")
        val xfrm = props?.child("xfrm")
        val frame = frameOf(xfrm)
        val fill = fills.fillOf(props, scope.context, groupFill) ?: groupFill
        if (frame == null) {
            walk(node, scope, transform, out, fill, depth + 1)
            return
        }
        val inner = GroupTransform(
            parent = transform,
            frame = frame,
            childX = emu(xfrm?.child("chOff")?.attr("x")) ?: frame.x,
            childY = emu(xfrm?.child("chOff")?.attr("y")) ?: frame.y,
            childWidth = emu(xfrm?.child("chExt")?.attr("cx")) ?: frame.width,
            childHeight = emu(xfrm?.child("chExt")?.attr("cy")) ?: frame.height
        )
        walk(node, scope, inner, out, fill, depth + 1)
    }

    private fun readShape(
        node: XNode,
        scope: Scope,
        transform: GroupTransform?,
        out: MutableList<SlideElement>,
        groupFill: Fill?
    ) {
        val ctx = scope.context
        val inherited = inherited(node, scope)
        val props = shapeProps(node, inherited)
        val frame = props.firstNotNullOfOrNull { frameOf(it.child("xfrm")) } ?: return
        val style = shapeStyle(node, inherited)

        val fill = fills.shapeFill(props, style, ctx, groupFill)
        val stroke = strokeOf(props.firstOrNull { it.child("ln") != null }, style, ctx.colors)
        val text = shapeText(node, inherited, style, scope)

        val visibleFill = fill?.takeUnless { it == Fill.None }
        if (visibleFill == null && stroke == null && text == null) return

        val geometrySpec = props.firstNotNullOfOrNull { it.child("prstGeom") ?: it.child("custGeom") }
        out += ShapeElement(
            frame = transform?.apply(frame) ?: frame,
            geometry = geometryOf(geometrySpec, boxVisible = visibleFill != null || stroke != null),
            adjust = adjustOf(geometrySpec),
            fill = visibleFill,
            stroke = stroke,
            text = text
        )
    }

    private fun shapeText(node: XNode, inherited: Inherited?, style: XNode?, scope: Scope): TextBody? {
        val txBody = node.child("txBody")
        val bodyChain = listOfNotNull(
            txBody?.child("bodyPr"),
            inherited?.layout?.path("txBody", "bodyPr"),
            inherited?.master?.path("txBody", "bodyPr")
        )
        val styles = textStyles(style, inherited, scope)
        return readTextBody(txBody, bodyChain, styles, scope.context.colors, issues)
    }

    /**
     * Known presets draw as themselves; connectors draw straight. Any other preset or custom
     * geometry falls back to its bounding box, which only counts as a simplification when the
     * box is actually drawn (a text-only shape looks the same either way).
     */
    private fun geometryOf(spec: XNode?, boxVisible: Boolean): Geometry {
        val preset = spec?.takeIf { it.name == "prstGeom" }?.attr("prst")
        val known = preset?.let { PRESETS[it] }
        return when {
            spec == null -> Geometry.RECT
            known != null -> known
            preset != null && preset in CONNECTOR_PRESETS -> Geometry.LINE.also { issues.shapesSimplified = true }
            else -> boundingBox(boxVisible)
        }
    }

    private fun boundingBox(boxVisible: Boolean): Geometry {
        if (boxVisible) issues.shapesSimplified = true
        return Geometry.RECT
    }

    private fun readPicture(
        node: XNode,
        scope: Scope,
        transform: GroupTransform?,
        out: MutableList<SlideElement>,
        fallbackFrame: Frame?
    ) {
        val props = shapeProps(node, inherited(node, scope))
        val frame = props.firstNotNullOfOrNull { frameOf(it.child("xfrm")) } ?: fallbackFrame ?: return
        countMedia(node)

        val blipFill = node.child("blipFill") ?: return
        val part = fills.blipPart(blipFill, scope.context) ?: run {
            issues.missingImages++
            return
        }
        val crop = cropOf(blipFill)
        val preset = props.firstNotNullOfOrNull { it.child("prstGeom") }?.attr("prst")
        out += PictureElement(
            frame = transform?.apply(frame) ?: frame,
            part = part,
            crop = crop,
            geometry = preset?.let { PRESETS[it] }?.takeIf { it != Geometry.LINE } ?: Geometry.RECT,
            stroke = strokeOf(node.child("spPr"), node.child("style"), scope.context.colors)
        )
    }

    /** Video and audio show as their poster picture; the media itself is counted, not played. */
    private fun countMedia(node: XNode) {
        val nvPr = node.child("nvPicPr")?.child("nvPr")
        if (nvPr != null && MEDIA_TAGS.any { nvPr.contains(it) }) issues.media++
    }

    private fun readGraphicFrame(
        node: XNode,
        scope: Scope,
        transform: GroupTransform?,
        out: MutableList<SlideElement>
    ) {
        val frame = graphicFrameOf(node, inherited(node, scope))
        val data = node.path("graphic", "graphicData")
        val uri = data?.attr("uri").orEmpty()
        when {
            uri.endsWith("/table") -> readTableFrame(data?.child("tbl"), frame, transform, scope, out)
            uri.contains("chart", ignoreCase = true) -> issues.charts++
            uri.endsWith("/diagram") -> issues.diagrams++
            else -> readObject(data, frame, scope, transform, out)
        }
    }

    private fun readTableFrame(
        table: XNode?,
        frame: Frame?,
        transform: GroupTransform?,
        scope: Scope,
        out: MutableList<SlideElement>
    ) {
        if (frame != null && table != null) {
            tables.readTable(table, transform?.apply(frame) ?: frame, scope.context)?.let(out::add)
        }
    }

    /** OLE objects usually carry a picture of themselves; show that when there is one. */
    private fun readObject(
        data: XNode?,
        frame: Frame?,
        scope: Scope,
        transform: GroupTransform?,
        out: MutableList<SlideElement>
    ) {
        val preview = data?.let { findDescendant(it, "pic") }
        if (preview != null && preview.child("blipFill") != null) {
            readPicture(preview, scope, transform, out, frame)
        } else {
            issues.embeddedObjects++
        }
    }
}

/** A shape's spPr followed by the inherited placeholders' ones, nearest first. */
private fun shapeProps(node: XNode, inherited: Inherited?): List<XNode> = listOfNotNull(
    node.child("spPr"),
    inherited?.layout?.child("spPr"),
    inherited?.master?.child("spPr")
)

/** The shape's style references (lnRef, fillRef, fontRef), its own or the nearest placeholder's. */
private fun shapeStyle(node: XNode, inherited: Inherited?): XNode? =
    node.child("style") ?: inherited?.layout?.child("style") ?: inherited?.master?.child("style")

/** The list-style cascade under a shape's own lstStyle, strongest first. */
private fun textStyles(style: XNode?, inherited: Inherited?, scope: Scope): List<ListStyle> {
    val colors = scope.context.colors
    val styles = ArrayList<ListStyle>()
    style?.child("fontRef")?.let { ref ->
        val face = if (ref.attr("idx") == "major") "+mj-lt" else "+mn-lt"
        styles += uniformStyle(LevelProps(run = RunProps(color = colorOf(ref, colors), typeface = face)))
    }
    inherited?.layout?.path("txBody", "lstStyle")?.let { parseListStyle(it, colors) }?.let(styles::add)
    inherited?.master?.path("txBody", "lstStyle")?.let { parseListStyle(it, colors) }?.let(styles::add)
    if (inherited != null) masterTextStyle(scope.master, inherited.type)?.let(styles::add)
    scope.defaults?.let(styles::add)
    return styles
}

/** The first adjust value (corner radius, arrow head size, …) as a fraction. */
private fun adjustOf(geometrySpec: XNode?): Float? =
    geometrySpec?.child("avLst")?.children("gd")?.firstOrNull()?.attr("fmla")
        ?.removePrefix("val")?.trim()?.toIntOrNull()?.let { it / PERCENT_SCALE }

/** srcRect as fractions trimmed from each edge; null when nothing is trimmed. */
private fun cropOf(blipFill: XNode): Crop? = blipFill.child("srcRect")?.let { r ->
    Crop(
        percent(r.attr("l")) ?: 0f,
        percent(r.attr("t")) ?: 0f,
        percent(r.attr("r")) ?: 0f,
        percent(r.attr("b")) ?: 0f
    )
}?.takeUnless { it.left == 0f && it.top == 0f && it.right == 0f && it.bottom == 0f }

/** A graphic frame's own xfrm, else its placeholder's (written either bare or inside spPr). */
private fun graphicFrameOf(node: XNode, inherited: Inherited?): Frame? =
    frameOf(node.child("xfrm"))
        ?: inherited?.layout?.let(::placeholderFrame)
        ?: inherited?.master?.let(::placeholderFrame)

private fun placeholderFrame(ph: XNode): Frame? = frameOf(ph.child("xfrm")) ?: frameOf(ph.path("spPr", "xfrm"))

/** mc:AlternateContent: the Fallback is the plain-DrawingML version, so it is preferred. */
private fun alternateOf(node: XNode): XNode? = node.child("Fallback") ?: node.child("Choice")

private fun XNode.contains(name: String): Boolean = findDescendant(this, name) != null

private fun findDescendant(node: XNode, name: String): XNode? {
    for (c in node.children) {
        if (c.name == name) return c
        findDescendant(c, name)?.let { return it }
    }
    return null
}
