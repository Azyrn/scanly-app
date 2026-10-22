package com.skeler.scanely.ui.pdftools.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolEngine
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.ui.pdftools.OptionsBody
import com.skeler.scanely.ui.pdftools.PasswordField
import com.skeler.scanely.ui.pdftools.PrimaryActionButton
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

/** toString is overridden so the password can never reach a log through the options object. */
class ProtectOptions(val password: String) {
    override fun toString(): String = "ProtectOptions(password=***)"
}

/** Typed twice, because a mistyped password would lock the user out of their own copy. */
class ProtectToolSpec @Inject constructor(private val engine: PdfToolEngine) : ToolSpec {
    override val tool = PdfTool.PROTECT
    override val input = ToolInput.PDF

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        // Plain remember: a password has no business in the saved-instance-state bundle.
        var password by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        val mismatch = confirm.isNotEmpty() && confirm != password

        if (state.documents.firstOrNull()?.isEncrypted == true) {
            Text(
                text = stringResource(R.string.pdf_tool_protect_already),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        OptionsBody(stringResource(R.string.pdf_tool_protect_body))
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.pdf_tool_password_label),
            imeAction = ImeAction.Next
        )
        PasswordField(
            value = confirm,
            onValueChange = { confirm = it },
            label = stringResource(R.string.pdf_tool_protect_confirm),
            isError = mismatch,
            supportingText = if (mismatch) stringResource(R.string.pdf_tool_protect_mismatch) else null
        )
        PrimaryActionButton(
            text = stringResource(R.string.pdf_tool_protect_action),
            enabled = state.documents.isNotEmpty() && password.isNotEmpty() && password == confirm,
            onClick = { onRun(ProtectOptions(password)) }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        engine.protect(input.documents.first(), (options as ProtectOptions).password, input.run)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProtectToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.PROTECT)
    abstract fun bind(spec: ProtectToolSpec): ToolSpec
}
