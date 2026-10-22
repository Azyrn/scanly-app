package com.skeler.scanely.core.ocr

/** Only Paddle carries per-line boxes and confidences. */
enum class OcrSource { ML_KIT, PADDLE }

sealed class OcrResult {
    data class Success(
        val text: String,
        val blocks: List<TextBlockData> = emptyList(),
        val confidence: Float = 1.0f,
        val source: OcrSource = OcrSource.ML_KIT,
        /** Layout-aware Markdown from the offline structure pipeline. */
        val markdown: String? = null
    ) : OcrResult()

    data class Error(val message: String) : OcrResult()

    data object Empty : OcrResult()
}

data class TextBlockData(
    val text: String,
    val boundingBoxLeft: Int,
    val boundingBoxTop: Int,
    val boundingBoxRight: Int,
    val boundingBoxBottom: Int,
    val confidence: Float = 1.0f,
    val page: Int = 1
)

/**
 * What to tell the user when an offline scan produced no text, or null when it genuinely found
 * nothing on the page.
 *
 * A failure used to fall through to "No text extracted", which tells the user their document is
 * blank when the fault was the app's. The engine's own message is not shown: it is an exception
 * string, not a sentence written for a person.
 */
fun scanFailureMessage(result: OcrResult?): String? {
    val error = result as? OcrResult.Error ?: return null
    // ML Kit's text model is an unbundled Play Services module; until it has downloaded, every
    // recognition fails with this message. Blaming the image would send the user hunting for
    // another one that will fail the same way.
    return if (error.message.contains("download", ignoreCase = true)) {
        "Text recognition is still being set up on this phone. Stay online for a minute, then " +
            "try again."
    } else {
        "Scanly couldn't read this file. The scan failed, so this doesn't mean the page is " +
            "blank. Try again, or pick a different file."
    }
}
