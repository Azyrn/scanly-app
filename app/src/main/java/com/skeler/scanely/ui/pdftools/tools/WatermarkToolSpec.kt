package com.skeler.scanely.ui.pdftools.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.skeler.scanely.R
import com.skeler.scanely.core.common.UriNames
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.PdfToolKey
import com.skeler.scanely.core.pdftools.ToolInput
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.stamp.StampLayer
import com.skeler.scanely.core.pdftools.stamp.WatermarkJob
import com.skeler.scanely.core.pdftools.stamp.WatermarkMark
import com.skeler.scanely.core.pdftools.stamp.WatermarkOpacity
import com.skeler.scanely.core.pdftools.stamp.WatermarkOptions
import com.skeler.scanely.core.pdftools.stamp.WatermarkPlacement
import com.skeler.scanely.core.pdftools.stamp.WatermarkRotation
import com.skeler.scanely.core.pdftools.stamp.WatermarkSize
import com.skeler.scanely.ui.components.OpenContent
import com.skeler.scanely.ui.pdftools.OPTIONS_ROW_GAP
import com.skeler.scanely.ui.pdftools.OptionsBody
import com.skeler.scanely.ui.pdftools.PrimaryActionButton
import com.skeler.scanely.ui.pdftools.SelectableRow
import com.skeler.scanely.ui.pdftools.ToolOptionsState
import com.skeler.scanely.ui.pdftools.ToolSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Inject

/**
 * Stamps text or a picked image on every page; the options object is [WatermarkOptions]. The image
 * is held as its content Uri and read by the job, under the picker's read grant.
 */
class WatermarkToolSpec @Inject constructor(private val job: WatermarkJob) : ToolSpec {
    override val tool = PdfTool.WATERMARK
    override val input = ToolInput.PDF

    @Composable
    override fun Options(state: ToolOptionsState, onRun: (options: Any) -> Unit) {
        val defaultText = stringResource(R.string.pdf_stamp_watermark_text_default)
        var useImage by rememberSaveable { mutableStateOf(false) }
        var text by rememberSaveable { mutableStateOf(defaultText) }
        var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
        var placement by rememberSaveable { mutableStateOf(WatermarkPlacement.CENTER) }
        var rotation by rememberSaveable { mutableStateOf(WatermarkRotation.RISING) }
        var opacity by rememberSaveable { mutableStateOf(WatermarkOpacity.MEDIUM) }
        var size by rememberSaveable { mutableStateOf(WatermarkSize.MEDIUM) }
        var layer by rememberSaveable { mutableStateOf(StampLayer.OVER) }

        OptionsBody(stringResource(R.string.pdf_stamp_watermark_body))
        ChipChoice(R.string.pdf_stamp_watermark_kind, KINDS, useImage, { useImage = it })
        if (useImage) {
            ImagePicker(imageUri) { imageUri = it }
        } else {
            val emptyError = if (text.isBlank()) stringResource(R.string.pdf_stamp_watermark_text_empty) else null
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace('\n', ' ').take(MAX_TEXT_LENGTH) },
                label = { Text(stringResource(R.string.pdf_stamp_watermark_text_label)) },
                singleLine = true,
                isError = emptyError != null,
                supportingText = emptyError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
        }
        ChipChoice(R.string.pdf_stamp_watermark_placement, PLACEMENTS, placement, { placement = it })
        ChipChoice(R.string.pdf_stamp_watermark_rotation, ROTATIONS, rotation, { rotation = it })
        ChipChoice(R.string.pdf_stamp_watermark_opacity, OPACITIES, opacity, { opacity = it })
        ChipChoice(R.string.pdf_stamp_watermark_size, SIZES, size, { size = it })
        LayerChoice(layer) { layer = it }

        val mark = if (useImage) {
            imageUri?.let { WatermarkMark.Image(it) }
        } else {
            text.trim().takeIf { it.isNotEmpty() }?.let { WatermarkMark.Text(it) }
        }
        PrimaryActionButton(
            text = stringResource(R.string.pdf_stamp_watermark_action),
            enabled = state.documents.isNotEmpty() && mark != null,
            onClick = {
                if (mark != null) onRun(WatermarkOptions(mark, placement, rotation, opacity, size, layer))
            }
        )
    }

    override suspend fun run(input: ToolJobInput, options: Any): ToolResult =
        job.watermark(input, options as WatermarkOptions)
}

