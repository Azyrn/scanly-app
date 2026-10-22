package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolEngine
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
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
 * Nothing to choose: the password was already typed to open the file, so the run starts as soon as
 * it is open. The options screen only appears after a cancel, holding the button to run again.
 */
class UnlockToolSpec @Inject constructor(private val engine: PdfToolEngine) : ToolSpec {
    override val tool = PdfTool.UNLOCK
    override val input = ToolInput.PDF
    override val runsImmediately = true

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        PrimaryActionButton(
            text = stringResource(R.string.pdf_tool_unlock),
            enabled = state.documents.isNotEmpty(),
            onClick = { onRun(Unit) }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        engine.unlock(input.documents.first(), input.run)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UnlockToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.UNLOCK)
    abstract fun bind(spec: UnlockToolSpec): ToolSpec
}
