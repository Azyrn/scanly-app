package com.skeler.scanely.docchat.presentation

import android.net.Uri
import com.skeler.scanely.R
import com.skeler.scanely.core.ai.PickedDocument
import com.skeler.scanely.ui.viewmodel.DesignOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The bytes a send takes with it; null where nothing was attached. */
internal class TakenAttachments(val document: String?, val photo: String?)

/**
 * The composer's document and photo for [DocChatViewModel]: read and encoded off the main thread,
 * with their progress in [composer]. The bytes stay here, out of UI state: megabytes of base64
 * have no business being diffed every frame. A newer pick or a removal outdates a read in flight.
 */
internal class ComposerAttachments(
    private val scope: CoroutineScope,
    private val operations: DesignOperations,
    private val composer: MutableStateFlow<ComposerUi>
) {
    private var documentBase64: String? = null
    private var photoBase64: String? = null
    private var documentJob: Job? = null
    private var photoJob: Job? = null
    private var documentVersion = 0L
    private var photoVersion = 0L

    /** The read in flight for a picture given the role to rebuild ([rebuild]) or to place. */
    fun job(rebuild: Boolean): Job? = if (rebuild) documentJob else photoJob

    fun attachDocument(uri: Uri) {
        val version = ++documentVersion
        documentJob?.cancel()
        documentBase64 = null
        composer.update {
            it.copy(
                document = AttachedDocument(name = "", isReading = true, uri = uri.toString()),
                scan = null,
                attachError = null
            )
        }
        documentJob = scope.launch { readDocument(uri, version) }
    }

    /** Reads the picked file: a PDF as it is, a picture normalised. The assistant reads it itself; nothing extracts its text here. */
    private suspend fun readDocument(uri: Uri, version: Long) {
        val name = operations.displayName(uri)
        composer.update { it.copy(document = it.document?.copy(name = name)) }
        val picked = try {
            operations.document(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (version != documentVersion) return
        if (picked !is PickedDocument.Ready) {
            composer.update { it.copy(document = null, attachError = picked.refusal()) }
            return
        }
        documentBase64 = picked.base64
        composer.update { it.copy(document = it.document?.copy(name = name, pages = picked.pages, isReading = false)) }
    }

    fun removeDocument() {
        documentVersion++
        documentJob?.cancel()
        documentBase64 = null
        composer.update { it.copy(document = null) }
    }

    fun attachPhoto(uri: Uri) {
        val version = ++photoVersion
        photoJob?.cancel()
        photoBase64 = null
        composer.update {
            it.copy(photo = AttachedPhoto(name = "", isPreparing = true, uri = uri.toString()), attachError = null)
        }
        photoJob = scope.launch {
            val name = operations.displayName(uri)
            val encoded = try {
                operations.photo(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (version != photoVersion) return@launch
            photoBase64 = encoded
            composer.update {
                it.copy(
                    photo = encoded?.let { _ -> it.photo?.copy(name = name, isPreparing = false) },
                    attachError = if (encoded == null) AttachError(R.string.chat_photo_unusable) else it.attachError
                )
            }
        }
    }

    fun removePhoto() {
        photoVersion++
        photoJob?.cancel()
        photoBase64 = null
        composer.update { it.copy(photo = null) }
    }

    /** What [draft] sends of the attached bytes; they are handed over and forgotten here. */
    fun take(draft: ComposerUi): TakenAttachments {
        val taken = TakenAttachments(
            document = documentBase64.takeIf { draft.document != null },
            photo = photoBase64.takeIf { draft.photo != null }
        )
        documentBase64 = null
        photoBase64 = null
        return taken
    }
}

private fun PickedDocument?.refusal(): AttachError = when (this) {
    is PickedDocument.TooManyPages -> AttachError(R.string.chat_file_too_many_pages, listOf(limit, pages))
    PickedDocument.TooLarge -> AttachError(R.string.chat_file_too_large)
    else -> AttachError(R.string.chat_file_unreadable)
}
