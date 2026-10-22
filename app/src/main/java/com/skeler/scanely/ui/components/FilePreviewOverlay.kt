package com.skeler.scanely.ui.components

import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.skeler.scanely.core.pdf.PdfPreview
import com.skeler.scanely.core.pdf.ScanExporter

private const val MAX_PREVIEW_PAGES = 3
private const val MAX_PREVIEW_LINES = 1500

/** Page bitmaps are ARGB_8888, so the cap is about memory, not sharpness. */
private const val MAX_RENDER_WIDTH_PX = 1080

/** Dark enough that the page owns the screen, light enough that the result stays visible. */
private const val BACKDROP = 0x99000000.toInt()

/** 297/210 — the page is drawn at the proportions the PDF and the .docx are laid out for. */
private const val A4_RATIO = 1.4142f

/** Pills, caption, buttons and their spacing, taken off the height before sizing the page. */
private val CHROME_HEIGHT = 168.dp

private val Paper = Color.White
private val PaperInk = Color(0xFF141414)
private val PaperMuted = Color(0xFF6B6B6B)
private val CodePaper = Color(0xFFF7F7F5)
private val CodeLine = Color(0xFFE2E2DE)
private val CodeNumber = Color(0xFF9A9A93)
private val SheetLine = Color(0xFFD5D5D5)
private val SheetHeader = Color(0xFFEDEDED)
private val SheetHeaderInk = Color(0xFF4A4A4A)
private val PillFill = Color(0xCC141414)
private val PillInk = Color(0xFFF0F0F0)
private val PillMuted = Color(0xFF8A8A8A)

/**
 * The file the export would write, floating over the dimmed result: real PDF pages, the CSV as a
 * sheet, the Markdown and JSON as their file text. Every format renders from the same
 * [ExportContent] the exporter takes, so what is on screen is what lands in Downloads.
 */
@Composable
fun FilePreviewOverlay(
    content: ExportContent,
    formats: List<TextExportFormat>,
    disabledFormats: Set<TextExportFormat>,
    onExport: (TextExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    val enabled = remember(formats, disabledFormats) { formats.filter { it !in disabledFormats } }
    var format by remember(enabled) {
        mutableStateOf(enabled.firstOrNull() ?: TextExportFormat.PDF)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DimmedWindow()

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .noRipple(onDismiss)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            // The sheet is A4, whatever the format: the pills, caption and buttons take their
            // room first, and the page is the largest A4 that fits in what is left.
            val pageArea = (maxHeight - CHROME_HEIGHT).coerceAtLeast(280.dp)
            val pageWidth = minOf(maxWidth, 520.dp, pageArea / A4_RATIO)
            val pageHeight = pageWidth * A4_RATIO

            Column(
                modifier = Modifier.width(pageWidth),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FormatPills(
                    formats = formats,
                    disabledFormats = disabledFormats,
                    selected = format,
                    onSelect = { format = it }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Preview of the .${format.extension} · saves to Downloads/Scanly",
                    style = MaterialTheme.typography.labelSmall,
                    color = PillInk.copy(alpha = 0.75f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                PreviewPage(
                    content = content,
                    format = format,
                    width = pageWidth,
                    height = pageHeight
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = PillInk)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onDismiss()
                            onExport(format)
                        },
                        enabled = format in enabled,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = PillFill,
                            disabledContentColor = PillMuted
                        )
                    ) {
                        Icon(
                            Icons.Rounded.SaveAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export ${format.label}")
                    }
                }
            }
        }
    }
}

/**
 * The backdrop is the dialog window's own background: it fills the whole window frame, where a
 * scrim drawn in the layout only reaches as far as the content and the system dim stops short of
 * the bottom of the screen. The system dim is kept low, for the status bar strip above the window.
 */
