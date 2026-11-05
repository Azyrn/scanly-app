package com.skeler.scanely.ui.screens

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.skeler.scanely.ui.theme.harmonizedWith
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

/**
 * [planLimit] for the photo picker, or null for the system's own maximum when the plan allows at
 * least that many: the system picker refuses a request above its limit.
 */
private const val SYSTEM_PICKER_DEFAULT_LIMIT = 100

private fun pickerLimit(planLimit: Int): Int? {
    val systemMax = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        MediaStore.getPickImagesMaxLimit()
    } else {
        // Android 11-12 may run the same picker via an SDK extension; 100 is its default limit.
        SYSTEM_PICKER_DEFAULT_LIMIT
    }
    return planLimit.coerceAtLeast(2).takeIf { it < systemMax }
}

private data class PendingPdfScan(val uri: Uri, val mode: AiMode, val pageCount: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
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

    // Photos travel as one PDF, so the plan's page limit is the photo limit (Free 3, Pro 300, Max 1,000).
    val photoLimit by aiViewModel.photoLimit.collectAsStateWithLifecycle()
    val aiMultiGalleryPicker = rememberMultiGalleryPicker(maxItems = pickerLimit(photoLimit)) { picked ->
        if (picked.isNotEmpty() && pendingAiMode != null) {
            // The pre-Android 13 fallback picker cannot limit its selection.
            val uris = picked.take(photoLimit)
            if (picked.size > photoLimit) {
                scope.launch {
                    snackbarHostState.showSnackbar(resources.getString(R.string.scr_home_ai_file_limit, photoLimit))
                }
            }
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
                        resources.getString(R.string.scr_home_ai_file_limit, MAX_AI_FILES)
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
                                        text = stringResource(R.string.scr_home_credits, quota.pagesRemaining),
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
                                contentDescription = stringResource(R.string.set_settings_title)
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
                    text = stringResource(R.string.scr_home_question),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )

                val accents = rememberActionAccents()
                val startDocumentScan = rememberDocumentScanStarter()

                MainActionButton(
                    iconRes = R.drawable.ic_action_aperture,
                    title = stringResource(R.string.scr_home_scan_document),
                    subtitle = stringResource(R.string.scr_home_scan_document_subtitle),
                    onClick = startDocumentScan,
                    accentTint = accents.document,
                    contentPadding = cardPadding
                )

                MainActionButton(
                    iconRes = R.drawable.ic_action_gallery_stack,
                    title = stringResource(R.string.scr_home_image_to_text),
                    subtitle = stringResource(R.string.scr_home_image_to_text_subtitle),
                    onClick = { launchGalleryPicker() },
                    accentTint = accents.gallery,
                    contentPadding = cardPadding
                )

                MainActionButton(
                    iconRes = R.drawable.ic_action_document,
                    title = stringResource(R.string.scr_home_pdf_to_text),
                    subtitle = stringResource(R.string.scr_home_pdf_to_text_subtitle),
                    onClick = { pdfLauncher() },
                    accentTint = accents.pdf,
                    contentPadding = cardPadding
                )

                MainActionButton(
                    iconRes = R.drawable.ic_action_pdf_edit,
                    title = stringResource(R.string.pdf_tool_edit),
                    subtitle = stringResource(R.string.scr_home_edit_pdf_subtitle),
                    onClick = { navigator.go(Destination.PdfTools) },
                    accentTint = accents.edit,
                    contentPadding = cardPadding
                )

                MainActionButton(
                    iconRes = R.drawable.ic_action_qr,
                    title = stringResource(R.string.scr_home_scan_barcode),
                    subtitle = stringResource(R.string.scr_home_scan_barcode_subtitle),
                    onClick = { navigator.go(Destination.BarcodeScanner) },
                    accentTint = accents.qr,
                    contentPadding = cardPadding
                )

                if (showsAiScanCard(offeredFeatures)) {
                    MainActionButton(
                        iconRes = R.drawable.ic_action_spark,
                        title = stringResource(R.string.cmp_ai_scan_title),
                        subtitle = stringResource(R.string.scr_home_ai_scan_subtitle),
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
            fileName = UriNames.displayName(context, pending.uri) ?: stringResource(R.string.scr_home_this_pdf),
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

/**
 * Each tool keeps its own earthy hue so the grid stays easy to scan, harmonized toward the
 * palette's primary so the five colours read as one family in every theme the user picks.
 */
@Composable
private fun rememberActionAccents(): ActionAccents {
    val primary = MaterialTheme.colorScheme.primary
    val dark = LocalDarkMode.current
    return remember(primary, dark) {
        fun Long.tool() = Color(this).harmonizedWith(primary)
        if (dark) {
            ActionAccents(
                document = 0xFFB6A9DC.tool(),
                gallery = 0xFFD8B878.tool(),
                pdf = 0xFFE0A79B.tool(),
                edit = 0xFF8FBBD9.tool(),
                qr = 0xFF9CC7AE.tool(),
                ai = primary
            )
        } else {
            ActionAccents(
                document = 0xFF5E4E80.tool(),
                gallery = 0xFF9A7B3F.tool(),
                pdf = 0xFFA05A4E.tool(),
                edit = 0xFF3F6B88.tool(),
                qr = 0xFF4F7A63.tool(),
                ai = primary
            )
        }
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
