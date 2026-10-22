package com.skeler.scanely.core.pdftools.repair

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.Qpdf
import com.skeler.scanely.core.pdfeditor.engine.QpdfResult
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.ToolOutput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.isOutOfSpace
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.PdfWriteCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "RepairJob"

/**
 * Rebuilds a PDF that other tools refuse. The file never went through the shared probe — the probe
 * is exactly what rejects it — so the damaged copy arrives in [ToolJobInput.files]; an already
 * opened [ToolJobInput.documents] entry is accepted too, for a file that opens but misbehaves.
 *
 * Three engines are tried in turn because each one recovers different damage: qpdf rebuilds a
 * broken or missing cross-reference table by scanning for objects; pdfium tolerates malformed
 * page trees and content that qpdf gives up on; PDFBox's lenient parser brute-forces object
 * offsets when both fail. A copy only counts once pdfium — the engine every other tool renders
 * with — opens it and finds pages, so a "repaired" file is never one the next tool rejects.
 */
@Singleton
class RepairJob @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageReader: PdfPageReader,
    private val workspace: ToolWorkspace
) {

    private val pdfium by lazy { PdfiumCore(context) }

    @Volatile
    private var pdfBoxReady = false

    private data class Source(val file: File, val displayName: String, val sizeBytes: Long, val password: String)

    suspend fun repair(input: ToolJobInput): ToolResult {
        val source = sourceOf(input)

        // Every attempt writes a whole new copy, and qpdf's temporaries can briefly double it.
        workspace.requireSpace(source.sizeBytes * 2)

        val base = source.displayName.substringBeforeLast('.').ifBlank { source.displayName }
        val fileName = ToolWorkspace.safeFileName(base) + SUFFIX_REPAIRED + PDF_EXTENSION
        val output = File(input.run, fileName)
        var delivered = false
        try {
            val pages = firstReadableCopy(source, output, input.onProgress)
            if (pages <= 0) throw ToolException(ToolFailure.Damaged)

            val toolOutput = ToolOutput(output, fileName, ScanExporter.PDF_MIME_TYPE, output.length())
            delivered = true
            return ToolResult(
                outputs = listOf(toolOutput),
                inputBytes = source.sizeBytes,
                notes = listOf(ToolNote(R.plurals.pdf_tool_repair_recovered, pages, plural = true))
            )
        } finally {
            if (!delivered) output.delete()
        }
    }

    /** An opened document, or a picked file that couldn't be opened (so no password). */
    private fun sourceOf(input: ToolJobInput): Source =
        input.documents.firstOrNull()
            ?.let { Source(it.file, it.displayName, it.sizeBytes, it.password) }
            ?: input.files.first().let { Source(it.file, it.displayName, it.sizeBytes, "") }

    /**
     * Tries each engine in turn until one writes a copy [output] that reads back with pages;
     * returns that page count, or 0 when none did.
     */
    private suspend fun firstReadableCopy(source: Source, output: File, onProgress: (Float?) -> Unit): Int {
        val attempts = listOf<suspend () -> Boolean>(
            { rewriteWithQpdf(source, output) },
            { saveWithPdfium(source, output) },
            { saveWithPdfBox(source, output) }
        )
        var pages = 0
        for ((index, attempt) in attempts.withIndex()) {
            coroutineContext.ensureActive()
            output.delete()
            if (attempt()) pages = pageReader.pageCount(output, source.password) ?: 0
            coroutineContext.ensureActive()
            onProgress((index + 1f) / attempts.size)
            if (pages > 0) break
            Log.w(TAG, "attempt ${index + 1} of ${attempts.size} produced no readable copy")
        }
        return pages
    }

    /**
     * A plain qpdf rewrite: qpdf reconstructs a broken xref on its own while reading, and its
     * "succeeded with warnings" exit already counts as Ok in the JNI layer. Only a wrong password
     * and a full disk stop the chain — no other engine can do better with either.
     */
    private suspend fun rewriteWithQpdf(source: Source, output: File): Boolean {
        val args = buildList {
            if (source.password.isNotEmpty()) add("--password=${source.password}")
            add(source.file.absolutePath)
            add(output.absolutePath)
        }
        val result = withContext(Dispatchers.IO) { Qpdf.run(*args.toTypedArray()) }
        return when (result) {
            QpdfResult.Ok -> output.isFile && output.length() > 0
            QpdfResult.WrongPassword -> throw ToolException(ToolFailure.WrongPassword)
            is QpdfResult.Failed -> {
                // qpdf messages name the file and failing object, never contents or passwords.
                Log.w(TAG, "qpdf rewrite failed: ${result.message}")
                if (IOException(result.message).isOutOfSpace()) throw ToolException(ToolFailure.LowStorage)
                false
            }
            is QpdfResult.BadUsage -> {
                Log.w(TAG, "qpdf rewrite rejected its arguments: ${result.message}")
                false
            }
        }
    }

    /** pdfium writes the objects it managed to load; FPDF_NO_INCREMENTAL (the default) drops the old xref. */
    private suspend fun saveWithPdfium(source: Source, output: File): Boolean = withContext(Dispatchers.IO) {
        try {
            ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val document = if (source.password.isEmpty()) {
                    pdfium.newDocument(descriptor)
                } else {
                    pdfium.newDocument(descriptor, source.password)
                }
                document.use { pdf ->
                    output.outputStream().buffered().use { sink ->
                        var writeFailure: IOException? = null
                        val saved = pdf.saveAsCopy(
                            object : PdfWriteCallback {
                                override fun WriteBlock(data: ByteArray?): Int {
                                    if (data == null || writeFailure != null) return 0
                                    return try {
                                        sink.write(data)
                                        1
                                    } catch (e: IOException) {
                                        writeFailure = e
                                        0
                                    }
                                }
                            }
                        )
                        writeFailure?.let { throw it }
                        saved
                    }
                }
            } && output.length() > 0
        } catch (e: IOException) {
            Log.w(TAG, "pdfium save failed", e)
            if (e.isOutOfSpace()) throw ToolException(ToolFailure.LowStorage, e)
            false
        }
    }

    /**
     * Last resort. Skipped for a password-protected file: PDFBox would have to re-encrypt on save,
     * and a copy that quietly lost or changed its protection is worse than an honest failure.
     * Temp-file buffering keeps a large damaged file from loading wholly into the heap.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun saveWithPdfBox(source: Source, output: File): Boolean = withContext(Dispatchers.IO) {
        if (source.password.isNotEmpty()) return@withContext false
        ensurePdfBox()
        try {
            PDDocument.load(source.file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                if (document.isEncrypted || document.numberOfPages <= 0) return@withContext false
                document.save(output)
            }
            output.length() > 0
        } catch (e: IOException) {
            Log.w(TAG, "PDFBox rebuild failed", e)
            if (e.isOutOfSpace()) throw ToolException(ToolFailure.LowStorage, e)
            false
        } catch (e: RuntimeException) {
            // PDFBox's parser throws unchecked exceptions on some malformed objects.
            Log.w(TAG, "PDFBox rebuild failed", e)
            false
        }
    }

    private fun ensurePdfBox() {
        if (pdfBoxReady) return
        synchronized(this) {
            if (pdfBoxReady) return
            PDFBoxResourceLoader.init(context)
            pdfBoxReady = true
        }
    }

    private companion object {
        const val SUFFIX_REPAIRED = "_repaired"
        const val PDF_EXTENSION = ".pdf"
    }
}
