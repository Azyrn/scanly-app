package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.image.JpgQuality
import com.skeler.scanely.core.pdftools.image.PdfToJpgJob
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

enum class PdfToJpgMode { PAGES, EXTRACT }

data class PdfToJpgOptions(val mode: PdfToJpgMode, val quality: JpgQuality)

class PdfToJpgToolSpec @Inject constructor(private val job: PdfToJpgJob) : ToolSpec {
    override val tool = PdfTool.PDF_TO_JPG

    override val input = ToolInput.PDF

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (Any) -> Unit) {
        var mode by rememberSaveable { mutableStateOf(PdfToJpgMode.PAGES) }
        var quality by rememberSaveable { mutableStateOf(JpgQuality.NORMAL) }

        Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP)
            ) {
                ChoiceRow(
                    title = stringResource(R.string.pdf_tool_jpg_mode_pages),
                    body = stringResource(R.string.pdf_tool_jpg_mode_pages_body),
                    selected = mode == PdfToJpgMode.PAGES,
                    onSelect = { mode = PdfToJpgMode.PAGES }
                )
                ChoiceRow(
                    title = stringResource(R.string.pdf_tool_jpg_mode_extract),
                    body = stringResource(R.string.pdf_tool_jpg_mode_extract_body),
                    selected = mode == PdfToJpgMode.EXTRACT,
                    onSelect = { mode = PdfToJpgMode.EXTRACT }
                )
            }

            // Extracted images keep their own resolution; quality only means something for renders.
            if (mode == PdfToJpgMode.PAGES) {
                Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                    Text(
                        text = stringResource(R.string.pdf_tool_jpg_quality),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
                    ) {
                        QUALITY_CHOICES.forEach { (value, label) ->
                            FilterChip(
                                selected = quality == value,
                                onClick = { quality = value },
                                label = { Text(stringResource(label)) }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { onRun(PdfToJpgOptions(mode, quality)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (mode == PdfToJpgMode.PAGES) {
                            R.string.pdf_tool_jpg_action_pages
                        } else {
                            R.string.pdf_tool_jpg_action_extract
                        }
                    )
                )
            }
        }
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult {
        val chosen = options as PdfToJpgOptions
        val document = input.documents.single()
        return when (chosen.mode) {
            PdfToJpgMode.PAGES -> job.pagesToJpg(document, chosen.quality, input.run, input.onProgress)
            PdfToJpgMode.EXTRACT -> job.extractImages(document, input.run, input.onProgress)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PdfToJpgToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.PDF_TO_JPG)
    abstract fun bind(spec: PdfToJpgToolSpec): ToolSpec
}

/** A radio row with a title and an explanation, matching the Compress level rows. */
@Composable
private fun ChoiceRow(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CARD_SHAPE,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        border = BorderStroke(BORDER_WIDTH, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ROW_PADDING_H, vertical = ROW_PADDING_V),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = ROW_PADDING_H)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

private val QUALITY_CHOICES = listOf(
    JpgQuality.NORMAL to R.string.pdf_tool_jpg_quality_normal,
    JpgQuality.HIGH to R.string.pdf_tool_jpg_quality_high
)

private val CARD_SHAPE = RoundedCornerShape(16.dp)
private val SECTION_GAP = 16.dp
private val ROW_GAP = 8.dp
private val ROW_PADDING_H = 12.dp
private val ROW_PADDING_V = 12.dp
private val BORDER_WIDTH = 1.dp
