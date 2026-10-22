package com.skeler.scanely.ui.pdftools

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.R
import com.skeler.scanely.core.common.UriNames
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.pdftools.ImportedFile
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolEngine
import com.skeler.scanely.core.pdftools.ProbeResult
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private const val TAG = "PdfToolViewModel"

/** Survives process death, so a picker result that outlives the process isn't asked for twice. */
private const val KEY_AWAITING_PICK = "awaiting_pick"

sealed interface ToolStage {
    data object Picking : ToolStage
    data class Importing(val progress: Float?, val fileName: String) : ToolStage
    data class Password(val fileName: String, val isRetry: Boolean) : ToolStage
    data object Options : ToolStage
    data class Running(val progress: Float?) : ToolStage
    data class Done(val result: ToolResult) : ToolStage
    data class Error(val failure: ToolFailure) : ToolStage
}

data class PdfToolUiState(
    val tool: PdfTool? = null,
    val stage: ToolStage = ToolStage.Picking,
    /** Opened PDF inputs, in the user's order. */
    val documents: List<ToolDocument> = emptyList(),
    /** Every other input, in the user's order. A tool uses one list or the other, never both. */
    val files: List<ImportedFile> = emptyList(),
    val savedUris: List<Uri> = emptyList(),
    val isSaving: Boolean = false,
    @StringRes val message: Int? = null,
    /** True while the screen should open the file picker; cleared by [PdfToolViewModel.onPickerLaunched]. */
    val launchPicker: Boolean = false,
    val exit: Boolean = false
) {
    val inputCount: Int get() = documents.size + files.size
}

/**
 * Runs any one-shot tool from the PDF tools grid through its [ToolSpec]: import, unlock if needed,
 * options, run, save. One run directory holds every input and output of a session, kept until the
 * user starts over or the screen goes away, so retries reuse the imported copies instead of reading
 * the provider again.
 */
