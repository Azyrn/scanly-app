@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.skeler.scanely.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeler.scanely.core.transcribe.AudioRecorder
import com.skeler.scanely.core.transcribe.TranscribeOptions
import com.skeler.scanely.core.transcribe.TranscriptFormats
import com.skeler.scanely.core.transcribe.TranscriptSummary
import com.skeler.scanely.core.transcribe.TranscriptionRunner
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.components.OpenContent
import com.skeler.scanely.ui.components.PaywallSheet
import com.skeler.scanely.ui.viewmodel.KeyTermEdit
import com.skeler.scanely.ui.viewmodel.PaywallViewModel
import com.skeler.scanely.ui.viewmodel.TranscribeUiState
import com.skeler.scanely.ui.viewmodel.TranscribeViewModel

@Composable
fun TranscribeScreen() {
    val context = LocalContext.current
    val activity = LocalActivity.current as ComponentActivity
    val navigator = LocalNavigator.current
    val viewModel: TranscribeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val paywallViewModel: PaywallViewModel = hiltViewModel(activity)
    val paywallState by paywallViewModel.state.collectAsStateWithLifecycle()

    var showLanguages by remember { mutableStateOf(false) }
    var showKeyTerms by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TranscriptSummary?>(null) }
    var micDenied by rememberSaveable { mutableStateOf(false) }

    // A finished job opens its transcript, once, and hands the pending audio over to it.
    val job = state.job
    LaunchedEffect(job) {
        if (job is TranscriptionRunner.State.Done) {
            viewModel.onOpened()
            navigator.go(Destination.Transcript(job.transcriptId))
        }
    }

    val picker = rememberLauncherForActivityResult(OpenContent()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri)
    }

    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    // Read the grant itself, not the result map: the map only holds what this launch asked for.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        micDenied = !hasMic() || !viewModel.startRecording()
    }

    fun record() {
        val wanted = buildList {
            if (!hasMic()) add(Manifest.permission.RECORD_AUDIO)
            // The recording notification. Recording works without it, just invisibly.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (wanted.isEmpty()) {
            micDenied = !viewModel.startRecording()
        } else {
            permissions.launch(wanted.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio to Text", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "main") {
                val recording = state.recording
                AnimatedContent(
                    targetState = when {
                        recording is AudioRecorder.State.Recording -> Panel.RECORDING
                        job is TranscriptionRunner.State.Working -> Panel.WORKING
                        state.pending != null -> Panel.PENDING
                        else -> Panel.START
                    },
                    label = "transcribe-panel"
                ) { panel ->
                    when (panel) {
                        Panel.RECORDING -> RecordingPanel(
                            state = recording as? AudioRecorder.State.Recording
                                ?: AudioRecorder.State.Recording(0, false, 0f),
                            maxMinutes = state.maxAudioMinutes,
                            onTogglePause = viewModel::togglePause,
                            onStop = viewModel::stopRecording,
                            onDiscard = viewModel::discardRecording
                        )
                        Panel.WORKING -> WorkingPanel(
                            state = job as? TranscriptionRunner.State.Working
                                ?: TranscriptionRunner.State.Working(TranscriptionRunner.Phase.PREPARING, 0f),
                            onCancel = viewModel::cancel
                        )
                        Panel.PENDING -> PendingPanel(
                            state = state,
                            onTranscribe = viewModel::transcribe,
                            onRemove = viewModel::clearPending
                        )
                        Panel.START -> StartPanel(
                            onRecord = ::record,
                            onPick = { picker.launch(arrayOf("audio/*", "video/*")) }
                        )
                    }
                }
            }

            val unsaved = job as? TranscriptionRunner.State.SaveFailed
            val failure = (job as? TranscriptionRunner.State.Failed)?.error ?: unsaved?.error
            val message = failure?.message ?: state.pickError
                ?: MIC_DENIED.takeIf { micDenied }
            if (message != null) {
                item(key = "error") {
                    ErrorCard(
                        message = message,
                        showUpgrade = failure?.isUpgradePath == true,
                        onUpgrade = { showPaywall = true },
                        onRetrySave = viewModel::retrySave.takeIf { unsaved != null },
                        onDismiss = {
                            micDenied = false
                            viewModel.dismissError()
                        }
                    )
                }
            }

            if (state.recording is AudioRecorder.State.Idle && job !is TranscriptionRunner.State.Working) {
                item(key = "options") {
                    OptionsCard(
                        state = state,
                        onLanguage = { showLanguages = true },
                        onKeyTerms = { showKeyTerms = true }
                    )
                }
                item(key = "plan") {
                    PlanNote(state = state, usesCustomEndpoint = paywallState.usesCustomEndpoint)
                }
            }

            if (state.recent.isNotEmpty()) {
                item(key = "recent-title") {
                    Text(
                        "Your transcripts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(state.recent, key = { it.id }) { summary ->
                    RecentRow(
                        summary = summary,
                        onOpen = { navigator.go(Destination.Transcript(summary.id)) },
                        onDelete = { confirmDelete = summary }
                    )
                }
            }
        }
    }

    if (showLanguages) {
        LanguageSheet(
            languages = state.languages,
            selected = state.language,
            onSelect = {
                viewModel.setLanguage(it)
                showLanguages = false
            },
            onDismiss = { showLanguages = false }
        )
    }

    if (showKeyTerms) {
        KeyTermsSheet(
            terms = state.keyTerms,
            onAdd = { viewModel.editKeyTerms(KeyTermEdit.Add(it)) },
            onRemove = { viewModel.editKeyTerms(KeyTermEdit.Remove(it)) },
            onClear = { viewModel.editKeyTerms(KeyTermEdit.Clear) },
            onDismiss = { showKeyTerms = false }
        )
    }

    confirmDelete?.let { summary ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete transcript?") },
            text = { Text("\"${summary.title}\" and its recording will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTranscript(summary.id)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    if (showPaywall) {
        PaywallSheet(
            state = paywallState,
            onDismiss = {
                showPaywall = false
                paywallViewModel.consumeMessage()
            },
            onRedeem = { key -> paywallViewModel.redeem(key) },
            onRestore = { paywallViewModel.restorePurchase() },
            onConfirmEmail = { paywallViewModel.confirmEmail() },
            onRetry = { paywallViewModel.refresh() },
            canWatchAd = paywallState.canWatchAd,
            onWatchAd = { paywallViewModel.watchAd(activity) }
        )
    }
}

private enum class Panel { START, RECORDING, WORKING, PENDING }

private const val MIC_DENIED =
    "Scanly needs microphone access to record. Allow it in Settings, or choose a file."

@Composable
private fun StartPanel(onRecord: () -> Unit, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Record, or pick a voice memo, meeting or video.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction(
                modifier = Modifier.weight(1f),
                icon = { Icon(Icons.Rounded.Mic, null, modifier = Modifier.size(30.dp)) },
                title = "Record",
                subtitle = "Keeps going with the screen off",
                onClick = onRecord
            )
            BigAction(
                modifier = Modifier.weight(1f),
                icon = { Icon(Icons.Rounded.AudioFile, null, modifier = Modifier.size(30.dp)) },
                title = "Choose file",
                subtitle = "Audio or video",
                onClick = onPick
            )
        }
    }
}

