package com.skeler.scanely.ui.screens

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeler.scanely.R
import com.skeler.scanely.core.ai.AiMode
import com.skeler.scanely.core.ai.showsAiScanCard
import com.skeler.scanely.core.common.LocalDarkMode
import com.skeler.scanely.core.common.UriNames
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.ScanViewModel
import com.skeler.scanely.ui.components.AiModeBottomSheet
import com.skeler.scanely.ui.components.ExtractionPresetSheet
import com.skeler.scanely.ui.components.HistoryPillButton
import com.skeler.scanely.ui.components.MAX_AI_FILES
import com.skeler.scanely.ui.components.MainActionButton
import com.skeler.scanely.ui.components.PageRangeDialog
import com.skeler.scanely.ui.components.PaywallSheet
import com.skeler.scanely.ui.components.SignInRequiredSheet
import com.skeler.scanely.ui.components.rememberMultiDocumentPicker
import com.skeler.scanely.ui.components.rememberMultiGalleryPicker
import com.skeler.scanely.ui.viewmodel.AiScanViewModel
import com.skeler.scanely.ui.viewmodel.OcrViewModel
import com.skeler.scanely.ui.viewmodel.PaywallViewModel
import com.skeler.scanely.ui.viewmodel.UnifiedScanViewModel
import kotlinx.coroutines.launch

private const val WORDMARK = "Scanly"

private val CHIP_TITLE_GAP = 6.dp

private const val PDF_MIME_TYPE = "application/pdf"

/** Server limit for document extraction. */
private const val EXTRACTION_MAX_PAGES = 8

