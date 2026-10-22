package com.skeler.scanely.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Max images/files accepted per AI scan (matches the per-file 20 MB cap in PayloadFactory). */
const val MAX_AI_FILES = 3

/** Everything the AI document flow accepts: PDF, Office formats OCR reads natively, plain text. */
val AI_DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.presentation",
    "text/plain"
)

@Suppress("ComposableNaming") // Returns a lambda, not a Unit-returning composable
@Composable
fun rememberGalleryPicker(
    onImageSelected: (Uri?) -> Unit
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        onImageSelected(uri)
    }

    return {
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
}

@Suppress("ComposableNaming")
@Composable
fun rememberMultiGalleryPicker(
    // Null = no app cap; the photo picker still enforces the system's own per-pick maximum.
    maxItems: Int? = MAX_AI_FILES,
    onImagesSelected: (List<Uri>) -> Unit
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = maxItems?.let { ActivityResultContracts.PickMultipleVisualMedia(it) }
            ?: ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        onImagesSelected(uris)
    }

    return {
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
}

@Suppress("ComposableNaming")
@Composable
fun rememberMultiDocumentPicker(
    mimeTypes: Array<String> = AI_DOCUMENT_MIME_TYPES,
    onDocumentsSelected: (List<Uri>) -> Unit
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = OpenMultipleContents()
    ) { uris ->
        onDocumentsSelected(uris)
    }

    return { launcher.launch(mimeTypes) }
}

/**
 * ACTION_GET_CONTENT, deliberately, where [ActivityResultContracts.OpenDocument] looks obvious.
 *
 * OPEN_DOCUMENT lists only apps implementing a DocumentsProvider, which most gallery apps do not.
 * The file the user wants is often instantly findable in their gallery and not at all in a folder
 * tree, and GET_CONTENT lists every app that can hand over a file of the right type.
 *
 * The trade is task-scoped read access, never persistable. Nothing here depends on persistence:
 * every picked file is copied into app storage before the grant can lapse.
 */
private fun contentIntent(mimeTypes: Array<String>): Intent =
    Intent(Intent.ACTION_GET_CONTENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        // A single concrete type filters the app list better than "*/*" plus EXTRA_MIME_TYPES,
        // which some pickers ignore entirely.
        .setType(mimeTypes.singleOrNull() ?: "*/*")
        .apply {
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }

class OpenContent : ActivityResultContract<Array<String>, Uri?>() {

    override fun createIntent(context: Context, input: Array<String>): Intent =
        contentIntent(input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

class OpenMultipleContents : ActivityResultContract<Array<String>, List<Uri>>() {

    override fun createIntent(context: Context, input: Array<String>): Intent =
        contentIntent(input).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        val data = intent.takeIf { resultCode == Activity.RESULT_OK } ?: return emptyList()

        // A multi-select arrives in the clip data; a single pick from the same picker arrives in
        // getData(), so both have to be read or picking one file returns nothing.
        val clip = data.clipData
        if (clip != null) {
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.distinct()
        }
        return listOfNotNull(data.data)
    }
}
