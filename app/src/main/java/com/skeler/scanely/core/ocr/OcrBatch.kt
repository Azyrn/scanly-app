package com.skeler.scanely.core.ocr

data class BatchProgress(val done: Int, val total: Int) {
    val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
}

/** Merges per-file results; labels files and renumbers block pages. */
class OcrBatchBuilder {
    private val text = StringBuilder()
    private val markdown = StringBuilder()
    private val blocks = mutableListOf<TextBlockData>()
    private var confidenceSum = 0f
    private var parts = 0
    private var pageOffset = 0
    private var allPaddle = true
    private var lastError: String? = null

    fun add(label: String?, result: OcrResult.Success) {
        val body = result.text.trimEnd()
        if (body.isNotBlank()) {
            if (text.isNotEmpty()) text.append("\n\n")
            if (label != null) text.append("=== ").append(label).append(" ===\n\n")
            text.append(body)
        }

        result.markdown?.trimEnd()?.takeIf { it.isNotBlank() }?.let { page ->
            if (markdown.isNotEmpty()) markdown.append("\n\n---\n\n")
            if (label != null) markdown.append("## ").append(label).append("\n\n")
            markdown.append(page)
        }

        blocks += result.blocks.map { it.copy(page = it.page + pageOffset) }
        pageOffset += result.blocks.maxOfOrNull { it.page } ?: 1
        confidenceSum += result.confidence
        if (result.source != OcrSource.PADDLE) allPaddle = false
        parts++
    }

    fun addError(message: String) {
        lastError = message
    }

    fun build(): OcrResult {
        if (parts > 0 && text.isNotBlank()) {
            return OcrResult.Success(
                text = text.toString(),
                blocks = blocks,
                confidence = confidenceSum / parts,
                source = if (allPaddle) OcrSource.PADDLE else OcrSource.ML_KIT,
                markdown = markdown.toString().ifBlank { null }
            )
        }
        // Fail only when nothing was read.
        return lastError?.let { OcrResult.Error(it) } ?: OcrResult.Empty
    }
}
