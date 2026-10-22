package com.skeler.scanely.core.pdftools.office2pdf.pptx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

/** Relationship ids are the one namespaced attribute the reader needs to tell apart ("id" vs "r:id"). */
internal const val REL_PREFIX = "r:"

/** Past this a part is treated as hostile or broken; real slides stay far below it. */
private const val MAX_NODES = 400_000

/** A part that isn't a well-formed, reasonably sized OOXML document. Maps to ToolFailure.Damaged. */
internal class PptxFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A parsed XML element, by local name (prefixes dropped). Slide parts are small and the reader
 * needs to look sideways — a placeholder's position lives in another part, a run's size three
 * levels up — so a tiny tree is simpler and safer than streaming. Only `t` (and a table's style
 * id) keep their text, since that is the only character data a slide's drawing needs.
 */
internal class XNode(val name: String, val attrs: Map<String, String>) {
    val children: MutableList<XNode> = ArrayList(0)
    var text: String = ""

    fun child(name: String): XNode? = children.firstOrNull { it.name == name }

    fun children(name: String): List<XNode> = children.filter { it.name == name }

    fun attr(name: String): String? = attrs[name]

    fun path(vararg names: String): XNode? {
        var node: XNode? = this
        for (n in names) node = node?.child(n) ?: return null
        return node
    }

    companion object {
        fun keepsText(name: String): Boolean = name == "t" || name == "tableStyleId"

        /** Attributes in a relationships namespace get the `r:` prefix; every other one goes by local name. */
        fun attrKey(namespace: String?, localName: String): String =
            if (namespace != null && namespace.endsWith("relationships")) REL_PREFIX + localName else localName

        /** For parsers without namespace processing: "p:sp" → "sp", but "r:embed" keeps its prefix. */
        fun localName(qualified: String): String = qualified.substringAfter(':')
    }
}

/**
 * The on-device parser. DOCTYPE processing is off, so an entity-expansion bomb fails as a parse
 * error instead of expanding; the node cap stops a part that is merely enormous.
 */
internal fun parsePullXml(input: InputStream): XNode {
    try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        parser.setInput(input, null)
        return PullTreeBuilder(parser).build()
    } catch (e: XmlPullParserException) {
        throw PptxFormatException("malformed XML", e)
    } catch (e: IOException) {
        throw PptxFormatException("unreadable XML", e)
    }
}

/** Builds the [XNode] tree from a pull parser's events, counting elements against [MAX_NODES]. */
private class PullTreeBuilder(private val parser: XmlPullParser) {
    private val stack = ArrayList<XNode>()
    private var root: XNode? = null
    private var count = 0

    fun build(): XNode {
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            onEvent(event)
            event = parser.next()
        }
        // Android's parser ends a truncated part quietly instead of throwing; open elements say so.
        if (stack.isNotEmpty()) throw PptxFormatException("unclosed element")
        return root ?: throw PptxFormatException("empty part")
    }

    private fun onEvent(event: Int) {
        when (event) {
            XmlPullParser.START_TAG -> startElement()
            XmlPullParser.TEXT -> {
                val node = stack.lastOrNull()
                if (node != null && XNode.keepsText(node.name)) node.text += parser.text
            }
            XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        }
    }

    private fun startElement() {
        if (++count > MAX_NODES) throw PptxFormatException("part has too many elements")
        val node = XNode(parser.name, attributes())
        val parent = stack.lastOrNull()
        if (parent == null) root = node else parent.children.add(node)
        stack.add(node)
    }

    private fun attributes(): Map<String, String> {
        val attrs = HashMap<String, String>(parser.attributeCount * 2)
        for (i in 0 until parser.attributeCount) {
            attrs[XNode.attrKey(parser.getAttributeNamespace(i), parser.getAttributeName(i))] =
                parser.getAttributeValue(i)
        }
        return attrs
    }
}
