package com.skeler.scanely.core.pdftools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Linux caps one path component at 255 bytes (NAME_MAX), not 255 characters. An Arabic letter is
 * two UTF-8 bytes, a CJK character three and an emoji four, so a name capped by characters can
 * still be too long to create — and then every PDF tool reports the file as unreadable.
 */
class SafeFileNameTest {

    @get:Rule val folder = TemporaryFolder()

    private val arabic = "تقرير الميزانية السنوية للشركة مع الملاحق والجداول المالية التفصيلية ".repeat(4)
    private val cjk = "年度予算報告書と詳細な財務諸表および付録".repeat(10)
    private val emoji = "📄🧾✅🙂".repeat(40)

    /** The longest name the import builds around a sanitised display name. */
    private fun importName(display: String) = "in-${Long.MAX_VALUE}-${ToolWorkspace.safeFileName(display)}"

    @Test
    fun `long non-Latin names stay within NAME_MAX once the import prefix goes on`() {
        for (name in listOf(arabic, cjk, emoji)) {
            val bytes = importName(name).toByteArray(Charsets.UTF_8).size
            assertTrue("${name.take(10)}… is $bytes bytes", bytes <= NAME_MAX_BYTES)
        }
    }

    @Test
    fun `long non-Latin names can be created on disk`() {
        for (name in listOf(arabic, cjk, emoji)) {
            val file = File(folder.root, importName(name))
            assertTrue(file.createNewFile())
        }
    }

    @Test
    fun `a cut never splits a surrogate pair`() {
        val safe = ToolWorkspace.safeFileName(emoji)
        // Every char is either outside the surrogate range or part of a complete pair.
        assertEquals(safe, String(safe.codePoints().toArray(), 0, safe.codePointCount(0, safe.length)))
        assertTrue(!safe.last().isHighSurrogate())
        assertEquals(safe.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8), safe)
    }

    @Test
    fun `short names are left alone`() {
        assertEquals("تقرير", ToolWorkspace.safeFileName("تقرير"))
        assertEquals("scan_1", ToolWorkspace.safeFileName("scan/1"))
    }

    private companion object {
        const val NAME_MAX_BYTES = 255
    }
}
