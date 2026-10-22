package com.skeler.scanely.ui.pdfeditor

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.signature.TypedSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Type-a-name alternative to photographing a signature.
 *
 * The preview is the very bitmap Save hands back, rendered off the main thread, so what the user
 * approves is exactly what gets stored and stamped. Paper signatures are dark ink, so the choice is
 * limited to the page editor's text colours rather than the bright annotation palette.
 */
@Composable
fun TypedSignatureDialog(
    onDismiss: () -> Unit,
    onSave: (bitmap: Bitmap, label: String) -> Unit
) {
    val context = LocalContext.current
    var fontLoadFinished by remember { mutableStateOf(false) }
    val typeface by produceState<Typeface?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { TypedSignature.loadTypeface(context) }
        fontLoadFinished = true
    }
    var name by rememberSaveable { mutableStateOf("") }
    var colorIndex by rememberSaveable { mutableIntStateOf(0) }
    val color = SignatureColors[colorIndex.coerceIn(0, SignatureColors.lastIndex)]

    val preview by produceState<RenderedSignature?>(initialValue = null, name, color, typeface) {
        val face = typeface ?: return@produceState
        // Typing fires a render per keystroke otherwise; a short pause batches them.
        delay(PREVIEW_DEBOUNCE_MS)
        val bitmap = withContext(Dispatchers.Default) {
            TypedSignature.render(name, face, color.toArgb())
        }
        value = RenderedSignature(name, color, bitmap)
    }
    // A render still catching up with the last keystroke must not be saved under the new name.
    val current = preview?.takeIf { it.name == name && it.color == color }?.bitmap

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.signature_typed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(TypedSignature.MAX_LENGTH) },
                    label = { Text(stringResource(R.string.signature_typed_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SignaturePreview(
                    bitmap = preview?.bitmap?.takeIf { name.isNotBlank() },
                    typefaceMissing = fontLoadFinished && typeface == null && name.isNotBlank()
                )
                Text(
                    text = stringResource(R.string.signature_typed_colour),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ColorChoices(selected = colorIndex, onSelect = { colorIndex = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { current?.let { onSave(it, name.trim()) } },
                enabled = current != null
            ) {
                Text(stringResource(R.string.signature_typed_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.signature_typed_cancel))
            }
        }
    )
}

private class RenderedSignature(val name: String, val color: Color, val bitmap: Bitmap?)

@Composable
private fun SignaturePreview(bitmap: Bitmap?, typefaceMissing: Boolean) {
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT.dp)
            .clip(RoundedCornerShape(12.dp))
            // Signatures land on white paper, so the preview is white in dark theme too.
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
            else -> Text(
                text = stringResource(
                    if (typefaceMissing) {
                        R.string.signature_typed_font_missing
                    } else {
                        R.string.signature_typed_preview_empty
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = PREVIEW_HINT
            )
        }
    }
}

@Composable
private fun ColorChoices(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SignatureColors.forEachIndexed { index, color ->
            val label = stringResource(R.string.signature_typed_colour_option, index + 1)
            Box(
                modifier = Modifier
                    .size(SWATCH_SIZE.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (index == selected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                    .semantics { contentDescription = label }
                    .clickable { onSelect(index) }
            )
        }
    }
}

/** Black, graphite and the dark blue of a ballpoint: the page editor's text colours that read as ink. */
private val SignatureColors: List<Color> = TextPalette.take(SIGNATURE_COLOR_COUNT)

private val PREVIEW_HINT = Color(0xFF6B6B6B)
private const val SIGNATURE_COLOR_COUNT = 3
private const val PREVIEW_HEIGHT = 96
private const val SWATCH_SIZE = 28
private const val PREVIEW_DEBOUNCE_MS = 120L
