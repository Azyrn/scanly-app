package com.skeler.scanely.ui.scan

import com.skeler.scanely.core.idcard.IdDocumentKind
import com.skeler.scanely.core.scan.ScanMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScanFlowTest {

    @Test
    fun `an ID side exists only for sides the document has`() {
        assertNotNull(IdSide.of(IdDocumentKind.ID_CARD, 1))
        assertNull(IdSide.of(IdDocumentKind.ID_CARD, 2))
        assertNull(IdSide.of(IdDocumentKind.PASSPORT, 1))
        assertNull(IdSide.of(IdDocumentKind.PASSPORT, -1))
    }

    @Test
    fun `ID sides are enhanced when saved, document pages are left for review to filter`() {
        val side = IdSide.of(IdDocumentKind.ID_CARD, 0)!!
        assertEquals(ScanMode.ID_CARD, ScanGoal.IdCopy(IdDocumentKind.ID_CARD).enhanceOnSave)
        assertEquals(ScanMode.ID_CARD, ScanGoal.ReplaceSide(side).enhanceOnSave)
        assertNull(ScanGoal.Pages(ScanMode.DOCUMENT).enhanceOnSave)
        assertNull(ScanGoal.Pages(ScanMode.WHITEBOARD).enhanceOnSave)
    }

    @Test
    fun `a card's front goes back to the camera and its back finishes`() {
        val goal = ScanGoal.IdCopy(IdDocumentKind.ID_CARD)
        assertEquals(CropStep.Camera, stepAfterCrop(goal, sidesTaken = 0, importsLeft = 0, done = false))
        assertEquals(CropStep.Finish, stepAfterCrop(goal, sidesTaken = 1, importsLeft = 0, done = false))
    }

    @Test
    fun `a passport finishes after its one side`() {
        val goal = ScanGoal.IdCopy(IdDocumentKind.PASSPORT)
        assertEquals(CropStep.Finish, stepAfterCrop(goal, sidesTaken = 0, importsLeft = 0, done = false))
    }

    @Test
    fun `two picked sides go front then back through the crop`() {
        val goal = ScanGoal.IdCopy(IdDocumentKind.ID_CARD)
        assertEquals(CropStep.NextImport, stepAfterCrop(goal, sidesTaken = 0, importsLeft = 1, done = false))
    }

    @Test
    fun `document pages add until Done`() {
        val goal = ScanGoal.Pages(ScanMode.DOCUMENT)
        assertEquals(CropStep.Camera, stepAfterCrop(goal, 0, importsLeft = 0, done = false))
        assertEquals(CropStep.NextImport, stepAfterCrop(goal, 0, importsLeft = 2, done = false))
        assertEquals(CropStep.Finish, stepAfterCrop(goal, 0, importsLeft = 2, done = true))
    }

    @Test
    fun `retaking one side of an open copy finishes at once`() {
        val goal = ScanGoal.ReplaceSide(IdSide.of(IdDocumentKind.ID_CARD, 0)!!)
        assertEquals(CropStep.Finish, stepAfterCrop(goal, 0, importsLeft = 0, done = false))
    }

    @Test
    fun `the crop's buttons follow the mode and side`() {
        val card = ScanGoal.IdCopy(IdDocumentKind.ID_CARD)
        assertEquals(CropActions.ADD_PAGE_DONE, cropActions(ScanGoal.Pages(ScanMode.WHITEBOARD), null))
        assertEquals(CropActions.RETAKE_NEXT_BACK, cropActions(card, IdSide.of(IdDocumentKind.ID_CARD, 0)))
        assertEquals(CropActions.RETAKE_MAKE_COPY, cropActions(card, IdSide.of(IdDocumentKind.ID_CARD, 1)))
        assertEquals(
            CropActions.RETAKE_MAKE_COPY,
            cropActions(ScanGoal.IdCopy(IdDocumentKind.PASSPORT), IdSide.of(IdDocumentKind.PASSPORT, 0))
        )
    }
}
