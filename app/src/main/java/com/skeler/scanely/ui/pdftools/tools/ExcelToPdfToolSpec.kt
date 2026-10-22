package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XlsxToPdfJob
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

/** Excel to PDF has nothing to choose: page size, orientation and scaling come from each sheet. */
data object ExcelToPdfOptions

/**
 * Runs as soon as the workbook is picked. The sheet's own Page Setup (orientation, fit to page)
 * is honoured, and a wide sheet without one turns landscape, so there is nothing to ask up front.
 */
class ExcelToPdfToolSpec @Inject constructor(private val job: XlsxToPdfJob) : ToolSpec {
    override val tool: PdfTool = PdfTool.EXCEL_TO_PDF

    override val input: ToolInput = ToolInput.EXCEL

    override val runsImmediately: Boolean = true

    /** Only shown if the flow ever asks; one button, the same run. */
    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OptionsBody(stringResource(R.string.excel2pdf_options_body))
            PrimaryActionButton(
                text = stringResource(R.string.excel2pdf_convert),
                enabled = state.files.isNotEmpty(),
                onClick = { onRun(ExcelToPdfOptions) }
            )
        }
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        job.convert(
            input.files.firstOrNull() ?: throw ToolException(ToolFailure.Unreadable),
            input.run,
            input.onProgress
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ExcelToPdfToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.EXCEL_TO_PDF)
    abstract fun bind(spec: ExcelToPdfToolSpec): ToolSpec
}
