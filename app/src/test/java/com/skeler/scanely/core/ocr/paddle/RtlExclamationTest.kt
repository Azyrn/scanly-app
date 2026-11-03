package com.skeler.scanely.core.ocr.paddle

import org.junit.Assert.assertEquals
import org.junit.Test

class RtlExclamationTest {

    @Test
    fun wordFinalHamzaAlefIsAnExclamationMark() {
        assertEquals("انا احب ذلك!\"", restoreExclamation("انا احب ذلكإ\""))
        assertEquals("سيليست! ولكن", restoreExclamation("سيليست إ ولكن".replace(" إ", "إ")))
        assertEquals("انا احرق ! وفي", restoreExclamation("انا احرق إ وفي"))
        assertEquals("معه!", restoreExclamation("معهإ"))
    }

    @Test
    fun wordInitialHamzaAlefIsKept() {
        val text = "إلى إِبراهيم وإكتفائي بالإرسال"
        assertEquals(text, restoreExclamation(text))
    }
}
