package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.skeler.scanely.core.pdftools.stamp.NumberFormat
import com.skeler.scanely.core.pdftools.stamp.NumberMargin
import com.skeler.scanely.core.pdftools.stamp.NumberPosition
import com.skeler.scanely.core.pdftools.stamp.NumberSize
import com.skeler.scanely.core.pdftools.stamp.PageNumbersJob
import com.skeler.scanely.core.pdftools.stamp.PageNumbersOptions
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

/** Numbers every page from a chosen one on; the options object is [PageNumbersOptions]. */
class PageNumbersToolSpec @Inject constructor(private val job: PageNumbersJob) : ToolSpec {
    override val tool = PdfTool.PAGE_NUMBERS
    override val input = ToolInput.PDF

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        val pageCount = state.documents.firstOrNull()?.pageCount ?: 0
        var position by rememberSaveable { mutableStateOf(NumberPosition.BOTTOM_CENTER) }
        var format by rememberSaveable { mutableStateOf(NumberFormat.PLAIN) }
        var size by rememberSaveable { mutableStateOf(NumberSize.MEDIUM) }
        var margin by rememberSaveable { mutableStateOf(NumberMargin.NORMAL) }
        var startText by rememberSaveable { mutableStateOf("1") }
        var firstText by rememberSaveable { mutableStateOf("1") }

        val start = startText.toIntOrNull()?.takeIf { it in 0..MAX_START }
        val first = firstText.toIntOrNull()?.takeIf { it in 1..pageCount }

        OptionsBody(stringResource(R.string.pdf_stamp_numbers_body))
        ChipChoice(R.string.pdf_stamp_numbers_position, POSITIONS, position, { position = it })
        ChipChoiceText(R.string.pdf_stamp_numbers_format, formatChoices(), format, { format = it })
        NumberField(
            value = startText,
            onValueChange = { startText = it.filter(Char::isDigit).take(MAX_DIGITS) },
            label = stringResource(R.string.pdf_stamp_numbers_start),
            error = if (start == null) stringResource(R.string.pdf_stamp_numbers_start_invalid) else null,
            hint = null
        )
        NumberField(
            value = firstText,
            onValueChange = { firstText = it.filter(Char::isDigit).take(MAX_DIGITS) },
            label = stringResource(R.string.pdf_stamp_numbers_first_page),
            error = if (first == null) {
                stringResource(R.string.pdf_stamp_numbers_first_page_invalid, pageCount)
            } else {
                null
            },
            hint = stringResource(R.string.pdf_stamp_numbers_first_page_hint)
        )
        ChipChoice(R.string.pdf_stamp_numbers_text_size, SIZES, size, { size = it })
        ChipChoice(R.string.pdf_stamp_numbers_margin, MARGINS, margin, { margin = it })
        PrimaryActionButton(
            text = stringResource(R.string.pdf_stamp_numbers_action),
            enabled = state.documents.isNotEmpty() && start != null && first != null,
            onClick = {
                if (start != null && first != null) {
                    onRun(PageNumbersOptions(position, format, start, first, size, margin))
                }
            }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        job.number(input, options as PageNumbersOptions)
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    hint: String?
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Each format chip shows the format itself, filled in for page 1 of 12, so the choice is literal. */
@Composable
private fun formatChoices(): List<Pair<NumberFormat, String>> =
    NumberFormat.entries.map { it to stringResource(it.template, SAMPLE_PAGE, SAMPLE_TOTAL) }

private const val MAX_START = 99_999
private const val MAX_DIGITS = 5
private const val SAMPLE_PAGE = 1
private const val SAMPLE_TOTAL = 12

private val POSITIONS = listOf(
    NumberPosition.TOP_LEFT to R.string.pdf_stamp_numbers_top_left,
    NumberPosition.TOP_CENTER to R.string.pdf_stamp_numbers_top_center,
    NumberPosition.TOP_RIGHT to R.string.pdf_stamp_numbers_top_right,
    NumberPosition.BOTTOM_LEFT to R.string.pdf_stamp_numbers_bottom_left,
    NumberPosition.BOTTOM_CENTER to R.string.pdf_stamp_numbers_bottom_center,
    NumberPosition.BOTTOM_RIGHT to R.string.pdf_stamp_numbers_bottom_right
)

private val SIZES = listOf(
    NumberSize.SMALL to R.string.pdf_stamp_size_small,
    NumberSize.MEDIUM to R.string.pdf_stamp_size_medium,
    NumberSize.LARGE to R.string.pdf_stamp_size_large
)

private val MARGINS = listOf(
    NumberMargin.NARROW to R.string.pdf_stamp_numbers_margin_narrow,
    NumberMargin.NORMAL to R.string.pdf_stamp_numbers_margin_normal,
    NumberMargin.WIDE to R.string.pdf_stamp_numbers_margin_wide
)

@Module
@InstallIn(SingletonComponent::class)
abstract class PageNumbersToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.PAGE_NUMBERS)
    abstract fun bind(spec: PageNumbersToolSpec): ToolSpec
}
