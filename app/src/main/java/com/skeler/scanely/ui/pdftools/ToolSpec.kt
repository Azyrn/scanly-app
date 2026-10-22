package com.skeler.scanely.ui.pdftools

import androidx.compose.runtime.Composable
import com.skeler.scanely.core.pdftools.ImportedFile
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult

/**
 * What the options screen knows about the picked inputs, and what it may do with them.
 *
 * Multi-file tools let the user add, drop and reorder inputs here; single-file tools ignore those
 * callbacks. [documents] holds opened PDFs, [files] every other input, each in the user's order.
 */
data class ToolOptionsState(
    val documents: List<ToolDocument>,
    val files: List<ImportedFile>,
    val onAddFiles: () -> Unit,
    val onRemove: (index: Int) -> Unit,
    val onMove: (from: Int, to: Int) -> Unit
)

/**
 * One one-shot tool: what it picks, its options screen, and its job.
 *
 * Each tool is its own `ToolSpec` class in `ui/pdftools/tools/`, bound into a Hilt map with
 * `@Binds @IntoMap @PdfToolKey(PdfTool.X)` in a module next to it. The grid shows a tool only once
 * it is bound, and the shared flow ([PdfToolScreen]) does the picking, password prompts, progress,
 * Cancel, errors and the Save/Share/Open result for every tool — a spec only describes its own part.
 */
interface ToolSpec {
    val tool: PdfTool

    val input: ToolInput

    /** Inputs needed before [Options] may run the job; Merge needs two. */
    val minInputs: Int get() = 1

    /** A tool with nothing to choose runs as soon as its inputs are open; [Options] is never shown. */
    val runsImmediately: Boolean get() = false

    /** Repair takes PDFs that may not open; the flow then imports them as plain files. */
    val probesInputs: Boolean get() = true

    /**
     * The options screen below the shared file summary. Calls [onRun] with this tool's options
     * object, which reaches [run] unchanged. Strings come from resources; no state is kept here
     * beyond what `remember` holds for the controls.
     */
    @Composable
    fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit)

    /**
     * The job. Throws [com.skeler.scanely.core.pdftools.ToolException] with the failure the user
     * can act on; must respect cancellation and leave nothing behind in [ToolJobInput.run] when it
     * fails or is cancelled.
     */
    suspend fun run(input: ToolJobInput, options: Any): ToolResult
}
