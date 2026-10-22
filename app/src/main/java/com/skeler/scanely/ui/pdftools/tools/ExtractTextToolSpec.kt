package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.skeler.scanely.core.pdftools.text.ExtractTextJob
import com.skeler.scanely.ui.pdftools.PrimaryActionButton
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

data class ExtractTextOptions(val pageSeparators: Boolean)

/**
 * One .txt for the whole document. Page markers default on: without them a reader can't tell
 * which page a passage came from, and they are one line to delete when unwanted.
 */
class ExtractTextToolSpec @Inject constructor(private val job: ExtractTextJob) : ToolSpec {
    override val tool = PdfTool.EXTRACT_TEXT
    override val input = ToolInput.PDF

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        var separators by rememberSaveable { mutableStateOf(true) }
        SwitchRow(
            title = stringResource(R.string.pdf_tool_extract_text_separators),
            body = stringResource(R.string.pdf_tool_extract_text_separators_body),
            checked = separators,
            onCheckedChange = { separators = it }
        )
        PrimaryActionButton(
            text = stringResource(R.string.pdf_tool_extract_text_action),
            enabled = state.documents.isNotEmpty(),
            onClick = { onRun(ExtractTextOptions(separators)) }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult {
        val separators = (options as ExtractTextOptions).pageSeparators
        return job.extract(input.documents.first(), separators, input.run, input.onProgress)
    }
}

/** The whole row toggles, matching the shared selectable rows' card look. */
@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = ROW_SHAPE,
        color = colors.surfaceContainerLow,
        border = BorderStroke(ROW_BORDER, colors.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ROW_PADDING_H, vertical = ROW_PADDING_V),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = ROW_PADDING_H)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ExtractTextToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.EXTRACT_TEXT)
    abstract fun bind(spec: ExtractTextToolSpec): ToolSpec
}

private val ROW_SHAPE = RoundedCornerShape(16.dp)
private val ROW_BORDER = 1.dp
private val ROW_PADDING_H = 12.dp
private val ROW_PADDING_V = 12.dp
