package com.skeler.scanely.core.ai

enum class AiMode {
    EXTRACT_TEXT,
    EXTRACT_PDF_TEXT,

    EXTRACT_DATA,

    DESIGN_DOCUMENT,
    TRANSCRIBE_AUDIO,
    ICON_TRANSLATE
}

sealed class AiResult {
    data class Success(
        val text: String,
        val images: Map<String, String> = emptyMap(),
        val links: List<String> = emptyList(),
        val unclear: List<String> = emptyList(),
        val extraction: ScanExtraction? = null
    ) : AiResult()

    data class Error(
        val message: String,
        val code: String? = null,
        val requiredTier: String? = null,
        /** The designer is asking the user something before it builds (and bills) the document. */
        val ask: DesignAsk? = null,
        /** Said by the server: this failure released its hold and billed nothing. */
        val notCharged: Boolean = false
    ) : AiResult() {
        /**
         * Whether a bigger plan is the answer. A size refusal counts only when the server names
         * the plan that lifts it: extraction's page cap, the designer's input and other fixed
         * limits are the same on every plan, and a paywall for them promised an upgrade that would
         * be refused again.
         */
        val isUpgradePath: Boolean
            get() = code in setOf("quota_exceeded", "format_not_in_plan", "budget_exhausted") ||
                (code == "doc_too_large" && requiredTier != null)
    }
}

data class AiRunInfo(val model: String)

enum class AiStage {
    PREPARING,
    UPLOADING,
    PROCESSING,
    GENERATING,
    COMPLETE
}

sealed class AiEvent {
    data class Stage(val stage: AiStage, val message: String? = null) : AiEvent()
    data class Delta(val textSoFar: String) : AiEvent()
    data class Finished(val result: AiResult, val runInfo: AiRunInfo? = null) : AiEvent()
}