private data class PendingPdfScan(val uri: Uri, val mode: AiMode, val pageCount: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val activity = LocalActivity.current as ComponentActivity
    val scanViewModel: ScanViewModel = hiltViewModel(activity)
    val aiViewModel: AiScanViewModel = hiltViewModel(activity)
    val ocrViewModel: OcrViewModel = hiltViewModel(activity)
    val unifiedViewModel: UnifiedScanViewModel = hiltViewModel(activity)
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    var showAiBottomSheet by remember { mutableStateOf(false) }
    val aiSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingAiMode by remember { mutableStateOf<AiMode?>(null) }
    var pendingPageRange by remember { mutableStateOf<PendingPdfScan?>(null) }
    var pendingExtractPreset by remember { mutableStateOf<String?>(null) }
    var showExtractionSheet by remember { mutableStateOf(false) }
    val extractionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val showSignInPrompt by scanViewModel.showSignInPrompt.collectAsStateWithLifecycle()

    val paywallViewModel: PaywallViewModel = hiltViewModel(activity)
    val paywallState by paywallViewModel.state.collectAsStateWithLifecycle()
    var showPaywall by remember { mutableStateOf(false) }

    val offeredFeatures by aiViewModel.offeredFeatures.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { aiViewModel.refreshOfferedFeatures() }

    fun startAiScan(uris: List<Uri>, mode: AiMode, pageRange: String? = null) {
        val preset = pendingExtractPreset.takeIf { mode == AiMode.EXTRACT_DATA }
        if (scanViewModel.triggerAiWithRateLimit {
                scanViewModel.onNewScanSelected()
                aiViewModel.processMultipleFiles(uris, mode, pageRange, preset)
            }
        ) {
            navigator.go(Destination.Results)
        }
    }

    val aiMultiGalleryPicker = rememberMultiGalleryPicker(maxItems = MAX_AI_FILES) { uris ->
        if (uris.isNotEmpty() && pendingAiMode != null) {
            val mode = pendingAiMode!!
            pendingAiMode = null
            startAiScan(uris, mode)
        }
    }

    val aiMultiDocumentPicker = rememberMultiDocumentPicker { selectedUris ->
        if (selectedUris.isNotEmpty() && pendingAiMode != null) {
            // SAF cannot limit multi-selection count.
            val uris = selectedUris.take(MAX_AI_FILES)
            if (selectedUris.size > MAX_AI_FILES) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "Up to $MAX_AI_FILES files per scan — using the first $MAX_AI_FILES."
                    )
                }
            }
            val mode = pendingAiMode!!
            pendingAiMode = null

            // Page ranges apply only to a single document.
            val single = uris.singleOrNull()
                ?.takeIf { context.contentResolver.getType(it) == PDF_MIME_TYPE }
            if (single == null) {
                startAiScan(uris, mode)
            } else {
                scope.launch {
                    val pages = aiViewModel.pdfPageCount(single)
                    if (pages > 1) {
                        pendingPageRange = PendingPdfScan(single, mode, pages)
                    } else {
                        startAiScan(uris, mode)
                    }
                }
            }
        }
    }

    val extractionPicker = rememberMultiDocumentPicker(
        mimeTypes = arrayOf(PDF_MIME_TYPE, "image/*")
    ) { selectedUris ->
        val uri = selectedUris.firstOrNull()
        if (uri != null && pendingExtractPreset != null) {
            if (context.contentResolver.getType(uri) != PDF_MIME_TYPE) {
                startAiScan(listOf(uri), AiMode.EXTRACT_DATA)
            } else {
                scope.launch {
                    val pages = aiViewModel.pdfPageCount(uri)
                    if (pages > 1) {
                        pendingPageRange = PendingPdfScan(uri, AiMode.EXTRACT_DATA, pages)
                    } else {
                        startAiScan(listOf(uri), AiMode.EXTRACT_DATA)
                    }
                }
            }
        }
    }

    // Offline OCR runs on-device and streams one image/page at a time, so it takes any number.
    val launchGalleryPicker = rememberMultiGalleryPicker(maxItems = null) { uris ->
        if (uris.isNotEmpty()) {
            aiViewModel.clearResult()
            ocrViewModel.clearResult()
            scanViewModel.onNewScanSelected()
            unifiedViewModel.processImages(uris)
            navigator.go(Destination.UnifiedResults)
        }
    }

    val pdfLauncher = rememberMultiDocumentPicker(
        mimeTypes = arrayOf(PDF_MIME_TYPE)
    ) { uris ->
        if (uris.isNotEmpty()) {
            aiViewModel.clearResult()
            ocrViewModel.clearResult()
            scanViewModel.onNewScanSelected()
            ocrViewModel.processPdfs(uris)
            navigator.go(Destination.Results)
        }
    }

    fun onAiModeSelected(mode: AiMode) {
        pendingAiMode = mode
        showAiBottomSheet = false
        when (mode) {
            AiMode.EXTRACT_TEXT -> aiMultiGalleryPicker()
            AiMode.EXTRACT_PDF_TEXT -> aiMultiDocumentPicker()
            AiMode.EXTRACT_DATA -> showExtractionSheet = true
            // A chat of its own: designs and revisions are turns, not a scan result.
            AiMode.DESIGN_DOCUMENT -> navigator.go(Destination.DocChat())
            // Its own screen too: a recording, a player and a transcript, not a scan result.
            AiMode.TRANSCRIBE_AUDIO -> navigator.go(Destination.Transcribe)
            else -> aiMultiGalleryPicker()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                // Keep the wordmark centered regardless of quota width or font scale.
                title = {
                    val titleStyle = MaterialTheme.typography.headlineMedium
                        .copy(fontWeight = FontWeight.SemiBold)
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        // Prevent the quota chip from overlapping the centered wordmark.
                        val titleWidth = with(density) {
                            measurer.measure(WORDMARK, titleStyle).size.width.toDp()
                        }
                        val chipMaxWidth =
                            ((maxWidth - titleWidth) / 2 - CHIP_TITLE_GAP).coerceAtLeast(0.dp)

                        Text(
                            text = WORDMARK,
                            style = titleStyle,
                            modifier = Modifier.align(Alignment.Center)
                        )

                        val quota = paywallState.takeIf { it.isQuotaKnown }
                        if (quota != null) {
                            AssistChip(
                                onClick = { showPaywall = true },
                                label = {
                                    Text(
                                        text = "${quota.pagesRemaining} credits",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                                ),
                                border = null,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .widthIn(max = chipMaxWidth)
                            )
                        }

                        IconButton(
                            onClick = { navigator.go(Destination.Settings) },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 4.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings_expressive),
                                contentDescription = "Settings"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val compact = maxHeight < 620.dp
            val gap = if (compact) 8.dp else 14.dp
            val cardPadding = if (compact) 14.dp else 20.dp
            val topPadding = if (compact) 8.dp else 14.dp

            // Scrolls when the cards do not fit (a small screen with large text hid AI Scan and
            // the extracts link); when they fit, the free height splits as the old spacers did.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(start = 24.dp, end = 24.dp, top = topPadding, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = homeArrangement(gap)
            ) {
                Text(
                    text = "What would you like to scan?",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )

                val accents = rememberActionAccents()
                val startDocumentScan = rememberDocumentScanStarter()

                MainActionButton(
                    iconRes = R.drawable.ic_action_aperture,
                    title = "Scan Document",
                    subtitle = "Auto-crop, enhance & export PDF",
                    onClick = startDocumentScan,
                    accentTint = accents.document,
                    contentPadding = cardPadding
                )

                MainActionButton(
                    iconRes = R.drawable.ic_action_gallery_stack,
                    title = "Image to Text",
                    subtitle = "From photos and screenshots",
                    onClick = { launchGalleryPicker() },
                    accentTint = accents.gallery,
                    contentPadding = cardPadding
                )

                MainActionButton(
                    iconRes = R.drawable.ic_action_document,
                    title = "PDF to Text",
                    subtitle = "Scanned pages included",
                    onClick = { pdfLauncher() },
                    accentTint = accents.pdf,
                    contentPadding = cardPadding
                )

                MainActionButton(
                    iconRes = R.drawable.ic_action_pdf_edit,
                    title = "Edit PDF",
                    subtitle = "Annotate, sign, merge & compress",
                    onClick = { navigator.go(Destination.PdfTools) },
                    accentTint = accents.edit,
                    contentPadding = cardPadding
                )

                MainActionButton(
                    iconRes = R.drawable.ic_action_qr,
                    title = "Scan Barcode/QR",
                    subtitle = "QR, product codes, PDF417",
                    onClick = { navigator.go(Destination.BarcodeScanner) },
                    accentTint = accents.qr,
                    contentPadding = cardPadding
                )

                if (showsAiScanCard(offeredFeatures)) {
                    MainActionButton(
                        iconRes = R.drawable.ic_action_spark,
                        title = "AI Scan",
                        subtitle = "Fields and tables, not just text",
                        onClick = { if (scanViewModel.requireSignInForAi()) showAiBottomSheet = true },
                        accentTint = accents.ai,
                        contentPadding = cardPadding
                    )
                }

                HistoryPillButton(onClick = { navigator.go(Destination.History) })
            }
        }
    }

    if (showAiBottomSheet) {
        AiModeBottomSheet(
            sheetState = aiSheetState,
            onDismiss = { showAiBottomSheet = false },
            onModeSelected = { mode -> onAiModeSelected(mode) },
            offeredFeatures = offeredFeatures,
            showExtractData = paywallState.extractionPresets.isNotEmpty()
        )
    }

    if (showExtractionSheet) {
        ExtractionPresetSheet(
            sheetState = extractionSheetState,
            presets = paywallState.extractionPresets,
            pageCost = paywallState.extractionPageCost,
            included = paywallState.allowsExtraction,
            onDismiss = { showExtractionSheet = false },
            onPresetSelected = { preset ->
                showExtractionSheet = false
                pendingExtractPreset = preset.id
                extractionPicker()
            }
        )
    }

    pendingPageRange?.let { pending ->
        PageRangeDialog(
            fileName = UriNames.displayName(context, pending.uri) ?: "This PDF",
            totalPages = pending.pageCount,
            onConfirm = { range ->
                pendingPageRange = null
                startAiScan(listOf(pending.uri), pending.mode, range)
            },
            onDismiss = { pendingPageRange = null },
            // Require a page range for extraction beyond the server limit.
            maxPages = EXTRACTION_MAX_PAGES.takeIf { pending.mode == AiMode.EXTRACT_DATA }
        )
    }

    if (showPaywall) {
        PaywallSheet(
            state = paywallState,
            onDismiss = {
                showPaywall = false
                paywallViewModel.consumeMessage()
            },
            onRedeem = { key -> paywallViewModel.redeem(key) },
            onRestore = { paywallViewModel.restorePurchase() },
            onConfirmEmail = { paywallViewModel.confirmEmail() },
            onRetry = { paywallViewModel.refresh() },
            canWatchAd = paywallState.canWatchAd,
            onWatchAd = { paywallViewModel.watchAd(activity) }
        )
    }

    if (showSignInPrompt) {
        SignInRequiredSheet(
            onDismiss = { scanViewModel.dismissSignInPrompt() },
            onSignIn = {
                scanViewModel.dismissSignInPrompt()
                navigator.go(Destination.Auth)
            }
        )
    }
}

