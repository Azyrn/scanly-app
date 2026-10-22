package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.imagetools.ConvertToJpgJob
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

/** Nothing to choose, but the options screen stays so the user can add or drop images first. */
data object ConvertToJpgOptions

class ConvertToJpgToolSpec @Inject constructor(private val job: ConvertToJpgJob) : ToolSpec {
    override val tool = PdfTool.CONVERT_TO_JPG
    override val input = ToolInput.IMAGES

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (Any) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(IMAGE_SECTION_GAP)) {
            ImageBodyText(stringResource(R.string.pdf_tool_img_to_jpg_body))
            ImageActionButton(stringResource(R.string.pdf_tool_img_to_jpg_action)) { onRun(ConvertToJpgOptions) }
        }
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult = job.convert(input)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ConvertToJpgToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.CONVERT_TO_JPG)
    abstract fun bind(spec: ConvertToJpgToolSpec): ToolSpec
}
