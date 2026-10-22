package com.skeler.scanely.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Scanly's own backend. The app never talks to Mistral; the Worker holds the key.
 *
 * Note what is *absent*: no model parameter anywhere, no page count, and no prompt outside
 * [compose] and [design]. Those two carry a user-written prompt, but only ever as a *user* message
 * under a system prompt the Worker owns, so this still cannot be repurposed into a general-purpose
 * LLM endpoint spending our credit on arbitrary requests.
 */
interface ScanlyApi {

    /** Tier, remaining pages, plan catalogue. The only source of truth for quota. */
    @GET("v1/me")
    suspend fun me(): MeResponse

    /** Erases the Firebase identity and every row keyed to it. Required by Play policy. */
    @DELETE("v1/me")
    suspend fun deleteAccount()

    /**
     * OCRs one file. The raw bytes go up as multipart; the server decides what they cost.
     *
     * [idempotencyKey] is one UUID per scan, reused across retries, so a retry after a timeout
     * replays the original result instead of billing twice. [pages] narrows a PDF to `1-5,8`; it is
     * the only parameter that moves the price and it can only lower it, since the server resolves
     * the range against the real document. [extract] is a preset *name*, never a schema or prompt.
     */
    @Multipart
    @POST("v1/ocr")
    suspend fun ocr(
        @Part file: MultipartBody.Part,
        @Header("X-Idempotency-Key") idempotencyKey: String,
        @Part pages: MultipartBody.Part?,
        @Part extract: MultipartBody.Part?
    ): OcrResponse

    /**
     * Transcribes one recording. The server measures its length from the bytes and bills what the
     * provider processed; nothing here states a duration or a price.
     *
     * [Streaming] because the Worker answers at once and holds the connection with whitespace until
     * the transcript is ready — minutes, for a long recording. A failure after that point arrives as
     * the error envelope inside a 200, so the body is parsed by [BackendClient], not by a converter.
     * [options] carries `language`, `format`, `diarize`, `fillerWords` and repeated `keyterm` parts.
     */
    @Streaming
    @Multipart
    @POST("v1/transcribe")
    suspend fun transcribe(
        @Part file: MultipartBody.Part,
        @Part options: List<MultipartBody.Part>,
        @Header("X-Idempotency-Key") idempotencyKey: String
    ): ResponseBody

    @Streaming
    @POST("v1/translate")
    suspend fun translate(
        @Body request: TranslateRequest,
        @Header(
            "X-Idempotency-Key"
        ) idempotencyKey: String
    ): ResponseBody

    /**
     * Designs a PDF and returns the raw bytes, not Markdown.
     *
     * Wrapped in [Response] because the design's id comes back in `X-Design-Id` and what it billed
     * in `X-Usage-Pages`; revising a design sends that id back, and the server reloads the Typst it
     * stored. The markup itself never reaches the client, so it cannot be edited and re-submitted.
     * `design-ask-v1` tells the server this app shows design_mismatch / design_missing_fields;
     * without it the server skips the check and designs, as it did for older releases.
     * `design-job-v1`: a photo or PDF rebuild may answer 202 [DesignJobAccepted] instead of the
     * PDF; the app then follows [designJob].
     */
    @POST("v1/design")
    @Headers("X-Scanly-Caps: design-ask-v1, design-job-v1")
    suspend fun design(
        @Body request: DesignRequest,
        @Header("X-Idempotency-Key") idempotencyKey: String
    ): Response<ResponseBody>

    /**
     * [design] for the chat: `design-chat-v1` makes every design a background job, followed with
     * [designJobEvents]. Same body, same billing; only how the answer arrives differs.
     */
    @POST("v1/design")
    @Headers("X-Scanly-Caps: design-ask-v1, design-job-v1, design-chat-v1")
    suspend fun designChat(
        @Body request: DesignRequest,
        @Header("X-Idempotency-Key") idempotencyKey: String
    ): Response<ResponseBody>

    /** A job's steps as server-sent events, ending with `done` or `failed`. Reporting only. */
    @Streaming
    @GET("v1/design/jobs/{id}/events")
    suspend fun designJobEvents(@Path("id") id: String): Response<ResponseBody>

    /**
     * The system and user prompts a *custom* endpoint needs to write the Typst for a design.
     *
     * Composed by the Worker rather than by the app because the prompt names the exact font
     * families installed in the compile container — one contract with `typst/Dockerfile` that a
     * copy in this repo would drift from on its own release schedule.
     */

