package com.skeler.scanely.core.ai.custom

import com.skeler.scanely.core.ai.ScanExtraction
import com.skeler.scanely.core.network.LenientJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns a filled-in extraction schema into the display rows the results screen already renders.
 *
 * A port of `fieldsOf` in `worker/src/extraction.ts`, and it has to be one: the backend flattens
 * annotations server-side so the app never learns any schema, which means the custom-endpoint path
 * — which the backend never sees — has nowhere to borrow that flattening from. Both walk the same
 * shapes in the same order, so a receipt looks identical whichever endpoint read it.
 */
internal object CustomExtraction {

    private const val MAX_FIELDS = 60

    private val json = LenientJson

    /**
     * Models fence JSON in Markdown however firmly they are told not to, and some open with a
     * sentence. Taking the outermost braces is what survives both.
     */
    fun parse(label: String, raw: String): ScanExtraction? {
        val root = objectIn(raw) ?: return null

        val fields = mutableListOf<Pair<String, String>>()
        walk(root, "", fields)

        return if (fields.isEmpty()) {
            null
        } else {
            ScanExtraction(label = label, fields = fields, json = root.toString())
        }
    }

    /**
     * The outermost braces, rather than the fence markers: it recovers the fenced reply, the one
     * prefaced with a sentence, and the one that obeyed the instruction, all the same way.
     */
    private fun objectIn(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        return runCatching {
            json.parseToJsonElement(raw.substring(start, end + 1))
        }.getOrNull() as? JsonObject
    }

    private fun walk(node: JsonElement, path: String, out: MutableList<Pair<String, String>>) {
        if (out.size >= MAX_FIELDS) return

        when (node) {
            is JsonNull -> Unit
            is JsonArray -> walkList(node, path, out)
            is JsonObject -> node.forEach { (key, value) -> walk(value, humanize(key), out) }
            is JsonPrimitive ->
                if (node.content.isNotEmpty()) out.add((path.ifEmpty { "Value" }) to node.content)
        }
    }

    /** One row per entry, the label singularised: `items` becomes `Item 1`, `Item 2`. */
    private fun walkList(node: JsonArray, path: String, out: MutableList<Pair<String, String>>) {
        val singular = path.ifEmpty { "Item" }.replace(Regex("s$", RegexOption.IGNORE_CASE), "")
        node.forEachIndexed { index, item ->
            val label = "$singular ${index + 1}"
            if (item is JsonObject) {
                summarize(item)?.let { out.add(label to it) }
            } else {
                walk(item, label, out)
            }
        }
    }

    /** One line for a line item: `Espresso · 2 · 18.0 · 36.0`. Absent values leave no gap. */
    private fun summarize(item: JsonObject): String? = item.values
        .mapNotNull { value ->
            when (value) {
                is JsonPrimitive -> value.content.ifEmpty { null }
                is JsonObject -> summarize(value)
                else -> null
            }
        }
        .joinToString(" · ")
        .ifEmpty { null }

    private fun humanize(key: String): String {
        val words = key.replace(Regex("[_-]+"), " ").trim()
        return words.replaceFirstChar(Char::uppercase)
    }

    /**
     * A reply that is not the schema is a failure, not a transcription the user did not ask for.
     * Silently handing back plain text is precisely how extraction went unnoticed for so long.
     */
    fun asResult(result: CustomScanResult, label: String): CustomScanResult {
        if (result !is CustomScanResult.Success) return result

        val fields = parse(label, result.markdown)
            ?: return CustomScanResult.Failure(ConnectionFailure.UnexpectedSchema)

        return CustomScanResult.Success(markdown = asMarkdown(fields), extraction = fields)
    }

    /** The same two-column shape the backend's extraction renders as, so the screen looks alike. */
    private fun asMarkdown(extraction: ScanExtraction): String = buildString {
        append("# ").append(extraction.label).append("\n\n")
        append("| Field | Value |\n| --- | --- |\n")
        extraction.fields.forEach { (label, value) ->
            append("| ").append(label).append(" | ").append(value.replace("|", "\\|")).append(" |\n")
        }
    }

    /**
     * Appended to the preset's own prompt. The schema travels from `/v1/me` rather than being
     * written here, so a preset added to the Worker reaches this path without an app release.
     */
    fun instructionFor(prompt: String, schema: String): String =
        "$prompt\n\nReturn a single JSON object and nothing else — no prose, no Markdown fence — " +
            "matching this JSON Schema exactly. Use null for anything the document does not " +
            "show; never invent a value.\n\n$schema"
}
