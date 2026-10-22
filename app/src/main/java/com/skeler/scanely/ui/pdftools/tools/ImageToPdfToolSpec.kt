package com.skeler.scanely.ui.pdftools.tools

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.image.ImagePageMargin
import com.skeler.scanely.core.pdftools.image.ImagePageOrientation
import com.skeler.scanely.core.pdftools.image.ImagePageSize
import com.skeler.scanely.core.pdftools.image.ImageToPdfJob
import com.skeler.scanely.core.pdftools.image.ImageToPdfSettings
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

/** The options object this spec hands its job; the settings themselves live with the job. */
data class ImageToPdfOptions(val settings: ImageToPdfSettings)

class ImageToPdfToolSpec @Inject constructor(private val job: ImageToPdfJob) : ToolSpec {
    override val tool = PdfTool.IMAGE_TO_PDF

    override val input = ToolInput.IMAGES

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (Any) -> Unit) {
        var pageSize by rememberSaveable { mutableStateOf(ImagePageSize.A4) }
        var orientation by rememberSaveable { mutableStateOf(ImagePageOrientation.AUTO) }
        var margin by rememberSaveable { mutableStateOf(ImagePageMargin.NONE) }

        Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
            Text(
                text = stringResource(R.string.pdf_tool_img2pdf_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ChipGroup(R.string.pdf_tool_img2pdf_page_size, SIZE_CHOICES, pageSize) { pageSize = it }
            // A fitted page takes the image's own shape, so there is no orientation to pick.
            if (pageSize != ImagePageSize.FIT) {
                ChipGroup(R.string.pdf_tool_img2pdf_orientation, ORIENTATION_CHOICES, orientation) {
                    orientation = it
                }
            }
            ChipGroup(R.string.pdf_tool_img2pdf_margin, MARGIN_CHOICES, margin) { margin = it }
            Button(
                onClick = { onRun(ImageToPdfOptions(ImageToPdfSettings(pageSize, orientation, margin))) },
                enabled = state.files.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.pdf_tool_img2pdf_action))
            }
        }
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        job.run(input.files, (options as ImageToPdfOptions).settings, input.run, input.onProgress)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ImageToPdfToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.IMAGE_TO_PDF)
    abstract fun bind(spec: ImageToPdfToolSpec): ToolSpec
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(
    @StringRes title: Int,
    choices: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        Text(text = stringResource(title), style = MaterialTheme.typography.titleSmall)
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
        ) {
            choices.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(stringResource(label)) }
                )
            }
        }
    }
}

private val SIZE_CHOICES = listOf(
    ImagePageSize.FIT to R.string.pdf_tool_img2pdf_size_fit,
    ImagePageSize.A4 to R.string.pdf_tool_img2pdf_size_a4,
    ImagePageSize.LETTER to R.string.pdf_tool_img2pdf_size_letter
)

private val ORIENTATION_CHOICES = listOf(
    ImagePageOrientation.AUTO to R.string.pdf_tool_img2pdf_orientation_auto,
    ImagePageOrientation.PORTRAIT to R.string.pdf_tool_img2pdf_orientation_portrait,
    ImagePageOrientation.LANDSCAPE to R.string.pdf_tool_img2pdf_orientation_landscape
)

private val MARGIN_CHOICES = listOf(
    ImagePageMargin.NONE to R.string.pdf_tool_img2pdf_margin_none,
    ImagePageMargin.SMALL to R.string.pdf_tool_img2pdf_margin_small,
    ImagePageMargin.BIG to R.string.pdf_tool_img2pdf_margin_big
)

private val SECTION_GAP = 16.dp
private val ROW_GAP = 8.dp
