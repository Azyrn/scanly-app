package com.skeler.scanely.core.ai

import android.os.SystemClock
import com.skeler.scanely.core.network.BackendError
import com.skeler.scanely.core.network.BackendErrorEnvelope
import com.skeler.scanely.core.network.ChatStreamChunk
import com.skeler.scanely.core.network.DesignJobAccepted
import com.skeler.scanely.core.network.DesignJobEvent
import com.skeler.scanely.core.network.DesignJobPending
import com.skeler.scanely.core.network.DesignRequest
import com.skeler.scanely.core.network.ScanlyApi
import com.skeler.scanely.core.network.TolerantJson
import com.skeler.scanely.core.network.TranscriptDto
import com.skeler.scanely.core.network.TranslateRequest
import com.skeler.scanely.core.network.backendError
import com.skeler.scanely.core.transcribe.TranscribeOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.source
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A backend failure that may succeed on retry.
 *
 * [retryAfterSeconds] is the server's own hint. Honouring it matters more than it looks: Gemini
 * answers a capacity failure with 30 seconds, and the local backoff alone waits about one, so
 * ignoring the hint burned all three attempts inside four seconds and reported a failure the
 * server had said would clear.
 */
internal class TransientAiException(
    message: String,
    val retryAfterSeconds: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/** A backend failure the user must resolve before retrying. */
internal class FatalAiException(val result: AiResult.Error) : Exception(result.message)

internal data class BackendResponse(
    val text: String,
    val images: Map<String, String> = emptyMap(),
    val links: List<String> = emptyList(),
    val unclear: List<String> = emptyList(),
    val extraction: ScanExtraction? = null,
    val pagesBilled: Int = 0
)

data class ScanExtraction(
    val label: String,
    val fields: List<Pair<String, String>>,
    val json: String
)

/**
 * Maps a backend error envelope onto the two exceptions the retry loop understands.
 *
 * Top-level rather than a method so [BackendClient] and `CustomDesigner` cannot drift apart on it.
 * The distinction it draws is the whole of the retry policy: `provider_error`, `internal` and
 * `rate_limited` may succeed on the next attempt, and everything else is the user's to resolve, so
 * retrying it only wastes their time.
 */
internal fun HttpException.toAiException(waitForDesign: Boolean = false): Exception {
    return backendFailure(code(), backendError(), waitForDesign)
}

/**
 * The same mapping for an error that arrived without an HTTP error status — `/v1/transcribe`
 * writes a failure that happens after it has started answering as the envelope inside a 200.
 */
internal fun backendFailure(
    status: Int,
    error: BackendError?,
    waitForDesign: Boolean = false
): Exception {
    val code = error?.code ?: when (status) {
        401 -> "unauthenticated"
        429 -> "rate_limited"
        in 400..499 -> "bad_request"
        else -> "internal"
    }
    val message = error?.message ?: when (status) {
        401 -> "Sign in to use AI scanning."
        else -> "AI scanning failed. Try again."
    }

    if (waitForDesign && code == "bad_request" && error?.reason == "duplicate_request") {
        return DesignStillProcessingException()
    }

    if (code == "bad_request" && error?.reason == "duplicate_request") {
        return TransientAiException(message, 5)
    }

    return when (code) {
        "provider_error", "internal", "rate_limited" ->
            TransientAiException(message, error?.retryAfterSeconds)
        else -> FatalAiException(
            AiResult.Error(
                message = message,
                code = code,
                requiredTier = error?.requiredTier,
                ask = designAsk(code, error),
                notCharged = error?.notCharged == true
            )
        )
    }
}

private fun designAsk(code: String, error: BackendError?): DesignAsk? = when (code) {
    "design_mismatch" -> DesignAsk.Mismatch(error?.reason.orEmpty(), error?.suggestedTemplate, error?.intakeToken)
    "design_missing_fields" -> error?.fields?.takeIf { it.isNotEmpty() }?.let { fields ->
        DesignAsk.MissingFields(fields.map { DesignField(it.id, it.label) }, error.intakeToken)
    }
    else -> null
}

/** Not a data class: an equals over the PDF bytes would be a deep comparison nothing wants. */
internal class DesignBytes(
    val pdf: ByteArray,
    val designId: String,
    val pagesBilled: Int
)

@Singleton
internal class BackendClient @Inject constructor(
    private val api: ScanlyApi
) {
    private val json = TolerantJson

    suspend fun ocr(
        file: PayloadFactory.Payload.File,
        idempotencyKey: String,
        pageRange: String? = null,
        extractPreset: String? = null
    ): BackendResponse {
        val part = MultipartBody.Part.createFormData(
            "file",
            file.fileName,
            file.bytes.toRequestBody(file.mimeType.toMediaTypeOrNull())
        )

        val response = try {
            api.ocr(
                part,
                idempotencyKey,
                pageRange?.let { MultipartBody.Part.createFormData("pages", it) },
                extractPreset?.let { MultipartBody.Part.createFormData("extract", it) }
            )
        } catch (e: HttpException) {
            throw e.toAiException()
        }

        val pages = response.pages.sortedBy { it.index }
        return BackendResponse(
            text = pages
                .map { it.markdown.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(PAGE_BREAK)
                .trim(),
            images = pages.flatMap { it.images.entries }.associate { it.key to it.value },
            links = pages.flatMap { it.links }.distinct(),
            unclear = pages.flatMap { it.unclear },
            extraction = response.extraction?.takeIf { it.fields.isNotEmpty() }?.let { dto ->
                ScanExtraction(
                    label = dto.label,
                    fields = dto.fields.map { it.label to it.value },
                    json = dto.json
                )
            },
            pagesBilled = response.usage.pages
        )
    }

    /**
     * Uploads a prepared recording and waits — through the Worker's heartbeat — for its transcript.
     *
     * The file streams from disk rather than through a byte array: a three-hour recording is tens
     * of megabytes, and [onUploadProgress] needs to see it leave anyway.
     */
    suspend fun transcribe(
        file: File,
        mimeType: String,
        options: TranscribeOptions,
        idempotencyKey: String,
        onUploadProgress: (Float) -> Unit
    ): TranscriptDto {
        val filePart = MultipartBody.Part.createFormData(
            "file",
            file.name,
            ProgressFileBody(file, mimeType.toMediaTypeOrNull(), onUploadProgress)
        )
        val parts = buildList {
            add(MultipartBody.Part.createFormData("language", options.language ?: "auto"))
            add(MultipartBody.Part.createFormData("format", options.formatNumbers.toString()))
            add(MultipartBody.Part.createFormData("diarize", options.speakerLabels.toString()))
            add(MultipartBody.Part.createFormData("fillerWords", options.keepFillerWords.toString()))
            options.keyTerms.forEach { add(MultipartBody.Part.createFormData("keyterm", it)) }
        }

        val body: ResponseBody = try {
            api.transcribe(filePart, parts, idempotencyKey)
        } catch (e: HttpException) {
            throw e.toAiException()
        }
        return decodeTranscript(body.use { it.string() })
    }

    /** Heartbeat spaces precede the JSON; a failure after the upload is an envelope in the 200. */
    private fun decodeTranscript(raw: String): TranscriptDto {
        val parsed = json.parseToJsonElement(raw.trim()) as? JsonObject
            ?: throw TransientAiException("The transcript arrived incomplete. Try again.")
        val error = parsed["error"]?.let {
            json.decodeFromJsonElement(BackendErrorEnvelope.serializer(), parsed).error
        }
        if (error != null) throw backendFailure(HTTP_OK, error)
        return json.decodeFromJsonElement(TranscriptDto.serializer(), parsed)
    }

    suspend fun translate(
        text: String,
        targetLanguage: String,
        idempotencyKey: String,
        emit: suspend (AiEvent) -> Unit
    ): String {
        val body: ResponseBody = try {
            api.translate(TranslateRequest(text, targetLanguage), idempotencyKey)
        } catch (e: HttpException) {
            throw e.toAiException()
        }
        return stream(body, "translation", emit)
    }

    /**
     * Unlike every other call here, a non-2xx does not arrive as an exception: the route is
     * declared to return [Response] so the success headers can be read, and Retrofit hands those
     * back rather than throwing. Failing to check [Response.isSuccessful] would turn a paywall
     * response into an error body parsed as a PDF.
     */
    suspend fun design(
        prompt: String,
        sourceText: String?,
        designId: String?,
        photo: String?,
        document: String?,
        idempotencyKey: String,
        template: String? = null,
        answer: DesignIntakeAnswer? = null,
        ocrReceipt: String? = null,
        onBackground: suspend (jobId: String) -> Unit = {},
        /** The chat app: every design is a job, followed live; see [followJob]. */
        chat: Boolean = false,
        onStep: suspend (id: String, at: Long) -> Unit = { _, _ -> }
    ): DesignBytes {
        val request = DesignRequest(
            prompt = prompt,
            sourceText = sourceText,
            designId = designId,
            photo = photo,
            document = document,
            template = template,
            force = answer?.force?.takeIf { it },
            intakeToken = answer?.intakeToken,
            fields = answer?.fields?.takeIf { it.isNotEmpty() },
            locale = Locale.getDefault().toLanguageTag(),
            ocrReceipt = ocrReceipt
        )
        val response = awaitDesignResponse {
            if (chat) api.designChat(request, idempotencyKey) else api.design(request, idempotencyKey)
        }
        if (response.code() == HTTP_ACCEPTED) {
            // A design the server runs as a background job. A retry of this request replays the
            // same job id, so following it again is never a second design.
            val job = response.body()?.use { json.decodeFromString(DesignJobAccepted.serializer(), it.string()) }
                ?: throw TransientAiException("The designer did not say where your document is being built.")
            onBackground(job.jobId)
            if (!chat) return awaitDesignJob(job.deadlineSeconds) { api.designJob(job.jobId) }.toDesignBytes()
            return followJob(job, onStep).toDesignBytes()
        }

        return response.toDesignBytes()
    }

    /**
     * Follows a chat design: its steps live from the events stream, then the PDF (or the error)
     * from the job route, exactly as a polling client gets it. A stream that will not open or
     * drops early costs nothing — polling, which also reports steps, carries on from there.
     */
    private suspend fun followJob(
        job: DesignJobAccepted,
        onStep: suspend (id: String, at: Long) -> Unit
    ): Response<ResponseBody> {
        try {
            val events = api.designJobEvents(job.jobId)
            if (events.isSuccessful) {
                events.body()?.let { body -> readJobEvents(body) { event -> event.report(onStep) } }
            } else {
                events.errorBody()?.close()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Polling below reports the steps instead.
        }
        return awaitDesignJob(job.deadlineSeconds, CHAT_POLL_MS, onPending = { pending ->
            runCatching { json.decodeFromString(DesignJobPending.serializer(), pending) }
                .getOrNull()?.steps?.forEach { onStep(it.step, it.at) }
        }) { api.designJob(job.jobId) }
    }

    /**
     * Reads `data:` lines until the job's `done` or `failed`, or the end of the stream. Blocking
     * reads, made interruptible so leaving the chat cancels them at once.
     */
    private suspend fun readJobEvents(body: ResponseBody, onEvent: suspend (DesignJobEvent) -> Unit) {
        body.use {
            val source = it.source()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = runInterruptible(Dispatchers.IO) { source.readUtf8Line() } ?: return
                if (!line.startsWith("data:")) continue
                val payload = line.substring(DATA_PREFIX.length).trim()
                val event = runCatching { json.decodeFromString(DesignJobEvent.serializer(), payload) }.getOrNull()
                    ?: continue
                onEvent(event)
                if (event.type != "step") return
            }
        }
    }

    private fun Response<ResponseBody>.toDesignBytes(): DesignBytes {
        val bytes = body()?.use { it.bytes() }
            ?: throw TransientAiException("The designer sent back an empty document.")
        val id = headers()[DESIGN_ID_HEADER]
            ?: throw TransientAiException("The designer sent back a document with no id.")

        return DesignBytes(
            pdf = bytes,
            designId = id,
            pagesBilled = headers()[USAGE_PAGES_HEADER]?.toIntOrNull() ?: 0
        )
    }

    private suspend fun stream(
        body: ResponseBody,
        label: String,
        emit: suspend (AiEvent) -> Unit
    ): String {
        emit(AiEvent.Stage(AiStage.PROCESSING))

        val accumulated = StringBuilder()
        val start = SystemClock.elapsedRealtime()
        var lastEmit = 0L

        try {
            consumeSse(body) { payload ->
                val piece = json.decodeFromString<ChatStreamChunk>(payload)
                    .choices.firstOrNull()?.delta?.content
                if (!piece.isNullOrEmpty()) {
                    if (accumulated.isEmpty()) emit(AiEvent.Stage(AiStage.GENERATING))
                    accumulated.append(piece)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEmit >= DELTA_THROTTLE_MS) {
                        lastEmit = now
                        emit(AiEvent.Delta(accumulated.toString()))
                    }
                }
            }
        } catch (e: FatalAiException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The operation key now protects chat replay, including a lost partial response.
            throw e
        }

        val result = accumulated.toString().trim()
        if (result.isNotEmpty()) {
            emit(AiEvent.Delta(result))
            aiDebug { "$label done in ${SystemClock.elapsedRealtime() - start} ms" }
        }
        return result
    }

    private suspend fun consumeSse(body: ResponseBody, onData: suspend (String) -> Unit) {
        body.use {
            val source = it.source()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: throw java.io.IOException("Incomplete chat response")
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload == "[DONE]") break
                if (payload.isNotEmpty()) onData(payload)
            }
        }
    }

    companion object {
        private const val DELTA_THROTTLE_MS = 100L
        private const val CHAT_POLL_MS = 2_000L
        private const val DATA_PREFIX = "data:"

        private const val PAGE_BREAK = "\n\n---\n\n"

        private const val HTTP_OK = 200

        private const val DESIGN_ID_HEADER = "X-Design-Id"
        private const val USAGE_PAGES_HEADER = "X-Usage-Pages"
    }
}

