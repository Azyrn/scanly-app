package com.skeler.scanely.core.pdftools.organize

import com.skeler.scanely.core.pdfeditor.engine.Qpdf
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val SUFFIX_MERGED = "_merged"

/** qpdf page-range syntax for every page. */
private const val ALL_PAGES = "1-z"

/**
 * Joins PDFs, in the order the user arranged them, into one new document.
 *
 * Built on qpdf's `--empty --pages` rather than QpdfEngine.merge because each input may have its
 * own password: the `--file=` form of page selection takes a `--password=` per file, so a locked
 * input is read with the password the user already typed for it instead of failing the whole
 * merge. Starting from an empty document means no single input's encryption or metadata is
 * carried over to the combined file.
 */
@Singleton
class MergeJob @Inject constructor(private val workspace: ToolWorkspace) {

    suspend fun merge(
        documents: List<ToolDocument>,
        run: File,
        onProgress: (Float?) -> Unit
    ): ToolResult {
        require(documents.size >= 2) { "merge needs at least two documents" }
        val inputBytes = documents.sumOf { it.sizeBytes }
        workspace.requireSpace((inputBytes * SPACE_FACTOR).toLong())

        val output = File(run, baseName(documents.first().displayName) + SUFFIX_MERGED + PDF_EXTENSION)
        var delivered = false
        try {
            // One native call that can't report progress or be interrupted, so the bar is
            // indeterminate until it returns.
            onProgress(null)
            val result = withContext(Dispatchers.IO) { Qpdf.run(*mergeArgs(documents, output)) }
            coroutineContext.ensureActive()
            result.requireOk("merge")
            val toolOutput = output.toPdfOutput("merge")
            onProgress(1f)
            delivered = true
            return ToolResult(listOf(toolOutput), inputBytes)
        } finally {
            if (!delivered) output.delete()
        }
    }

    private fun mergeArgs(documents: List<ToolDocument>, output: File): Array<String> {
        val args = mutableListOf("--empty", "--pages")
        documents.forEach { document ->
            args += "--file=${document.file.absolutePath}"
            // An owner-only file opens without one; passing an empty password is harmless but noise.
            if (document.password.isNotEmpty()) args += "--password=${document.password}"
            args += "--range=$ALL_PAGES"
        }
        args += "--"
        args += output.absolutePath
        return args.toTypedArray()
    }
}
