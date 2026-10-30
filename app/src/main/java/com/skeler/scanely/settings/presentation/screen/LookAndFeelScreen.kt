@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.settings.presentation.screen

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.skeler.scanely.R
import com.skeler.scanely.core.common.LocalDarkMode
import com.skeler.scanely.core.common.LocalSettings
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.settings.data.SettingsKeys
import com.skeler.scanely.settings.presentation.viewmodel.SettingsViewModel
import com.skeler.scanely.ui.components.ConnectedGroup
import com.skeler.scanely.ui.components.GroupPosition
import com.skeler.scanely.ui.components.PaletteChip
import com.skeler.scanely.ui.components.SettingSwitchTile
import com.skeler.scanely.ui.components.SettingsSectionHeader
import com.skeler.scanely.ui.components.illustrations.PaletteShowcase
import com.skeler.scanely.ui.theme.SeedColor
import com.skeler.scanely.ui.theme.SeedPalettes

@Composable
fun LookAndFeelScreen(
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val navigator = LocalNavigator.current

    val settings = LocalSettings.current
    val useDynamicColors = settings.useDynamicColors
    val seedColorIndex = settings.seedColorIndex
    val isOledMode = settings.isOledModeEnabled
    val isDark = LocalDarkMode.current

    val selectedName = when {
        useDynamicColors -> stringResource(R.string.set_palette_wallpaper)
        else -> SeedPalettes.ALL.getOrElse(seedColorIndex) { SeedPalettes.DEFAULT }.localizedName()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_look_and_feel_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.pdf_tools_back)
                        )
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
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PaletteShowcase(
                        modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    AnimatedContent(
                        targetState = selectedName,
                        transitionSpec = {
                            (fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 4 }) togetherWith
                                (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 4 })
                        },
                        label = "PaletteName"
                    ) { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = stringResource(R.string.set_palette_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )

                    Spacer(modifier = Modifier.height(28.dp))
                }
            }

            item {
                SettingsSectionHeader(
                    text = stringResource(R.string.set_section_color_palette),
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp)
                ) {
                    item(key = "dynamic") {
                        PaletteChip(
                            label = stringResource(R.string.set_palette_dynamic),
                            isSelected = useDynamicColors,
                            onClick = {
                                settingsViewModel.setBoolean(SettingsKeys.USE_DYNAMIC_COLORS, true)
                            }
                        )
                    }
                    itemsIndexed(
                        items = SeedPalettes.ALL,
                        key = { _, seedColor -> seedColor.name }
                    ) { index, seedColor ->
                        PaletteChip(
                            label = seedColor.localizedName(),
                            dotColor = Color(seedColor.primary),
                            dotBrush = if (seedColor.monochrome) BlackWhiteDot else null,
                            isSelected = !useDynamicColors && seedColorIndex == index,
                            onClick = {
                                settingsViewModel.setInt(SettingsKeys.SEED_COLOR_INDEX, index)
                                settingsViewModel.setBoolean(SettingsKeys.USE_DYNAMIC_COLORS, false)
                            }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(36.dp)) }

            item {
                SettingsSectionHeader(
                    text = stringResource(R.string.set_section_personalize),
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                ConnectedGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SettingSwitchTile(
                        title = stringResource(R.string.set_dark_theme),
                        subtitle = if (isDark) stringResource(R.string.set_on) else stringResource(R.string.set_off),
                        icon = Icons.Rounded.DarkMode,
                        checked = isDark,
                        position = GroupPosition.Top,
                        onCheckedChange = { checked ->
                            val newMode = if (checked) {
                                AppCompatDelegate.MODE_NIGHT_YES
                            } else {
                                AppCompatDelegate.MODE_NIGHT_NO
                            }
                            settingsViewModel.setInt(SettingsKeys.THEME_MODE, newMode)
                        }
                    )

                    SettingSwitchTile(
                        title = stringResource(R.string.set_pure_black_title),
                        subtitle = stringResource(R.string.set_pure_black_subtitle),
                        icon = Icons.Rounded.Contrast,
                        checked = isOledMode,
                        position = GroupPosition.Bottom,
                        onCheckedChange = { checked ->
                            settingsViewModel.setBoolean(SettingsKeys.IS_OLED_MODE_ENABLED, checked)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SeedColor.localizedName(): String = when (this) {
    SeedPalettes.CeruleanWave -> stringResource(R.string.set_palette_cerulean_wave)
    SeedPalettes.LavenderVolt -> stringResource(R.string.set_palette_lavender_volt)
    SeedPalettes.AmethystHaze -> stringResource(R.string.set_palette_amethyst_haze)
    SeedPalettes.EmeraldGrove -> stringResource(R.string.set_palette_emerald_grove)
    SeedPalettes.SunsetCoral -> stringResource(R.string.set_palette_sunset_coral)
    SeedPalettes.RoseBlush -> stringResource(R.string.set_palette_rose_blush)
    SeedPalettes.Monochrome -> stringResource(R.string.set_palette_monochrome)
    else -> name
}

private val BlackWhiteDot = Brush.linearGradient(
    0f to Color.Black,
    0.5f to Color.Black,
    0.5f to Color.White,
    1f to Color.White
)
