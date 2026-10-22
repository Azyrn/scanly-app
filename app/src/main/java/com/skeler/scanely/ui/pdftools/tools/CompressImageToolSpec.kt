package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.skeler.scanely.core.pdftools.imagetools.CompressImageJob
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

data class CompressImageOptions(val quality: Int, val pngToJpg: Boolean)

class CompressImageToolSpec @Inject constructor(private val job: CompressImageJob) : ToolSpec {
    override val tool = PdfTool.COMPRESS_IMAGE
    override val input = ToolInput.IMAGES

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (Any) -> Unit) {
        var quality by rememberSaveable { mutableIntStateOf(CompressImageJob.QUALITY_RECOMMENDED) }
        var pngToJpg by rememberSaveable { mutableStateOf(false) }
        // Names are enough to decide whether the toggle is worth showing; the job sniffs the bytes.
        val hasLossless = state.files.any { file ->
            LOSSLESS_EXTENSIONS.any { file.displayName.endsWith(it, ignoreCase = true) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(IMAGE_SECTION_GAP)) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(IMAGE_SECTION_GAP)) {
                QUALITY_CHOICES.forEach { (value, title, body) ->
                    ImageChoiceRow(
                        title = stringResource(title),
                        body = stringResource(body),
                        selected = quality == value,
                        onSelect = { quality = value }
                    )
                }
            }
            if (hasLossless) {
                ImageSwitchRow(
                    title = stringResource(R.string.pdf_tool_img_png_to_jpg),
                    body = stringResource(R.string.pdf_tool_img_png_to_jpg_body),
                    checked = pngToJpg,
                    onCheckedChange = { pngToJpg = it }
                )
            }
            ImageActionButton(stringResource(R.string.pdf_tool_img_compress_action)) {
                onRun(CompressImageOptions(quality, pngToJpg && hasLossless))
            }
        }
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult {
        val chosen = options as CompressImageOptions
        return job.compress(input, chosen.quality, chosen.pngToJpg)
    }

    private companion object {
        val LOSSLESS_EXTENSIONS = listOf(".png", ".bmp")

        val QUALITY_CHOICES = listOf(
            Triple(
                CompressImageJob.QUALITY_RECOMMENDED,
                R.string.pdf_tool_img_compress_recommended,
                R.string.pdf_tool_img_compress_recommended_body
            ),
            Triple(
                CompressImageJob.QUALITY_STRONG,
                R.string.pdf_tool_img_compress_strong,
                R.string.pdf_tool_img_compress_strong_body
            ),
            Triple(
                CompressImageJob.QUALITY_LIGHT,
                R.string.pdf_tool_img_compress_light,
                R.string.pdf_tool_img_compress_light_body
            )
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CompressImageToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.COMPRESS_IMAGE)
    abstract fun bind(spec: CompressImageToolSpec): ToolSpec
}
