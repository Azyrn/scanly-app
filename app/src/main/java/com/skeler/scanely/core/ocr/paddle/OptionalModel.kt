package com.skeler.scanely.core.ocr.paddle

import android.content.Context
import android.util.Log
import com.skeler.scanely.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** An opt-in model that is downloaded rather than bundled, kept at one file once verified. */
class OptionalModel internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val downloader: HfModelDownloader,
    private val repo: String,
    /** How download failures are logged, e.g. "UVDoc". */
    private val logName: String,
    fileProvider: () -> File
) {
    // context.filesDir stats/creates the dir on every call; lazy keeps the
    // constructor free of disk I/O (eager Hilt injection lands on main).
    private val file by lazy(fileProvider)

    private val _state = MutableStateFlow<PackState>(PackState.Missing)
    val state: StateFlow<PackState> = _state.asStateFlow()

    fun isInstalled(): Boolean = file.exists()

    fun bytes(): ByteArray = file.readBytes()

    /** Marks a model already on disk installed, unless a download has set the state since. */
    internal fun refresh() {
        if (isInstalled() && _state.value is PackState.Missing) {
            _state.value = PackState.Installed
        }
    }

    /**
     * Runs on the store's own scope, so leaving the settings screen doesn't cancel a download
     * half-way. [onInstalled] runs only once the model is verified and on disk.
     */
    fun downloadAsync(onInstalled: suspend () -> Unit = {}) {
        scope.launch { if (download().isSuccess) onInstalled() }
    }

    suspend fun download(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext Result.success(Unit)
        // A second tap must not start a second writer on the same file, nor report success
        // for a download it didn't make.
        if (_state.value is PackState.Downloading) {
            return@withContext Result.failure(IllegalStateException("Already downloading"))
        }
        _state.value = PackState.Downloading(0f)
        runCatching {
            downloader.downloadVerified(repo, file) {
                _state.value = PackState.Downloading(it)
            }
            _state.value = PackState.Installed
        }.onFailure { e ->
            Log.e(TAG, "$logName download failed", e)
            file.delete()
            _state.value = PackState.Failed(e.message ?: context.getString(R.string.ocr_err_download))
        }.map { }
    }

    fun delete() {
        file.delete()
        _state.value = PackState.Missing
    }

    private companion object {
        const val TAG = "PaddleModelStore"
    }
}
