package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.organize.MergeJob
import com.skeler.scanely.ui.pdftools.OptionsBody
import com.skeler.scanely.ui.pdftools.PrimaryActionButton
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

/** Merge has nothing to choose beyond the file order, which the shared flow's list already holds. */
data object MergeOptions

/** Joins two or more PDFs, in the order arranged on the options screen, into one. */
class MergeToolSpec @Inject constructor(private val job: MergeJob) : ToolSpec {
    override val tool = PdfTool.MERGE
    override val input = ToolInput.PDFS
    override val minInputs = 2

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        val count = state.documents.size
        val pages = state.documents.sumOf { it.pageCount }
        Text(
            text = stringResource(
                R.string.pdf_tool_merge_summary,
                pluralStringResource(R.plurals.pdf_tool_merge_files, count, count),
                pluralStringResource(R.plurals.pdf_tool_pages, pages, pages)
            ),
            style = MaterialTheme.typography.titleSmall
        )
        OptionsBody(stringResource(R.string.pdf_tool_merge_body))
        if (count < minInputs) {
            Text(
                text = stringResource(R.string.pdf_tool_merge_need_more),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        PrimaryActionButton(
            text = stringResource(R.string.pdf_tool_merge_action),
            enabled = count >= minInputs,
            onClick = { onRun(MergeOptions) }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        job.merge(input.documents, input.run, input.onProgress)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MergeToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.MERGE)
    abstract fun bind(spec: MergeToolSpec): ToolSpec
}
