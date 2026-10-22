@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeler.scanely.core.common.LocalDarkMode
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.text.FilenameSuggester
import com.skeler.scanely.core.transcribe.Transcript
import com.skeler.scanely.core.transcribe.TranscriptFormats
import com.skeler.scanely.core.transcribe.TranscriptSegment
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.components.ExportContent
import com.skeler.scanely.ui.components.ExportNameDialog
import com.skeler.scanely.ui.components.TextExportFormat
import com.skeler.scanely.ui.components.rememberStorageAccess
import com.skeler.scanely.ui.components.rememberTextExporter
import com.skeler.scanely.ui.viewmodel.PlayerState
import com.skeler.scanely.ui.viewmodel.TranscriptViewModel
import kotlinx.coroutines.launch

@Composable
fun TranscriptScreen() {
    val navigator = LocalNavigator.current
    val viewModel: TranscriptViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.deleted) { if (state.deleted) navigator.back() }

    val transcript = state.transcript
    var menuOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renamingSpeaker by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val exportDocument = rememberTextExporter()
    val exportPlain = rememberPlainExporter()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        transcript?.title ?: "Transcript",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(enabled = transcript != null) { renaming = true }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (transcript != null && !transcript.isEmpty) {
                        IconButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText(transcript.title, TranscriptFormats.plainText(transcript))
                            )
                            Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy transcript") }
                        IconButton(onClick = { share(context, transcript) }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share transcript")
                        }
                        Box {
                            IconButton(onClick = { exportOpen = true }) {
                                Icon(Icons.Rounded.SaveAlt, contentDescription = "Save as")
                            }
                            DropdownMenu(expanded = exportOpen, onDismissRequest = { exportOpen = false }) {
                                TranscriptExport.entries.forEach { format ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(format.label, fontWeight = FontWeight.Medium)
                                                Text(
                                                    format.hint,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            exportOpen = false
                                            when (format.document) {
                                                null -> exportPlain(transcript, format)
                                                else -> exportDocument(
                                                    ExportContent(
                                                        TranscriptFormats.markdown(transcript),
                                                        isMarkdown = true
                                                    ),
                                                    format.document
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                menuOpen = false
                                renaming = true
                            })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                menuOpen = false
                                confirmDelete = true
                            })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (state.player.available) {
                PlayerBar(
                    player = state.player,
                    onToggle = viewModel::togglePlay,
                    onSeek = viewModel::seekTo,
                    onSkip = viewModel::skip,
                    onSpeed = viewModel::cycleSpeed
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            transcript == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("This transcript is no longer on this phone.")
            }
            else -> TranscriptBody(
                transcript = transcript,
                playhead = Playhead(
                    segment = state.activeSegment,
                    word = state.activeWord,
                    following = state.player.playing,
                    canSeek = state.player.available
                ),
                contentPadding = padding,
                onSeek = viewModel::seekAndPlay,
                onSpeaker = { renamingSpeaker = it }
            )
        }
    }

    if (renaming && transcript != null) {
        TextPromptDialog(
            title = "Rename transcript",
            initial = transcript.title,
            onConfirm = {
                viewModel.rename(it)
                renaming = false
            },
            onDismiss = { renaming = false }
        )
    }

    renamingSpeaker?.let { speaker ->
        TextPromptDialog(
            title = "Name this speaker",
            initial = transcript?.speakerNames?.get(speaker).orEmpty(),
            placeholder = "Speaker ${speaker + 1}",
            confirmLabel = "Save",
            onConfirm = {
                viewModel.renameSpeaker(speaker, it)
                renamingSpeaker = null
            },
            onDismiss = { renamingSpeaker = null }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete transcript?") },
            text = { Text("The transcript and its recording will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/** Where playback is, for the highlight and the follow-along scroll. */
private data class Playhead(val segment: Int, val word: Int, val following: Boolean, val canSeek: Boolean)

@Composable
private fun TranscriptBody(
    transcript: Transcript,
    playhead: Playhead,
    contentPadding: PaddingValues,
    onSeek: (Long) -> Unit,
    onSpeaker: (Int) -> Unit
) {
    val activeSegment = playhead.segment
    val following = playhead.following
    val listState = rememberLazyListState()
    // While playing, keep the spoken paragraph in view — but only move when it has left the screen,
    // so reading ahead is not yanked back every sentence.
    LaunchedEffect(activeSegment, following) {
        if (!following || activeSegment < 0) return@LaunchedEffect
        val item = activeSegment + 1
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (item !in visible) listState.animateScrollToItem(item)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "facts") { Facts(transcript) }
        if (transcript.isEmpty) {
            item(key = "empty") {
                Text(
                    "No speech was found in this recording.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        itemsIndexed(transcript.segments, key = { i, _ -> i }) { index, segment ->
            SegmentItem(
                transcript = transcript,
                segment = segment,
                activeWord = if (index == activeSegment) playhead.word else -1,
                onSeek = onSeek,
                canSeek = playhead.canSeek,
                onSpeaker = onSpeaker
            )
        }
    }
}

@Composable
private fun Facts(transcript: Transcript) {
    val facts = listOfNotNull(
        TranscriptFormats.clock(transcript.durationMs),
        transcript.language.takeIf { it.isNotBlank() }?.let(TranscriptFormats::languageName),
        when {
            transcript.speakerCount > 1 -> "${transcript.speakerCount} speakers"
            transcript.speakerCount == 1 -> "1 speaker"
            else -> null
        },
        "${transcript.creditsUsed} ${if (transcript.creditsUsed == 1) "credit" else "credits"}"
            .takeIf { transcript.creditsUsed > 0 }
    )
    Text(
        facts.joinToString(" · "),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SegmentItem(
    transcript: Transcript,
    segment: TranscriptSegment,
    activeWord: Int,
    onSeek: (Long) -> Unit,
    canSeek: Boolean,
    onSpeaker: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val speaker = segment.speaker
            if (speaker != null) {
                val color = speakerColor(speaker)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(color.copy(alpha = 0.16f))
                        .clickable { onSpeaker(speaker) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        transcript.speakerName(speaker).orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Text(
                TranscriptFormats.clock(segment.startMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = canSeek) { onSeek(segment.startMs) }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        val highlight = MaterialTheme.colorScheme.primaryContainer
        val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer
        val text = remember(segment.text, activeWord, highlight) {
            buildAnnotatedString {
                append(segment.text)
                segment.words.getOrNull(activeWord)?.let { w ->
                    addStyle(SpanStyle(background = highlight, color = onHighlight), w.from, w.to)
                }
            }
        }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp,
                lineHeight = 27.sp,
                textDirection = TextDirection.Content
            ),
            onTextLayout = { layout = it },
            modifier = Modifier.pointerInput(segment, canSeek) {
                if (!canSeek) return@pointerInput
                detectTapGestures { position ->
                    val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                    val word = segment.words.lastOrNull { it.from <= offset } ?: segment.words.firstOrNull()
                    onSeek(word?.startMs ?: segment.startMs)
                }
            }
        )
    }
}

@Composable
private fun PlayerBar(
    player: PlayerState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    onSpeed: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val duration = player.durationMs.coerceAtLeast(1)
            Slider(
                value = if (dragging) dragValue else player.positionMs.toFloat() / duration,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    onSeek((dragValue * duration).toLong())
                    dragging = false
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shown = if (dragging) (dragValue * duration).toLong() else player.positionMs
                Text(
                    "${TranscriptFormats.clock(shown)} / ${TranscriptFormats.clock(player.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onSkip(-10_000) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds") }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(52.dp)) {
                    Icon(
                        if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (player.playing) "Pause" else "Play"
                    )
                }
                IconButton(onClick = { onSkip(10_000) }) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds") }
                TextButton(onClick = onSpeed, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(speedLabel(player.speed))
                }
            }
        }
    }
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    placeholder: String? = null,
    confirmLabel: String = "Rename",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(80) },
                singleLine = true,
                placeholder = placeholder?.let { { Text(it) } }
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A distinct, readable hue per speaker, stable across screens and exports. */
@Composable
private fun speakerColor(speaker: Int): Color {
    val palette = if (LocalDarkMode.current) SPEAKERS_DARK else SPEAKERS_LIGHT
    return palette[speaker % palette.size]
}

private val SPEAKERS_LIGHT = listOf(
    0xFF3F6B88,
    0xFFA05A4E,
    0xFF4F7A63,
    0xFF7B5EA7,
    0xFF9A7B3F,
    0xFF2F7F86,
    0xFFA8537E,
    0xFF5F6B2E
).map(::Color)
private val SPEAKERS_DARK = listOf(
    0xFF8FBBD9,
    0xFFE0A79B,
    0xFF9CC7AE,
    0xFFC3AEE6,
    0xFFD8B878,
    0xFF86CBD1,
    0xFFE3A2C4,
    0xFFB9C47F
).map(::Color)

/** Every way a transcript can be saved. [document] formats go through the shared exporter. */
private enum class TranscriptExport(
    val label: String,
    val hint: String,
    val extension: String,
    val mimeType: String,
    val document: TextExportFormat? = null
) {
    PDF("PDF", "Speakers and times, ready to print", "pdf", ScanExporter.PDF_MIME_TYPE, TextExportFormat.PDF),
    WORD("Word", "Editable document", "docx", ScanExporter.WORD_MIME_TYPE, TextExportFormat.WORD),
    TEXT("Text", "Plain .txt", "txt", "text/plain"),
    MARKDOWN("Markdown", "For notes apps", "md", ScanExporter.MARKDOWN_MIME_TYPE),
    SRT("Subtitles (SRT)", "For video editors", "srt", "application/x-subrip"),
    VTT("Subtitles (WebVTT)", "For the web, with speakers", "vtt", "text/vtt")
}

private class PendingPlainExport(val transcript: Transcript, val format: TranscriptExport)

/** Name, write to Downloads/Scanly, then offer to share — the same flow as the document exports. */
@Composable
private fun rememberPlainExporter(): (Transcript, TranscriptExport) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val withStorageAccess = rememberStorageAccess()
    var pending by remember { mutableStateOf<PendingPlainExport?>(null) }

    pending?.let { export ->
        ExportNameDialog(
            suggestion = FilenameSuggester.fromDisplayName(export.transcript.title),
            extension = export.format.extension,
            onDismiss = { pending = null },
            onConfirm = { name ->
                pending = null
                withStorageAccess {
                    scope.launch {
                        val t = export.transcript
                        val body = when (export.format) {
                            TranscriptExport.SRT -> TranscriptFormats.srt(t)
                            TranscriptExport.VTT -> TranscriptFormats.vtt(t)
                            TranscriptExport.MARKDOWN -> TranscriptFormats.markdown(t)
                            else -> TranscriptFormats.plainText(t)
                        }
                        runCatching {
                            ScanExporter.exportPlainText(
                                context,
                                body,
                                "$name.${export.format.extension}",
                                export.format.mimeType
                            )
                        }.fold(
                            onSuccess = { uri ->
                                Toast.makeText(context, "Saved to Downloads/Scanly", Toast.LENGTH_SHORT).show()
                                ScanExporter.shareDocument(context, uri, export.format.mimeType)
                            },
                            onFailure = {
                                val label = export.format.label
                                Toast.makeText(context, "Couldn't save $label", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        )
    }
    return { transcript, format -> pending = PendingPlainExport(transcript, format) }
}

private fun share(context: Context, transcript: Transcript) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, transcript.title)
        putExtra(Intent.EXTRA_TEXT, TranscriptFormats.plainText(transcript))
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share transcript")) }
}
