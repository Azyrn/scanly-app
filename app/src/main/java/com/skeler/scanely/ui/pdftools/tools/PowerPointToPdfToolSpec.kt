package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.office2pdf.pptx.PptxToPdfJob
import com.skeler.scanely.ui.pdftools.PrimaryActionButton
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

/**
 * Nothing to choose: a deck converts at its own slide size, one page per visible slide. The
 * options screen only appears after a cancel, holding the button to run again.
 */
class PowerPointToPdfToolSpec @Inject constructor(private val job: PptxToPdfJob) : ToolSpec {
    override val tool = PdfTool.POWERPOINT_TO_PDF
    override val input = ToolInput.POWERPOINT
    override val runsImmediately = true

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        PrimaryActionButton(
            text = stringResource(R.string.pdf_tool_ppt2pdf_action),
            enabled = state.files.isNotEmpty(),
            onClick = { onRun(Unit) }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        job.convert(input.files.first(), input.run, input.onProgress)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PowerPointToPdfToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.POWERPOINT_TO_PDF)
    abstract fun bind(spec: PowerPointToPdfToolSpec): ToolSpec
}
