package com.skeler.scanely.core.ai.custom

import com.skeler.scanely.core.ai.AiEvent
import com.skeler.scanely.core.ai.AiStage
import com.skeler.scanely.core.ai.DesignBytes
import com.skeler.scanely.core.ai.TransientAiException
import com.skeler.scanely.core.ai.awaitDesignResponse
import com.skeler.scanely.core.ai.toAiException
import com.skeler.scanely.core.network.DesignCompileFailure
import com.skeler.scanely.core.network.DesignCompileRequest
import com.skeler.scanely.core.network.DesignPromptRequest
import com.skeler.scanely.core.network.ScanlyApi
import com.skeler.scanely.core.network.TolerantJson
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Designs a PDF using the user's own model, and Scanly's compiler.
 *
 * A design is two steps — write Typst, compile Typst — and only the second one needs us. So the
 * split runs down that seam: the endpoint the user pays for writes the markup, on their key and
 * their bill, and the Worker turns it into a PDF. A stronger model than the one Scanly can afford
 * to run for everybody produces a better document through exactly this path.
 *
 * The repair loop is the part that has to live here rather than on the server. `buildDesign` in the
 * Worker feeds a failed compile's diagnostics back to Gemini and tries once more, which is what
 * took the measured first-attempt rate from 8/10 to 10/10. The Worker cannot do that for a model it
 * has no key for, so the same one-attempt loop is driven from this side instead.
 */
