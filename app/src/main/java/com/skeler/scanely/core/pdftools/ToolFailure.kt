package com.skeler.scanely.core.pdftools

/**
 * Why a tool run stopped. Each one is a different message, because each one asks the user to do
 * something different: find the file again, repair it, free space, or retype a password.
 */
sealed interface ToolFailure {
    /** The picked file couldn't be read from its provider. */
    data object Unreadable : ToolFailure

    /** Read, but damaged or not a PDF. */
    data object Damaged : ToolFailure

    data object LowStorage : ToolFailure

    data object WrongPassword : ToolFailure

    /** Unlock on a document that has no password. */
    data object NotEncrypted : ToolFailure

    /** The document has nothing this tool can take out of it — no images to extract, say. */
    data object NothingFound : ToolFailure

    /** Compression that couldn't beat the original; the larger copy is never handed back. */
    data object NoGain : ToolFailure

    /** The engine failed for a reason the user can't act on; [detail] is for the log only. */
    data class Failed(val detail: String?) : ToolFailure
}

class ToolException(val failure: ToolFailure, cause: Throwable? = null) :
    Exception(failure.toString(), cause)
