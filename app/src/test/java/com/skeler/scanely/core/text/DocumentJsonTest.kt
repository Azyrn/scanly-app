package com.skeler.scanely.core.text

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentJsonTest {

    private val sample = """
        # Report

        First paragraph of **text**.

        | Name | Marks |
        | :--- | :--- |
        | Selvi | 98 |

        - item one

        ${"$$"}E = mc^2${"$$"}

        ![Figure 1](img-0.jpeg)
    """.trimIndent()

    @Test
    fun `every block becomes a typed element`() {
        val json = JSONObject(DocumentJson.fromMarkdown(sample))
        val elements = json.getJSONArray("elements")
        val types = (0 until elements.length()).map { elements.getJSONObject(it).getString("type") }
        assertEquals(listOf("heading", "paragraph", "table", "list_item", "equation", "image"), types)
    }

    @Test
    fun `elements carry their structure`() {
        val json = JSONObject(DocumentJson.fromMarkdown(sample))
        val elements = json.getJSONArray("elements")

        val heading = elements.getJSONObject(0)
        assertEquals(1, heading.getInt("level"))
        assertEquals("Report", heading.getString("text"))

        val paragraph = elements.getJSONObject(1)
        assertEquals("First paragraph of text.", paragraph.getString("text"))

        val table = elements.getJSONObject(2)
        assertEquals(2, table.getInt("columns"))
        assertEquals("Name", table.getJSONArray("header").getString(0))
        assertEquals("98", table.getJSONArray("rows").getJSONArray(0).getString(1))

        val equation = elements.getJSONObject(4)
        assertEquals("E = mc^2", equation.getString("latex"))
        assertEquals("E = mc²", equation.getString("text"))

        val image = elements.getJSONObject(5)
        assertEquals("Figure 1", image.getString("alt"))
        assertEquals("img-0.jpeg", image.getString("source"))
    }

    @Test
    fun `stats summarize the document`() {
        val json = JSONObject(DocumentJson.fromMarkdown(sample))
        assertEquals("scanly.document.v1", json.getString("format"))
        val stats = json.getJSONObject("stats")
        assertEquals(6, stats.getInt("elements"))
        assertTrue(stats.getInt("words") > 0)
        assertTrue(stats.getInt("characters") > 0)
    }
}