@Composable
private fun BigAction(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onPrimary
                ) { icon() }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun RecordingPanel(
    state: AudioRecorder.State.Recording,
    maxMinutes: Int,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit
) {
    // The plan's limit is enforced on upload; stopping here spares recording what can't be sent.
    val limitMs = maxMinutes * 60_000L
    LaunchedEffect(state.elapsedMs >= limitMs) {
        if (state.elapsedMs >= limitMs) onStop()
    }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
                        )
                )
                Text(
                    if (state.paused) "Paused" else "Recording",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                TranscriptFormats.clock(state.elapsedMs),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Light
            )
            LevelMeter(level = state.level)
            Text(
                "Up to $maxMinutes minutes on your plan",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(onClick = onDiscard, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Discard recording")
                }
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(76.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = "Stop and keep", modifier = Modifier.size(36.dp))
                }
                FilledTonalIconButton(
                    onClick = onTogglePause,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = if (state.paused) "Resume" else "Pause"
                    )
                }
            }
        }
    }
}

/** A row of bars that rise with the input level, so the user can see the mic is hearing them. */
@Composable
private fun LevelMeter(level: Float) {
    val animated by animateFloatAsState(targetValue = level, label = "level")
    val bars = 24
    Row(
        modifier = Modifier.height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(bars) { i ->
            // A gentle arch, scaled by the level, so the meter reads as a voice and not a bar chart.
            val shape = 0.35f + 0.65f * kotlin.math.sin(Math.PI * (i + 0.5) / bars).toFloat()
            val fraction = (0.08f + animated * 1.6f * shape).coerceIn(0.08f, 1f)
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun WorkingPanel(state: TranscriptionRunner.State.Working, onCancel: () -> Unit) {
    val (title, body) = when (state.phase) {
        TranscriptionRunner.Phase.PREPARING -> "Preparing audio" to "Compressing it for upload on this phone."
        TranscriptionRunner.Phase.UPLOADING -> "Uploading" to "Sending the recording to Scanly AI."
        TranscriptionRunner.Phase.TRANSCRIBING ->
            "Transcribing" to "Long recordings take a few minutes. You can leave this screen — " +
                "the transcript is saved here when it's done."
    }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val progress = state.progress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                state.detail ?: body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.cancellable) {
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun PendingPanel(state: TranscribeUiState, onTranscribe: () -> Unit, onRemove: () -> Unit) {
    val pending = state.pending ?: return
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (pending.source is TranscriptionRunner.Source.Recorded) {
                        Icons.Rounded.Mic
                    } else {
                        Icons.Rounded.AudioFile
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        pending.source.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (pending.probed) TranscriptFormats.clock(pending.durationMs) else "Reading file…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove")
                }
            }
            val estimate = state.estimatedCredits
            if (estimate != null) {
                val balance = state.creditsRemaining?.let { " · you have $it" }.orEmpty()
                val passes = if (state.options.passes > 1) " (auto-detect with formatting runs twice)" else ""
                Text(
                    "About $estimate ${if (estimate == 1) "credit" else "credits"}$balance$passes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onTranscribe,
                enabled = pending.probed,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Transcribe", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    showUpgrade: Boolean,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit,
    /** Set when a paid transcript is held unsaved: saving again costs nothing. */
    onRetrySave: (() -> Unit)? = null
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                Row {
                    if (showUpgrade) TextButton(onClick = onUpgrade) { Text("See plans") }
                    if (onRetrySave != null) TextButton(onClick = onRetrySave) { Text("Save again") }
                    TextButton(onClick = onDismiss) { Text(if (onRetrySave != null) "Discard" else "Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun OptionsCard(state: TranscribeUiState, onLanguage: () -> Unit, onKeyTerms: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            NavRow(
                title = "Language",
                value = state.language?.let(TranscriptFormats::languageName) ?: "Auto-detect",
                onClick = onLanguage
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            NavRow(
                title = "Key terms",
                value = if (state.keyTerms.isEmpty()) {
                    "Names and words to spell right"
                } else {
                    "${state.keyTerms.size} of ${TranscribeOptions.MAX_KEY_TERMS}"
                },
                onClick = onKeyTerms
            )
        }
    }
}

@Composable
private fun NavRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun PlanNote(state: TranscribeUiState, usesCustomEndpoint: Boolean) {
    val minutesPerCredit = state.secondsPerCredit / 60
    val lines = buildList {
        add(
            "1 credit ≈ $minutesPerCredit minutes of audio · up to ${state.maxAudioMinutes} minutes " +
                "per recording on your plan."
        )
        if (usesCustomEndpoint) {
            add("Audio to Text always runs on Scanly AI and uses your credits, even with your own endpoint on.")
        }
    }
    Text(
        lines.joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun RecentRow(summary: TranscriptSummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val facts = listOfNotNull(
                    DateUtils.getRelativeTimeSpanString(summary.createdAt).toString(),
                    TranscriptFormats.clock(summary.durationMs),
                    "${summary.speakerCount} speakers".takeIf { summary.speakerCount > 1 }
                )
                Text(
                    facts.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (summary.preview.isNotBlank()) {
                    Text(
                        summary.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete ${summary.title}")
            }
        }
    }
}

@Composable
private fun LanguageSheet(
    languages: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sorted = remember(languages) { languages.sortedBy { TranscriptFormats.languageName(it) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            "Language",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        Text(
            "Pick the main language spoken. Other languages in the recording are still transcribed " +
                "as spoken; this one decides how numbers and dates are written.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            item {
                LanguageRow(
                    title = "Auto-detect",
                    subtitle = "Finds the language itself, but runs twice, so it costs twice the credits.",
                    selected = selected == null,
                    onClick = { onSelect(null) }
                )
            }
            items(sorted) { code ->
                LanguageRow(
                    title = TranscriptFormats.languageName(code),
                    subtitle = null,
                    selected = selected == code,
                    onClick = { onSelect(code) }
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun KeyTermsSheet(
    terms: List<String>,
    onAdd: (String) -> String?,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val add = {
        note = onAdd(input)
        input = ""
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Key terms", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    "${terms.size} / ${TranscribeOptions.MAX_KEY_TERMS}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Names, product words and jargon the transcriber should spell exactly. Used for " +
                    "every recording until you remove them. Paste several separated by commas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("e.g. Sebastian, Typst, Mistral OCR") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() })
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = add, enabled = input.isNotBlank()) { Text("Add") }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                terms.forEach { term ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(term) },
                        label = { Text(term) },
                        trailingIcon = {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Remove $term",
                                modifier = Modifier.size(InputChipDefaults.IconSize)
                            )
                        }
                    )
                }
            }
            if (terms.isNotEmpty()) {
                TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) { Text("Clear all") }
            }
        }
    }
}
