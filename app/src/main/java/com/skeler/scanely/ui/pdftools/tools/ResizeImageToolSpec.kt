package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.imagetools.ResizeImageJob
import com.skeler.scanely.core.pdftools.imagetools.ResizeMode
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

data class ResizeImageOptions(val mode: ResizeMode)

class ResizeImageToolSpec @Inject constructor(private val job: ResizeImageJob) : ToolSpec {
    override val tool = PdfTool.RESIZE_IMAGE
    override val input = ToolInput.IMAGES

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (Any) -> Unit) {
        var bySize by rememberSaveable { mutableStateOf(false) }
        var percent by rememberSaveable { mutableIntStateOf(DEFAULT_PERCENT) }
        var widthText by rememberSaveable { mutableStateOf("") }
        var heightText by rememberSaveable { mutableStateOf("") }
        var keepAspect by rememberSaveable { mutableStateOf(true) }
        var noEnlarge by rememberSaveable { mutableStateOf(true) }

        val width = parseSide(widthText)
        val height = parseSide(heightText)
        val widthInvalid = widthText.isNotEmpty() && width == null
        val heightInvalid = heightText.isNotEmpty() && height == null
        val sizeReady = !widthInvalid && !heightInvalid && (width != null || height != null)

        Column(verticalArrangement = Arrangement.spacedBy(IMAGE_SECTION_GAP)) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(IMAGE_SECTION_GAP)) {
                ImageChoiceRow(
                    title = stringResource(R.string.pdf_tool_img_resize_by_percent),
                    body = null,
                    selected = !bySize,
                    onSelect = { bySize = false }
                )
                ImageChoiceRow(
                    title = stringResource(R.string.pdf_tool_img_resize_by_size),
                    body = null,
                    selected = bySize,
                    onSelect = { bySize = true }
                )
            }
            if (!bySize) {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(IMAGE_SECTION_GAP)) {
                    PERCENTS.forEach { value ->
                        FilterChip(
                            selected = percent == value,
                            onClick = { percent = value },
                            label = { Text(stringResource(R.string.pdf_tool_img_resize_percent, value)) }
                        )
                    }
                }
            } else {
                SideField(
                    value = widthText,
                    onValueChange = { widthText = it.filter(Char::isDigit).take(MAX_DIGITS) },
                    label = stringResource(R.string.pdf_tool_img_resize_width),
                    invalid = widthInvalid,
                    imeAction = ImeAction.Next
                )
                SideField(
                    value = heightText,
                    onValueChange = { heightText = it.filter(Char::isDigit).take(MAX_DIGITS) },
                    label = stringResource(R.string.pdf_tool_img_resize_height),
                    invalid = heightInvalid,
                    imeAction = ImeAction.Done
                )
                val nothingTyped = width == null && height == null
                if (nothingTyped && !widthInvalid && !heightInvalid) {
                    ImageBodyText(stringResource(R.string.pdf_tool_img_resize_size_hint))
                }
                ImageSwitchRow(
                    title = stringResource(R.string.pdf_tool_img_resize_keep_aspect),
                    body = null,
                    checked = keepAspect,
                    onCheckedChange = { keepAspect = it }
                )
                ImageSwitchRow(
                    title = stringResource(R.string.pdf_tool_img_resize_no_enlarge),
                    body = null,
                    checked = noEnlarge,
                    onCheckedChange = { noEnlarge = it }
                )
            }
            ImageActionButton(
                label = stringResource(R.string.pdf_tool_img_resize_action),
                enabled = !bySize || sizeReady
            ) {
                val mode = if (bySize) {
                    ResizeMode.Fit(width, height, keepAspect, noEnlarge)
                } else {
                    ResizeMode.Percent(percent)
                }
                onRun(ResizeImageOptions(mode))
            }
        }
    }

    @Composable
    private fun SideField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        invalid: Boolean,
        imeAction: ImeAction
    ) {
        val error = if (invalid) stringResource(R.string.pdf_tool_img_resize_size_invalid, MAX_SIDE) else null
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = invalid,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
            modifier = Modifier.fillMaxWidth()
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        job.resize(input, (options as ResizeImageOptions).mode)

    private companion object {
        val PERCENTS = listOf(25, 50, 75)
        const val DEFAULT_PERCENT = 50

        /** Wider than any phone camera; the job's pixel budget caps the area anyway. */
        const val MAX_SIDE = 20_000
        const val MAX_DIGITS = 6

        fun parseSide(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1..MAX_SIDE }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ResizeImageToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.RESIZE_IMAGE)
    abstract fun bind(spec: ResizeImageToolSpec): ToolSpec
}