    /** A background design: 202 while it builds, the PDF (same headers as [design]) when done. */
    @GET("v1/design/jobs/{id}")
    suspend fun designJob(@Path("id") id: String): Response<ResponseBody>

    @POST("v1/design/prompt")
    suspend fun designPrompt(@Body request: DesignPromptRequest): DesignPromptResponse

    /**
     * Compiles Typst a user's own model wrote, and returns the PDF.
     *
     * Two different 200s, told apart by `Content-Type`: `application/pdf` is the finished document,
     * and `application/json` is [DesignCompileFailure] — markup the compiler rejected, which the
     * caller repairs and posts again rather than reporting as an error.
     */
    @POST("v1/design/compile")
    suspend fun designCompile(
        @Body request: DesignCompileRequest,
        @Header("X-Idempotency-Key") idempotencyKey: String
    ): Response<ResponseBody>

    /**
     * Binds this device to a license key bought on the website. Re-activating a device that is
     * already bound is free — a reinstall must not burn one of the three slots.
     */
    @POST("v1/license/activate")
    suspend fun activateLicense(@Body request: LicenseRequest): LicenseEntitlementDto

    /** The periodic re-check. Also what attaches the account to an activation made before sign-in. */
    @POST("v1/license/status")
    suspend fun licenseStatus(@Body request: LicenseRequest): LicenseEntitlementDto

    /** Frees this device's slot so the key can be used elsewhere. */
    @POST("v1/license/deactivate")
    suspend fun deactivateLicense(@Body request: LicenseRequest): LicenseEntitlementDto

    /**
     * The keys this account owns — the one licence route that hands a key out rather than taking
     * one, and so the only one that will not answer without a token.
     *
     * Sends no body: the account is the whole request. The server matches on an activation this
     * account already made, or on its *confirmed* email address; an unconfirmed one comes back as
     * [LicenseRecoveryResponse.emailUnverified] with nothing found.
     */
    @POST("v1/license/mine")
    suspend fun myLicenses(): LicenseRecoveryResponse
}

/**
 * These routes accept an unauthenticated caller — the key is the credential — but the Worker
 * links `license_activations.uid` only when an `Authorization` header is present, and quota is
 * keyed by uid. Going through [ScanlyApi] is what guarantees the header: its Retrofit client
 * carries [BackendAuthInterceptor] on every call. A license redeemed without it would report
 * success and never resolve to Pro on the OCR path.
 */
@Serializable
data class LicenseRequest(
    val key: String,
    val deviceId: String,
    val deviceName: String? = null
)

@Serializable
data class LicenseRecoveryResponse(
    val licenses: List<RecoveredLicenseDto> = emptyList(),
    /** Nothing was searched beyond this account's own activations. Confirm the address and retry. */
    val emailUnverified: Boolean = false
)

@Serializable
data class RecoveredLicenseDto(
    val key: String,
    val tier: String,
    val status: String,
    /** Usable keys come first, so the first entry is the one to redeem. */
    val grantsAccess: Boolean = false,
    val currentPeriodEnd: Long? = null
)

@Serializable
data class LicenseEntitlementDto(
    val tier: String,
    val status: String,
    val grantsAccess: Boolean,
    val currentPeriodEnd: Long? = null,
    val activationsUsed: Int = 0,
    val maxActivations: Int = 0
)

/**
 * Quota balances and operation costs are credits. The legacy page field names match `/v1/me`
 * for compatibility; document limits such as [maxPagesPerDoc] still count physical pages.
 */
