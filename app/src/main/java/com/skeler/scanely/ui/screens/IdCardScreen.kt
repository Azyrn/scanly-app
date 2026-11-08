package com.skeler.scanely.ui.screens

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.skeler.scanely.R
import com.skeler.scanely.core.idcard.CopyLook
import com.skeler.scanely.core.idcard.IdCardLayout
import com.skeler.scanely.core.pdf.DocumentIntents
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.scan.ScanMode
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.components.rememberStorageAccess
import com.skeler.scanely.ui.viewmodel.IdCardUiState
import com.skeler.scanely.ui.viewmodel.IdCardViewModel
import com.skeler.scanely.ui.viewmodel.IdCopyEvent
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.NumberFormat
import kotlin.math.roundToInt

/** The ID copy: a live, true-size A4 preview with the few choices a copier would offer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current as ComponentActivity
    val navigator = LocalNavigator.current
    val vm: IdCardViewModel = hiltViewModel(activity)
    val state by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val withStorageAccess = rememberStorageAccess()

    // Retake side 0/1, or 1 to add the back: the scan camera in ID mode, same card/passport; the new side
    // comes back through IdCardViewModel.replaceSide.
    fun rescan(index: Int) = navigator.go(
        Destination.ScanCamera(mode = ScanMode.ID_CARD.name, kind = state.kind.name, replaceSide = index)
    )

    val leave = {
        vm.clear()
        navigator.back()
    }
    BackHandler(onBack = leave)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    LaunchedEffect(state.event) {
        when (val event = state.event) {
            is IdCopyEvent.Saved -> DocumentIntents.openDocument(
                context,
                event.uri,
                event.mimeType,
                resources.getString(R.string.scr_scan_review_open_with, "PDF")
            )
            is IdCopyEvent.Share -> DocumentIntents.shareDocument(
                context,
                context.cacheUri(event.file),
                ScanExporter.PDF_MIME_TYPE
            )
            is IdCopyEvent.Print -> printPdf(context, event.file, resources.getString(R.string.id_print_job))
            null -> Unit
        }
        if (state.event != null) vm.consumeEvent()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Like Results: a centred title that scrolls with the page; only the back button stays.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(if (state.kind.sides == 1) R.string.id_title_passport else R.string.id_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(8.dp))
                PagePreview(state)
                Spacer(Modifier.height(20.dp))
                SideControls(
                    state = state,
                    onRetake = { index -> rescan(index) },
                    onAddBack = { rescan(1) },
                    onRotate = vm::rotateSide,
                    onSwap = vm::swapSides
                )
                Spacer(Modifier.height(8.dp))
                SizePicker(
                    steps = IdCardLayout.sizeSteps(state.kind),
                    selected = state.scale,
                    onSelect = vm::setScale
                )
                Spacer(Modifier.height(16.dp))
                LookPicker(state.look, vm::setLook)
                Spacer(Modifier.height(14.dp))
                WatermarkField(state.watermark, vm::setWatermark)
                // room to scroll the last field clear of the floating bar
                Spacer(Modifier.height(BAR_HEIGHT + BAR_MARGIN * 2))
            }
            ActionBar(
                enabled = state.sides.isNotEmpty() && !state.isWorking,
                onSavePdf = { withStorageAccess(vm::savePdf) },
                onSaveImage = { withStorageAccess(vm::saveImage) },
                onPrint = vm::print,
                onShare = vm::share,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            FilledTonalIconButton(
                onClick = leave,
                shape = RoundedCornerShape(16.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.pdf_tools_back))
            }
            if (state.isWorking || state.isLoading) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f)),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        }
    }
}

/** The page as it will print: white A4 on a soft shadow, the dominant element of the screen. */
@Composable
private fun PagePreview(state: IdCardUiState) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(PREVIEW_WIDTH_FRACTION)
            .aspectRatio(IdCardLayout.A4_WIDTH_MM / IdCardLayout.A4_HEIGHT_MM)
            .shadow(10.dp, RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        color = androidx.compose.ui.graphics.Color.White
    ) {
        state.preview?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.id_preview_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun SideControls(
    state: IdCardUiState,
    onRetake: (Int) -> Unit,
    onAddBack: () -> Unit,
    onRotate: (Int) -> Unit,
    onSwap: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        state.sides.indices.forEach { index ->
            SideRow(
                label = stringResource(
                    when {
                        state.kind.sides == 1 -> R.string.id_side_page
                        index == 0 -> R.string.id_side_front
                        else -> R.string.id_side_back
                    }
                ),
                offShape = state.offShapeSide == index,
                onRetake = { onRetake(index) },
                onRotate = { onRotate(index) }
            )
            Spacer(Modifier.height(8.dp))
        }
        AnimatedVisibility(state.missingBack) {
            FilledTonalButton(
                onClick = onAddBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.id_add_back), fontWeight = FontWeight.SemiBold)
            }
        }
        if (state.sides.size == 2) {
            TextButton(onClick = onSwap, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.id_swap))
            }
        }
    }
}

