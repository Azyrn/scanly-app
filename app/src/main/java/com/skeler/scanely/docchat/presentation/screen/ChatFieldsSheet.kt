package com.skeler.scanely.docchat.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.docchat.data.FieldsBody

/**
 * The missing details as a sheet over the chat, keyboard up on the first field: each label stays
 * visible above its field, Next walks the fields and Done on the last one submits, and Submit
 * sits below the fields where the keyboard cannot cover it. The screen hides the composer and
 * the jump-to-latest button while this is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FieldsSheet(
    replyId: String,
    body: FieldsBody,
    onSubmit: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    var values by rememberSaveable(replyId) { mutableStateOf(body.fields.associate { it.id to "" }) }
    val complete = body.fields.all { values[it.id].orEmpty().isNotBlank() }
    val focus = LocalFocusManager.current
    val first = remember { FocusRequester() }
    val sheet = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.imePadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.chat_fields_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.chat_fields_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            ) {
                body.fields.forEachIndexed { index, field ->
                    val last = index == body.fields.lastIndex
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(field.label, style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(
                            value = values[field.id].orEmpty(),
                            onValueChange = { values = values + (field.id to it.take(FIELD_LIMIT)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = if (last) ImeAction.Done else ImeAction.Next),
                            keyboardActions = KeyboardActions(
                                onNext = { focus.moveFocus(FocusDirection.Down) },
                                onDone = { if (complete) onSubmit(values) else focus.clearFocus() }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                        )
                    }
                }
            }
            Button(
                onClick = { onSubmit(values) },
                enabled = complete,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) { Text(stringResource(R.string.chat_fields_submit)) }
        }
    }
    // Fully open, never half: the keyboard comes up with it, on the first field.
    LaunchedEffect(Unit) {
        sheet.expand()
        first.requestFocus()
    }
}

private const val FIELD_LIMIT = 200
