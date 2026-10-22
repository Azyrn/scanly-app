package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.repair.RepairJob
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
 * Nothing to choose, so it runs as soon as the file is in. The input is a PDF the probe would
 * reject, which is why the flow hands it over unprobed in [ToolOptionsState.files]; the options
 * screen only appears after a cancel, holding the button to run again.
 */
class RepairToolSpec @Inject constructor(private val job: RepairJob) : ToolSpec {
    override val tool = PdfTool.REPAIR
    override val input = ToolInput.PDF
    override val runsImmediately = true

    /** The damaged file is the point: probing it first would reject exactly what this tool is for. */
    override val probesInputs = false

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        PrimaryActionButton(
            text = stringResource(R.string.pdf_tool_repair_action),
            enabled = state.files.isNotEmpty() || state.documents.isNotEmpty(),
            onClick = { onRun(Unit) }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult = job.repair(input)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepairToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.REPAIR)
    abstract fun bind(spec: RepairToolSpec): ToolSpec
}