private data class ActionAccents(
    val document: Color,
    val gallery: Color,
    val pdf: Color,
    val edit: Color,
    val qr: Color,
    val ai: Color
)

@Composable
private fun rememberActionAccents(): ActionAccents {
    val ai = MaterialTheme.colorScheme.primary
    return if (LocalDarkMode.current) {
        ActionAccents(
            document = Color(0xFFB6A9DC),
            gallery = Color(0xFFD8B878),
            pdf = Color(0xFFE0A79B),
            edit = Color(0xFF8FBBD9),
            qr = Color(0xFF9CC7AE),
            ai = ai
        )
    } else {
        ActionAccents(
            document = Color(0xFF5E4E80),
            gallery = Color(0xFF9A7B3F),
            pdf = Color(0xFFA05A4E),
            edit = Color(0xFF3F6B88),
            qr = Color(0xFF4F7A63),
            ai = ai
        )
    }
}

/**
 * [gap] between items; leftover height goes a fifth under the first item (the question) and the
 * rest after the last, as the 0.4/1.6 weighted spacers placed it. With no leftover it is plain spacing.
 */
private fun homeArrangement(gap: Dp) = object : Arrangement.Vertical {
    override val spacing = gap

    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val gapPx = gap.roundToPx()
        val used = sizes.sum() + gapPx * (sizes.size - 1).coerceAtLeast(0)
        val extra = (totalSize - used).coerceAtLeast(0)
        var y = 0
        sizes.forEachIndexed { index, size ->
            outPositions[index] = y
            y += size + gapPx + if (index == 0) extra / LEFTOVER_PARTS else 0
        }
    }
}

private const val LEFTOVER_PARTS = 5
