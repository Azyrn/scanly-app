package com.skeler.scanely.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private val sample = """
        Question 1: Highlight above 90 Marks.

        | Name | Tamil | English | Maths | Science | Total |
        | :--- | :--- | :--- | :--- | :--- | :--- |
        | Moni | 80 | 85 | 88 | 82 | |
        | **Selvi** | 90 | 98 | 98 | 90 | |
    """.trimIndent()

    @Test
    fun `table parses without the divider row`() {
        val table = MarkdownParser.parse(sample).filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Name", "Tamil", "English", "Maths", "Science", "Total"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Moni", "80", "85", "88", "82", ""), table.rows[0])
        assertEquals(6, table.rows[1].size)
    }

    @Test
    fun `plain text drops pipes and emphasis markers`() {
        val plain = MarkdownParser.toPlainText(sample)
        assertFalse(plain.contains("|"))
        assertFalse(plain.contains("**"))
        assertFalse(plain.contains(":---"))
        assertTrue(plain.contains("Question 1: Highlight above 90 Marks."))
        assertTrue(plain.contains("Selvi"))
    }

    @Test
    fun `inline spans carry emphasis`() {
        val spans = MarkdownParser.parseInline("plain **bold** *it* `code` ~~out~~ <u>u</u>")
        assertTrue(spans.single { it.text == "bold" }.bold)
        assertTrue(spans.single { it.text == "it" }.italic)
        assertTrue(spans.single { it.text == "code" }.code)
        assertTrue(spans.single { it.text == "out" }.strike)
        assertTrue(spans.single { it.text == "u" }.underline)
    }

    /**
     * The end of a line is where a closing `*` used to be mistaken for a literal one, leaving a
     * stray asterisk on every `### Welcome *(5 min)*` an agenda produces.
     */
    @Test
    fun `emphasis closing at the end of the line still closes`() {
        val spans = MarkdownParser.parseInline("1. Welcome *(5 min)*")

        assertEquals("1. Welcome (5 min)", spans.joinToString("") { it.text })
        assertTrue(spans.single { it.text == "(5 min)" }.italic)
        assertFalse(spans.any { "*" in it.text })
    }

    @Test
    fun `a trailing asterisk with nothing to close stays literal`() {
        assertEquals("rated 5*", MarkdownParser.parseInline("rated 5*").joinToString("") { it.text })
        assertFalse(MarkdownParser.parseInline("rated 5*").any { it.italic })

        // A lone asterisk after a space is not a marker either.
        assertEquals("2 x 3 *", MarkdownParser.parseInline("2 x 3 *").joinToString("") { it.text })
    }

    @Test
    fun `arithmetic and headings survive`() {
        assertEquals("5 * 3 = 15", MarkdownParser.toPlainText("5 * 3 = 15"))
        val heading = MarkdownParser.parse("## Report").single()
        assertEquals(MdBlock.Heading(2, "Report"), heading)
    }

    @Test
    fun `plain OCR text is not flagged as markdown`() {
        assertFalse(MarkdownParser.looksLikeMarkdown("Hello world\nsecond line"))
        assertTrue(MarkdownParser.looksLikeMarkdown(sample))
    }

    @Test
    fun `escaped pipe stays inside its cell`() {
        val source = "| Rate | Max |\n| :--- | :--- |\n| 5\\|10 | 20 |"
        val table = MarkdownParser.parse(source).filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("5\\|10", "20"), table.rows[0])
        assertTrue(MarkdownParser.toPlainText(source).contains("5|10"))
    }

    @Test
    fun `unmatched markers stay literal`() {
        assertEquals("Use *asterisk for footnotes", MarkdownParser.toPlainText("Use *asterisk for footnotes"))
        assertEquals("5*3=15", MarkdownParser.toPlainText("5*3=15"))
        assertEquals("a ** b", MarkdownParser.toPlainText("a ** b"))
        assertEquals("open ~~strike", MarkdownParser.toPlainText("open ~~strike"))
        assertEquals("a <u> b", MarkdownParser.toPlainText("a <u> b"))
    }

    @Test
    fun `backslash escapes render literally`() {
        assertEquals("- not a bullet", MarkdownParser.toPlainText("\\- not a bullet"))
        assertEquals("5 * 3", MarkdownParser.parseInline("5 \\* 3").joinToString("") { it.text })
        assertEquals("C:\\temp\\file", MarkdownParser.toPlainText("C:\\temp\\file"))
    }

    @Test
    fun `links still parse mid-line`() {
        val spans = MarkdownParser.parseInline("see [docs](https://x.dev) here")
        val link = spans.single { it.link != null }
        assertEquals("docs", link.text)
        assertEquals("https://x.dev", link.link)
    }

    @Test
    fun `single-line display math becomes an equation block`() {
        val block = MarkdownParser.parse("\$\$E = mc^2\$\$").single()
        assertEquals(MdBlock.Equation("E = mc^2"), block)
    }

    @Test
    fun `multi-line display math collects until the closing marker`() {
        val source = "before\n\n$$\n\\frac{a}{b} = c\n$$\n\nafter"
        val equation = MarkdownParser.parse(source).filterIsInstance<MdBlock.Equation>().single()
        assertEquals("\\frac{a}{b} = c", equation.latex)
        assertTrue(MarkdownParser.toPlainText(source).contains("a/b = c"))
    }

    @Test
    fun `standalone image line becomes an image block`() {
        val block = MarkdownParser.parse("![Figure 1: results](img-0.jpeg)").single()
        assertEquals(MdBlock.Image("Figure 1: results", "img-0.jpeg"), block)
        assertEquals("[Image: Figure 1: results]", (block as MdBlock.Image).label)
    }

    @Test
    fun `a table rewritten without outer pipes still parses`() {
        val source = """
            # Rapporto

            Regione | Unità | Totale
            :--- | :--- | :---
            Nord | 120 | 4800
            Sud | 90 | 3600

            Il totale include l'IVA.
        """.trimIndent()

        val table = MarkdownParser.parse(source).filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Regione", "Unità", "Totale"), table.header)
        assertEquals(listOf(listOf("Nord", "120", "4800"), listOf("Sud", "90", "3600")), table.rows)
        assertTrue(MarkdownParser.looksLikeMarkdown(source))
    }

    @Test
    fun `prose with a pipe is not a table`() {
        val source = "Press Ctrl | Alt to continue\n--- and then wait"
        assertTrue(MarkdownParser.parse(source).none { it is MdBlock.Table })
    }

    @Test
    fun `a fence the translator added around the document comes off`() {
        val document = "# Titolo\n\n| A | B |\n| --- | --- |\n| 1 | 2 |"
        val fenced = "Ecco la traduzione:\n\n```markdown\n$document\n```"
        assertEquals(document, MarkdownParser.unwrapAddedFence("# Title", fenced))
    }

    /**
     * Composition has no source document, so it unwraps against `""`. These two pin the behaviour
     * that matters there: the wrapper comes off, and a document that genuinely ends in a code
     * block does not get eaten.
     */
    @Test
    fun `a fence the writer added around a composed document comes off`() {
        val document = "# Invoice\n\n| Item | Price |\n| --- | --- |\n| Design | 400 |"
        val fenced = "Here is your document:\n\n```markdown\n$document\n```"
        assertEquals(document, MarkdownParser.unwrapAddedFence("", fenced))
    }

    @Test
    fun `a composed document ending in a code block keeps it`() {
        val document = buildString {
            append("# Deployment runbook\n\n")
            repeat(6) { append("Step ${it + 1}: check the service is healthy before continuing.\n\n") }
            append("```\nkubectl rollout status deploy/api\n```")
        }
        assertEquals(document, MarkdownParser.unwrapAddedFence("", document))
    }

    @Test
    fun `a document that was already one code block keeps its fence`() {
        val source = "```\nprint(1)\n```"
        val translated = "```\nstampa(1)\n```"
        assertEquals(translated, MarkdownParser.unwrapAddedFence(source, translated))
    }

    @Test
    fun `unfenced and partially fenced translations are left alone`() {
        val plain = "# Titolo\n\nTesto."
        assertEquals(plain, MarkdownParser.unwrapAddedFence("# Title", plain))

        val withCode = "# Titolo\n\n```\ncode\n```\n\nCoda."
        assertEquals(withCode, MarkdownParser.unwrapAddedFence("# Title", withCode))
    }

    @Test
    fun `equations and images flag text as markdown`() {
        assertTrue(MarkdownParser.looksLikeMarkdown("\$\$x^2\$\$"))
        assertTrue(MarkdownParser.looksLikeMarkdown("![chart](img-1.png)"))
        assertFalse(MarkdownParser.looksLikeMarkdown("price is $$ high"))
    }
}
