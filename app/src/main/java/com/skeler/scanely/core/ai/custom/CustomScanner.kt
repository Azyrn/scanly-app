package com.skeler.scanely.core.ai.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.scale
import com.skeler.scanely.core.ai.ScanExtraction
import com.skeler.scanely.core.network.CustomProviderApi
import com.skeler.scanely.core.network.TolerantJson
import com.skeler.scanely.core.ocr.PdfRendererHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

internal sealed interface CustomScanResult {
    data class Success(
        val markdown: String,
        val extraction: ScanExtraction? = null
    ) : CustomScanResult

    data class Failure(val failure: ConnectionFailure) : CustomScanResult
    data class Unsupported(val message: String) : CustomScanResult
}

/** What a scan should ask the endpoint for: a transcription, or a filled-in schema. */
internal data class ExtractionJob(
    val label: String,
    val prompt: String,
    val schema: String
)

/**
 * Runs everything the user's own endpoint can do: scans, extractions, translations, drafts, and the
 * Typst behind a design.
 *
 * Unlike the backend path, the document never leaves as raw bytes: a chat completions API takes
 * images, so pages are rendered here and sent as base64 JPEG. That is also why Office formats are
 * refused rather than attempted — only the backend can convert those.
 *
 * Every entry point here is a single non-streaming completion. That is the shape the three
 * protocols genuinely share; anything richer would have to be written three times.
 */