@Composable
private fun DimmedWindow() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.apply {
            setBackgroundDrawable(ColorDrawable(BACKDROP))
            setDimAmount(0.25f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
}

@Composable
private fun Modifier.noRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

@Composable
private fun FormatPills(
    formats: List<TextExportFormat>,
    disabledFormats: Set<TextExportFormat>,
    selected: TextExportFormat,
    onSelect: (TextExportFormat) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        formats.forEach { format ->
            val enabled = format !in disabledFormats
            val isSelected = format == selected
            Text(
                text = format.label,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    enabled -> PillInk
                    else -> PillMuted
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else PillFill)
                    .clickable(enabled = enabled) { onSelect(format) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

/** The page itself — the only solid surface in the overlay. */
@Composable
private fun PreviewPage(
    content: ExportContent,
    format: TextExportFormat,
    width: Dp,
    height: Dp
) {
    val asMarkdown = remember(content) { rendersAsMarkdown(content) }

    when (format) {
        TextExportFormat.PDF -> PdfPages(
            text = content.text,
            asMarkdown = asMarkdown,
            width = width,
            height = height
        )

        TextExportFormat.WORD -> Sheet(height = height, color = Paper) {
            DocumentBody(
                text = content.text,
                asMarkdown = asMarkdown
            )
        }

        TextExportFormat.CSV -> {
            val rows = remember(content) { tableRows(content) }
            Sheet(height = height, color = Paper) {
                if (rows.isEmpty()) {
                    PageMessage("No table in this text, so a CSV would have nothing to carry.")
                } else {
                    SheetBody(rows)
                }
            }
        }

        TextExportFormat.MARKDOWN -> Sheet(height = height, color = CodePaper) {
            FileTextBody(content.text)
        }

        TextExportFormat.JSON -> {
            val json = remember(content, asMarkdown) {
                ScanExporter.buildJson(content.text, content.blocks, asMarkdown)
            }
            Sheet(height = height, color = CodePaper) {
                FileTextBody(json)
            }
        }
    }
}

@Composable
private fun Sheet(
    height: Dp,
    color: Color,
    body: @Composable () -> Unit
) {
    Surface(
        color = color,
        contentColor = PaperInk,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 16.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .noRipple {}
    ) {
        body()
    }
}

/** The exporter's own PDF, rasterised page by page, each page its own sheet. */
@Composable
private fun PdfPages(
    text: String,
    asMarkdown: Boolean,
    width: Dp,
    height: Dp
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }.toInt().coerceIn(320, MAX_RENDER_WIDTH_PX)

    val rendered by produceState<Result<PdfPreview>?>(null, text, asMarkdown, widthPx) {
        value = null
        value = runCatching {
            ScanExporter.renderTextPdfPages(
                context = context,
                text = text,
                asMarkdown = asMarkdown,
                pageWidthPx = widthPx,
                maxPages = MAX_PREVIEW_PAGES
            )
        }
    }

    val preview = rendered?.getOrNull()

    when {
        rendered == null -> Sheet(height = height, color = Paper) {
            PageMessage("Rendering pages…", loading = true)
        }

        preview == null || preview.pages.isEmpty() -> Sheet(height = height, color = Paper) {
            PageMessage("Couldn't render this document as a PDF.")
        }

        else -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .verticalScroll(rememberScrollState())
                .noRipple {},
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            preview.pages.forEachIndexed { index, page ->
                Surface(
                    color = Paper,
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                ) {
                    Image(
                        bitmap = page.asImageBitmap(),
                        contentDescription = "Page ${index + 1} of ${preview.pageCount}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (preview.pageCount > preview.pages.size) {
                Text(
                    text = "First ${preview.pages.size} of ${preview.pageCount} pages",
                    style = MaterialTheme.typography.labelSmall,
                    color = PillInk,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Word carries the document, not a page image — so this shows the content the .docx holds; Word
 * does its own pagination on open.
 */
@Composable
private fun DocumentBody(text: String, asMarkdown: Boolean) {
    MaterialTheme(colorScheme = lightColorScheme(), typography = MaterialTheme.typography) {
        Box(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            if (asMarkdown) {
                MarkdownContent(text = text)
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    ),
                    color = PaperInk
                )
            }
        }
    }
}

/** The CSV as a spreadsheet reads it: lettered columns, numbered rows, header row first. */
@Composable
private fun SheetBody(rows: List<List<String>>) {
    val columns = remember(rows) { rows.maxOf { it.size } }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
    ) {
        Row {
            SheetCell(text = "", width = 44.dp, header = true)
            repeat(columns) { column -> SheetCell(text = columnLabel(column), header = true) }
        }
        rows.forEachIndexed { index, row ->
            Row {
                SheetCell(text = "${index + 1}", width = 44.dp, header = true)
                repeat(columns) { column ->
                    SheetCell(text = row.getOrElse(column) { "" }, bold = index == 0)
                }
            }
        }
    }
}

@Composable
private fun SheetCell(
    text: String,
    width: Dp = 140.dp,
    header: Boolean = false,
    bold: Boolean = false
) {
    Box(
        modifier = Modifier
            .width(width)
            .heightIn(min = 38.dp)
            .background(if (header) SheetHeader else Color.Transparent)
            .border(0.5.dp, SheetLine)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = if (header) Alignment.Center else Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            fontWeight = if (header || bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (header) SheetHeaderInk else PaperInk
        )
    }
}

/** Spreadsheet column names: A…Z, then AA, AB and on. */
private fun columnLabel(index: Int): String {
    var remaining = index
    return buildString {
        do {
            insert(0, 'A' + remaining % 26)
            remaining = remaining / 26 - 1
        } while (remaining >= 0)
    }
}

/** Markdown and JSON are text files: this is the file, line for line. */
@Composable
private fun FileTextBody(text: String) {
    val lines = remember(text) { text.lines() }
    val shown = remember(lines) { lines.take(MAX_PREVIEW_LINES) }
    val numbers = remember(shown) { shown.indices.joinToString("\n") { "${it + 1}" } }
    val body = remember(shown) { shown.joinToString("\n") }
    val mono = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp
    )

    Column {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            Text(
                text = numbers,
                style = mono,
                color = CodeNumber,
                textAlign = TextAlign.End,
                modifier = Modifier.width(30.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = body, style = mono, color = PaperInk)
        }

        if (lines.size > shown.size) {
            Text(
                text = "First ${shown.size} of ${lines.size} lines",
                style = MaterialTheme.typography.labelSmall,
                color = PaperMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CodeLine)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun PageMessage(message: String, loading: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = PaperMuted,
            textAlign = TextAlign.Center
        )
    }
}
