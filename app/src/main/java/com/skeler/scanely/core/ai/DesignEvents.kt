package com.skeler.scanely.core.ai

import android.net.Uri

/**
 * The designer returns a PDF, not text, so it does not reuse [AiResult]. Failures do, because the
 * paywall and quota sheets already switch on [AiResult.Error.code].
 */
sealed class DesignResult {
    data class Success(
        val pdf: Uri,
        /** Send back to revise this design; the server still holds the Typst it was built from. */
        val designId: String,
        val pagesBilled: Int
    ) : DesignResult()

    data class Failure(
        val error: AiResult.Error,
        /** Safe to recover with the same request ID after background connectivity loss. */
        val canRecoverOnForeground: Boolean = false
    ) : DesignResult()
}

sealed class DesignEvent {
    data class Stage(val stage: AiStage, val message: String? = null) : DesignEvent()
    data class Finished(val result: DesignResult) : DesignEvent()

    /** The server is building this one as a background job; [jobId] can be followed without the app. */
    data class Background(val jobId: String) : DesignEvent()

    /** A step the server's design reached ([id] as the Worker names it), at [at] on the server's clock. */
    data class Step(val id: String, val at: Long) : DesignEvent()
}

/**
 * The designer checked the request before building it and needs the user first. Nothing was held
 * or billed. [intakeToken] goes back with the answer so the server does not check again.
 */
sealed class DesignAsk {
    abstract val intakeToken: String?

    /** The attached source is another kind of content than the requested document. */
    data class Mismatch(
        val reason: String,
        val suggestedTemplate: String?,
        override val intakeToken: String?
    ) : DesignAsk()

    /** Required details are missing; the document is built once the user fills these in. */
    data class MissingFields(
        val fields: List<DesignField>,
        override val intakeToken: String?
    ) : DesignAsk()
}

data class DesignField(val id: String, val label: String)

/** How the user answered a [DesignAsk]; sent with the re-sent design request. */
data class DesignIntakeAnswer(
    val force: Boolean = false,
    val intakeToken: String? = null,
    val fields: Map<String, String> = emptyMap()
)
