package com.skeler.scanely.ui.pdfeditor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun PasswordDialog(
    fileName: String,
    isRetry: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password needed") },
        text = {
            Column {
                Text(
                    text = if (isRetry) {
                        "That password didn't open $fileName."
                    } else {
                        "$fileName is encrypted."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = isRetry,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) {
                Text("Open")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Applying edits rewrites the document, which moves the byte offsets every existing signature was
 * computed over. Those signatures do not survive it, and there is no undo once the file is written.
 */
@Composable
fun SignatureAtRiskDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val subject = if (count == 1) "a digital signature" else "$count digital signatures"
    val pronoun = if (count == 1) "that signature" else "those signatures"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This document is signed") },
        text = {
            Text(
                text = "It carries $subject. Saving your edits rewrites the file, and $pronoun " +
                    "will no longer verify. The original file on disk is not affected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Save anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun TextEntryDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    supporting: String? = null
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                supporting?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Crops by dragging a frame over the page itself. The result is returned as a 0..1 box rather
 * than points, so one drag can crop a whole selection of differently sized pages.
 */
@Composable
fun CropPageDialog(
    pageCount: Int,
    loadPage: suspend () -> Bitmap?,
    onConfirm: (Rect) -> Unit,
    onDismiss: () -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var frame by remember { mutableStateOf(Rect(0.06f, 0.06f, 0.94f, 0.94f)) }

    LaunchedEffect(Unit) { bitmap = loadPage() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 3.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = if (pageCount > 1) "Crop $pageCount pages" else "Crop page",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Drag the corners. Everything outside the frame is trimmed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 420.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val page = bitmap
                    if (page == null) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    } else {
                        CropSurface(page, frame) { frame = it }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { frame = Rect(0f, 0f, 1f, 1f) }) { Text("Reset") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { onConfirm(frame) }) { Text("Crop") }
                }
            }
        }
    }
}

@Composable
private fun CropSurface(
    page: Bitmap,
    frame: Rect,
    onFrameChange: (Rect) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
    ) {
        val aspect = page.width.toFloat() / page.height.coerceAtLeast(1)
        val pageHeight = maxWidth / aspect

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(pageHeight)
        ) {
            Image(
                bitmap = page.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )

            var grab by remember { mutableStateOf(CropGrab.NONE) }
            val current by rememberUpdatedState(frame)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pageHeight)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { start ->
                                grab = grabFor(
                                    start,
                                    current,
                                    size.width.toFloat(),
                                    size.height.toFloat()
                                )
                            },
                            onDragEnd = { grab = CropGrab.NONE },
                            onDrag = { change, delta ->
                                change.consume()
                                val dx = delta.x / size.width
                                val dy = delta.y / size.height
                                onFrameChange(current.dragged(grab, dx, dy))
                            }
                        )
                    }
            ) {
                drawCropFrame(frame)
            }
        }
    }
}

private enum class CropGrab { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

private fun grabFor(point: Offset, frame: Rect, width: Float, height: Float): CropGrab {
    val corners = listOf(
        CropGrab.TOP_LEFT to Offset(frame.left * width, frame.top * height),
        CropGrab.TOP_RIGHT to Offset(frame.right * width, frame.top * height),
        CropGrab.BOTTOM_LEFT to Offset(frame.left * width, frame.bottom * height),
        CropGrab.BOTTOM_RIGHT to Offset(frame.right * width, frame.bottom * height)
    )
    val nearest = corners.minByOrNull { (_, corner) -> (corner - point).getDistance() }
    if (nearest != null && (nearest.second - point).getDistance() <= HANDLE_RADIUS_PX) {
        return nearest.first
    }
    val inside = point.x in frame.left * width..frame.right * width &&
        point.y in frame.top * height..frame.bottom * height
    return if (inside) CropGrab.MOVE else CropGrab.NONE
}

private fun Rect.dragged(grab: CropGrab, dx: Float, dy: Float): Rect = when (grab) {
    CropGrab.NONE -> this
    CropGrab.MOVE -> {
        val shiftX = dx.coerceIn(-left, 1f - right)
        val shiftY = dy.coerceIn(-top, 1f - bottom)
        Rect(left + shiftX, top + shiftY, right + shiftX, bottom + shiftY)
    }
    CropGrab.TOP_LEFT -> Rect(
        (left + dx).coerceIn(0f, right - MIN_CROP),
        (top + dy).coerceIn(0f, bottom - MIN_CROP),
        right,
        bottom
    )
    CropGrab.TOP_RIGHT -> Rect(
        left,
        (top + dy).coerceIn(0f, bottom - MIN_CROP),
        (right + dx).coerceIn(left + MIN_CROP, 1f),
        bottom
    )
    CropGrab.BOTTOM_LEFT -> Rect(
        (left + dx).coerceIn(0f, right - MIN_CROP),
        top,
        right,
        (bottom + dy).coerceIn(top + MIN_CROP, 1f)
    )
    CropGrab.BOTTOM_RIGHT -> Rect(
        left,
        top,
        (right + dx).coerceIn(left + MIN_CROP, 1f),
        (bottom + dy).coerceIn(top + MIN_CROP, 1f)
    )
}

private fun DrawScope.drawCropFrame(frame: Rect) {
    val box = Rect(
        frame.left * size.width,
        frame.top * size.height,
        frame.right * size.width,
        frame.bottom * size.height
    )
    val scrim = Color.Black.copy(alpha = 0.45f)

    drawRect(scrim, Offset.Zero, Size(size.width, box.top))
    drawRect(scrim, Offset(0f, box.bottom), Size(size.width, size.height - box.bottom))
    drawRect(scrim, Offset(0f, box.top), Size(box.left, box.height))
    drawRect(scrim, Offset(box.right, box.top), Size(size.width - box.right, box.height))

    drawRect(Color.White, box.topLeft, box.size, style = Stroke(width = 2f))

    val arm = 22f
    listOf(
        box.topLeft to Offset(1f, 1f),
        Offset(box.right, box.top) to Offset(-1f, 1f),
        Offset(box.left, box.bottom) to Offset(1f, -1f),
        Offset(box.right, box.bottom) to Offset(-1f, -1f)
    ).forEach { (corner, direction) ->
        drawLine(
            Color.White,
            corner,
            corner + Offset(arm * direction.x, 0f),
            strokeWidth = 6f
        )
        drawLine(
            Color.White,
            corner,
            corner + Offset(0f, arm * direction.y),
            strokeWidth = 6f
        )
    }
}

private const val HANDLE_RADIUS_PX = 64f
private const val MIN_CROP = 0.08f
