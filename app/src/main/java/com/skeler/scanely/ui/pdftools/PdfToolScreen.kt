@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.ui.pdftools

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeler.scanely.R
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.ToolOutput
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.components.OpenContent
import com.skeler.scanely.ui.components.OpenMultipleContents
import com.skeler.scanely.ui.components.rememberStorageAccess
import kotlinx.coroutines.launch

private const val TAG = "PdfToolScreen"

/**
 * Runs [tool] through its [ToolSpec]: pick, open, choose options, run, then save, share or open.
 * Nothing here knows any one tool; the spec supplies what to pick, the options and the job.
 */
@Composable
fun PdfToolScreen(tool: PdfTool) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val navigator = LocalNavigator.current
    val vm: PdfToolViewModel = hiltViewModel()
    LaunchedEffect(vm, tool) { vm.start(tool) }
    val spec = remember(vm, tool) { vm.specFor(tool) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val withStorageAccess = rememberStorageAccess()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // start() again before the result: a picker result delivered to a ViewModel rebuilt after
    // process death can arrive before the LaunchedEffect above has run.
    val singlePicker = rememberLauncherForActivityResult(OpenContent()) { uri ->
        vm.start(tool)
        vm.onPicked(listOfNotNull(uri))
    }
    val multiPicker = rememberLauncherForActivityResult(OpenMultipleContents()) { uris ->
        vm.start(tool)
        vm.onPicked(uris)
    }

    // The ViewModel decides when the picker opens (entry, "Add files", "Choose another file", a
    // declined password) and remembers across process death that it is already open, so it never
    // opens twice.
    LaunchedEffect(state.launchPicker, spec) {
        if (state.launchPicker && spec != null) {
            vm.onPickerLaunched()
            val launcher = if (spec.input.multiple) multiPicker else singlePicker
            launcher.launch(spec.input.mimeTypes)
        }
    }

    LaunchedEffect(state.exit, spec) {
        if (state.exit || spec == null) navigator.backWhenSettled()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        vm.consumeMessage()
        snackbarHostState.showSnackbar(resources.getString(message))
    }

    val stage = state.stage
    BackHandler(enabled = stage is ToolStage.Running || stage is ToolStage.Importing) { vm.cancel() }

    fun showMessage(message: Int) {
        scope.launch { snackbarHostState.showSnackbar(resources.getString(message)) }
    }

    fun outputUri(index: Int, output: ToolOutput): Uri? =
        state.savedUris.getOrNull(index) ?: context.cacheUri(output)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(tool.labelRes),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.pdf_tools_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (stage) {
                ToolStage.Picking, is ToolStage.Password -> Unit
                is ToolStage.Importing -> ProgressContent(
                    progress = stage.progress,
                    label = stringResource(R.string.pdf_tool_reading, stage.fileName),
                    onCancel = vm::cancel
                )
                is ToolStage.Running -> ProgressContent(
                    progress = stage.progress,
                    label = runningLabel(state),
                    onCancel = vm::cancel
                )
                ToolStage.Options -> if (spec != null) {
                    val optionsState = remember(state.documents, state.files) { vm.optionsState(state) }
                    ScrollingContent {
                        Column(verticalArrangement = Arrangement.spacedBy(OPTIONS_SECTION_GAP)) {
                            InputsSection(spec = spec, options = optionsState)
                            spec.Options(state = optionsState, onRun = vm::run)
                        }
                    }
                }
                is ToolStage.Done -> ScrollingContent {
                    val outputs = stage.result.outputs
                    ToolDoneContent(
                        result = stage.result,
                        isSaved = state.savedUris.isNotEmpty(),
                        isSaving = state.isSaving,
                        onSave = { withStorageAccess(vm::save) },
                        onShare = {
                            val uris = outputs.mapIndexedNotNull { index, output -> outputUri(index, output) }
                            val shared = when {
                                uris.isEmpty() || uris.size < outputs.size -> false
                                uris.size == 1 -> ScanExporter.shareDocument(context, uris[0], outputs[0].mimeType)
                                else -> ScanExporter.shareDocuments(context, uris, sharedMimeType(outputs))
                            }
                            if (!shared) showMessage(R.string.pdf_tool_no_viewer)
                        },
                        onOpen = { index ->
                            val output = outputs.getOrNull(index) ?: return@ToolDoneContent
                            val uri = outputUri(index, output)
                            val opened = uri != null && ScanExporter.openDocument(
                                context,
                                uri,
                                output.mimeType,
                                resources.getString(R.string.pdf_tool_open_with)
                            )
                            if (!opened) showMessage(R.string.pdf_tool_no_viewer)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is ToolStage.Error -> ScrollingContent {
                    ToolErrorContent(
                        failure = stage.failure,
                        canRetry = state.inputCount > 0 && stage.failure.isRetryable,
                        onRetry = vm::retry,
                        onPickAnother = vm::pickAnother,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (stage is ToolStage.Password) {
        ToolPasswordDialog(
            fileName = stage.fileName,
            isRetry = stage.isRetry,
            onConfirm = vm::submitPassword,
            onDismiss = vm::dismissPassword
        )
    }
}

@Composable
private fun runningLabel(state: PdfToolUiState): String {
    val count = state.inputCount
    return if (count > 1) {
        pluralStringResource(R.plurals.pdf_tool_flow_working_files, count, count)
    } else {
        val name = state.documents.firstOrNull()?.displayName ?: state.files.firstOrNull()?.displayName
        stringResource(R.string.pdf_tool_working, name.orEmpty())
    }
}

/**
 * A summary card per input. Multi-file tools get remove and reorder controls on each card, an
 * "Add files" button, and a hint while there are too few inputs for the spec's button to work.
 */
@Composable
private fun InputsSection(spec: ToolSpec, options: ToolOptionsState) {
    val multiple = spec.input.multiple
    val count = options.documents.size + options.files.size
    Column(verticalArrangement = Arrangement.spacedBy(INPUT_GAP)) {
        options.documents.forEachIndexed { index, document ->
            FileSummaryCard(
                document = document,
                actions = if (multiple) {
                    { InputActions(document.displayName, index, count, options) }
                } else {
                    null
                }
            )
        }
        options.files.forEachIndexed { index, file ->
            FileSummaryCard(
                file = file,
                actions = if (multiple) {
                    { InputActions(file.displayName, index, count, options) }
                } else {
                    null
                }
            )
        }
        if (multiple) {
            OutlinedButton(onClick = options.onAddFiles, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(BUTTON_ICON_GAP))
                Text(stringResource(R.string.pdf_tool_flow_add_files))
            }
        }
        if (count < spec.minInputs) {
            Text(
                text = pluralStringResource(R.plurals.pdf_tool_flow_min_inputs, spec.minInputs, spec.minInputs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InputActions(name: String, index: Int, count: Int, options: ToolOptionsState) {
    IconButton(onClick = { options.onMove(index, index - 1) }, enabled = index > 0) {
        Icon(
            Icons.Rounded.KeyboardArrowUp,
            contentDescription = stringResource(R.string.pdf_tool_flow_move_up, name)
        )
    }
    IconButton(onClick = { options.onMove(index, index + 1) }, enabled = index < count - 1) {
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            contentDescription = stringResource(R.string.pdf_tool_flow_move_down, name)
        )
    }
    IconButton(onClick = { options.onRemove(index) }) {
        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.pdf_tool_flow_remove, name))
    }
}

/** The one type that covers every output, so the share sheet offers apps that take all of them. */
private fun sharedMimeType(outputs: List<ToolOutput>): String {
    val types = outputs.map { it.mimeType }.distinct()
    types.singleOrNull()?.let { return it }
    val family = types.map { it.substringBefore('/') }.distinct().singleOrNull()
    return if (family != null) "$family/*" else "*/*"
}

@Composable
private fun ProgressContent(progress: Float?, label: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CONTENT_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (progress != null) {
            CircularProgressIndicator(progress = { progress })
        } else {
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(PROGRESS_GAP))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(PROGRESS_GAP))
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.pdf_tool_cancel))
        }
    }
}

@Composable
private fun ToolPasswordDialog(
    fileName: String,
    isRetry: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Keyed on the attempt, so a wrong password clears the box for the next try.
    var password by remember(isRetry) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_tool_password_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (isRetry) R.string.pdf_tool_password_retry else R.string.pdf_tool_password_body,
                        fileName
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRetry) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(DIALOG_GAP))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.pdf_tool_password_label),
                    isError = isRetry
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.pdf_tool_password_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pdf_tool_cancel)) }
        }
    )
}

/** Options, results and errors scroll, and stay a readable width on tablets. */
@Composable
private fun ScrollingContent(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(CONTENT_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth()) { content() }
    }
}

/** An unsaved output, served from the cache through the app's FileProvider. */
private fun Context.cacheUri(output: ToolOutput): Uri? = try {
    FileProvider.getUriForFile(this, "$packageName.fileprovider", output.file)
} catch (e: IllegalArgumentException) {
    Log.w(TAG, "No FileProvider path covers the tool output", e)
    null
}

private val CONTENT_PADDING = 20.dp
private val CONTENT_MAX_WIDTH = 560.dp
private val PROGRESS_GAP = 20.dp
private val DIALOG_GAP = 12.dp
private val INPUT_GAP = 8.dp
private val BUTTON_ICON_GAP = 8.dp
