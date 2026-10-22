package com.skeler.scanely.ui.pdftools

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.ImportedFile
import com.skeler.scanely.core.pdftools.ToolDocument

/*
 * The building blocks every tool's options screen is made of, so forty tools still look like one
 * app: summary cards for the inputs, selectable rows for a choice, a password box, and the one
 * primary button that starts the run.
 */

/** An opened PDF: its name, size and page count. [actions] sit at the card's end (remove, reorder). */
@Composable
fun FileSummaryCard(
    document: ToolDocument,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    FileSummaryCard(
        name = document.displayName,
        details = stringResource(
            R.string.pdf_tool_file_summary,
            Formatter.formatShortFileSize(context, document.sizeBytes),
            pluralStringResource(R.plurals.pdf_tool_pages, document.pageCount, document.pageCount)
        ),
        modifier = modifier,
        actions = actions
    )
}

/** A picked file that isn't a PDF (an image, an Office file): its name and size. */
@Composable
fun FileSummaryCard(
    file: ImportedFile,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    FileSummaryCard(
        name = file.displayName,
        details = Formatter.formatShortFileSize(LocalContext.current, file.sizeBytes),
        modifier = modifier,
        actions = actions
    )
}

@Composable
fun FileSummaryCard(
    name: String,
    details: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(CARD_PADDING),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            actions?.invoke(this)
        }
    }
}

/**
 * One choice in a single-choice list, with its consequence spelled out under the title. Wrap a
 * group of them in a Column with `Modifier.selectableGroup()` so TalkBack reads them as one set.
 */
@Composable
fun SelectableRow(
    title: String,
    body: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CARD_SHAPE,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        border = BorderStroke(BORDER_WIDTH, if (selected) colors.primary else colors.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ROW_PADDING_H, vertical = ROW_PADDING_V),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = ROW_PADDING_H)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** The button that starts a tool's run; full width, so every tool ends the same way. */
@Composable
fun PrimaryActionButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) {
        Text(text)
    }
}

/** Explanatory text above a tool's controls. */
@Composable
fun OptionsBody(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** A password box with a show/hide toggle; shared by the options and the unlock prompt. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.pdf_tool_hide_password else R.string.pdf_tool_show_password
                    )
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

/** Gap between the blocks of an options screen; specs use it for their own columns. */
val OPTIONS_SECTION_GAP = 16.dp

/** Gap between the rows of one choice group. */
val OPTIONS_ROW_GAP = 8.dp

private val CARD_SHAPE = RoundedCornerShape(16.dp)
private val CARD_PADDING = 16.dp
private val ROW_PADDING_H = 12.dp
private val ROW_PADDING_V = 12.dp
private val BORDER_WIDTH = 1.dp
