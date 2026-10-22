@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.docchat.presentation.screen

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.skeler.scanely.R
import com.skeler.scanely.core.scan.DocumentScannerManager
import com.skeler.scanely.docchat.presentation.AttachError
import com.skeler.scanely.docchat.presentation.ComposerUi
import com.skeler.scanely.docchat.presentation.DocChatUiState
import com.skeler.scanely.docchat.presentation.DocChatViewModel
import com.skeler.scanely.ui.viewmodel.CONTENT_PRESETS
import com.skeler.scanely.ui.viewmodel.PaywallState
import java.io.File

private enum class ComposerSheet { SCANS }

/**
 * The bottom of the chat: what is attached, what the next message will change, the field and the
 * send (or stop) button, in one container. Everything here is about the message not yet sent.
 */
@Composable
internal fun ChatComposer(
    state: DocChatUiState,
    paywall: PaywallState,
    viewModel: DocChatViewModel,
    attaching: Boolean,
    focusRequester: FocusRequester,
    onAttaching: (Boolean) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sheet by remember { mutableStateOf<ComposerSheet?>(null) }
    val pickers = rememberAttachPickers(viewModel)
    val composer = state.composer
    val haptics = LocalHapticFeedback.current
    // Read only by the field, send and the caption, so a keystroke recomposes nothing else.
    val text = viewModel.composerText.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
    ) {
        ComposerSuggestions(composer, viewModel)
        Surface(
            shape = RoundedCornerShape(24.dp),
            // Opaque: see-through, a message behind it read through the draft (2026-10-16).
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.animateContentSize(ChatMotion.size())) {
                Attachments(composer, viewModel)
                ComposerField(
                    text = text,
                    hint = if (state.revision != null) {
                        R.string.chat_composer_hint_revise
                    } else {
                        R.string.chat_composer_hint
                    },
                    onText = viewModel::updateText,
                    focusRequester = focusRequester
                )
                // Two controls only, each a 48dp target inside the container's 8dp padding: nothing
                // in this row can push send out of view.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                ) {
                    AttachButton(enabled = state.workingReplyId == null, onClick = { onAttaching(true) })
                    Spacer(Modifier.weight(1f))
                    SendButton(
                        working = state.workingReplyId != null,
                        canSend = { state.canSend(text.value) },
                        onSend = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSend()
                        },
                        onStop = viewModel::stop
                    )
                }
            }
        }
        CostCaption(paywall, agent = state.nextGoesToAgent, emptyChat = state.messages.isEmpty())
    }

    if (attaching) {
        AttachSheet(
            onDismiss = { onAttaching(false) },
            onCamera = pickers.camera,
            onPhotos = pickers.photos,
            onFiles = pickers.files,
            onScans = { sheet = ComposerSheet.SCANS }
        )
    }
    when (sheet) {
        ComposerSheet.SCANS -> ScansSheet(
            scans = state.recentScans,
            onPick = {
                viewModel.attachScan(it)
                sheet = null
            },
            onDismiss = { sheet = null }
        )
        null -> Unit
    }
}

/** The field itself: no box of its own, one line when empty, growing to [MAX_LINES] and then scrolling. */
@Composable
private fun ComposerField(
    text: State<String>,
    @StringRes hint: Int,
    onText: (String) -> Unit,
    focusRequester: FocusRequester
) {
    val colors = MaterialTheme.colorScheme
    // The field keeps its own text (no round trip through the view model per keystroke) and
    // reports changes; text the app sets — an example, a cleared field after send — flows back in.
    val field = rememberTextFieldState(text.value)
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect { if (it != text.value) onText(it) }
    }
    LaunchedEffect(field) {
        snapshotFlow { text.value }.collect { if (it != field.text.toString()) field.setTextAndPlaceCursorAtEnd(it) }
    }
    BasicTextField(
        state = field,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = MAX_LINES),
        decorator = { inner ->
            Box {
                if (field.text.isEmpty()) {
                    Text(
                        stringResource(hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)
            .focusRequester(focusRequester)
    )
}

@Composable
private fun AttachButton(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.chat_attach)
    ComposerControl(
        label = label,
        enabled = enabled,
        container = MaterialTheme.colorScheme.surfaceContainerHighest,
        onClick = onClick
    ) {
        Icon(
            painterResource(R.drawable.ic_sym_add),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ICON_SIZE)
        )
    }
}

/** A 48dp touch target holding a 40dp circle: the size every composer control shares. */
@Composable
private fun ComposerControl(
    label: String,
    enabled: Boolean,
    container: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(TOUCH_SIZE)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(VISUAL_SIZE).clip(CircleShape).background(container)
        ) { content() }
    }
}

