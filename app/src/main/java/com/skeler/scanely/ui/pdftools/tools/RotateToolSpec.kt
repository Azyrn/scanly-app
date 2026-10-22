package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolEngine
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.ui.pdftools.OPTIONS_ROW_GAP
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

/** Quarter turns clockwise: 90, 180 or 270. */
data class RotateOptions(val degrees: Int)

/** Turns every page the same way; single pages are the editor's Organize mode. */
class RotateToolSpec @Inject constructor(private val engine: PdfToolEngine) : ToolSpec {
    override val tool = PdfTool.ROTATE
    override val input = ToolInput.PDF

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        var degrees by rememberSaveable { mutableIntStateOf(ROTATE_RIGHT) }
        OptionsBody(stringResource(R.string.pdf_tool_rotate_body))
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(OPTIONS_ROW_GAP)
        ) {
            ROTATE_CHOICES.forEach { (value, label) ->
                FilterChip(
                    selected = degrees == value,
                    onClick = { degrees = value },
                    label = { Text(stringResource(label)) }
                )
            }
        }
        PrimaryActionButton(
            text = stringResource(R.string.pdf_tool_rotate_action),
            enabled = state.documents.isNotEmpty(),
            onClick = { onRun(RotateOptions(degrees)) }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        engine.rotateAll(input.documents.first(), (options as RotateOptions).degrees, input.run)
}

private const val ROTATE_RIGHT = 90
private const val ROTATE_HALF = 180
private const val ROTATE_LEFT = 270

private val ROTATE_CHOICES = listOf(
    ROTATE_RIGHT to R.string.pdf_tool_rotate_right,
    ROTATE_HALF to R.string.pdf_tool_rotate_half,
    ROTATE_LEFT to R.string.pdf_tool_rotate_left
)

@Module
@InstallIn(SingletonComponent::class)
abstract class RotateToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.ROTATE)
    abstract fun bind(spec: RotateToolSpec): ToolSpec
}