@Serializable
data class MeResponse(
    val tier: String,
    val pagesRemainingMonth: Int,
    val pagesRemainingDay: Int,
    /**
     * Rewarded-ad pages, already counted in the two above. Separate so the UI can tell "you have
     * pages" from "you have pages because you watched ads".
     */
    val bonusPagesMonth: Int = 0,
    val bonusPagesDay: Int = 0,
    /** Pages one rewarded ad is worth, and how many more the server will honour today. */
    val rewardPagesPerAd: Int = 0,
    val rewardsRemainingToday: Int = 0,
    val pagesPerMonth: Int,
    val maxPagesPerDoc: Int,
    val maxUploadBytes: Long,
    val allowsOffice: Boolean,
    val allowsTranslation: Boolean,
    val allowsExtraction: Boolean = false,
    val allowsDesign: Boolean = false,
    /**
     * What a design typically costs a paid plan, for display. A design is billed on what it
     * actually cost to generate, so the real figure comes back in `X-Usage-Pages`.
     */
    val designPageCost: Int = 2,
    /**
     * Pages the account must have free before a design may start. This is a hold, not a price —
     * most of it is refunded on completion — but it is the number that explains a `quota_exceeded`
     * near the quota edge.
     */
    val designMaxPageCost: Int = 16,
    /**
     * What a design costs when the user's own endpoint wrote the Typst and only the compile was
     * ours. Charged instead of [designPageCost], not as well as it.
     */
    val designCompilePageCost: Int = 1,
    val periodResetsAt: Long,
    /** Longest recording one transcription may be on this plan. */
    val maxAudioMinutes: Int = 10,
    /** Audio one credit buys. The app quotes an estimate from this; the server bills the truth. */
    val transcribeSecondsPerCredit: Int = 180,
    /** Language codes the transcriber formats for. Served so a new one needs no app release. */
    val transcribeLanguages: List<String> = emptyList(),
    /** Served rather than compiled in, so a new preset ships without an app release. */
    val extractionPresets: List<ExtractionPresetDto> = emptyList(),
    /** Pages one extracted page costs, since annotation is billed above the plain rate. */
    val extractionPageCost: Int = 1,
    val plans: List<PlanDto> = emptyList()
)

/**
 * [prompt] and [schema] are only ever used to drive a *custom* endpoint: `/v1/ocr` takes the preset
 * id and looks its own schema up, so what the app holds here can never steer Scanly's model.
 * Served rather than compiled in, so a new preset works on both paths without an app release.
 */
@Serializable
data class ExtractionPresetDto(
    val id: String,
    val label: String,
    val description: String = "",
    val prompt: String = "",
    val schema: JsonObject? = null
)

@Serializable
data class PlanDto(
    val id: String,
    val productId: String? = null,
    val priceUsd: Double,
    val pagesPerMonth: Int,
    val maxPagesPerDoc: Int,
    val allowsOffice: Boolean,
    val allowsTranslation: Boolean,
    val allowsExtraction: Boolean = false,
    val allowsDesign: Boolean = false
)

@Serializable
data class OcrResponse(
    val pages: List<OcrPageDto> = emptyList(),
    val extraction: ExtractionDto? = null,
    val usage: OcrUsage = OcrUsage()
)

/**
 * A filled-in extraction preset. The server flattens the annotation into display rows, so the app
 * renders fields without knowing any schema; [json] is the same data for export.
 */
@Serializable
data class ExtractionDto(
    val preset: String = "",
    val label: String = "",
    val fields: List<ExtractedFieldDto> = emptyList(),
    val json: String = ""
)

@Serializable
data class ExtractedFieldDto(val label: String = "", val value: String = "")

@Serializable
data class OcrPageDto(
    val index: Int = 0,
    val markdown: String = "",
    /** Embedded figures, keyed by the id the Markdown references. */
    val images: Map<String, String> = emptyMap(),
    /** URLs the page links to. */
    val links: List<String> = emptyList(),
    /** Passages the OCR model itself flagged as low confidence. */
    val unclear: List<String> = emptyList()
)

@Serializable
data class OcrUsage(val pages: Int = 0)

/**
 * `/v1/transcribe`'s answer. Each word is `[startMs, endMs, charStart]` relative to its segment:
 * times from [TranscriptSegmentDto.start], the offset into [TranscriptSegmentDto.text]. A word runs
 * to the next word's start, less the joining space, or to the end of the text.
 */
@Serializable
data class TranscriptDto(
    val language: String = "",
    val durationSeconds: Double = 0.0,
    val formatted: Boolean = false,
    val speakerCount: Int = 0,
    val segments: List<TranscriptSegmentDto> = emptyList(),
    val usage: OcrUsage = OcrUsage()
)

@Serializable
data class TranscriptSegmentDto(
    val speaker: Int? = null,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val text: String = "",
    val words: List<List<Long>> = emptyList()
)

@Serializable
data class TranslateRequest(
    val text: String,
    val targetLanguage: String
)

/**
 * [sourceText] is a scan's text to lay out; omitted, the model writes the content too.
 * [designId] names a previous design to revise, which is how "change something" avoids re-sending
 * the source and re-paying to regenerate from nothing.
 */
