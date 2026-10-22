package com.skeler.scanely.ui.pdftools.tools

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.pdf2office.PdfToWordJob
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

/** Nothing to choose; the options object only marks the run as this tool's. */
data object PdfToWordOptions

/**
 * Nothing to choose, but not run immediately: the screen says what the conversion keeps and
 * what it simplifies before the user commits a Pro conversion to it.
 */
class PdfToWordToolSpec @Inject constructor(private val job: PdfToWordJob) : ToolSpec {
    override val tool = PdfTool.PDF_TO_WORD
    override val input = ToolInput.PDF

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        OfficeConvertOptions(
            body = R.string.pdf2office_word_body,
            action = R.string.pdf2office_word_action,
            state = state,
            onConvert = { onRun(PdfToWordOptions) }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult = job.convert(input)
}

/** The shared options screen of the three PDF → Office converters: what to expect, then Convert. */
@Composable
internal fun OfficeConvertOptions(
    @StringRes body: Int,
    @StringRes action: Int,
    state: ToolOptionsState,
    onConvert: () -> Unit
) {
    OptionsBody(stringResource(body))
    PrimaryActionButton(
        text = stringResource(action),
        enabled = state.documents.isNotEmpty(),
        onClick = onConvert
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PdfToWordToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.PDF_TO_WORD)
    abstract fun bind(spec: PdfToWordToolSpec): ToolSpec
}
