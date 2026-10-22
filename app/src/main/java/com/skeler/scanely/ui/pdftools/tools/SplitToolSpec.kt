package com.skeler.scanely.ui.pdftools.tools

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.organize.PageRanges
import com.skeler.scanely.core.pdftools.organize.RangeError
import com.skeler.scanely.core.pdftools.organize.RangeParse
import com.skeler.scanely.core.pdftools.organize.SplitJob
import com.skeler.scanely.core.pdftools.organize.SplitMode
import com.skeler.scanely.ui.pdftools.OPTIONS_ROW_GAP
import com.skeler.scanely.ui.pdftools.OptionsBody
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

/** The mode, already validated against the document's page count. */
data class SplitOptions(val mode: SplitMode)

/**
 * Split, extract and delete pages: all three are "write some of this document's pages to new
 * files", so they share one tool, one range syntax and one job rather than three grid tiles.
 */
class SplitToolSpec @Inject constructor(private val job: SplitJob) : ToolSpec {
    override val tool = PdfTool.SPLIT
    override val input = ToolInput.PDF

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        val document = state.documents.firstOrNull()
        var choice by rememberSaveable { mutableStateOf(SplitChoice.RANGES) }
        var rangesText by rememberSaveable { mutableStateOf("") }
        var everyText by rememberSaveable { mutableStateOf(DEFAULT_EVERY.toString()) }
        var sizeText by rememberSaveable { mutableStateOf(DEFAULT_SIZE_MB) }

        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(OPTIONS_ROW_GAP)
        ) {
            SplitChoice.entries.forEach { entry ->
                SelectableRow(
                    title = stringResource(entry.title),
                    body = stringResource(entry.body),
                    selected = choice == entry,
                    onSelect = { choice = entry }
                )
            }
        }
        if (document == null) return

        val mode = when (choice) {
            SplitChoice.RANGES, SplitChoice.EXTRACT, SplitChoice.DELETE ->
                rangesMode(choice, rangesText, document) { rangesText = it }
            SplitChoice.EVERY -> everyMode(everyText, document.pageCount) { everyText = it }
            SplitChoice.SIZE -> sizeMode(sizeText) { sizeText = it }
        }
        Preview(mode, document)
        if (document.password.isNotEmpty()) OptionsBody(stringResource(R.string.pdf_tool_split_locked))
        PrimaryActionButton(
            text = stringResource(choice.action),
            enabled = mode != null,
            onClick = { mode?.let { onRun(SplitOptions(it)) } }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        job.split(input.documents.first(), (options as SplitOptions).mode, input.run, input.onProgress)
}

private enum class SplitChoice(@StringRes val title: Int, @StringRes val body: Int, @StringRes val action: Int) {
    RANGES(
        R.string.pdf_tool_split_mode_ranges,
        R.string.pdf_tool_split_mode_ranges_body,
        R.string.pdf_tool_split_action
    ),
    EVERY(
        R.string.pdf_tool_split_mode_every,
        R.string.pdf_tool_split_mode_every_body,
        R.string.pdf_tool_split_action
    ),
    EXTRACT(
        R.string.pdf_tool_split_mode_extract,
        R.string.pdf_tool_split_mode_extract_body,
        R.string.pdf_tool_split_extract_action
    ),
    DELETE(
        R.string.pdf_tool_split_mode_delete,
        R.string.pdf_tool_split_mode_delete_body,
        R.string.pdf_tool_split_delete_action
    ),
    SIZE(
        R.string.pdf_tool_split_mode_size,
        R.string.pdf_tool_split_mode_size_body,
        R.string.pdf_tool_split_action
    )
}

/** The range box, and the mode it makes; an untouched box disables the button without scolding. */
@Composable
private fun rangesMode(
    choice: SplitChoice,
    text: String,
    document: ToolDocument,
    onChange: (String) -> Unit
): SplitMode? {
    val parsed = PageRanges.parse(text, document.pageCount)
    var mode: SplitMode? = null
    var error: String? = null
    when (parsed) {
        is RangeParse.Invalid -> if (parsed.error != RangeError.EMPTY) error = parsed.message(document.pageCount)
        is RangeParse.Ok -> {
            val ranges = parsed.ranges
            mode = when (choice) {
                SplitChoice.EXTRACT -> SplitMode.Extract(ranges)
                SplitChoice.DELETE -> SplitMode.Delete(ranges)
                else -> SplitMode.ByRanges(ranges)
            }
            if (choice == SplitChoice.DELETE && PageRanges.pagesOf(ranges).size >= document.pageCount) {
                mode = null
                error = stringResource(R.string.pdf_tool_split_delete_all)
            }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.pdf_tool_split_ranges_label)) },
        placeholder = { Text(stringResource(R.string.pdf_tool_split_ranges_hint)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
    )
    return mode
}

