package com.skeler.scanely.ui.scan

import com.skeler.scanely.core.idcard.IdDocumentKind
import com.skeler.scanely.core.scan.ScanMode

/**
 * The side of an ID document the camera is taking. Only sides the document has can be made: [of] refuses
 * an index at or past [IdDocumentKind.sides].
 */
@ConsistentCopyVisibility
data class IdSide private constructor(val kind: IdDocumentKind, val index: Int) {
    val isLast: Boolean get() = index == kind.sides - 1

    companion object {
        fun of(kind: IdDocumentKind, index: Int): IdSide? =
            if (index in 0 until kind.sides) IdSide(kind, index) else null
    }
}

/**
 * Pages another screen asked the scanner for (a signature, a document for the chat): Document mode only, at
 * most [maxPages], handed back under [key] instead of going to review.
 */
data class Handoff(val key: String, val maxPages: Int) {
    companion object {
        const val SIGNATURE = "signature"
        const val CHAT = "chat"
    }
}

/** What the scan session is collecting, and so where it ends. */
sealed interface ScanGoal {
    /** Document or whiteboard pages, for the review screen, or for [handoff]'s caller when it is set. */
    data class Pages(val mode: ScanMode, val handoff: Handoff? = null) : ScanGoal

    /** The sides of a new ID or passport copy, in order. */
    data class IdCopy(val kind: IdDocumentKind) : ScanGoal

    /** One side of the ID copy already open, taken again. */
    data class ReplaceSide(val side: IdSide) : ScanGoal
}

/**
 * The mode whose AUTO enhance is baked into a page when it is saved, or null when the page is saved as
 * photographed. ID sides go straight to the ID copy, which has no filter step, so they are enhanced here
 * (colour-safe, [ScanMode.ID_CARD]); document and whiteboard pages are filtered later on the review screen.
 */
val ScanGoal.enhanceOnSave: ScanMode?
    get() = when (this) {
        is ScanGoal.Pages -> null
        is ScanGoal.IdCopy, is ScanGoal.ReplaceSide -> ScanMode.ID_CARD
    }

/** Where confirming a crop leads. */
sealed interface CropStep {
    /** Back to the camera for another page or the next ID side. */
    data object Camera : CropStep

    /** The next photo picked from the gallery, into the crop. */
    data object NextImport : CropStep

    /** The session is complete. */
    data object Finish : CropStep
}

/**
 * The single rule for what follows a confirmed crop.
 *
 * @param sidesTaken ID sides already confirmed before this one.
 * @param importsLeft gallery photos still waiting for their crop.
 * @param done the user chose Done (Document / Whiteboard) rather than Add page.
 * @param pagesTaken pages already confirmed before this one; a handoff ends at its page cap.
 */
fun stepAfterCrop(goal: ScanGoal, sidesTaken: Int, importsLeft: Int, done: Boolean, pagesTaken: Int = 0): CropStep {
    val complete = when (goal) {
        is ScanGoal.ReplaceSide -> true
        is ScanGoal.IdCopy -> sidesTaken + 1 >= goal.kind.sides
        is ScanGoal.Pages -> done || goal.handoff?.let { pagesTaken + 1 >= it.maxPages } == true
    }
    return when {
        complete -> CropStep.Finish
        importsLeft > 0 -> CropStep.NextImport
        else -> CropStep.Camera
    }
}

/** The labels the crop screen's action row shows (spec 4, ID mode labels). */
enum class CropActions { ADD_PAGE_DONE, RETAKE_NEXT_BACK, RETAKE_MAKE_COPY, RETAKE_DONE }

fun cropActions(goal: ScanGoal, side: IdSide?): CropActions = when (goal) {
    is ScanGoal.Pages -> if (goal.handoff?.maxPages == 1) CropActions.RETAKE_DONE else CropActions.ADD_PAGE_DONE
    is ScanGoal.ReplaceSide -> CropActions.RETAKE_MAKE_COPY
    is ScanGoal.IdCopy ->
        if (side == null || side.isLast) CropActions.RETAKE_MAKE_COPY else CropActions.RETAKE_NEXT_BACK
}