@Serializable
data class DesignRequest(
    val prompt: String,
    val sourceText: String? = null,
    val designId: String? = null,
    /**
     * Base64 JPEG the design may place, e.g. the portrait on a CV. It goes to the compile service
     * as the file the document opens, and to the model, which sizes and places it for what it
     * shows. Omitted on a revision: the server kept it with the design.
     */
    val photo: String? = null,
    /**
     * Base64 JPEG or PDF the model reads and rebuilds — the page the user photographed, not an
     * illustration for it. Sent as bytes rather than as OCR text because the layout is the thing
     * being reproduced, and a scan with no text layer has no text to send.
     */
    val document: String? = null,
    /**
     * A gallery template id. The Worker shows the model that template's compiled Typst as the
     * house style to build from, and ignores ids it does not know.
     */
    val template: String? = null,
    /** "Continue anyway" after the server answered design_mismatch. */
    val force: Boolean? = null,
    /** The signed verdict from that answer, so replying to it costs no second check. */
    val intakeToken: String? = null,
    /** Answers to design_missing_fields, by the field ids the server sent. */
    val fields: Map<String, String>? = null,
    /** BCP 47, e.g. "ar-MA": labels for asked fields and the default digit style. */
    val locale: String? = null,
    /** From /v1/design/ocr for this [document]: [sourceText] is its corrected text. */
    val ocrReceipt: String? = null
)

@Serializable
data class DesignJobAccepted(val jobId: String, val deadlineSeconds: Int = 300)

/** A step a design job reached, server clock. Ids: planning, writing, building, checking, fixing, done. */
@Serializable
data class DesignJobStep(val step: String, val at: Long)

/** The 202 body of GET /v1/design/jobs/:id while it runs. Servers before steps send only the state. */
@Serializable
data class DesignJobPending(val state: String = "", val steps: List<DesignJobStep> = emptyList())

/** One event from the job's events stream; `steps` comes with `done` and `failed`. */
@Serializable
data class DesignJobEvent(
    val type: String,
    val step: String? = null,
    val at: Long = 0,
    val steps: List<DesignJobStep> = emptyList()
)

/**
 * [priorSource] is Typst the caller just failed to compile, which with [diagnostics] turns the
 * reply into a repair. [designId] names a stored design to revise instead, and the Worker loads its
 * source itself — so the app never has to hold Typst between sessions.
 */
@Serializable
data class DesignPromptRequest(
    val prompt: String,
    val sourceText: String? = null,
    val priorSource: String? = null,
    val designId: String? = null,
    val diagnostics: String? = null,
    val hasPhoto: Boolean = false,
    val template: String? = null
)

@Serializable
data class DesignPromptResponse(
    val system: String,
    val user: String
)

@Serializable
data class DesignCompileRequest(
    val source: String,
    val prompt: String,
    /** Base64 JPEG, or null on a revision, where [designId] reuses the one already stored. */
    val photo: String? = null,
    val designId: String? = null
)

/** The compiler's own words, for the one repair attempt. Never shown to the user as-is. */
@Serializable
data class DesignCompileFailure(
    val ok: Boolean = false,
    val diagnostics: String = "",
    /** `markup`: free, and what a repair fixes. `resources`: the compiler's limits; billed. */
    val reason: String? = null,
    val billed: Boolean = false,
    val pagesBilled: Int = 0
)

/** Error envelope. `code` is a stable string the UI switches on; `message` is user-facing prose. */
@Serializable
data class BackendErrorEnvelope(val error: BackendError? = null)

@Serializable
data class BackendError(
    val code: String = "internal",
    val message: String = "Something went wrong. Try again.",
    val tier: String? = null,
    val requiredTier: String? = null,
    val pagesRemainingMonth: Int? = null,
    val retryAfterSeconds: Int? = null,
    val maxActivations: Int? = null,
    val reason: String? = null,
    /** design_mismatch: the gallery template the source suits better. */
    val suggestedTemplate: String? = null,
    /** design_mismatch / design_missing_fields: send back with the answer. */
    val intakeToken: String? = null,
    /** design_missing_fields: what to ask for, labelled in the app's language. */
    val fields: List<BackendField>? = null,
    /** A failed design: the server released its hold, so nothing was billed. */
    val notCharged: Boolean? = null
)

@Serializable
data class BackendField(val id: String, val label: String)
