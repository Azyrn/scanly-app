package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.imagetools.ConvertFromJpgJob
import com.skeler.scanely.core.pdftools.imagetools.ImageTarget
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

data class ConvertFromJpgOptions(val target: ImageTarget)

class ConvertFromJpgToolSpec @Inject constructor(private val job: ConvertFromJpgJob) : ToolSpec {
    override val tool = PdfTool.CONVERT_FROM_JPG
    override val input = ToolInput.IMAGES

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (Any) -> Unit) {
        var target by rememberSaveable { mutableStateOf(ImageTarget.PNG) }
        Column(verticalArrangement = Arrangement.spacedBy(IMAGE_SECTION_GAP)) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(IMAGE_SECTION_GAP)) {
                TARGET_CHOICES.forEach { (value, title, body) ->
                    ImageChoiceRow(
                        title = stringResource(title),
                        body = stringResource(body),
                        selected = target == value,
                        onSelect = { target = value }
                    )
                }
            }
            ImageActionButton(stringResource(R.string.pdf_tool_img_from_jpg_action)) {
                onRun(ConvertFromJpgOptions(target))
            }
        }
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        job.convert(input, (options as ConvertFromJpgOptions).target)

    private companion object {
        val TARGET_CHOICES = listOf(
            Triple(ImageTarget.PNG, R.string.pdf_tool_img_from_jpg_png, R.string.pdf_tool_img_from_jpg_png_body),
            Triple(ImageTarget.WEBP, R.string.pdf_tool_img_from_jpg_webp, R.string.pdf_tool_img_from_jpg_webp_body),
            Triple(ImageTarget.GIF, R.string.pdf_tool_img_from_jpg_gif, R.string.pdf_tool_img_from_jpg_gif_body)
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ConvertFromJpgToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.CONVERT_FROM_JPG)
    abstract fun bind(spec: ConvertFromJpgToolSpec): ToolSpec
}