// One function per thing the user can do on the screen, plus the job plumbing they share.
@Suppress("TooManyFunctions")
@HiltViewModel
class PdfToolViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val workspace: ToolWorkspace,
    private val engine: PdfToolEngine,
    private val specs: Map<PdfTool, @JvmSuppressWildcards ToolSpec>,
    private val savedState: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PdfToolUiState(launchPicker = savedState.get<Boolean>(KEY_AWAITING_PICK) != true)
    )
    val uiState: StateFlow<PdfToolUiState> = _uiState.asStateFlow()

    private var spec: ToolSpec? = null
    private var run: File? = null
    private var activeJob: Job? = null

    /** Set by [addFiles], so the next pick joins the inputs instead of replacing them. */
    private var appending = false

    /** The answer to the password prompt an import is waiting on; null means the file was declined. */
    private var passwordAnswer: CompletableDeferred<String?>? = null

    /**
     * Bumped whenever a job is abandoned. qpdf can't be interrupted mid-write, so a cancelled job
     * may still return; its answer is only applied while its generation is current.
     */
    @Volatile
    private var generation = 0

    fun specFor(tool: PdfTool): ToolSpec? = specs[tool]

    fun start(tool: PdfTool) {
        if (spec != null) return
        spec = specs[tool]
        _uiState.update { it.copy(tool = tool, exit = it.exit || spec == null) }
    }

    /** The options screen's view of the inputs, with the callbacks a multi-file tool needs. */
    fun optionsState(state: PdfToolUiState): ToolOptionsState =
        ToolOptionsState(state.documents, state.files, ::addFiles, ::remove, ::move)

    fun onPickerLaunched() {
        savedState[KEY_AWAITING_PICK] = true
        _uiState.update { it.copy(launchPicker = false) }
    }

    fun onPicked(uris: List<Uri>) {
        savedState[KEY_AWAITING_PICK] = false
        val spec = spec ?: return
        val append = appending && _uiState.value.inputCount > 0
        appending = false
        if (uris.isEmpty()) {
            _uiState.update { it.afterEmptyPick() }
            return
        }
        if (!append) {
            releaseRun()
            _uiState.update { it.withoutInputs() }
        }
        val dir = runDir()
        val picked = if (spec.input.multiple) uris else uris.take(1)
        _uiState.update { it.copy(stage = ToolStage.Importing(null, "")) }
        launchJob { gen ->
            picked.forEachIndexed { index, uri -> importOne(gen, spec, dir, uri, index, picked.size) }
            if (gen != generation) return@launchJob
            if (_uiState.value.inputCount == 0) pickAnother() else inputsReady(spec)
        }
    }

    fun submitPassword(password: String) {
        if (_uiState.value.stage !is ToolStage.Password) return
        passwordAnswer?.complete(password)
    }

    /**
     * Declining the password means this file can't be used. A single-file tool opens the picker
     * again; a multi-file tool just leaves this one out.
     */
    fun dismissPassword() {
        if (_uiState.value.stage !is ToolStage.Password) return
        if (spec?.input?.multiple == true) passwordAnswer?.complete(null) else pickAnother()
    }

    fun addFiles() {
        val stage = _uiState.value.stage
        if (spec?.input?.multiple != true || stage != ToolStage.Options) return
        appending = true
        savedState[KEY_AWAITING_PICK] = false
        _uiState.update { it.copy(launchPicker = true) }
    }

    /** [index] counts in whichever list this tool's inputs live in. */
    fun remove(index: Int) {
        if (_uiState.value.stage != ToolStage.Options) return
        _uiState.update { it.removingInput(index) }
    }

    fun move(from: Int, to: Int) {
        if (_uiState.value.stage != ToolStage.Options) return
        _uiState.update { it.withInputMoved(from, to) }
    }

    /** Runs the spec's job with [options] exactly as its options screen built them. */
    fun run(options: Any) {
        val spec = spec ?: return
        val state = _uiState.value
        val dir = run ?: return
        if (state.stage is ToolStage.Running || state.stage is ToolStage.Importing) return
        if (state.inputCount < spec.minInputs) return
        _uiState.update {
            it.copy(stage = ToolStage.Running(null), savedUris = emptyList(), isSaving = false)
        }
        launchJob { gen ->
            val input = ToolJobInput(state.documents, state.files, dir) { progress ->
                // Jobs report from whatever thread they work on; only a live run's progress counts.
                _uiState.update {
                    if (gen == generation && it.stage is ToolStage.Running) {
                        it.copy(stage = ToolStage.Running(progress?.coerceIn(0f, 1f)))
                    } else {
                        it
                    }
                }
            }
            val result = spec.run(input, options)
            setStage(gen, ToolStage.Done(result))
        }
    }

    fun cancel() {
        val stage = _uiState.value.stage
        if (stage !is ToolStage.Importing && stage !is ToolStage.Running) return
        abandonJob()
        if (_uiState.value.inputCount > 0) {
            _uiState.update { it.copy(stage = ToolStage.Options, message = R.string.pdf_tool_cancelled) }
        } else {
            // Nothing was opened yet, so there is nothing to go back to on this screen.
            _uiState.update { it.copy(exit = true, message = R.string.pdf_tool_cancelled) }
        }
    }

    fun save() {
        val state = _uiState.value
        val done = state.stage as? ToolStage.Done ?: return
        if (state.isSaving || state.savedUris.isNotEmpty()) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val uris = withContext(Dispatchers.IO) {
                    done.result.outputs.map { output ->
                        ScanExporter.exportFile(appContext, output.file, output.fileName, output.mimeType)
                    }
                }
                _uiState.update {
                    it.copy(isSaving = false, savedUris = uris, message = R.string.pdf_tool_saved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving tool output failed", e)
                _uiState.update { it.copy(isSaving = false, message = R.string.pdf_tool_save_failed) }
            }
        }
    }

    fun retry() {
        val state = _uiState.value
        if (state.stage !is ToolStage.Error) return
        when {
            state.inputCount == 0 -> pickAnother()
            // A tool with no options has no options screen to return to; it simply runs again.
            spec?.runsImmediately == true -> {
                _uiState.update { it.copy(stage = ToolStage.Options) }
                run(Unit)
            }
            else -> _uiState.update { it.copy(stage = ToolStage.Options) }
        }
    }

    fun pickAnother() {
        abandonJob()
        releaseRun()
        appending = false
        savedState[KEY_AWAITING_PICK] = false
        _uiState.update { it.picking() }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        generation++
        releaseRun()
    }

    /**
     * Copies one picked file into [dir] and, for a PDF tool, opens it — asking for its password
     * as often as it takes. Each input joins the state as soon as it is ready, so a cancel keeps
     * the ones already done.
     */
    private suspend fun importOne(gen: Int, spec: ToolSpec, dir: File, uri: Uri, index: Int, count: Int) {
        val file = copyIn(gen, spec, dir, uri, index, count) ?: return
        when {
            gen != generation -> Unit
            !spec.input.isPdf || !spec.probesInputs -> _uiState.update { it.copy(files = it.files + file) }
            else -> openOrSkip(gen, spec, file)?.let { document ->
                if (gen == generation) _uiState.update { it.copy(documents = it.documents + document) }
            }
        }
    }

    /** Progress covers the whole batch, so the bar never runs back to zero between files. */
    private suspend fun copyIn(
        gen: Int,
        spec: ToolSpec,
        dir: File,
        uri: Uri,
        index: Int,
        count: Int
    ): ImportedFile? {
        val name = withContext(Dispatchers.IO) { UriNames.displayName(appContext, uri) }
            ?: uri.lastPathSegment.orEmpty()
        val overall = { progress: Float? -> batchProgress(progress, index, count) }
        setStage(gen, ToolStage.Importing(overall(0f), name))
        return try {
            workspace.import(uri, dir) { progress -> setStage(gen, ToolStage.Importing(overall(progress), name)) }
        } catch (e: ToolException) {
            skipOrThrow(spec, e, ToolFailure.Unreadable)
            null
        }
    }

    /** The opened document, or null when the user declined its password or it was skipped. */
    private suspend fun openOrSkip(gen: Int, spec: ToolSpec, file: ImportedFile): ToolDocument? {
        val document = try {
            open(gen, file)
        } catch (e: ToolException) {
            file.file.delete()
            skipOrThrow(spec, e, ToolFailure.Damaged)
            null
        }
        if (document == null) file.file.delete()
        return document
    }

    /** One bad file in a batch shouldn't cost the user the rest of it; a single-file tool stops. */
    private fun skipOrThrow(spec: ToolSpec, e: ToolException, skippable: ToolFailure) {
        if (!spec.input.multiple || e.failure != skippable) throw e
        _uiState.update { it.copy(message = R.string.pdf_tool_flow_skipped) }
    }

    /** Probes [file] until it opens; null when the user declines the password prompt. */
    private suspend fun open(gen: Int, file: ImportedFile): ToolDocument? {
        var password = ""
        while (true) {
            when (val result = probe(file, password)) {
                is ProbeResult.Opened -> return result.document
                ProbeResult.NeedsPassword -> {
                    val answer = CompletableDeferred<String?>()
                    passwordAnswer = answer
                    setStage(gen, ToolStage.Password(file.displayName, isRetry = password.isNotEmpty()))
                    val typed = answer.await()
                    passwordAnswer = null
                    password = typed ?: return null
                    setStage(gen, ToolStage.Importing(null, file.displayName))
                }
            }
        }
    }

    private suspend fun probe(file: ImportedFile, password: String): ProbeResult = try {
        engine.probe(file, password)
    } catch (e: ToolException) {
        if (e.failure == ToolFailure.WrongPassword && password.isNotEmpty()) ProbeResult.NeedsPassword else throw e
    }

    private fun inputsReady(spec: ToolSpec) {
        _uiState.update { it.copy(stage = ToolStage.Options) }
        if (spec.runsImmediately && _uiState.value.inputCount >= spec.minInputs) run(Unit)
    }

    /** Runs [block] as the one active job, turning every failure into an error stage. */
    private fun launchJob(block: suspend (gen: Int) -> Unit) {
        activeJob?.cancel()
        val gen = ++generation
        activeJob = viewModelScope.launch {
            try {
                block(gen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ToolException) {
                setStage(gen, ToolStage.Error(e.failure))
            } catch (e: Exception) {
                Log.w(TAG, "Tool job failed", e)
                setStage(gen, ToolStage.Error(ToolFailure.Failed(e.message)))
            }
        }
    }

    private fun abandonJob() {
        generation++
        activeJob?.cancel()
        activeJob = null
        passwordAnswer?.cancel()
        passwordAnswer = null
    }

    private fun setStage(gen: Int, stage: ToolStage) {
        if (gen == generation) _uiState.update { it.copy(stage = stage) }
    }

    private fun runDir(): File = run ?: workspace.newRun().also { run = it }

    private fun releaseRun() {
        run?.let(workspace::release)
        run = null
    }
}