private class DesignStillProcessingException : Exception()

/** Streams [file] as a request body, reporting the fraction written so far. */
private class ProgressFileBody(
    private val file: File,
    private val type: MediaType?,
    private val onProgress: (Float) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType? = type
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length().coerceAtLeast(1L)
        var written = 0L
        file.source().use { source ->
            while (true) {
                val read = source.read(sink.buffer, UPLOAD_CHUNK_BYTES)
                if (read == -1L) break
                sink.flush()
                written += read
                onProgress((written.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }

    private companion object {
        const val UPLOAD_CHUNK_BYTES = 64L * 1024
    }
}

internal const val HTTP_ACCEPTED = 202

/**
 * Follows a background design until it answers with the PDF. Its failure is final: the job ran
 * once, and retrying the request would only replay the same failed job.
 */
internal suspend fun awaitDesignJob(
    deadlineSeconds: Int,
    pollDelayMs: Long = 4_000L,
    /** The 202 body while the job runs: its state and, from servers that report them, its steps. */
    onPending: (suspend (String) -> Unit)? = null,
    call: suspend () -> Response<ResponseBody>
): Response<ResponseBody> {
    val response = withTimeoutOrNull((deadlineSeconds + 60) * 1000L) {
        var response = call()
        while (response.isSuccessful && response.code() == HTTP_ACCEPTED) {
            response.body()?.use { body -> onPending?.invoke(body.string()) }
            delay(pollDelayMs)
            response = call()
        }
        response
    } ?: throw TransientAiException("Your document is still being built. Try again in a moment.")
    if (!response.isSuccessful) {
        val error = HttpException(response).backendError()
        val failure = backendFailure(response.code(), error)
        throw failure as? FatalAiException ?: FatalAiException(
            AiResult.Error(
                failure.message ?: "The designer failed. Try again.",
                code = error?.code,
                notCharged = error?.notCharged == true
            )
        )
    }
    return response
}

/** Poll only an explicitly in-flight design; retain its original billing key at the call site. */
internal suspend fun awaitDesignResponse(
    pollDelayMs: Long = 5_000L,
    call: suspend () -> Response<ResponseBody>
): Response<ResponseBody> = withTimeout(300_000L) {
    var response = call()
    while (!response.isSuccessful) {
        val failure = HttpException(response).toAiException(waitForDesign = true)
        if (failure !is DesignStillProcessingException) throw failure
        delay(pollDelayMs)
        response = call()
    }
    response
}

/** A step event reports itself; `done` and `failed` carry the whole list, which fills any gap. */
private suspend fun DesignJobEvent.report(onStep: suspend (id: String, at: Long) -> Unit) {
    if (type == "step" && step != null) onStep(step, at) else steps.forEach { onStep(it.step, it.at) }
}
