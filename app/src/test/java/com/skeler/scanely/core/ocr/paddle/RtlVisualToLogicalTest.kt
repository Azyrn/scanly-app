package com.skeler.scanely.core.ocr.paddle

import org.junit.Assert.assertEquals
import org.junit.Test

/** Visual-order CTC output back to logical order, with the LTR runs inside it kept LTR. */
class RtlVisualToLogicalTest {

    @Test
    fun `arabic words come back in logical order`() {
        assertEquals("مرحبا بالعالم", RtlText.visualToLogical("ملاعلاب ابحرم"))
    }

    @Test
    fun `arabic-indic number keeps its arabic comma and digit order`() {
        // Measured on img3_munch: "١١٩،٩٢" came back as "٩٢،١١٩" because the Arabic comma
        // split the digits into two runs that were then restored in the wrong order.
        assertEquals(
            "مليون ١١٩،٩٢ دولار",
            RtlText.visualToLogical("رالود ١١٩،٩٢ نويلم")
        )
    }

    @Test
    fun `arabic decimal and thousands separators join a number run`() {
        assertEquals(
            "سعر ١٢٬٣٤٥٫٦٧ درهم",
            RtlText.visualToLogical("مهرد ١٢٬٣٤٥٫٦٧ رعس")
        )
    }

    @Test
    fun `latin phrase inside an arabic line stays readable`() {
        assertEquals(
            "النسخة Version 2.5 الجديدة",
            RtlText.visualToLogical("ةديدجلا Version 2.5 ةخسنلا")
        )
    }

    @Test
    fun `line with no rtl characters is untouched`() {
        assertEquals("Invoice 4,471.20", RtlText.visualToLogical("Invoice 4,471.20"))
    }

    @Test
    fun `brackets are mirrored`() {
        assertEquals("(مرحبا)", RtlText.visualToLogical("(ابحرم)"))
    }
}