@Singleton
internal class CustomDesigner @Inject constructor(
    private val api: ScanlyApi,
    private val scanner: CustomScanner
) {
    private val json = TolerantJson

    suspend fun design(
        prompt: String,
        sourceText: String?,
        designId: String?,
        photo: String?,
        template: String?,
        config: CustomProviderConfig,
        emit: suspend (AiEvent) -> Unit
    ): CustomDesignResult {
        val ask = DesignPromptRequest(
            prompt = prompt,
            sourceText = sourceText,
            designId = designId,
            hasPhoto = photo != null,
            template = template
        )

        val first = attempt(ask, prompt, photo, designId, config, emit)
        val rejected = when (first) {
            is Attempt.Built -> return CustomDesignResult.Success(first.design)
            is Attempt.Stopped -> return first.result
            is Attempt.Rejected -> first
        }

        // One repair, matching the server's. A second would double the cost of the cases that fail
        // for reasons a repair does not fix.
        //
        // The design id stays on the request even though `priorSource` now names the document to
        // fix — the Worker prefers the source it was sent, and the id is still what tells it a
        // photo is waiting. Dropping it made the repair prompt claim there was no photo while the
        // compile behind it still placed one.
        val repair = ask.copy(
            priorSource = rejected.source,
            diagnostics = rejected.diagnostics
        )

        return when (val second = attempt(repair, prompt, photo, designId, config, emit)) {
            is Attempt.Built -> CustomDesignResult.Success(second.design)
            is Attempt.Stopped -> second.result
            // Deliberately not the diagnostics: Typst quotes the offending source, and that source
            // may carry the user's own scanned document.
            is Attempt.Rejected -> CustomDesignResult.Failure(
                "${config.model} wrote a document Scanly couldn't compile, twice. Try describing " +
                    "it differently, or use a stronger model."
            )
        }
    }

    private sealed interface Attempt {
        data class Built(val design: DesignBytes) : Attempt

        /** Compiled and refused. [source] is what to repair, [diagnostics] is what to repair it by. */
        data class Rejected(val source: String, val diagnostics: String) : Attempt

        /** A failure no repair can fix; the run ends with [result]. */
        sealed interface Stopped : Attempt {
            val result: CustomDesignResult.Failure
        }

        /** The endpoint itself failed, so there is nothing to repair. */
        data class EndpointFailed(override val result: CustomDesignResult.Failure) : Stopped

        /**
         * The compiler ran out of time or memory, and billed for it. Not the model's markup, so a
         * repair would only be a second billed compile of a document that is still too heavy.
         */
        data class OverLimit(override val result: CustomDesignResult.Failure) : Stopped
    }

    /** One round trip to the user's model, then one to the compiler. */
    private suspend fun attempt(
        request: DesignPromptRequest,
        prompt: String,
        photo: String?,
        designId: String?,
        config: CustomProviderConfig,
        emit: suspend (AiEvent) -> Unit
    ): Attempt {
        emit(AiEvent.Stage(AiStage.GENERATING))
        val draft = when (val written = writeTypst(request, config)) {
            is TypstDraft.Written -> written.source
            is TypstDraft.Failed -> return Attempt.EndpointFailed(written.result)
        }

        emit(AiEvent.Stage(AiStage.PROCESSING))
        return when (val compiled = compile(draft, prompt, photo, designId)) {
            is CompileOutcome.Built -> Attempt.Built(compiled.design)
            is CompileOutcome.Rejected -> Attempt.Rejected(draft, compiled.diagnostics)
            is CompileOutcome.OverLimit -> Attempt.OverLimit(
                CustomDesignResult.Failure(overLimitMessage(compiled.pagesBilled))
            )
        }
    }

    private sealed interface TypstDraft {
        data class Written(val source: String) : TypstDraft
        data class Failed(val result: CustomDesignResult.Failure) : TypstDraft
    }

    private suspend fun writeTypst(
        request: DesignPromptRequest,
        config: CustomProviderConfig
    ): TypstDraft {
        val prompts = try {
            api.designPrompt(request)
        } catch (e: HttpException) {
            throw e.toAiException()
        }

        return when (val result = scanner.writeTypst(prompts.system, prompts.user, config)) {
            is CustomScanResult.Success -> TypstDraft.Written(unfence(result.markdown))
            is CustomScanResult.Failure ->
                TypstDraft.Failed(CustomDesignResult.Failure(result.failure.message))
            is CustomScanResult.Unsupported ->
                TypstDraft.Failed(CustomDesignResult.Failure(result.message))
        }
    }

    private sealed interface CompileOutcome {
        data class Built(val design: DesignBytes) : CompileOutcome
        data class Rejected(val diagnostics: String) : CompileOutcome
        data class OverLimit(val pagesBilled: Int) : CompileOutcome
    }

    private suspend fun compile(
        source: String,
        prompt: String,
        photo: String?,
        designId: String?
    ): CompileOutcome {
        val key = UUID.randomUUID().toString()
        val response = awaitDesignResponse {
            api.designCompile(DesignCompileRequest(source, prompt, photo, designId), key)
        }

        // The route answers 200 either way; the content type says which. Checking it rather than
        // the status is what keeps an uncompilable document a repairable step instead of an error.
        val contentType = response.headers()["Content-Type"].orEmpty()
        if (contentType.startsWith("application/json")) {
            val body = response.body()?.use { it.string() }.orEmpty()
            val failure = runCatching {
                json.decodeFromString<DesignCompileFailure>(body)
            }.getOrNull()
            if (failure?.billed == true || failure?.reason == "resources") {
                return CompileOutcome.OverLimit(failure.pagesBilled)
            }
            return CompileOutcome.Rejected(
                failure?.diagnostics?.ifBlank { null } ?: "error: the document failed to compile"
            )
        }

        return CompileOutcome.Built(response.toDesignBytes())
    }

    private fun Response<ResponseBody>.toDesignBytes(): DesignBytes {
        val bytes = body()?.use { it.bytes() }
            ?: throw TransientAiException("The compiler sent back an empty document.")
        val id = headers()[DESIGN_ID_HEADER]
            ?: throw TransientAiException("The compiler sent back a document with no id.")

        return DesignBytes(
            pdf = bytes,
            designId = id,
            pagesBilled = headers()[USAGE_PAGES_HEADER]?.toIntOrNull() ?: 0
        )
    }

    /**
     * Models fence code however firmly the prompt says not to. The server strips the same thing
     * from Gemini's output for the same reason — prose ahead of the document is a guaranteed
     * compile failure, and one regex here saves a whole repair round trip on the user's bill.
     */
    private fun unfence(text: String): String {
        val trimmed = text.trim()
        val fenced = Regex("```(?:typ|typst)?\\s*\\n([\\s\\S]*?)```").find(trimmed)
        return (fenced?.groupValues?.get(1) ?: trimmed).trim()
    }

    private fun overLimitMessage(pagesBilled: Int): String {
        val spent = when (pagesBilled) {
            0 -> "The attempt was"
            1 -> "The attempt used 1 credit and was"
            else -> "The attempt used $pagesBilled credits and was"
        }
        return "This design is too heavy for Scanly's compiler — it ran out of time or memory. " +
            "$spent not retried. Try a shorter document, or fewer pages and images."
    }

    private companion object {
        const val DESIGN_ID_HEADER = "X-Design-Id"
        const val USAGE_PAGES_HEADER = "X-Usage-Pages"
    }
}

internal sealed interface CustomDesignResult {
    data class Success(val design: DesignBytes) : CustomDesignResult
    data class Failure(val message: String) : CustomDesignResult
}
