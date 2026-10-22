package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.office2pdf.docx.DocxToPdfJob
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

/** Word to PDF has nothing to choose. */
data object WordToPdfOptions

/**
 * Runs as soon as the .docx is in; the options screen only appears after a cancel, holding the
 * button to run again and a line on what can't come through yet.
 */
class WordToPdfToolSpec @Inject constructor(private val job: DocxToPdfJob) : ToolSpec {
    override val tool = PdfTool.WORD_TO_PDF
    override val input = ToolInput.WORD
    override val runsImmediately = true

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        OptionsBody(stringResource(R.string.pdf_tool_word_to_pdf_body))
        PrimaryActionButton(
            text = stringResource(R.string.pdf_tool_word_to_pdf_action),
            enabled = state.files.isNotEmpty(),
            onClick = { onRun(WordToPdfOptions) }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult = job.convert(input)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WordToPdfToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.WORD_TO_PDF)
    abstract fun bind(spec: WordToPdfToolSpec): ToolSpec
}
