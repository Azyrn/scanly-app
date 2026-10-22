@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.skeler.scanely.settings.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeler.scanely.R
import com.skeler.scanely.core.ai.custom.AuthStyle
import com.skeler.scanely.core.ai.custom.CustomScanner
import com.skeler.scanely.core.ai.custom.EndpointUrl
import com.skeler.scanely.core.ai.custom.ProviderProtocol
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.settings.presentation.viewmodel.CustomProviderUiState
import com.skeler.scanely.settings.presentation.viewmodel.CustomProviderViewModel
import com.skeler.scanely.ui.components.SettingsSectionHeader

@Composable
fun CustomProviderScreen(
    viewModel: CustomProviderViewModel = hiltViewModel()
) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Custom endpoint", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!state.isPro) {
                item { ProRequiredCard() }
            }

            item { IntroCard() }
            item { EndpointFields(state = state, viewModel = viewModel) }
            state.failure?.let { failure -> item { FailureCard(message = failure.message) } }

            if (state.isPro) {
                item { VerificationRow(state) }
            }

            item { Actions(state = state, viewModel = viewModel) }

            if (state.isPro && (state.isVerified || state.credentials.isComplete)) {
                item { EnableRow(state = state, viewModel = viewModel) }
            }
        }
    }
}

@Composable
private fun ProRequiredCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "A Pro feature",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Connecting your own AI provider is part of Scanly Pro. The fields below show " +
                    "exactly what you'd set up — they unlock once you're on Pro. Upgrade, or " +
                    "redeem a key you already own, from the Account section in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Connect any AI provider. Pick the protocol it speaks, paste the endpoint URL, " +
                    "the model name and your API key, then test the connection.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Most hosts are OpenAI-compatible — Groq, OpenAI, OpenRouter, NVIDIA NIM, " +
                    "Together, Fireworks. Claude and Gemini have their own protocols, and a " +
                    "self-hosted server on your network works over plain http.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.custom_provider_on_your_endpoint_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.custom_provider_build_documents_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Scanned PDFs go up to ${CustomScanner.MAX_PAGES} pages at a time, since they " +
                    "travel as images in one request. Office documents still need Scanly AI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EndpointFields(state: CustomProviderUiState, viewModel: CustomProviderViewModel) {
    var keyVisible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionHeader(text = "Protocol")

        ProtocolChips(state = state, viewModel = viewModel)

        Text(
            text = state.credentials.protocol.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SettingsSectionHeader(text = "Endpoint")

        OutlinedTextField(
            value = state.credentials.url,
            onValueChange = viewModel::setUrl,
            label = { Text("Endpoint URL") },
            placeholder = { Text(urlPlaceholder(state.credentials.protocol)) },
            singleLine = true,
            enabled = state.canEdit,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            ),
            supportingText = {
                Text(
                    if (EndpointUrl.looksLikeCloudflare(state.credentials.url)) {
                        "Cloudflare Workers AI puts your account ID in the path — paste the full " +
                            "URL including it."
                    } else {
                        when (state.credentials.protocol) {
                            ProviderProtocol.OPENAI -> "Usually ends in /v1/chat/completions."
                            ProviderProtocol.ANTHROPIC -> "Usually ends in /v1/messages."
                            ProviderProtocol.GEMINI ->
                                "Just the host is enough — the model and path are added for you."
                        }
                    }
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.credentials.model,
            onValueChange = viewModel::setModel,
            label = { Text("Model") },
            placeholder = { Text("llama-3.3-70b-versatile") },
            singleLine = true,
            enabled = state.canEdit,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            supportingText = { Text("Spelled exactly as your provider lists it.") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.credentials.apiKey,
            onValueChange = viewModel::setApiKey,
            label = { Text("API key") },
            singleLine = true,
            enabled = state.canEdit,
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (keyVisible) "Hide key" else "Show key"
                    )
                }
            },
            supportingText = { Text("Stored on this device only. Never sent to Scanly's servers.") },
            modifier = Modifier.fillMaxWidth()
        )

        AuthStylePicker(state = state, viewModel = viewModel)

        if (state.credentials.authStyle == AuthStyle.CUSTOM) {
            OutlinedTextField(
                value = state.credentials.customAuthHeader,
                onValueChange = viewModel::setCustomAuthHeader,
                label = { Text("Header name") },
                placeholder = { Text("x-api-key") },
                singleLine = true,
                enabled = state.canEdit,
                supportingText = { Text("The header your gateway expects the key in.") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ProtocolChips(state: CustomProviderUiState, viewModel: CustomProviderViewModel) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProviderProtocol.entries.forEach { protocol ->
            FilterChip(
                selected = state.credentials.protocol == protocol,
                onClick = { viewModel.setProtocol(protocol) },
                enabled = state.canEdit,
                label = { Text(protocol.label) }
            )
        }
    }
}

@Composable
private fun AuthStylePicker(state: CustomProviderUiState, viewModel: CustomProviderViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val current = state.credentials.authStyle

    Column {
        Text(
            text = "Send the key as",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { expanded = true }, enabled = state.canEdit) {
            Text(current.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AuthStyle.entries.forEach { style ->
                DropdownMenuItem(
                    text = { Text(style.label) },
                    onClick = {
                        viewModel.setAuthStyle(style)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun FailureCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun VerificationRow(state: CustomProviderUiState) {
    if (!state.credentials.isComplete && !state.isVerified) return

    val verified = state.isVerified
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (verified) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = if (verified) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (verified) {
                "Verified — this endpoint answered correctly."
            } else {
                "Unverified — test the connection before saving."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Actions(state: CustomProviderUiState, viewModel: CustomProviderViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = viewModel::testConnection,
            enabled = state.canTest,
            modifier = Modifier.weight(1f)
        ) {
            if (state.isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("Testing…")
            } else {
                Text("Test connection")
            }
        }

        Button(
            onClick = viewModel::save,
            enabled = state.canSave,
            modifier = Modifier.weight(1f)
        ) {
            Text("Save")
        }
    }

    if (state.isPro && state.credentials.isComplete) {
        TextButton(onClick = viewModel::clear) {
            Text("Remove endpoint")
        }
    }
}

@Composable
private fun EnableRow(state: CustomProviderUiState, viewModel: CustomProviderViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Use for AI features", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (state.isVerified) {
                    "Scans, data extraction, translation, drafts and designs all use your " +
                        "endpoint instead of Scanly AI. Audio to Text always uses Scanly AI, " +
                        "since a chat endpoint can't take audio."
                } else {
                    "Verify the endpoint first."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = state.isEnabled,
            onCheckedChange = viewModel::setEnabled,
            enabled = state.isVerified || state.isEnabled
        )
    }
}

private fun urlPlaceholder(protocol: ProviderProtocol): String = when (protocol) {
    ProviderProtocol.OPENAI -> "https://api.groq.com/openai/v1/chat/completions"
    ProviderProtocol.ANTHROPIC -> "https://api.anthropic.com/v1/messages"
    ProviderProtocol.GEMINI -> "https://generativelanguage.googleapis.com"
}
