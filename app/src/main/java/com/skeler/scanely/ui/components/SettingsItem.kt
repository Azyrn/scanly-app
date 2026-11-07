package com.skeler.scanely.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

data class SettingsItem(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    @param:DrawableRes val iconRes: Int? = null,
    val avatarUrl: String? = null,
    val trailingIcon: ImageVector? = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
    val trailingContent: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit
)
