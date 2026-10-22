package com.skeler.scanely.docchat.data

import android.util.Log
import com.skeler.scanely.core.network.backendError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChatFileRestorer"

/**
 * Brings back a file this phone does not have — a chat restored from the server, or a PDF the
 * cache let go — by asking the Worker to recompile the kept design. Free, but it is a request on
 * the user's allowance and a compile on ours, so one runs at a time and only when needed.
 */
@Singleton
class ChatFileRestorer @Inject constructor(
    private val api: ChatSyncApi,
    private val files: ChatFiles,
    private val repository: ChatRepository
) {
    private val mutex = Mutex()
    private val _restored = MutableStateFlow<Set<String>>(emptySet())

    /** Design ids restored in this process, so a card showing a placeholder can redraw. */
    val restored: StateFlow<Set<String>> = _restored.asStateFlow()

    /** The local file, fetching it if needed. Null if it cannot be had; an expired design is marked so. */
    suspend fun ensure(messageId: String, body: FileBody): File? = mutex.withLock {
        files.cachedFile(body.designId, body.format)?.let { return@withLock it }
        if (body.expired) return@withLock null
        try {
            val response = fetch(body)
            if (!response.isSuccessful) {
                val error = HttpException(response).backendError()
                if (error?.code == EXPIRED) repository.update(messageId, body.copy(expired = true))
                return@withLock null
            }
            val bytes = response.body()?.use { it.bytes() } ?: return@withLock null
            val file = files.write(body.designId, body.format, bytes)
            thumbnail(body, file)
            _restored.update { it + body.designId }
            file
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Restore failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun thumbnail(body: FileBody, file: File) {
        if (body.format == DocFormat.PDF) files.makeThumbnail(body.designId, file)
    }

    /** An agent's file is kept as it was delivered; a designer's is recompiled from its source. */
    private suspend fun fetch(body: FileBody) =
        body.agentFileId?.let { api.agentFile(it) } ?: api.designPdf(body.designId)

    private companion object {
        const val EXPIRED = "design_expired"
    }
}