@Composable
private fun SideRow(label: String, offShape: Boolean, onRetake: () -> Unit, onRotate: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (offShape) {
                    Text(
                        stringResource(R.string.id_off_shape),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            SmallAction(Icons.Rounded.RotateRight, stringResource(R.string.id_rotate), onRotate)
            TextButton(onClick = onRetake) { Text(stringResource(R.string.id_retake)) }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = description) }
}

/**
 * How big the sides print: true size (what a copier does, and what offices expect) or one of the
 * enlargements that still fit on A4. Front and back always share one size — they are the same card.
 */
@Composable
private fun SizePicker(steps: List<Float>, selected: Float, onSelect: (Float) -> Unit) {
    if (steps.size < 2) return
    val locale = LocalConfiguration.current.locales[0]
    val index = steps.indexOf(selected).coerceAtLeast(0)
    val slider = remember(steps) {
        SliderState(steps = steps.size - 2, trackRange = 0f..steps.lastIndex.toFloat())
    }
    slider.value = index.toFloat()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.id_size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (index == 0) {
                        stringResource(R.string.id_size_actual)
                    } else {
                        NumberFormat.getPercentInstance(locale).format(steps[index])
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                state = slider,
                onValueChange = { onSelect(steps[it.roundToInt().coerceIn(steps.indices)]) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookPicker(selected: CopyLook, onSelect: (CopyLook) -> Unit) {
    val looks = CopyLook.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        looks.forEachIndexed { index, look ->
            SegmentedButton(
                selected = look == selected,
                onClick = { onSelect(look) },
                shape = SegmentedButtonDefaults.itemShape(index, looks.size)
            ) {
                Text(
                    stringResource(
                        when (look) {
                            CopyLook.COLOUR -> R.string.id_look_colour
                            CopyLook.GREY -> R.string.id_look_grey
                            CopyLook.PHOTOCOPY -> R.string.id_look_photocopy
                        }
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun WatermarkField(text: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Verified, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.id_watermark_clear))
                }
            }
        },
        label = { Text(stringResource(R.string.id_watermark_label)) },
        placeholder = { Text(stringResource(R.string.id_watermark_hint)) },
        supportingText = { Text(stringResource(R.string.id_watermark_why)) },
        shape = MaterialTheme.shapes.large
    )
}

/**
 * A floating, translucent dock over the content: the page fades out beneath it instead of being cut off
 * by a solid strip. Save PDF is the one filled action; image, print and share sit beside it as quiet icons.
 */
@Composable
private fun ActionBar(
    enabled: Boolean,
    onSavePdf: () -> Unit,
    onSaveImage: () -> Unit,
    onPrint: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    FADE_SOLID_FROM to colors.background.copy(alpha = FADE_ALPHA),
                    1f to colors.background.copy(alpha = FADE_ALPHA)
                )
            )
            .padding(start = BAR_MARGIN, end = BAR_MARGIN, top = BAR_MARGIN * 2, bottom = BAR_MARGIN)
    ) {
        Surface(
            shape = CircleShape,
            color = colors.surfaceContainerHighest.copy(alpha = DOCK_ALPHA),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Button(
                    onClick = onSavePdf,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(BAR_HEIGHT - 12.dp),
                    shape = CircleShape
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.id_save_pdf),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(4.dp))
                DockAction(
                    painterResource(R.drawable.ic_line_image),
                    stringResource(R.string.id_save_image),
                    enabled,
                    onSaveImage
                )
                DockAction(
                    painterResource(R.drawable.ic_line_print),
                    stringResource(R.string.id_print),
                    enabled,
                    onPrint
                )
                DockAction(
                    painterResource(R.drawable.ic_line_share),
                    stringResource(R.string.id_share),
                    enabled,
                    onShare
                )
            }
        }
    }
}

@Composable
private fun DockAction(icon: Painter, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(BAR_HEIGHT - 12.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    ) {
        Icon(icon, contentDescription = description)
    }
}

private val BAR_HEIGHT = 64.dp
private val BAR_MARGIN = 16.dp
private const val DOCK_ALPHA = 0.92f
private const val FADE_ALPHA = 0.96f
private const val FADE_SOLID_FROM = 0.55f

private fun Context.cacheUri(file: File) = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

/** Hands a finished one-page PDF to the system print dialog, at true size (no scaling to fit). */
private fun printPdf(context: Context, file: File, jobName: String) {
    val manager = context.getSystemService(PrintManager::class.java) ?: return
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(file.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: java.io.IOException) {
                callback.onWriteFailed(e.message)
            }
        }
    }
    val attributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .build()
    manager.print(jobName, adapter, attributes)
}

private const val PREVIEW_WIDTH_FRACTION = 0.78f
