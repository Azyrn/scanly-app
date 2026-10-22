package com.skeler.scanely.ui.components

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.skeler.scanely.core.common.UriNames
import com.skeler.scanely.core.ocr.TextBlockData
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.text.FilenameSuggester
import com.skeler.scanely.core.text.TextTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TextExportFormat(val mimeType: String, val extension: String, val label: String) {
    PDF(ScanExporter.PDF_MIME_TYPE, "pdf", "PDF"),
    WORD(ScanExporter.WORD_MIME_TYPE, "docx", "Word"),
    MARKDOWN(ScanExporter.MARKDOWN_MIME_TYPE, "md", "Markdown"),
    CSV(ScanExporter.CSV_MIME_TYPE, "csv", "CSV"),
    JSON(ScanExporter.JSON_MIME_TYPE, "json", "JSON")
}

/** Shared export input, kept in sync with the preview. */
data class ExportContent(
    val text: String,
    val isMarkdown: Boolean = false,
    val blocks: List<TextBlockData> = emptyList(),
    /** Markdown source used by CSV to preserve table structure. */
    val tableSource: String? = null
)

val TEXT_EXPORT_FORMATS = listOf(
    TextExportFormat.PDF,
    TextExportFormat.WORD,
    TextExportFormat.MARKDOWN,
    TextExportFormat.CSV
)

val PADDLE_EXPORT_FORMATS = TEXT_EXPORT_FORMATS + TextExportFormat.JSON

fun unavailableFormats(content: ExportContent): Set<TextExportFormat> =
    if (tableRows(content).isNotEmpty()) emptySet() else setOf(TextExportFormat.CSV)

/** Detects Markdown structure conservatively when restoring historical text. */
private val MARKDOWN_HEADING = Regex("^#{1,6}\\s+\\S")

internal fun rendersAsMarkdown(content: ExportContent): Boolean =
    content.isMarkdown || content.text.lineSequence().any { line ->
        val trimmed = line.trim()
        trimmed.startsWith("```") ||
            (trimmed.length > 2 && trimmed.startsWith("|") && trimmed.endsWith("|")) ||
            MARKDOWN_HEADING.containsMatchIn(trimmed)
    }

internal fun tableRows(content: ExportContent): List<List<String>> {
    val structured = content.tableSource
        ?.takeIf { !content.isMarkdown && it.isNotBlank() }
        ?.let { TextTables.rows(it, asMarkdown = true) }
    return if (!structured.isNullOrEmpty()) {
        structured
    } else {
        TextTables.rows(content.text, rendersAsMarkdown(content))
    }
}

private class PendingExport(
    val content: ExportContent,
    val format: TextExportFormat,
    val rows: List<List<String>>,
    val suggestedName: String
)

@Composable
fun rememberTextExporter(sourceUri: Uri? = null): (ExportContent, TextExportFormat) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val withStorageAccess = rememberStorageAccess()
    var pending by remember { mutableStateOf<PendingExport?>(null) }
    val originalName by produceState<String?>(initialValue = null, sourceUri) {
        value = withContext(Dispatchers.IO) {
            sourceUri?.let { resolveOriginalName(context, it) }
        }
    }

    pending?.let { export ->
        ExportNameDialog(
            suggestion = export.suggestedName,
            extension = export.format.extension,
            onDismiss = { pending = null },
            onConfirm = { name ->
                pending = null
                withStorageAccess { scope.launch { write(context, export, name) } }
            }
        )
    }

    return exporter@{ content: ExportContent, format: TextExportFormat ->
        val text = content.text
        if (text.isBlank()) {
            toast(context, "Nothing to export")
            return@exporter
        }
        val rows = if (format == TextExportFormat.CSV) tableRows(content) else emptyList()
        if (format == TextExportFormat.CSV && rows.isEmpty()) {
            toast(context, "No table data to export")
            return@exporter
        }
        pending = PendingExport(
            content = content,
            format = format,
            rows = rows,
            suggestedName = originalName ?: FilenameSuggester.suggest(text)
        )
    }
}

private fun resolveOriginalName(context: Context, uri: Uri): String? {
    val displayName = UriNames.displayName(context, uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')

    return displayName
        ?.takeIf { it.isNotBlank() }
        ?.let(FilenameSuggester::fromDisplayName)
}

private suspend fun write(context: Context, export: PendingExport, name: String) {
    val content = export.content
    val text = content.text
    val format = export.format
    val asMarkdown = rendersAsMarkdown(content)

    runCatching {
        when (format) {
            TextExportFormat.PDF ->
                ScanExporter.exportTextPdf(context, text, asMarkdown, name)
            TextExportFormat.WORD ->
                ScanExporter.exportTextWord(context, text, asMarkdown, name)
            TextExportFormat.MARKDOWN ->
                ScanExporter.exportTextMarkdown(context, text, name)
            TextExportFormat.CSV ->
                ScanExporter.exportTextCsv(context, export.rows, name)
            TextExportFormat.JSON ->
                ScanExporter.exportTextJson(context, text, content.blocks, name, asMarkdown)
        }
    }.fold(
        onSuccess = { uri ->
            toast(context, "Saved to Downloads/Scanly")
            ScanExporter.shareDocument(context, uri, format.mimeType)
        },
        onFailure = {
            toast(context, "Couldn't save ${format.name}")
        }
    )
}

private fun toast(context: Context, message: String) =
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

/** Play-build alias: structured OCR output offers the same formats as the offline engine. */
val STRUCTURED_EXPORT_FORMATS = PADDLE_EXPORT_FORMATS