@Composable
private fun ImagePicker(imageUri: String?, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(OpenContent()) { uri ->
        uri?.let { onPicked(it.toString()) }
    }
    val name = remember(imageUri) {
        imageUri?.let { UriNames.displayName(context, it.toUri()) ?: it.toUri().lastPathSegment.orEmpty() }
    }
    OptionsBody(
        if (name != null) {
            stringResource(R.string.pdf_stamp_watermark_image_chosen, name)
        } else {
            stringResource(R.string.pdf_stamp_watermark_image_none)
        }
    )
    OutlinedButton(onClick = { launcher.launch(arrayOf(IMAGE_MIME)) }, modifier = Modifier.fillMaxWidth()) {
        val label = if (imageUri == null) {
            R.string.pdf_stamp_watermark_pick_image
        } else {
            R.string.pdf_stamp_watermark_change_image
        }
        Text(stringResource(label))
    }
}

/** Rows rather than chips: "under" needs its scanned-page caveat spelled out before it's chosen. */
@Composable
private fun LayerChoice(layer: StampLayer, onSelect: (StampLayer) -> Unit) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(OPTIONS_ROW_GAP)
    ) {
        Text(stringResource(R.string.pdf_stamp_watermark_layer), style = MaterialTheme.typography.titleSmall)
        SelectableRow(
            title = stringResource(R.string.pdf_stamp_watermark_layer_over),
            body = stringResource(R.string.pdf_stamp_watermark_layer_over_body),
            selected = layer == StampLayer.OVER,
            onSelect = { onSelect(StampLayer.OVER) }
        )
        SelectableRow(
            title = stringResource(R.string.pdf_stamp_watermark_layer_under),
            body = stringResource(R.string.pdf_stamp_watermark_layer_under_body),
            selected = layer == StampLayer.UNDER,
            onSelect = { onSelect(StampLayer.UNDER) }
        )
    }
}

private const val IMAGE_MIME = "image/*"
private const val MAX_TEXT_LENGTH = 100

private val KINDS = listOf(
    false to R.string.pdf_stamp_watermark_kind_text,
    true to R.string.pdf_stamp_watermark_kind_image
)

private val PLACEMENTS = listOf(
    WatermarkPlacement.CENTER to R.string.pdf_stamp_watermark_center,
    WatermarkPlacement.TOP to R.string.pdf_stamp_watermark_top,
    WatermarkPlacement.BOTTOM to R.string.pdf_stamp_watermark_bottom,
    WatermarkPlacement.TILED to R.string.pdf_stamp_watermark_tiled
)

private val ROTATIONS = listOf(
    WatermarkRotation.NONE to R.string.pdf_stamp_watermark_rotation_none,
    WatermarkRotation.RISING to R.string.pdf_stamp_watermark_rotation_rising,
    WatermarkRotation.FALLING to R.string.pdf_stamp_watermark_rotation_falling
)

private val OPACITIES = listOf(
    WatermarkOpacity.LIGHT to R.string.pdf_stamp_watermark_opacity_light,
    WatermarkOpacity.MEDIUM to R.string.pdf_stamp_watermark_opacity_medium,
    WatermarkOpacity.STRONG to R.string.pdf_stamp_watermark_opacity_strong
)

private val SIZES = listOf(
    WatermarkSize.SMALL to R.string.pdf_stamp_size_small,
    WatermarkSize.MEDIUM to R.string.pdf_stamp_size_medium,
    WatermarkSize.LARGE to R.string.pdf_stamp_size_large
)

@Module
@InstallIn(SingletonComponent::class)
abstract class WatermarkToolModule {
    @Binds
    @IntoMap
    @PdfToolKey(PdfTool.WATERMARK)
    abstract fun bind(spec: WatermarkToolSpec): ToolSpec
}
