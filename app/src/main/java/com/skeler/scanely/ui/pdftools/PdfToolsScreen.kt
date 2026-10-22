@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.ui.pdftools

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeler.scanely.R
import com.skeler.scanely.core.common.LocalDarkMode
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.core.pdftools.ToolSection
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.components.PaywallSheet
import com.skeler.scanely.ui.pdfeditor.EditorTool
import com.skeler.scanely.ui.pdfeditor.rememberPdfEditorStarter
import com.skeler.scanely.ui.viewmodel.PaywallViewModel

/**
 * The grid behind the home screen's Edit PDF card: one tile per tool, grouped by section.
 *
 * A locked tool is badged and opens the paywall instead of a file picker, because asking for a
 * file and only then saying no wastes the pick. Tools that are really modes of the editor hand
 * the file to it; everything else runs its own flow screen.
 */
@Composable
fun PdfToolsScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val navigator = LocalNavigator.current
    val vm: PdfToolsViewModel = hiltViewModel()
    val isPro by vm.isPro.collectAsStateWithLifecycle()
    val startEditor = rememberPdfEditorStarter()

    val paywallViewModel: PaywallViewModel = hiltViewModel(activity)
    val paywallState by paywallViewModel.state.collectAsStateWithLifecycle()
    var showPaywall by remember { mutableStateOf(false) }

    val onTool: (PdfTool) -> Unit = { tool ->
        when {
            tool.requiresPro && !isPro -> showPaywall = true
            tool.opensEditor -> startEditor(if (tool == PdfTool.SIGN) EditorTool.SIGNATURE else null)
            else -> navigator.go(Destination.PdfTool(tool.name))
        }
    }

    // Same frame as the Results screen: the title scrolls away with the tools while the back
    // button stays pinned over them.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ToolGrid(
            tools = vm.tools,
            isPro = isPro,
            onTool = onTool,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        )
        FilledTonalIconButton(
            onClick = navigator::back,
            shape = BACK_SHAPE,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = BACK_INSET_START, top = BACK_INSET_TOP)
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.pdf_tools_back)
            )
        }
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
            reason = stringResource(R.string.pdf_tools_paywall_reason),
            isFeatureLock = true
        )
    }
}

/**
 * One labelled group per [ToolSection], each a single raised container holding its tiles. The
 * tiles themselves carry no box, so the screen reads as four groups rather than 25 cards.
 */
@Composable
private fun ToolGrid(
    tools: List<PdfTool>,
    isPro: Boolean,
    onTool: (PdfTool) -> Unit,
    modifier: Modifier = Modifier
) {
    val sections = remember(tools) {
        ToolSection.entries.mapNotNull { section ->
            tools.filter { it.section == section }.takeIf { it.isNotEmpty() }?.let { section to it }
        }
    }

    BoxWithConstraints(modifier) {
        val columns = if (maxWidth >= WIDE_LAYOUT_MIN) COLUMNS_WIDE else COLUMNS
        LazyColumn(
            contentPadding = PaddingValues(
                start = SCREEN_PADDING,
                end = SCREEN_PADDING,
                bottom = SCREEN_PADDING +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)
        ) {
            item(key = TITLE_KEY) {
                Text(
                    text = stringResource(R.string.pdf_tools_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = TITLE_PADDING_V)
                        .semantics { heading() }
                )
            }
            items(sections, key = { (section, _) -> section.name }) { (section, sectionTools) ->
                ToolSectionBlock(
                    title = stringResource(section.titleRes),
                    tools = sectionTools,
                    columns = columnsFor(sectionTools.size, columns),
                    isPro = isPro,
                    onTool = onTool
                )
            }
        }
    }
}

/** A group that would leave one tile alone on a phone's last row of three goes four across. */
private fun columnsFor(count: Int, columns: Int): Int =
    if (columns == COLUMNS && count % COLUMNS == 1 && count % COLUMNS_SHORT_GROUP == 0) {
        COLUMNS_SHORT_GROUP
    } else {
        columns
    }

@Composable
private fun ToolSectionBlock(
    title: String,
    tools: List<PdfTool>,
    columns: Int,
    isPro: Boolean,
    onTool: (PdfTool) -> Unit
) {
    // White on the off-white page in light; one step up from the page in dark, where the lowest
    // container would sit below it.
    val container = if (LocalDarkMode.current) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }

    Column(verticalArrangement = Arrangement.spacedBy(HEADER_GAP)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = HEADER_INSET)
                .semantics { heading() }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(container)
                .padding(CONTAINER_PADDING)
        ) {
            tools.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { tool ->
                        ToolTile(
                            tool = tool,
                            locked = tool.requiresPro && !isPro,
                            onClick = { onTool(tool) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Short last rows keep the columns of the rows above.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * The label is read once, as the tile's description, with the Pro badge's text after it when
 * shown, so TalkBack says "Compress PDF, Pro, Button" rather than the name twice.
 */
@Composable
private fun ToolTile(
    tool: PdfTool,
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = stringResource(tool.labelRes)

    Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TILE_PADDING_H, vertical = TILE_PADDING_V)
        ) {
            ToolIcon(tool, Modifier.size(dimensionResource(R.dimen.pdf_tool_icon_size)))
            Spacer(Modifier.height(ICON_LABEL_GAP))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
        if (locked) {
            ProBadge(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(BADGE_INSET)
            )
        }
    }
}

private val ToolSection.titleRes: Int
    get() = when (this) {
        ToolSection.ORGANIZE -> R.string.pdf_tools_section_organize
        ToolSection.CONVERT -> R.string.pdf_tools_section_convert
        ToolSection.EDIT -> R.string.pdf_tools_section_edit
        ToolSection.IMAGE -> R.string.pdf_tools_section_image
    }

/** The layers share one grid, so stacking them at one size lines them up. */
@Composable
private fun ToolIcon(tool: PdfTool, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier) {
        tool.layers.forEach { layer ->
            Icon(
                imageVector = ImageVector.vectorResource(layer.res),
                contentDescription = null,
                tint = layer.format?.accent() ?: ink,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

@Composable
private fun ProBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.pdf_tools_pro_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .padding(horizontal = BADGE_PADDING_H, vertical = BADGE_PADDING_V)
    )
}

private const val COLUMNS = 3
private const val TITLE_KEY = "title"
private val BACK_SHAPE = RoundedCornerShape(16.dp)
private val BACK_INSET_START = 12.dp
private val BACK_INSET_TOP = 8.dp
private val TITLE_PADDING_V = 14.dp
private const val COLUMNS_WIDE = 6
private const val COLUMNS_SHORT_GROUP = 4
private val WIDE_LAYOUT_MIN = 600.dp
private val SCREEN_PADDING = 16.dp
private val SECTION_SPACING = 24.dp
private val HEADER_GAP = 10.dp
private val HEADER_INSET = 4.dp
private val CONTAINER_PADDING = 6.dp
private val TILE_PADDING_H = 4.dp
private val TILE_PADDING_V = 10.dp
private val ICON_LABEL_GAP = 8.dp
private val BADGE_INSET = 4.dp
private val BADGE_PADDING_H = 6.dp
private val BADGE_PADDING_V = 1.dp
