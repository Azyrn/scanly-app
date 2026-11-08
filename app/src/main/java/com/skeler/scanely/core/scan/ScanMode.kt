package com.skeler.scanely.core.scan

import com.skeler.scanely.core.idcard.IdDocumentKind
import com.skeler.scanely.core.image.CardEnhancer
import com.skeler.scanely.core.image.DocumentEnhancer
import com.skeler.scanely.core.image.ScanFilter

/** What the user is scanning: decides the filter a page starts with and whether its shape is fixed. */
enum class ScanMode(val defaultFilter: ScanFilter) {
    DOCUMENT(ScanFilter.AUTO),
    ID_CARD(ScanFilter.AUTO),
    WHITEBOARD(ScanFilter.WHITEBOARD);

    /** Width/height the page is warped to (landscape; PageWarp turns it for a portrait card), or null. */
    fun forcedAspect(kind: IdDocumentKind): Float? = if (this == ID_CARD) kind.aspect else null

    /**
     * What AUTO means for this mode: a clean white page for documents and boards; for cards a
     * colour-safe enhance, because a card's pastel security print and photo are content, not paper.
     */
    fun autoEnhance(pixels: IntArray, width: Int, height: Int): IntArray = when (this) {
        ID_CARD -> CardEnhancer.enhance(pixels, width, height)
        DOCUMENT, WHITEBOARD -> DocumentEnhancer.enhance(pixels, width, height)
    }
}
