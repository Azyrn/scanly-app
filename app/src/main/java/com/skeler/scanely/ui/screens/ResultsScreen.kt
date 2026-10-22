@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeler.scanely.core.ai.AiFeature
import com.skeler.scanely.core.ai.AiResult
import com.skeler.scanely.core.ocr.OcrResult
import com.skeler.scanely.core.ocr.OcrSource
import com.skeler.scanely.core.ocr.scanFailureMessage
import com.skeler.scanely.core.text.DocumentJson
import com.skeler.scanely.core.text.MarkdownParser
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.navigation.OnScreenExit
import com.skeler.scanely.ui.ScanViewModel
import com.skeler.scanely.ui.components.BatchScanProgress
import com.skeler.scanely.ui.components.CredentialBadge
import com.skeler.scanely.ui.components.DocumentLinksCard
import com.skeler.scanely.ui.components.EmptyResultContent
import com.skeler.scanely.ui.components.ExportContent
import com.skeler.scanely.ui.components.ExtractedFieldsCard
import com.skeler.scanely.ui.components.ExtractedTextActions
import com.skeler.scanely.ui.components.ExtractedTextSection
import com.skeler.scanely.ui.components.LanguageChipRow
import com.skeler.scanely.ui.components.PaywallSheet
import com.skeler.scanely.ui.components.ProcessingContent
import com.skeler.scanely.ui.components.ResultsSectionHeader
import com.skeler.scanely.ui.components.STRUCTURED_EXPORT_FORMATS
import com.skeler.scanely.ui.components.ScanResultSkeleton
import com.skeler.scanely.ui.components.SignInRequiredSheet
import com.skeler.scanely.ui.components.TEXT_EXPORT_FORMATS
import com.skeler.scanely.ui.components.TranslatingContent
import com.skeler.scanely.ui.components.TranslationLanguages
import com.skeler.scanely.ui.components.UnclearPassagesNotice
import com.skeler.scanely.ui.components.rememberExtractedTextState
import com.skeler.scanely.ui.components.rememberMarkdownPrinter
import com.skeler.scanely.ui.components.rememberTextExporter
import com.skeler.scanely.ui.components.unavailableFormats
import com.skeler.scanely.ui.viewmodel.AiScanViewModel
import com.skeler.scanely.ui.viewmodel.OcrViewModel
import com.skeler.scanely.ui.viewmodel.PaywallViewModel
import com.skeler.scanely.ui.viewmodel.aiDisplayText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen() {
    val context = LocalContext.current
    val activity = LocalActivity.current as ComponentActivity
    val scanViewModel: ScanViewModel = hiltViewModel(activity)
    val aiViewModel: AiScanViewModel = hiltViewModel(activity)
    val offeredFeatures by aiViewModel.offeredFeatures.collectAsStateWithLifecycle()
    val ocrViewModel: OcrViewModel = hiltViewModel(activity)
    val navigator = LocalNavigator.current

    val scanState by scanViewModel.uiState.collectAsStateWithLifecycle()
    val aiState by aiViewModel.aiState.collectAsStateWithLifecycle()
    val ocrState by ocrViewModel.uiState.collectAsStateWithLifecycle()

    val isProcessing = aiState.isProcessing || ocrState.isProcessing
    val isTranslating = aiState.isTranslating

    val historyText = scanState.historyText
    val aiResultText = aiDisplayText(aiState.result)
    val ocrResultText = when (val result = ocrState.result) {
        is OcrResult.Success -> result.text
        else -> null
    }
    val primaryResultText = historyText ?: aiResultText ?: ocrResultText
    val isAiResult = aiResultText != null

    val cachedLanguages = aiState.translationCache.keys.toList()
    val currentLanguage = aiState.currentLanguage
    val displayText = currentLanguage?.let { aiState.translationCache[it] } ?: primaryResultText

    // The AI answers in Markdown: "Original" reads it as plain text, "Markdown" renders it,
    // and "JSON" shows the same structure as data (one element per block, typed).
    var resultView by remember { mutableStateOf(ResultView.PLAIN) }

    // Markdown/JSON exports only describe data the offline engine still has (edits drop it).
    val paddleResult = (ocrState.result as? OcrResult.Success)
        ?.takeIf { it.source == OcrSource.PADDLE }
        ?.takeIf { it.blocks.isNotEmpty() || it.markdown != null }
        ?.takeIf { historyText == null && aiResultText == null && currentLanguage == null }
    val structuredMarkdown = paddleResult?.markdown?.takeIf { it.isNotBlank() }
    // Only provenance-known AI text is treated as Markdown; sniffing plain OCR text corrupts it.
    val textIsMarkdown = remember(displayText, isAiResult) {
        isAiResult && displayText?.let { MarkdownParser.looksLikeMarkdown(it) } == true
    }
    val markdownSource = structuredMarkdown ?: displayText?.takeIf { textIsMarkdown }
    val hasMarkdown = markdownSource != null
    val markdownMode = resultView == ResultView.MARKDOWN && hasMarkdown
    val jsonMode = resultView == ResultView.JSON && hasMarkdown
    val shownText = remember(displayText, textIsMarkdown) {
        if (textIsMarkdown) displayText?.let(MarkdownParser::toPlainText) else displayText
    }
    val jsonText = remember(markdownSource) { markdownSource?.let(DocumentJson::fromMarkdown) }
    val printText = rememberMarkdownPrinter()

    val exportText = rememberTextExporter()
    // Structure (offline boxes or AI Markdown) is what a JSON file can carry.
    val exportFormats = if (paddleResult != null || hasMarkdown) {
        STRUCTURED_EXPORT_FORMATS
    } else {
        TEXT_EXPORT_FORMATS
    }

    val isOnline by scanViewModel.isOnline.collectAsStateWithLifecycle()
    val showSignInPrompt by scanViewModel.showSignInPrompt.collectAsStateWithLifecycle()

    val paywallViewModel: PaywallViewModel = hiltViewModel(activity)
    val paywallState by paywallViewModel.state.collectAsStateWithLifecycle()

    // The server refused the scan for a reason a bigger plan fixes — out of pages, document too
    // long, format not in the plan. Its own message says exactly which, so the sheet can show the
    // real reason instead of a generic pitch.
    // A translation refused for the same class of reason gets the same sheet, instead of a toast
    // telling the user to retry a call their plan will refuse again.
    val upgradeReason = (aiState.result as? AiResult.Error)
        ?.takeIf { it.isUpgradePath }
        ?.message
        ?: aiState.translationUpgrade?.message
    var paywallDismissed by remember(upgradeReason) { mutableStateOf(false) }

    var showLanguageMenu by remember { mutableStateOf(false) }
    val languages = TranslationLanguages.ALL

    val textState = rememberExtractedTextState(displayText.orEmpty())
    val onSaveExtracted: (String) -> Unit = { edited ->
        when {
            currentLanguage != null -> aiViewModel.updateText(edited)
            historyText != null -> scanViewModel.updateHistoryText(edited)
            isAiResult -> aiViewModel.updateText(edited)
            // Editing the offline engine's Markdown view feeds Markdown back, not plain text.
            markdownMode && structuredMarkdown != null -> ocrViewModel.updateMarkdown(edited)
            else -> ocrViewModel.updateText(edited)
        }
    }

    val onBack: () -> Unit = { navigator.back() }

    // Discarded when the screen is actually gone, rather than on a timer sized to outlast the pop
    // animation: a cancelled back gesture no longer wipes the result the user is still reading.
    OnScreenExit {
        scanViewModel.clearState()
        aiViewModel.clearResult()
        ocrViewModel.clearResult()
    }

    LaunchedEffect(aiState.message) {
        aiState.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            aiViewModel.consumeMessage()
        }
    }

    if (upgradeReason != null && !paywallDismissed) {
        PaywallSheet(
            state = paywallState,
            reason = upgradeReason,
            onDismiss = {
                paywallDismissed = true
                paywallViewModel.consumeMessage()
                aiViewModel.consumeTranslationUpgrade()
            },
            onRedeem = { key -> paywallViewModel.redeem(key) },
            onRestore = { paywallViewModel.restorePurchase() },
            onConfirmEmail = { paywallViewModel.confirmEmail() },
            onRetry = { paywallViewModel.refresh() },
            canWatchAd = paywallState.canWatchAd,
            onWatchAd = { paywallViewModel.watchAd(activity) }
        )
    }

    // Guards the AI rescan and translate actions below.
    if (showSignInPrompt) {
        SignInRequiredSheet(
            onDismiss = { scanViewModel.dismissSignInPrompt() },
            onSignIn = {
                scanViewModel.dismissSignInPrompt()
                navigator.go(Destination.Auth)
            }
        )
    }

    // A failed AI run is shown as a failure, never as text. The same button retries it unless the
    // plan refused it — then the paywall is the next step and a retry would be refused again.
    val aiError = (aiState.result as? AiResult.Error)?.takeIf { displayText == null }
    val retryableError = aiError != null && !aiError.isUpgradePath

    val showRescan = !isProcessing && !isTranslating &&
        ((displayText != null && isAiResult) || retryableError) &&
        aiState.lastImageUri != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Results",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                when {
                    aiState.isProcessing -> {
                        ProcessingContent(
                            currentFile = aiState.currentFileIndex,
                            totalFiles = aiState.totalFiles,
                            stage = aiState.stage,
                            stageMessage = aiState.stageMessage,
                            streamingText = aiState.streamingText,
                            onCancel = {
                                aiViewModel.cancelProcessing()
                                onBack()
                            }
                        )
                    }
                    isProcessing -> {
                        ocrState.progress?.let { progress ->
                            BatchScanProgress(
                                progress = progress,
                                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
                            )
                        }
                        ScanResultSkeleton(
                            modifier = Modifier.padding(top = 8.dp),
                            showChips = false
                        )
                    }
                    isTranslating -> {
                        TranslatingContent()
                    }
                    aiError != null -> {
                        AiErrorContent(message = aiError.message, canRetry = retryableError)
                    }
                    displayText != null -> {
                        val runInfo = aiState.runInfo
                        val credential: (@Composable () -> Unit)? =
                            if (isAiResult && historyText == null && runInfo != null &&
                                aiState.result is AiResult.Success
                            ) {
                                {
                                    CredentialBadge(model = runInfo.model)
                                }
                            } else {
                                null
                            }

                        if (isAiResult && isOnline) {
                            LanguageChipRow(
                                cachedLanguages = cachedLanguages,
                                currentLanguage = currentLanguage,
                                showLanguageMenu = showLanguageMenu,
                                onShowLanguageMenu = { showLanguageMenu = true },
                                onDismissLanguageMenu = { showLanguageMenu = false },
                                onSelectOriginal = {
                                    resultView = ResultView.PLAIN
                                    aiViewModel.showOriginal()
                                },
                                onSelectCached = {
                                    resultView = ResultView.PLAIN
                                    aiViewModel.selectCachedLanguage(it)
                                },
                                allLanguages = languages,
                                onNewLanguageSelected = { language ->
                                    showLanguageMenu = false
                                    scanViewModel.triggerAiWithRateLimit {
                                        resultView = ResultView.PLAIN
                                        aiViewModel.translateResult(language)
                                    }
                                },
                                onComposeText = { navigator.go(Destination.TextCompose) },
                                showMarkdown = hasMarkdown,
                                markdownSelected = markdownMode,
                                onSelectMarkdown = { resultView = ResultView.MARKDOWN },
                                showJson = hasMarkdown,
                                jsonSelected = jsonMode,
                                onSelectJson = { resultView = ResultView.JSON },
                                showTranslate = AiFeature.TRANSLATE in offeredFeatures
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        } else if (hasMarkdown) {
                            // Offline scans have no language row; still offer the structured view.
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !markdownMode && !jsonMode,
                                    onClick = { resultView = ResultView.PLAIN },
                                    label = { Text("Original") }
                                )
                                FilterChip(
                                    selected = markdownMode,
                                    onClick = { resultView = ResultView.MARKDOWN },
                                    label = { Text("Markdown") }
                                )
                                FilterChip(
                                    selected = jsonMode,
                                    onClick = { resultView = ResultView.JSON },
                                    label = { Text("JSON") }
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        // Both describe the scan itself, so they follow the scan and not a
                        // translation of it, and never a row reopened from history.
                        val scan = (aiState.result as? AiResult.Success)
                            ?.takeIf { historyText == null && currentLanguage == null }
                        if (scan != null) {
                            val cards: List<@Composable () -> Unit> = listOfNotNull(
                                scan.extraction?.let {
                                    {
                                        ExtractedFieldsCard(it, onCopyJson = { json -> copyToClipboard(context, json) })
                                    }
                                },
                                scan.unclear.takeIf { it.isNotEmpty() }
                                    ?.let { { UnclearPassagesNotice(it) } },
                                scan.links.takeIf { it.isNotEmpty() }
                                    ?.let { { DocumentLinksCard(it) } }
                            )
                            cards.forEachIndexed { index, card ->
                                card()
                                Spacer(
                                    modifier = Modifier.height(
                                        if (index == cards.lastIndex) 14.dp else 8.dp
                                    )
                                )
                            }
                        }

                        // Copy, export, print, edit and the body all follow the visible view.
                        val visibleText = when {
                            jsonMode -> jsonText
                            markdownMode -> markdownSource
                            else -> shownText
                        } ?: displayText
                        // The JSON view exports the document, not the JSON string: Save as JSON
                        // then matches the screen and the other formats keep their layout.
                        val preview = remember(visibleText, markdownMode, jsonMode, paddleResult) {
                            if (jsonMode) {
                                ExportContent(
                                    text = markdownSource,
                                    isMarkdown = true,
                                    blocks = paddleResult?.blocks.orEmpty()
                                )
                            } else {
                                ExportContent(
                                    text = visibleText,
                                    isMarkdown = markdownMode,
                                    tableSource = markdownSource,
                                    blocks = paddleResult?.blocks.orEmpty()
                                )
                            }
                        }
                        val disabledFormats = remember(preview) { unavailableFormats(preview) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            ResultsSectionHeader(
                                icon = Icons.AutoMirrored.Rounded.Notes,
                                title = "Extracted Text"
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ExtractedTextActions(
                                    state = textState,
                                    text = visibleText,
                                    onCopy = { copyToClipboard(context, visibleText) },
                                    onExport = { format -> exportText(preview, format) },
                                    exportFormats = exportFormats,
                                    disabledFormats = disabledFormats,
                                    // JSON is a generated view of the result, not editable text.
                                    onSaveEdit = if (jsonMode) null else onSaveExtracted,
                                    onPrint = { printText(preview.text, preview.isMarkdown) },
                                    previewContent = preview,
                                    compact = true
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        ExtractedTextSection(
                            state = textState,
                            text = visibleText,
                            credential = credential,
                            markdown = markdownMode,
                            mono = jsonMode,
                            images = (aiState.result as? AiResult.Success)?.images.orEmpty()
                        )
                    }
                    else -> {
                        EmptyResultContent(failure = scanFailureMessage(ocrState.result))
                    }
                }

                Spacer(modifier = Modifier.height(100.dp))
            }
        }

        if (showRescan) {
            FilledTonalIconButton(
                onClick = {
                    aiViewModel.getRescanParams()?.let { params ->
                        scanViewModel.triggerAiWithRateLimit {
                            aiViewModel.processImage(params.uri, params.mode, params.pageRange)
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 20.dp, bottom = 24.dp)
                    .size(56.dp)
            ) {
                Icon(
                    Icons.Rounded.Autorenew,
                    contentDescription = "Rescan",
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        FilledTonalIconButton(
            onClick = onBack,
            shape = RoundedCornerShape(16.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 8.dp)
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
    }
}

/** Three readings of the same result: prose, rendered Markdown, or typed JSON elements. */
private enum class ResultView { PLAIN, MARKDOWN, JSON }

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Extracted Text", text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

/** An AI run that produced nothing, said plainly, with the button below as the way forward. */
@Composable
private fun AiErrorContent(message: String, canRetry: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "The scan didn't finish",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (canRetry) "$message\n\nTap the button below to try again." else message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