@Singleton
internal class CustomScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: CustomProviderApi,
    private val pdfRenderer: PdfRendererHelper
) {
    private val json = TolerantJson

    /**
     * [pageRange] is the same `1-5,8` spec the backend route takes. It is applied here because the
     * server never sees this scan — before it was honoured, choosing pages 40-45 of a long PDF
     * quietly returned pages 1-20.
     */
    suspend fun scan(
        uri: Uri,
        config: CustomProviderConfig,
        pageRange: String? = null,
        extraction: ExtractionJob? = null
    ): CustomScanResult {
        val images = when (val pages = renderPages(uri, pageRange)) {
            is PageResult.Unsupported -> return CustomScanResult.Unsupported(pages.message)
            is PageResult.Rendered -> pages.images
        }
        if (images.isEmpty()) return CustomScanResult.Unsupported("This file has no readable pages.")

        return if (extraction == null) {
            complete(config, SYSTEM_INSTRUCTION, USER_PROMPT, images)
        } else {
            CustomExtraction.asResult(
                complete(
                    config,
                    EXTRACTION_INSTRUCTION,
                    CustomExtraction.instructionFor(extraction.prompt, extraction.schema),
                    images
                ),
                extraction.label
            )
        }
    }

    suspend fun translate(
        text: String,
        targetLanguage: String,
        config: CustomProviderConfig
    ): CustomScanResult = complete(
        config,
        translationInstruction(targetLanguage),
        text,
        emptyList()
    )

    /**
     * Asks the endpoint for the Typst behind a design. Both prompts come from the Worker rather
     * than from here — see `designPrompts` in `worker/src/gemini.ts` for why.
     */
    suspend fun writeTypst(
        systemInstruction: String,
        userTurn: String,
        config: CustomProviderConfig
    ): CustomScanResult = complete(config, systemInstruction, userTurn, emptyList())

    private suspend fun complete(
        config: CustomProviderConfig,
        systemInstruction: String,
        prompt: String,
        images: List<String>
    ): CustomScanResult {
        val request = ProviderRequest.completion(
            protocol = config.protocol,
            model = config.model,
            systemInstruction = systemInstruction,
            prompt = prompt,
            imagesBase64 = images
        )

        val response = try {
            api.post(config.url, config.headers(), request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CustomScanResult.Failure(
                ConnectionDiagnosis.fromException(e, EndpointUrl.hostOf(config.url))
            )
        }

        val body = if (response.isSuccessful) {
            response.body()?.string()
        } else {
            val failure = ConnectionDiagnosis.fromHttpStatus(
                response.code(),
                response.errorBody()?.string(),
                config.model
            )
            return CustomScanResult.Failure(failure)
        }

        ConnectionDiagnosis.fromSuccessBody(body, config.model, config.protocol)?.let {
            return CustomScanResult.Failure(it)
        }

        val markdown = extractContent(body, config.protocol).orEmpty().trim()
        return if (markdown.isEmpty()) {
            CustomScanResult.Failure(ConnectionFailure.UnexpectedSchema)
        } else {
            CustomScanResult.Success(markdown)
        }
    }

    private fun extractContent(body: String?, protocol: ProviderProtocol): String? {
        if (body.isNullOrBlank()) return null
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        return ProviderResponse.extractText(protocol, root)
    }

    private sealed interface PageResult {
        data class Rendered(val images: List<String>) : PageResult
        data class Unsupported(val message: String) : PageResult
    }

    private suspend fun renderPages(uri: Uri, pageRange: String?): PageResult {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

        return when {
            mimeType.startsWith("image/") -> renderImage(uri)
            mimeType == PDF_MIME_TYPE -> renderPdf(uri, pageRange)
            else -> PageResult.Unsupported(
                "Your own endpoint can scan images and PDFs. Office documents still need Scanly " +
                    "AI — turn the custom endpoint off in Settings to scan this file."
            )
        }
    }

    private fun renderImage(uri: Uri): PageResult {
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return PageResult.Unsupported("Couldn't read that image.")
        return PageResult.Rendered(listOf(encode(bitmap).also { bitmap.recycle() }))
    }

    private suspend fun renderPdf(uri: Uri, pageRange: String?): PageResult {
        val pageCount = pdfRenderer.getPageCount(uri)
        if (pageCount <= 0) return PageResult.Unsupported("Couldn't read that PDF.")

        return when (val selected = pagesToSend(pageRange, pageCount)) {
            is Selected.Refused -> PageResult.Unsupported(selected.message)
            is Selected.Indices -> PageResult.Rendered(encodePages(uri, selected.values))
        }
    }

    private sealed interface Selected {
        data class Indices(val values: Set<Int>) : Selected
        data class Refused(val message: String) : Selected
    }

    private fun pagesToSend(pageRange: String?, pageCount: Int): Selected =
        when (val selection = PageSelection.parse(pageRange, pageCount)) {
            is PageSelection.Result.Malformed ->
                Selected.Refused("Page range must look like 1-5 or 2,4,7.")

            is PageSelection.Result.OutOfRange -> Selected.Refused(
                "This PDF has ${selection.pageCount} " +
                    "${if (selection.pageCount == 1) "page" else "pages"}, so that range is empty."
            )

            // Refused rather than truncated. One request carries every page, so a longer document
            // silently became its first twenty — a wrong answer that looks like a right one.
            is PageSelection.Result.Pages -> if (selection.indices.size > MAX_PAGES) {
                Selected.Refused(
                    "Your own endpoint takes up to $MAX_PAGES pages at a time, and this is " +
                        "${selection.indices.size}. Pick a page range, or turn the endpoint off " +
                        "in Settings to use Scanly AI."
                )
            } else {
                Selected.Indices(selection.indices.toSet())
            }
        }

    private suspend fun encodePages(uri: Uri, pages: Set<Int>): List<String> {
        val images = mutableListOf<String>()
        pdfRenderer.forEachPage(uri, pages) { _, bitmap ->
            images.add(encode(bitmap))
            bitmap.recycle()
        }
        return images
    }

    private fun encode(bitmap: Bitmap): String {
        val scaled = downscale(bitmap)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= MAX_IMAGE_DIMENSION) return bitmap
        val ratio = MAX_IMAGE_DIMENSION.toFloat() / maxDim
        return bitmap.scale(
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1)
        )
    }

    internal companion object {
        const val PDF_MIME_TYPE = "application/pdf"
        const val MAX_IMAGE_DIMENSION = 1536
        const val JPEG_QUALITY = 85

        /** One request carries every page, so this bounds both the payload and the user's bill. */
        const val MAX_PAGES = 20

        const val SYSTEM_INSTRUCTION =
            "You are an OCR engine. Transcribe the document images into GitHub-flavoured " +
                "Markdown. Preserve headings, lists, and tables. Output only the transcription, " +
                "with no commentary, preamble, or code fences around the whole document."

        const val USER_PROMPT = "Transcribe this document."

        /**
         * Structured output is asked for in the prompt rather than through each protocol's own
         * switch — OpenAI's `response_format`, Gemini's `responseMimeType`. Those are not uniformly
         * implemented by the gateways this setting exists to support, and one that rejects an
         * unknown field answers 400, which fails the scan outright. A fenced or chatty reply, which
         * is the worst a prompt-driven ask produces, is something `CustomExtraction` recovers from.
         */
        const val EXTRACTION_INSTRUCTION =
            "You read documents and return structured data. You reply with one JSON object and " +
                "nothing else: no explanation, no Markdown code fence. Every value comes from the " +
                "document — never guess, and use null for anything it does not show."

        fun translationInstruction(targetLanguage: String): String =
            "You are a translator. Translate the user's message into $targetLanguage. Keep all " +
                "Markdown structure exactly as it is — headings, lists, tables and their " +
                "separator rows, emphasis and code spans — translating only the text inside it. " +
                "Numbers, dates, currency amounts, URLs, email addresses and code stay as they " +
                "are. Output only the translation, with no preamble and no code fence around it."
    }
}
