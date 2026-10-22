package com.skeler.scanely.core.ai.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other half of the extraction that used to be dropped. A custom endpoint answers with JSON,
 * and these are the shapes it answers with in practice — fenced, prefaced, or exactly as asked.
 *
 * The flattening mirrors `fieldsOf` in `worker/src/extraction.ts` so a receipt read on the user's
 * own model renders the same rows as one read by Scanly's.
 */
class CustomExtractionTest {

    private val receipt = """
        {
          "merchant": "Café Rossi",
          "date": "2026-09-01",
          "currency": "MAD",
          "subtotal": 84.5,
          "tax": null,
          "total": 92.0,
          "payment_method": "",
          "items": [
            { "description": "Espresso", "quantity": 2, "unit_price": 18.0, "total": 36.0 },
            { "description": "Msemen", "quantity": 1, "unit_price": 48.5, "total": 48.5 }
          ]
        }
    """.trimIndent()

    @Test
    fun `plain JSON becomes labelled rows`() {
        val result = CustomExtraction.parse("Receipt", receipt)!!

        assertEquals("Receipt", result.label)
        assertEquals("Café Rossi", result.fields.first { it.first == "Merchant" }.second)
        assertEquals("MAD", result.fields.first { it.first == "Currency" }.second)
    }

    /** Underscored keys are what the schema uses; a user should never see one. */
    @Test
    fun `keys are humanised the way the server humanises them`() {
        val labels = CustomExtraction.parse("Receipt", receipt)!!.fields.map { it.first }
        assertTrue(labels.toString(), "Payment method" !in labels || "payment_method" !in labels)
        assertTrue(labels.none { it.contains('_') })
    }

    /** Null means "the document does not show this", and an empty string means the same. */
    @Test
    fun `absent values are omitted rather than rendered as the word null`() {
        val fields = CustomExtraction.parse("Receipt", receipt)!!.fields
        assertTrue(fields.toString(), fields.none { it.first == "Tax" })
        assertTrue(fields.toString(), fields.none { it.second == "null" })
        assertTrue(fields.toString(), fields.none { it.first == "Payment method" })
    }

    @Test
    fun `a list of objects is summarised one row per item, singularised`() {
        val fields = CustomExtraction.parse("Receipt", receipt)!!.fields
        assertEquals("Espresso · 2 · 18.0 · 36.0", fields.first { it.first == "Item 1" }.second)
        assertEquals("Msemen · 1 · 48.5 · 48.5", fields.first { it.first == "Item 2" }.second)
    }

    @Test
    fun `a fenced reply is recovered instead of failing the scan`() {
        val fenced = "```json\n$receipt\n```"
        assertEquals("Café Rossi", CustomExtraction.parse("Receipt", fenced)!!.fields.first().second)
    }

    @Test
    fun `a reply that opens with a sentence is recovered too`() {
        val chatty = "Sure! Here is the extracted receipt:\n\n$receipt\n\nLet me know if you need more."
        assertEquals("Café Rossi", CustomExtraction.parse("Receipt", chatty)!!.fields.first().second)
    }

    @Test
    fun `a reply with no JSON at all is a failure, not an empty result`() {
        assertNull(CustomExtraction.parse("Receipt", "I could not read that image."))
        assertNull(CustomExtraction.parse("Receipt", ""))
    }

    /** An object of nothing but nulls has no fields, and must not render as a blank card. */
    @Test
    fun `an all-null object extracts nothing`() {
        assertNull(CustomExtraction.parse("Receipt", """{"merchant": null, "total": null}"""))
    }

    @Test
    fun `the instruction carries the schema the server served`() {
        val instruction = CustomExtraction.instructionFor("Read the receipt.", """{"type":"object"}""")
        assertTrue(instruction.startsWith("Read the receipt."))
        assertTrue(instruction.contains("""{"type":"object"}"""))
        assertTrue(instruction.contains("null"))
    }
}
