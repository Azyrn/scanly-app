package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdftools.office2pdf.docx.ArabicAdvances
import org.junit.Assert.assertEquals
import org.junit.Test

/** Contextual forms and the width of a shaped run ([ArabicAdvances]). */
class DocxArabicWidthsTest {

    @Test
    fun `letters take their contextual forms and lam-alef joins`() {
        // beh initial, beh medial, beh final; then lam + alef as the isolated ligature.
        assertEquals("ﺑﺒﺐ ﻻ", ArabicAdvances.shape("ببب لا"))
        // alef does not join forward: the beh after it starts again (isolated alef, isolated beh).
        assertEquals("ﺍﺏ", ArabicAdvances.shape("اب"))
        // a mark between two letters keeps them joined.
        assertEquals("ﺑَﺐ", ArabicAdvances.shape("بَب"))
    }

    @Test
    fun `a run is as wide as the sum of its shaped advances`() {
        val advances = ArabicAdvances.parse(sequenceOf("# comment", "FE91\t0.25\t0.3\tmeasured", "FE90\t0.5\t0.6\test", "0020\t0.3\t0.3\tmeasured"))
        val shaped = ArabicAdvances.shape("بب 1")
        assertEquals(10f * (0.25f + 0.5f + 0.3f) + 4f, advances.width(shaped, 10f, bold = false) { 4f }, 1e-4f)
        assertEquals(10f * (0.3f + 0.6f + 0.3f) + 4f, advances.width(shaped, 10f, bold = true) { 4f }, 1e-4f)
    }
}
