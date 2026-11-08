package com.skeler.scanely.core.scan

import com.skeler.scanely.core.idcard.IdDocumentKind
import com.skeler.scanely.core.image.ScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanModeTest {

    @Test
    fun eachModeStartsWithItsFilter() {
        assertEquals(ScanFilter.AUTO, ScanMode.DOCUMENT.defaultFilter)
        assertEquals(ScanFilter.WHITEBOARD, ScanMode.WHITEBOARD.defaultFilter)
        assertEquals(ScanFilter.AUTO, ScanMode.ID_CARD.defaultFilter)
    }

    @Test
    fun onlyIdModeForcesAShape() {
        assertNull(ScanMode.DOCUMENT.forcedAspect(IdDocumentKind.ID_CARD))
        assertNull(ScanMode.WHITEBOARD.forcedAspect(IdDocumentKind.PASSPORT))
        assertEquals(85.60f / 53.98f, ScanMode.ID_CARD.forcedAspect(IdDocumentKind.ID_CARD)!!, 1e-4f)
        assertEquals(125f / 88f, ScanMode.ID_CARD.forcedAspect(IdDocumentKind.PASSPORT)!!, 1e-4f)
    }
}