/** A pick that came back empty: back to the options, or out when there is nothing to go back to. */
private fun PdfToolUiState.afterEmptyPick(): PdfToolUiState =
    if (inputCount == 0) copy(exit = true) else copy(stage = ToolStage.Options)

private fun PdfToolUiState.withoutInputs(): PdfToolUiState =
    copy(documents = emptyList(), files = emptyList(), savedUris = emptyList())

/** Back to an empty picker, with the picker asked for. */
private fun PdfToolUiState.picking(): PdfToolUiState = copy(
    stage = ToolStage.Picking,
    documents = emptyList(),
    files = emptyList(),
    savedUris = emptyList(),
    isSaving = false,
    launchPicker = true
)

/** Drops input [index] from whichever list holds this tool's inputs, deleting its copy. */
private fun PdfToolUiState.removingInput(index: Int): PdfToolUiState =
    if (documents.isNotEmpty()) {
        documents.getOrNull(index)?.file?.delete()
        copy(documents = documents.filterIndexed { i, _ -> i != index })
    } else {
        files.getOrNull(index)?.file?.delete()
        copy(files = files.filterIndexed { i, _ -> i != index })
    }

private fun PdfToolUiState.withInputMoved(from: Int, to: Int): PdfToolUiState =
    if (documents.isNotEmpty()) {
        copy(documents = documents.moved(from, to))
    } else {
        copy(files = files.moved(from, to))
    }

/** One file's progress as a share of the whole batch, so the bar never runs back to zero. */
private fun batchProgress(progress: Float?, index: Int, count: Int): Float? =
    if (count == 1) progress else (index + (progress ?: 0f)) / count

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