/**
 * Send: an arrow up, filled with the primary color once there is text or an attachment. While a
 * turn works it becomes stop, a filled square, in the same place.
 */
@Composable
private fun SendButton(working: Boolean, canSend: () -> Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val active = working || canSend()
    val container by animateColorAsState(
        if (active) colors.primary else colors.surfaceContainerHighest,
        ChatMotion.color(),
        label = "send-container"
    )
    val content by animateColorAsState(
        if (active) colors.onPrimary else colors.onSurfaceVariant,
        ChatMotion.color(),
        label = "send-content"
    )
    ComposerControl(
        label = stringResource(if (working) R.string.chat_stop else R.string.chat_send),
        enabled = active,
        container = container,
        onClick = { if (working) onStop() else onSend() }
    ) {
        // A plain crossfade: no scaling in or out.
        AnimatedContent(
            targetState = working,
            transitionSpec = { ChatMotion.crossfade() },
            label = "send-stop"
        ) { isWorking ->
            Icon(
                painterResource(if (isWorking) R.drawable.ic_sym_stop else R.drawable.ic_sym_arrow_upward),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(ICON_SIZE)
            )
        }
    }
}

/**
 * Above the container: the role question for a picked picture, or one-tap wordings for what is
 * attached. Chips fill the field; they never send.
 */
@Composable
private fun ComposerSuggestions(composer: ComposerUi, viewModel: DocChatViewModel) {
    val pending = composer.pendingImage
    val rebuildPrompt = stringResource(R.string.chat_role_rebuild_prompt)
    val placePrompt = stringResource(R.string.chat_role_place_prompt)
    when {
        pending != null && composer.askRole -> RoleQuestion(
            onRebuild = { viewModel.setImageRole(rebuild = true, prompt = rebuildPrompt, andSend = true) },
            onPlace = { viewModel.setImageRole(rebuild = false, prompt = placePrompt, andSend = true) }
        )
        pending != null -> SuggestionRow {
            ComposerChip(R.string.chat_role_rebuild, R.drawable.ic_sym_description) {
                viewModel.setImageRole(rebuild = true, prompt = rebuildPrompt)
            }
            ComposerChip(R.string.chat_role_place, R.drawable.ic_sym_image) {
                viewModel.setImageRole(rebuild = false, prompt = placePrompt)
            }
        }
        // Not templates: these keep an attached document what it is and change only how it looks.
        composer.document != null || composer.scan != null -> SuggestionRow {
            CONTENT_PRESETS.forEach { preset ->
                val prompt = preset.prompt
                ComposerChip(preset.label, null) { viewModel.useExample(prompt) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        content = content
    )
}

@Composable
private fun ComposerChip(@StringRes label: Int, @DrawableRes icon: Int?, onClick: () -> Unit) {
    SuggestionChip(
        onClick = onClick,
        label = { Text(stringResource(label)) },
        icon = icon?.let { res ->
            {
                Icon(
                    painterResource(res),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            iconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = null
    )
}

/** Send was tapped with the picture's role still open: asked here, before anything is sent or billed. */
@Composable
private fun RoleQuestion(onRebuild: () -> Unit, onPlace: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chat_role_question), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.chat_role_question_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRebuild) { Text(stringResource(R.string.chat_role_answer_rebuild)) }
                OutlinedButton(onClick = onPlace) { Text(stringResource(R.string.chat_role_answer_place)) }
            }
        }
    }
}

@Composable
private fun Attachments(composer: ComposerUi, viewModel: DocChatViewModel) {
    if (!composer.hasAttachment && composer.attachError == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, top = 8.dp)
    ) {
        composer.document?.let { document ->
            val busy = document.isReading
            AttachmentThumb(
                title = if (busy) stringResource(R.string.chat_preparing_file) else document.name,
                subtitle = if (busy) {
                    null
                } else {
                    pluralStringResource(R.plurals.chat_pages, document.pages, document.pages)
                },
                onRemove = viewModel::removeDocument,
                leading = { Thumbnail(document.uri, R.drawable.ic_sym_description, busy) }
            )
        }
        composer.photo?.let { photo ->
            AttachmentThumb(
                title = if (photo.isPreparing) stringResource(R.string.chat_preparing_picture) else photo.name,
                subtitle = if (photo.isPreparing) null else stringResource(R.string.chat_picture_role),
                onRemove = viewModel::removePhoto,
                leading = { Thumbnail(photo.uri, R.drawable.ic_sym_image, photo.isPreparing) }
            )
        }
        composer.pendingImage?.let { image ->
            AttachmentThumb(
                title = image.name,
                subtitle = stringResource(R.string.chat_image_pending),
                onRemove = viewModel::removePendingImage,
                leading = { Thumbnail(image.uri, R.drawable.ic_sym_image, busy = false) }
            )
        }
        composer.attachError?.let { error -> AttachErrorChip(error, onDismiss = viewModel::dismissAttachError) }
        composer.scan?.let { scan ->
            AttachmentThumb(
                title = scan.label,
                subtitle = stringResource(R.string.chat_scan_role),
                onRemove = { viewModel.attachScan(null) },
                leading = { ThumbIcon(painterResource(R.drawable.ic_sym_text_snippet)) }
            )
        }
    }
}