@Composable
private fun RangeParse.Invalid.message(pageCount: Int): String = when (error) {
    RangeError.EMPTY -> stringResource(R.string.pdf_tool_split_range_empty)
    RangeError.SYNTAX -> stringResource(R.string.pdf_tool_split_range_syntax, token.orEmpty())
    RangeError.OUT_OF_BOUNDS -> stringResource(R.string.pdf_tool_split_range_out, token.orEmpty(), pageCount)
    RangeError.REVERSED -> stringResource(R.string.pdf_tool_split_range_reversed, token.orEmpty())
}

/** A number box between − and + buttons, clamped to 1..pageCount. */
@Composable
private fun everyMode(text: String, pageCount: Int, onChange: (String) -> Unit): SplitMode? {
    val pages = text.trim().toIntOrNull()?.takeIf { it in 1..pageCount }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onChange(((pages ?: 1) - 1).coerceIn(1, pageCount).toString()) },
            enabled = pages != null && pages > 1
        ) {
            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.pdf_tool_split_every_less))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { value -> onChange(value.filter { it.isDigit() }.take(MAX_EVERY_DIGITS)) },
            label = { Text(stringResource(R.string.pdf_tool_split_every_label)) },
            singleLine = true,
            isError = pages == null,
            supportingText = if (pages == null) {
                { Text(stringResource(R.string.pdf_tool_split_every_invalid, pageCount)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { onChange(((pages ?: 0) + 1).coerceIn(1, pageCount).toString()) },
            enabled = pages == null || pages < pageCount
        ) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.pdf_tool_split_every_more))
        }
    }
    return pages?.let { SplitMode.EveryN(it) }
}

/** Megabytes, decimals allowed with either separator; the box is checked, the size is not guessed. */
@Composable
private fun sizeMode(text: String, onChange: (String) -> Unit): SplitMode? {
    val megabytes = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= MAX_SIZE_MB }
    val bytes = megabytes?.let { (it * BYTES_PER_MB).toLong() }?.takeIf { it > 0 }
    OptionsBody(stringResource(R.string.pdf_tool_split_size_body))
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            onChange(value.filter { it.isDigit() || it == '.' || it == ',' }.take(MAX_SIZE_CHARS))
        },
        label = { Text(stringResource(R.string.pdf_tool_split_size_label)) },
        singleLine = true,
        isError = bytes == null,
        supportingText = if (bytes == null) {
            { Text(stringResource(R.string.pdf_tool_split_size_invalid)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
    )
    return bytes?.let { SplitMode.MaxSize(it) }
}

/** What the run will make, when that is known before running; by-size can't be known. */
@Composable
private fun Preview(mode: SplitMode?, document: ToolDocument) {
    val text = when (mode) {
        is SplitMode.ByRanges -> files(mode.ranges.size)
        is SplitMode.EveryN -> files((document.pageCount + mode.pages - 1) / mode.pages)
        is SplitMode.Extract -> pages(mode.ranges.sumOf { it.size })
        is SplitMode.Delete -> pages(document.pageCount - PageRanges.pagesOf(mode.ranges).size)
        is SplitMode.MaxSize, null -> return
    }
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun files(count: Int): String = pluralStringResource(R.plurals.pdf_tool_split_makes_files, count, count)

@Composable
private fun pages(count: Int): String = pluralStringResource(R.plurals.pdf_tool_split_keeps_pages, count, count)

private const val DEFAULT_EVERY = 1
private const val DEFAULT_SIZE_MB = "10"
private const val MAX_EVERY_DIGITS = 6
private const val MAX_SIZE_CHARS = 8
private const val MAX_SIZE_MB = 100_000.0
private const val BYTES_PER_MB = 1024.0 * 1024.0

@Module
@InstallIn(SingletonComponent::class)
abstract class SplitToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.SPLIT)
    abstract fun bind(spec: SplitToolSpec): ToolSpec
}
