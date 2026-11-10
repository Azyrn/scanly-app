@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skeler.scanely.R
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.components.ExportContent
import com.skeler.scanely.ui.components.ExportMenu
import com.skeler.scanely.ui.components.SaveIcon
import com.skeler.scanely.ui.components.TEXT_EXPORT_FORMATS
import com.skeler.scanely.ui.components.rememberTextExporter
import com.skeler.scanely.ui.components.unavailableFormats

@Composable
fun TextComposeScreen() {
    val navigator = LocalNavigator.current
    val exportText = rememberTextExporter()

    var text by remember { mutableStateOf("") }
    var exportMenuOpen by remember { mutableStateOf(false) }

    // What you typed is what you get: this screen has no Markdown view.
    val preview = remember(text) { ExportContent(text) }
    val disabledFormats = remember(preview) { unavailableFormats(preview) }

    val counts = remember(text) {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        words to text.length
    }

    val readingStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 17.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.015.sp,
        textDirection = TextDirection.Content,
        color = MaterialTheme.colorScheme.onSurface
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cmp_chip_text_to_pdf), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.pdf_tools_back)
                        )
                    }
                },
                actions = {
                    Box {
                        FilledTonalIconButton(
                            onClick = { exportMenuOpen = true },
                            shape = RoundedCornerShape(14.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = SaveIcon,
                                contentDescription = stringResource(R.string.cmp_export),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        ExportMenu(
                            expanded = exportMenuOpen,
                            formats = TEXT_EXPORT_FORMATS,
                            disabledFormats = disabledFormats,
                            onSelect = { format ->
                                exportMenuOpen = false
                                exportText(preview, format)
                            },
                            onDismiss = { exportMenuOpen = false }
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.scr_text_compose_placeholder),
                        style = readingStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = readingStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = stringResource(
                    R.string.cmp_word_char_counts,
                    pluralStringResource(R.plurals.cmp_word_count, counts.first, counts.first),
                    pluralStringResource(R.plurals.cmp_char_count, counts.second, counts.second)
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
    }
}