/** A pick that was refused (too many pages, too large, unreadable), until dismissed or replaced. */
@Composable
private fun AttachErrorChip(error: AttachError, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(
                stringResource(error.text, *error.args.toTypedArray()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.widthIn(max = ERROR_TEXT_WIDTH)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    painterResource(R.drawable.ic_sym_close),
                    contentDescription = stringResource(R.string.chat_attach_error_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** One attachment inside the container: a thumbnail, its name, and a remove button. */
@Composable
private fun AttachmentThumb(
    title: String,
    onRemove: () -> Unit,
    subtitle: String? = null,
    leading: @Composable () -> Unit
) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) { leading() }
            Column(Modifier.padding(start = 8.dp).widthIn(max = THUMB_LABEL_WIDTH)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    painterResource(R.drawable.ic_sym_close),
                    contentDescription = stringResource(R.string.chat_remove, title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** The picked image itself when it is one; the kind's icon for a PDF or while it is being read. */
@Composable
private fun Thumbnail(uri: String?, @DrawableRes fallback: Int, busy: Boolean) {
    when {
        busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        uri == null -> ThumbIcon(painterResource(fallback))
        else -> SubcomposeAsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            error = { ThumbIcon(painterResource(fallback)) },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun ThumbIcon(painter: Painter) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            painter,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * One line under the composer: what chatting costs, in a new chat only (the agent bills only for a
 * file it builds); the designer's quoted price on the legacy path, as before.
 */
@Composable
private fun CostCaption(paywall: PaywallState, agent: Boolean, emptyChat: Boolean) {
    val line = when {
        agent -> stringResource(R.string.chat_cost_agent).takeIf { emptyChat }
        !paywall.isQuotaKnown -> null
        else -> paywall.quotedDesignPageCost.let { pluralStringResource(R.plurals.chat_cost_hint, it, it) }
    } ?: return
    Text(
        line,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 8.dp, end = 8.dp)
    )
}

private class AttachPickers(val camera: () -> Unit, val photos: () -> Unit, val files: () -> Unit)

/**
 * Camera is ML Kit's document scanner, returning one cropped PDF of up to the designer's page
 * limit — a page to rebuild; without Play services it falls back to the system camera's single
 * photo. Photos is the system photo picker (no permission). A picked picture's role is asked in the
 * composer; a PDF is always a document to rebuild.
 */
@Composable
private fun rememberAttachPickers(viewModel: DocChatViewModel): AttachPickers {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val captureUri = remember {
        val file = File(context.cacheDir, "scans/chat_capture.jpg").apply { parentFile?.mkdirs() }
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf?.uri?.let(viewModel::attachDocument)
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) viewModel.attachDocument(captureUri)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { viewModel.attachImage(it, null) }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val isPdf = context.contentResolver.getType(uri) == PDF_MIME
        if (isPdf) viewModel.attachDocument(uri) else viewModel.attachImage(uri, null)
    }
    return AttachPickers(
        camera = {
            if (activity != null && DocumentScannerManager.isScannerAvailable(context)) {
                DocumentScannerManager.getPdfScanIntent(activity, MAX_SCAN_PAGES)
                    .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
                    .addOnFailureListener { takePicture.launch(captureUri) }
            } else {
                takePicture.launch(captureUri)
            }
        },
        photos = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        files = { openFile.launch(arrayOf(PDF_MIME, "image/*")) }
    )
}

private const val MAX_LINES = 6
private val TOUCH_SIZE = 48.dp
private val VISUAL_SIZE = 40.dp
private val ICON_SIZE = 24.dp
private val THUMB_LABEL_WIDTH = 140.dp
private val ERROR_TEXT_WIDTH = 240.dp
private const val PDF_MIME = "application/pdf"

/** The designer's page limit (DesignInputs.MAX_PAGES). */
private const val MAX_SCAN_PAGES = 10
