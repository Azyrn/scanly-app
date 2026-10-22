package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.runtime.Composable
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.pdf2office.PdfToPowerPointJob
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

/** Nothing to choose; the options object only marks the run as this tool's. */
data object PdfToPowerPointOptions

/** Shown before running for the same reason as [PdfToWordToolSpec]: the user sees what to expect. */
class PdfToPowerPointToolSpec @Inject constructor(private val job: PdfToPowerPointJob) : ToolSpec {
    override val tool = PdfTool.PDF_TO_POWERPOINT
    override val input = ToolInput.PDF

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        OfficeConvertOptions(
            body = R.string.pdf2office_powerpoint_body,
            action = R.string.pdf2office_powerpoint_action,
            state = state,
            onConvert = { onRun(PdfToPowerPointOptions) }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult = job.convert(input)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PdfToPowerPointToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.PDF_TO_POWERPOINT)
    abstract fun bind(spec: PdfToPowerPointToolSpec): ToolSpec
}
