package com.skeler.scanely.core.text

import org.json.JSONArray
import org.json.JSONObject

/**
 * The scan as data: one JSON element per Markdown block, each carrying its type (heading,
 * paragraph, table, equation, image, …), so other tools can consume the document without
 * re-parsing Markdown. This is what the JSON view shows and the JSON export writes.
 */
object DocumentJson {

    fun fromMarkdown(source: String): String {
        val blocks = MarkdownParser.parse(source)
        val elements = JSONArray()
        blocks.forEachIndexed { index, block -> elements.put(element(index, block)) }

        val plain = MarkdownParser.toPlainText(source)
        return JSONObject()
            .put("format", "scanly.document.v1")
            .put(
                "stats",
                JSONObject()
                    .put("elements", blocks.size)
                    .put("words", plain.split(WHITESPACE).count { it.isNotBlank() })
                    .put("characters", plain.length)
            )
            .put("elements", elements)
            .toString(INDENT_SPACES)
    }

    private fun element(index: Int, block: MdBlock): JSONObject {
        val json = JSONObject().put("index", index)
        when (block) {
            is MdBlock.Heading ->
                json
                    .put("type", "heading")
                    .put("level", block.level)
                    .put("text", MarkdownParser.plain(block.text))

            is MdBlock.Paragraph ->
                json
                    .put("type", "paragraph")
                    .put("text", MarkdownParser.plain(block.lines.joinToString("\n")))

            is MdBlock.Bullet -> {
                json.put("type", if (block.checked != null) "checkbox" else "list_item")
                    .put("text", MarkdownParser.plain(block.text))
                    .put("indent", block.indent)
                block.checked?.let { json.put("checked", it) }
            }

            is MdBlock.Numbered ->
                json
                    .put("type", "numbered_item")
                    .put("marker", block.marker)
                    .put("text", MarkdownParser.plain(block.text))
                    .put("indent", block.indent)

            is MdBlock.Quote ->
                json
                    .put("type", "quote")
                    .put("text", MarkdownParser.plain(block.lines.joinToString("\n")))

            is MdBlock.Code ->
                json
                    .put("type", "code")
                    .put("text", block.text)

            is MdBlock.Table ->
                json
                    .put("type", "table")
                    .put("columns", block.header.size)
                    .put("header", JSONArray(block.header.map(MarkdownParser::plain)))
                    .put(
                        "rows",
                        JSONArray(block.rows.map { row -> JSONArray(row.map(MarkdownParser::plain)) })
                    )

            is MdBlock.Equation ->
                json
                    .put("type", "equation")
                    .put("latex", block.latex)
                    .put("text", MathText.prettify(block.latex))

            is MdBlock.Image ->
                json
                    .put("type", "image")
                    .put("alt", block.alt)
                    .put("source", block.url)

            MdBlock.Divider -> json.put("type", "divider")
        }
        return json
    }

    private val WHITESPACE = Regex("\\s+")
    private const val INDENT_SPACES = 2
}
