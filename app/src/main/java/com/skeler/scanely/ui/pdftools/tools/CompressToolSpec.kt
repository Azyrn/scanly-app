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
import com.skeler.scanely.core.pdfeditor.engine.CompressionLevel
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolEngine
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.ui.pdftools.OPTIONS_ROW_GAP
import com.skeler.scanely.ui.pdftools.PrimaryActionButton
import com.skeler.scanely.ui.pdftools.SelectableRow
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

data class CompressOptions(val level: CompressionLevel)

/** Compress is the one tool whose whole point is the size, so its result shows the saving. */
class CompressToolSpec @Inject constructor(private val engine: PdfToolEngine) : ToolSpec {
    override val tool = PdfTool.COMPRESS
    override val input = ToolInput.PDF

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        var level by rememberSaveable { mutableStateOf(CompressionLevel.BALANCED) }
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(OPTIONS_ROW_GAP)
        ) {
            COMPRESS_CHOICES.forEach { choice ->
                SelectableRow(
                    title = stringResource(choice.title),
                    body = stringResource(choice.body),
                    selected = level == choice.level,
                    onSelect = { level = choice.level }
                )
            }
        }
        PrimaryActionButton(
            text = stringResource(R.string.pdf_tool_compress_action),
            enabled = state.documents.isNotEmpty(),
            onClick = { onRun(CompressOptions(level)) }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult {
        val level = (options as CompressOptions).level
        return engine.compress(input.documents.first(), level, input.run).copy(showSizeChange = true)
    }
}

private data class CompressChoice(val level: CompressionLevel, val title: Int, val body: Int)

private val COMPRESS_CHOICES = listOf(
    CompressChoice(
        CompressionLevel.LOSSLESS,
        R.string.pdf_tool_compress_lossless,
        R.string.pdf_tool_compress_lossless_body
    ),
    CompressChoice(
        CompressionLevel.BALANCED,
        R.string.pdf_tool_compress_balanced,
        R.string.pdf_tool_compress_balanced_body
    ),
    CompressChoice(
        CompressionLevel.SMALLEST,
        R.string.pdf_tool_compress_smallest,
        R.string.pdf_tool_compress_smallest_body
    )
)

@Module
@InstallIn(SingletonComponent::class)
abstract class CompressToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.COMPRESS)
    abstract fun bind(spec: CompressToolSpec): ToolSpec
}
