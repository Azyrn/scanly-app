package com.skeler.scanely.ui.pdftools

import android.os.SystemClock
import android.util.Log
import com.skeler.scanely.core.ai.ConversionCharges
import com.skeler.scanely.core.pdftools.ImportedFile
import com.skeler.scanely.core.pdftools.OutputValidator
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.reports.FailureReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TAG = "ToolRunGuard"

/** Conversions that draw pages: a result blank on every page is a broken one. */
private val DRAWN_CONVERSIONS = setOf(PdfTool.WORD_TO_PDF, PdfTool.POWERPOINT_TO_PDF, PdfTool.EXCEL_TO_PDF)

private const val TIMEOUT_BASE_MS = 3 * 60_000L
private const val TIMEOUT_PER_PAGE_MS = 3_000L
private const val TIMEOUT_PER_MB_MS = 10_000L
private const val TIMEOUT_MAX_MS = 30 * 60_000L
private const val BYTES_PER_MB = 1024L * 1024L

/**
 * What every tool run gets around [ToolSpec.run]: a time limit, the output check (a broken file is
 * deleted, never offered), credits back for a cloud read a failed run paid for, and a failure
 * report (type, tool and timing only) for what the user can't fix themselves.
 */
class ToolRunGuard @Inject constructor(
    private val validator: OutputValidator,
    private val charges: ConversionCharges,
    private val reporter: FailureReporter
) {
    /** Called, from any thread, when a failed run's credits came back. */
    var onRefunded: () -> Unit = {}

    suspend fun run(
        tool: PdfTool,
        documents: List<ToolDocument>,
        files: List<ImportedFile>,
        block: suspend () -> ToolResult
    ): ToolResult {
        val name = tool.name.lowercase()
        val pages = documents.sumOf { it.pageCount }.takeIf { it > 0 }
        val started = SystemClock.elapsedRealtime()
        charges.clear()
        reporter.conversionStarted(name, pages)
        try {
            val result = checked(tool, withTimeoutOrNull(timeoutFor(documents, files)) { block() })
            charges.clear()
            return result
        } catch (e: CancellationException) {
            refundCharges()
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            report(name, e, SystemClock.elapsedRealtime() - started, pages)
            refundCharges()
            throw e
        } finally {
            reporter.conversionEnded()
        }
    }

    /** The result, if it finished in time and passes its check; its files are deleted if not. */
    private suspend fun checked(tool: PdfTool, result: ToolResult?): ToolResult {
        if (result == null) throw ToolException(ToolFailure.TimedOut)
        val problem = validator.problem(result, checkBlank = tool in DRAWN_CONVERSIONS) ?: return result
        Log.w(TAG, "$tool output failed its check: $problem")
        result.outputs.forEach { it.file.delete() }
        throw ToolException(ToolFailure.BrokenOutput(problem))
    }

    private fun report(tool: String, e: Exception, durationMs: Long, pages: Int?) {
        val failure = (e as? ToolException)?.failure
        if (failure != null && !failure.isReportable) return
        val error = when (failure) {
            null -> FailureReporter.rootType(e)
            is ToolFailure.Failed -> e.cause?.let(FailureReporter::rootType) ?: "EngineFailed"
            else -> failure.javaClass.simpleName
        }
        val where = if (failure == null) FailureReporter.appFrame(e) else null
        reporter.conversionFailed(tool, error, durationMs, pages, where)
    }

    /** Off the screen's scope: leaving the failed run must not cancel the refund. */
    private fun refundCharges() {
        charges.scope.launch { if (charges.refundAll() > 0) onRefunded() }
    }

    /** Generous: the limit is for a job that is stuck, never for a big file that is merely slow. */
    private fun timeoutFor(documents: List<ToolDocument>, files: List<ImportedFile>): Long {
        val pages = documents.sumOf { it.pageCount.toLong() }
        val megabytes = (documents.sumOf { it.sizeBytes } + files.sumOf { it.sizeBytes }) / BYTES_PER_MB
        return (TIMEOUT_BASE_MS + pages * TIMEOUT_PER_PAGE_MS + megabytes * TIMEOUT_PER_MB_MS)
            .coerceAtMost(TIMEOUT_MAX_MS)
    }
}

/** Failures the user can't fix and we should hear about; a wrong password or a full disk is not one. */
private val ToolFailure.isReportable: Boolean
    get() = this is ToolFailure.Failed || this is ToolFailure.BrokenOutput || this == ToolFailure.TimedOut
