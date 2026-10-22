package com.skeler.scanely.core.ai

import android.net.Uri
import android.os.SystemClock
import com.skeler.scanely.core.ai.custom.CustomDesignResult
import com.skeler.scanely.core.ai.custom.CustomDesigner
import com.skeler.scanely.core.ai.custom.CustomProviderConfig
import com.skeler.scanely.core.ai.custom.CustomProviderResolver
import com.skeler.scanely.core.ai.custom.CustomScanResult
import com.skeler.scanely.core.ai.custom.CustomScanner
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.core.text.MarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

const val AI_PROVIDER_NAME = "Scanly AI"

@Singleton
class GenerativeAiService @Inject internal constructor(
    private val payloadFactory: PayloadFactory,
    private val client: BackendClient,
    private val executor: BackendExecutor,
    private val networkObserver: NetworkObserver,
    private val customProviderResolver: CustomProviderResolver,
    private val customScanner: CustomScanner,
    private val customDesigner: CustomDesigner,
    private val extractionCatalog: ExtractionCatalog,
    private val designStore: DesignStore,
    private val entitlementRepository: com.skeler.scanely.core.billing.EntitlementRepository
) {
    /**
     * The one place that decides between the user's endpoint and Scanly's backend.
     *
     * Every entry point below goes through this, and none of them re-decides. Each used to make the
     * call itself, and the failures were all of the same shape — an `if` that was simply never
     * written, which reads as nothing at all at the call site. See [AiFeature].
     */
    private suspend fun <T> route(
        feature: AiFeature,
        onCustomEndpoint: suspend (CustomProviderConfig) -> T,
        onScanlyBackend: suspend () -> T
    ): T {
        val config = if (feature.routing.usesCustomEndpoint) customProviderResolver.resolve() else null
        return if (config != null) onCustomEndpoint(config) else onScanlyBackend()
    }

    /** Sends a file to either the user's configured provider or the Scanly backend. */
    @AiEntryPoint(AiFeature.SCAN)
    fun processFileEvents(
        uri: Uri,
        pageRange: String? = null,
        extractPreset: String? = null
    ): Flow<AiEvent> =
        channelFlow {
            val totalStart = SystemClock.elapsedRealtime()
            send(AiEvent.Stage(AiStage.PREPARING))

            if (!networkObserver.isCurrentlyOnline()) {
                send(AiEvent.Finished(AiResult.Error(OFFLINE_MESSAGE)))
                return@channelFlow
            }

            // Resolved before the file is read so a custom-provider scan does not read it twice.
            // Plain text is answered locally on either route and never reaches a provider.
            val handled = !payloadFactory.isPlainText(uri) && route(
                AiFeature.SCAN,
                onCustomEndpoint = { config ->
                    scanOnEndpoint(uri, config, pageRange, extractPreset) { send(it) }
                    true
                },
                onScanlyBackend = { false }
            )
            if (handled) return@channelFlow

            scanOnScanly(uri, pageRange, extractPreset, totalStart) { send(it) }
        }.flowOn(Dispatchers.IO)

    private suspend fun scanOnEndpoint(
        uri: Uri,
        config: CustomProviderConfig,
        pageRange: String?,
        extractPreset: String?,
        emit: suspend (AiEvent) -> Unit
    ) {
        emit(AiEvent.Stage(AiStage.PROCESSING))

        // The preset's schema and prompt, not its id: this request never reaches the Worker that
        // could look one up. A preset the server has not described yet is refused rather than
        // scanned plainly, which would hand back a transcription where fields were asked for.
        val job = extractionCatalog.jobFor(extractPreset)
        if (extractPreset != null && job == null) {
            emit(
                AiEvent.Finished(
                    AiResult.Error(
                        "Scanly couldn't load that extraction type. Try again, or turn your own " +
                            "endpoint off in Settings to use Scanly AI."
                    )
                )
            )
            return
        }

        val result = customScanner.scan(uri, config, pageRange, job)
        emit(
            finished(result, config) { scan ->
                AiResult.Success(scan.markdown, extraction = scan.extraction)
            }
        )
    }

    private suspend fun scanOnScanly(
        uri: Uri,
        pageRange: String?,
        extractPreset: String?,
        totalStart: Long,
        emit: suspend (AiEvent) -> Unit
    ) {
        val payload = try {
            payloadFactory.create(
                uri,
                entitlementRepository.entitlement.value?.maxUploadBytes ?: PayloadFactory.MAX_UPLOAD_BYTES
            )
        } catch (e: PayloadFactory.PayloadException) {
            emit(AiEvent.Finished(AiResult.Error(e.message ?: "Failed to read file")))
            return
        }

        if (payload is PayloadFactory.Payload.LocalText) {
            emit(AiEvent.Stage(AiStage.COMPLETE))
            emit(AiEvent.Finished(AiResult.Success(payload.text)))
            return
        }

        // Reuse this key across retries to avoid billing a completed scan twice.
        val idempotencyKey = UUID.randomUUID().toString()
        val file = payload as PayloadFactory.Payload.File

        val outcome = executor.run(emit = emit) {
            emit(AiEvent.Stage(AiStage.PROCESSING))
            val response = client.ocr(file, idempotencyKey, pageRange, extractPreset)
            if (response.text.isBlank()) {
                throw FatalAiException(AiResult.Error("No text could be read from this file."))
            }
            response
        }

        if (outcome is AiOutcome.Success) {
            emit(AiEvent.Stage(AiStage.COMPLETE))
            aiDebug { "total ${SystemClock.elapsedRealtime() - totalStart} ms" }
        }
        emit(AiEvent.Finished(asScan(outcome)))
    }

    private fun asScan(outcome: AiOutcome<BackendResponse>): AiResult = when (outcome) {
        is AiOutcome.Success -> AiResult.Success(
            outcome.value.text,
            outcome.value.images,
            outcome.value.links,
            outcome.value.unclear,
            outcome.value.extraction
        )
        is AiOutcome.Fatal -> outcome.error
        is AiOutcome.Exhausted -> AiResult.Error(exhaustedMessage(outcome))
    }

    /**
     * Transcribes a prepared recording on Scanly's backend — the only place it can run; see
     * [AiFeature.TRANSCRIBE]. [idempotencyKey] is one per recording and reused across retries, so a
     * connection lost while waiting replays the stored transcript instead of billing again.
     */
    @AiEntryPoint(AiFeature.TRANSCRIBE)
    suspend fun transcribe(
        file: java.io.File,
        mimeType: String,
        options: com.skeler.scanely.core.transcribe.TranscribeOptions,
        idempotencyKey: String,
        onUploadProgress: (Float) -> Unit,
        emit: suspend (AiEvent) -> Unit
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        if (!networkObserver.isCurrentlyOnline()) {
            return@withContext TranscriptionResult.Failure(AiResult.Error(OFFLINE_MESSAGE))
        }
        val outcome = executor.run(emit = emit, attemptTimeoutMs = TRANSCRIBE_ATTEMPT_TIMEOUT_MS) {
            client.transcribe(file, mimeType, options, idempotencyKey, onUploadProgress)
        }
        when (outcome) {
            is AiOutcome.Success -> TranscriptionResult.Success(outcome.value)
            is AiOutcome.Fatal -> TranscriptionResult.Failure(outcome.error)
            is AiOutcome.Exhausted -> TranscriptionResult.Failure(AiResult.Error(exhaustedMessage(outcome)))
        }
    }

    /**
     * Designs a PDF. [sourceText] is a scan to lay out, or null to have the model write the content
     * too; [designId] revises an earlier design instead of starting over.
     *
     * Split across both routes rather than running on one. A chat endpoint cannot return a PDF, but
     * it is not being asked to: it writes Typst, which is text, and Scanly's container compiles it.
     * So a user with a stronger model than the one Scanly runs for everybody gets that model's
     * document — and still pays a page for the compile, which is genuinely ours. See [CustomDesigner].
     */
    @AiEntryPoint(AiFeature.DESIGN)
    fun designDocumentEvents(
        prompt: String,
        sourceText: String? = null,
        designId: String? = null,
        photo: String? = null,
        document: String? = null,
        requestId: String = UUID.randomUUID().toString(),
        template: String? = null,
        answer: DesignIntakeAnswer? = null,
        ocrReceipt: String? = null,
        /** The chat app: every design is a job, followed with live steps ([DesignEvent.Step]). */
        chat: Boolean = false
    ): Flow<DesignEvent> =
        channelFlow {
            send(DesignEvent.Stage(AiStage.PREPARING))

            if (!networkObserver.isCurrentlyOnline()) {
                send(
                    DesignEvent.Finished(
                        DesignResult.Failure(AiResult.Error(OFFLINE_MESSAGE), canRecoverOnForeground = true)
                    )
                )
                return@channelFlow
            }

            val listeners = DesignListeners(
                stage = { event -> if (event is AiEvent.Stage) send(DesignEvent.Stage(event.stage, event.message)) },
                background = { jobId ->
                    send(DesignEvent.Background(jobId))
                    // The chat says this itself, and only for the rebuilds that take minutes.
                    if (!chat) send(DesignEvent.Stage(AiStage.GENERATING, BACKGROUND_MESSAGE))
                },
                step = { id, at -> send(DesignEvent.Step(id, at)) }
            )

            // A custom run also bills the user's model and cannot replay that generation.
            val retrySafe = !usesCustomProvider()
            // One key per user action, reused across this call's retries, so a design that times
            // out on the wire and succeeds on retry is billed once rather than twice.
            val outcome = executor.run(emit = listeners.stage, retrySafe = retrySafe) {
                val call = DesignCall(prompt, sourceText, designId, photo, document, template, answer, ocrReceipt, chat)
                buildDesign(call, requestId, listeners)
            }
            send(finishedDesign(outcome, retrySafe))
        }.flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<DesignEvent>.finishedDesign(
        outcome: AiOutcome<DesignBytes>,
        retrySafe: Boolean
    ): DesignEvent.Finished = when (outcome) {
        is AiOutcome.Success -> {
            val design = outcome.value
            val pdf = designStore.write(design.designId, design.pdf)
            send(DesignEvent.Stage(AiStage.COMPLETE))
            DesignEvent.Finished(DesignResult.Success(pdf, design.designId, design.pagesBilled))
        }
        is AiOutcome.Fatal -> DesignEvent.Finished(DesignResult.Failure(outcome.error))
        is AiOutcome.Exhausted -> DesignEvent.Finished(
            DesignResult.Failure(AiResult.Error(exhaustedMessage(outcome)), canRecoverOnForeground = retrySafe)
        )
    }

    /**
     * The two halves of a design, behind one routing decision. A custom-endpoint failure is thrown
     * as fatal rather than returned: the retry loop must not re-run a design the user's own model
     * already charged them for twice.
     */
    private suspend fun buildDesign(
        call: DesignCall,
        idempotencyKey: String,
        listeners: DesignListeners
    ): DesignBytes = with(call) {
        val stage = listeners.stage
        route(
            AiFeature.DESIGN,
            onCustomEndpoint = { config ->
                // Refused rather than designed without it. /v1/design/prompt hands back text for the
                // user's own model to complete, and there is nowhere in that exchange to put an image —
                // so proceeding would drop the document silently and invent one in its place, which is
                // the exact failure this input was added to fix.
                if (document != null) {
                    throw FatalAiException(
                        AiResult.Error(
                            "Your own AI endpoint can't read a picked image or PDF. Turn off the " +
                                "custom endpoint to rebuild this document, or design from a scan instead."
                        )
                    )
                }
                when (
                    val result =
                        customDesigner.design(prompt, sourceText, designId, photo, template, config, stage)
                ) {
                    is CustomDesignResult.Success -> result.design
                    is CustomDesignResult.Failure ->
                        throw FatalAiException(AiResult.Error(result.message))
                }
            },
            onScanlyBackend = {
                stage(AiEvent.Stage(AiStage.GENERATING))
                client.design(
                    prompt, sourceText, designId, photo, document, idempotencyKey, template, answer, ocrReceipt,
                    onBackground = listeners.background,
                    chat = chat,
                    onStep = listeners.step
                )
            }
        )
    }

    suspend fun usesCustomProvider(): Boolean =
        withContext(Dispatchers.IO) { customProviderResolver.resolve() != null }

    @AiEntryPoint(AiFeature.TRANSLATE)
    suspend fun translateText(text: String, targetLanguage: String): AiResult =
        withContext(Dispatchers.IO) {
            if (!networkObserver.isCurrentlyOnline()) {
                return@withContext AiResult.Error(OFFLINE_MESSAGE)
            }

            route(
                AiFeature.TRANSLATE,
                onCustomEndpoint = { config ->
                    asResult(customScanner.translate(text, targetLanguage, config)) {
                        MarkdownParser.unwrapAddedFence(text, it.markdown)
                    }
                },
                onScanlyBackend = { translateOnScanly(text, targetLanguage) }
            )
        }

    private suspend fun translateOnScanly(text: String, targetLanguage: String): AiResult {
        val key = UUID.randomUUID().toString()
        val outcome = executor.run(emit = {}) {
            val result = client.translate(text, targetLanguage, key, emit = {})
            if (result.isBlank()) {
                throw FatalAiException(AiResult.Error("Translation came back empty."))
            }
            MarkdownParser.unwrapAddedFence(text, result)
        }

        return when (outcome) {
            is AiOutcome.Success -> AiResult.Success(outcome.value)
            is AiOutcome.Fatal -> outcome.error
            is AiOutcome.Exhausted -> AiResult.Error(exhaustedMessage(outcome))
        }
    }

    private fun asResult(
        result: CustomScanResult,
        onSuccess: (CustomScanResult.Success) -> String
    ): AiResult = when (result) {
        is CustomScanResult.Success -> AiResult.Success(onSuccess(result))
        is CustomScanResult.Failure -> AiResult.Error(result.failure.message)
        is CustomScanResult.Unsupported -> AiResult.Error(result.message)
    }

    /** Names the endpoint that answered, so a result screen can say whose model wrote it. */
    private fun finished(
        result: CustomScanResult,
        config: CustomProviderConfig,
        onSuccess: (CustomScanResult.Success) -> AiResult
    ): AiEvent.Finished = when (result) {
        is CustomScanResult.Success ->
            AiEvent.Finished(onSuccess(result), AiRunInfo(config.model))
        is CustomScanResult.Failure ->
            AiEvent.Finished(AiResult.Error(result.failure.message))
        is CustomScanResult.Unsupported ->
            AiEvent.Finished(AiResult.Error(result.message))
    }

    private fun exhaustedMessage(outcome: AiOutcome.Exhausted): String = when {
        outcome.networkProblem && !networkObserver.isCurrentlyOnline() -> OFFLINE_MESSAGE
        outcome.networkProblem ->
            "Couldn't reach $AI_PROVIDER_NAME — your connection looks unstable. " +
                "Check it and try again."
        else -> outcome.message ?: "Couldn't complete the request. Please try again."
    }

    companion object {
        private const val OFFLINE_MESSAGE =
            "No internet connection. Check your network and try again."

        /**
         * Per attempt, not per request. The Worker streams a heartbeat while xAI works, so the
         * socket never idles; this bounds a three-hour recording's upload plus its ~9-minute
         * two-pass wait, with room for a slow uplink.
         */
        private const val TRANSCRIBE_ATTEMPT_TIMEOUT_MS = 30 * 60_000L
    }
}

sealed interface TranscriptionResult {
    data class Success(val transcript: com.skeler.scanely.core.network.TranscriptDto) : TranscriptionResult
    data class Failure(val error: AiResult.Error) : TranscriptionResult
}

/** Where a key is bought. Rendered as plain text — never as a link. See `docs/PADDLE.md` §10. */
const val SCANLY_SITE = "scanlyocr.com"

/** What one design request carries, as [GenerativeAiService.designDocumentEvents] received it. */
private data class DesignCall(
    val prompt: String,
    val sourceText: String?,
    val designId: String?,
    val photo: String?,
    val document: String?,
    val template: String?,
    val answer: DesignIntakeAnswer? = null,
    val ocrReceipt: String? = null,
    val chat: Boolean = false
)

/** Where a design's progress goes: coarse stages, the background job id, and the server's own steps. */
private class DesignListeners(
    val stage: suspend (AiEvent) -> Unit,
    val background: suspend (String) -> Unit,
    val step: suspend (id: String, at: Long) -> Unit
)

private const val BACKGROUND_MESSAGE = "Building your document. You can leave the app; we'll notify you."
