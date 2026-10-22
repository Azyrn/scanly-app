package com.skeler.scanely.core.pdftools.office2pdf.pptx

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

private const val MAX_ENTRIES = 20_000

/** Uncompressed caps, counted on the actual stream: a zip's declared sizes can lie. */
private const val MAX_XML_BYTES = 16L * 1024 * 1024
internal const val MAX_MEDIA_BYTES = 64L * 1024 * 1024

/** Enough to see a slide root's attributes without parsing the slide. */
private const val HEAD_BYTES = 4096

private const val MAX_CACHED_PARTS = 32

private val HIDDEN_SLIDE = Regex("""<(?:\w+:)?sld\b[^>]*\sshow\s*=\s*["'](?:0|false)["']""")

internal class Rel(val type: String, val target: String)

/** Where a part's relationships, colours and inherited placeholders come from. */
internal class PartContext(val rels: Map<String, Rel>, val colors: Colors)

internal class MasterPart(
    val root: XNode,
    val context: PartContext,
    val placeholders: List<XNode>,
    val titleStyle: ListStyle?,
    val bodyStyle: ListStyle?,
    val otherStyle: ListStyle?
)

internal class LayoutPart(
    val root: XNode,
    val context: PartContext,
    val master: MasterPart?,
    val placeholders: List<XNode>
)

/**
 * The .pptx archive: size-capped part reads, relationships, and the masters, layouts and themes
 * that many slides share, cached so a long deck parses each of them once (within the cache cap).
 * A file that isn't a zip, or has too many entries, throws [PptxFormatException].
 */
internal class PptxPackage(
    file: File,
    private val parseXml: (InputStream) -> XNode
) : Closeable {

    private val zip: ZipFile = try {
        ZipFile(file)
    } catch (e: IOException) {
        throw PptxFormatException("not a zip archive", e)
    } catch (e: IllegalArgumentException) {
        // Entry names that aren't valid in the archive's declared encoding.
        throw PptxFormatException("not a zip archive", e)
    }

    private val masters = LinkedHashMap<String, MasterPart?>()
    private val layouts = LinkedHashMap<String, LayoutPart?>()
    private val themes = LinkedHashMap<String, Theme>()

    init {
        if (zip.size() > MAX_ENTRIES) {
            zip.close()
            throw PptxFormatException("too many entries")
        }
    }

    override fun close() {
        zip.close()
    }

    fun hasEntry(part: String): Boolean = zip.getEntry(part) != null

    fun openEntry(part: String, limit: Long): InputStream? {
        val entry = zip.getEntry(part) ?: return null
        if (entry.isDirectory) return null
        return BoundedInputStream(zip.getInputStream(entry), limit)
    }

    fun parsePart(part: String): XNode? =
        openEntry(part, MAX_XML_BYTES)?.let { stream ->
            BufferedInputStream(stream).use { parseXml(it) }
        }

    fun isHidden(part: String): Boolean {
        val head = openEntry(part, MAX_XML_BYTES)?.use { stream ->
            val buffer = ByteArray(HEAD_BYTES)
            var filled = 0
            while (filled < buffer.size) {
                val read = stream.read(buffer, filled, buffer.size - filled)
                if (read < 0) break
                filled += read
            }
            String(buffer, 0, filled, Charsets.UTF_8)
        } ?: return false
        return HIDDEN_SLIDE.containsMatchIn(head)
    }

    fun relsOf(part: String): Map<String, Rel> {
        val dir = part.substringBeforeLast('/', "")
        val name = part.substringAfterLast('/')
        val relsPart = if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
        val root = parsePart(relsPart) ?: return emptyMap()
        return root.children("Relationship").mapNotNull { r ->
            val id = r.attr("Id") ?: return@mapNotNull null
            val target = r.attr("Target") ?: return@mapNotNull null
            if (r.attr("TargetMode") == "External") return@mapNotNull null
            id to Rel(r.attr("Type").orEmpty(), resolvePath(dir, target))
        }.toMap()
    }

    private fun <T> LinkedHashMap<String, T>.cached(key: String, load: () -> T): T {
        if (containsKey(key)) {
            @Suppress("UNCHECKED_CAST")
            return get(key) as T
        }
        if (size >= MAX_CACHED_PARTS) remove(keys.first())
        return load().also { put(key, it) }
    }

    fun layoutOf(part: String): LayoutPart? = layouts.cached(part) {
        val root = parsePart(part) ?: return@cached null
        val rels = relsOf(part)
        val master = rels.values.firstOrNull { it.type.endsWith("/slideMaster") }?.let { masterOf(it.target) }
        LayoutPart(
            root = root,
            context = PartContext(rels, master?.context?.colors ?: Colors.DEFAULT),
            master = master,
            placeholders = placeholdersIn(root)
        )
    }

    private fun masterOf(part: String): MasterPart? = masters.cached(part) {
        val root = parsePart(part) ?: return@cached null
        val rels = relsOf(part)
        val theme = rels.values.firstOrNull { it.type.endsWith("/theme") }?.target?.let { target ->
            themes.cached(target) { Theme.parse(parsePart(target)) }
        } ?: Theme.DEFAULT
        val map = root.child("clrMap")?.attrs?.takeIf { it.isNotEmpty() } ?: Colors.DEFAULT_MAP
        val colors = Colors(theme, map)
        val styles = root.child("txStyles")
        MasterPart(
            root = root,
            context = PartContext(rels, colors),
            placeholders = placeholdersIn(root),
            titleStyle = parseListStyle(styles?.child("titleStyle"), colors),
            bodyStyle = parseListStyle(styles?.child("bodyStyle"), colors),
            otherStyle = parseListStyle(styles?.child("otherStyle"), colors)
        )
    }
}

private fun resolvePath(dir: String, target: String): String {
    val decoded = target.replace("%20", " ")
    val joined = if (decoded.startsWith("/")) decoded.drop(1) else "$dir/$decoded"
    val out = ArrayList<String>()
    for (segment in joined.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out.add(segment)
        }
    }
    return out.joinToString("/")
}

/** A slide, layout or master's shape tree. */
internal fun shapeTree(root: XNode): XNode? = root.path("cSld", "spTree")

private fun placeholdersIn(root: XNode): List<XNode> =
    shapeTree(root)?.children.orEmpty().filter { placeholderOf(it) != null }

/** Fails the read once more than [limit] bytes have come out, whatever the zip header claimed. */
private class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) check(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) check(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) check(skipped)
        return skipped
    }

    private fun check(n: Long) {
        count += n
        if (count > limit) throw IOException("entry larger than $limit bytes")
    }
}
