@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.settings.presentation.screen

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.StarRate
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.skeler.scanely.BuildConfig
import com.skeler.scanely.R
import com.skeler.scanely.auth.presentation.viewmodel.AuthViewModel
import com.skeler.scanely.core.barcode.BarcodeEngine
import com.skeler.scanely.core.common.AppLanguage
import com.skeler.scanely.core.ocr.TextOcrEngine
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.settings.data.SettingsKeys
import com.skeler.scanely.settings.presentation.FontLicensesDialog
import com.skeler.scanely.settings.presentation.LegalLinks
import com.skeler.scanely.settings.presentation.viewmodel.SettingsViewModel
import com.skeler.scanely.ui.components.PaywallSheet
import com.skeler.scanely.ui.components.SettingsItem
import com.skeler.scanely.ui.components.SettingsListSection
import com.skeler.scanely.ui.viewmodel.PaywallViewModel
import com.skeler.scanely.ui.viewmodel.planRow

private const val DEVELOPER_URL = "https://github.com/Azyrn/scanly-app"
private const val DEVELOPER_AVATAR_URL = "https://github.com/Azyrn.png"
private const val TELEGRAM_URL = "https://telegram.me/ScanlyOCR"

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val activity = LocalActivity.current as ComponentActivity

    val currentUser by authViewModel.currentUser.collectAsState()
    var showAccountMenu by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }

    val paywallViewModel: PaywallViewModel = hiltViewModel(activity)
    val paywallState by paywallViewModel.state.collectAsState()
    val isPrivacyOptionsRequired by paywallViewModel.isPrivacyOptionsRequired.collectAsState()
    var showPaywall by remember { mutableStateOf(false) }
    var showRemoveKeyDialog by remember { mutableStateOf(false) }
    var showFontLicenses by remember { mutableStateOf(false) }

    val engineId by settingsViewModel.getString(SettingsKeys.BARCODE_ENGINE)
        .collectAsState(initial = BarcodeEngine.DEFAULT.id)
    val barcodeEngine = BarcodeEngine.fromId(engineId)
    var showEngineDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val languageTag = remember { AppLanguage.current(activity) }

    val textEngineId by settingsViewModel.getString(SettingsKeys.TEXT_OCR_ENGINE)
        .collectAsState(initial = TextOcrEngine.DEFAULT.id)
    val textEngine = TextOcrEngine.fromId(textEngineId)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.pdf_tools_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                val user = currentUser
                SettingsListSection(
                    title = stringResource(R.string.set_section_account),
                    items = buildList {
                        if (user == null) {
                            add(
                                SettingsItem(
                                    title = stringResource(R.string.set_sign_in),
                                    subtitle = stringResource(R.string.set_sign_in_subtitle),
                                    icon = Icons.AutoMirrored.Rounded.Login,
                                    onClick = { navigator.go(Destination.Auth) }
                                )
                            )
                        } else {
                            add(
                                SettingsItem(
                                    title = user.label,
                                    subtitle = user.email ?: stringResource(R.string.set_signed_in),
                                    icon = Icons.Rounded.AccountCircle,
                                    avatarUrl = user.photoUrl,
                                    trailingContent = {
                                        AccountMenuButton(
                                            expanded = showAccountMenu,
                                            uid = user.uid,
                                            onExpandedChange = { showAccountMenu = it },
                                            onSignOut = {
                                                showAccountMenu = false
                                                showSignOutDialog = true
                                            }
                                        )
                                    },
                                    onClick = { showAccountMenu = true }
                                )
                            )
                            // Plan and remaining pages come from the server, never from local state.
                            val plan = planRow(activity, paywallState)
                            add(
                                SettingsItem(
                                    title = plan.title,
                                    subtitle = plan.subtitle,
                                    icon = Icons.Rounded.AutoAwesome,
                                    onClick = { showPaywall = true }
                                )
                            )

                            // The durable home for licence management; the paywall sheet is only
                            // the in-the-moment prompt. Removing the key here frees its slot.
                            val license = paywallState.license
                            if (license.isActive) {
                                val entitlement = license.entitlement
                                add(
                                    SettingsItem(
                                        title = stringResource(R.string.set_pro_key_title),
                                        subtitle = if (entitlement != null &&
                                            entitlement.maxActivations > 0
                                        ) {
                                            stringResource(
                                                R.string.set_pro_key_active_devices,
                                                entitlement.activationsUsed,
                                                entitlement.maxActivations
                                            )
                                        } else {
                                            stringResource(R.string.set_pro_key_active_here)
                                        },
                                        icon = Icons.Rounded.Key,
                                        trailingIcon = Icons.Rounded.Delete,
                                        onClick = { showRemoveKeyDialog = true }
                                    )
                                )
                            }

                            add(
                                SettingsItem(
                                    title = stringResource(R.string.set_delete_account_title),
                                    subtitle = stringResource(R.string.set_delete_account_subtitle),
                                    icon = Icons.Rounded.PersonRemove,
                                    onClick = { showDeleteAccountDialog = true }
                                )
                            )
                        }
                    }
                )
            }

            item {
                SettingsListSection(
                    title = stringResource(R.string.set_section_customize),
                    items = listOf(
                        SettingsItem(
                            title = stringResource(R.string.set_look_and_feel_title),
                            subtitle = stringResource(R.string.set_look_and_feel_subtitle),
                            iconRes = R.drawable.ic_look_and_feel,
                            onClick = { navigator.go(Destination.LookAndFeel) }
                        ),
                        SettingsItem(
                            title = stringResource(R.string.settings_language_title),
                            subtitle = AppLanguage.displayName(languageTag)
                                ?: stringResource(R.string.settings_language_system),
                            icon = Icons.Rounded.Language,
                            onClick = { showLanguageDialog = true }
                        ),
                        SettingsItem(
                            title = stringResource(R.string.set_text_recognition_title),
                            subtitle = stringResource(R.string.set_text_recognition_subtitle, textEngine.displayName),
                            icon = Icons.Rounded.TextFields,
                            onClick = { navigator.go(Destination.TextRecognition) }
                        ),
                        SettingsItem(
                            title = stringResource(R.string.set_barcode_engine_title),
                            subtitle = barcodeEngine.displayName,
                            icon = Icons.Rounded.QrCodeScanner,
                            onClick = { showEngineDialog = true }
                        ),
                        SettingsItem(
                            title = stringResource(R.string.set_custom_endpoint_title),
                            subtitle = stringResource(R.string.set_custom_endpoint_subtitle),
                            icon = Icons.Rounded.Hub,
                            onClick = { navigator.go(Destination.CustomProvider) }
                        )
                    )
                )
            }

            item {
                SettingsListSection(
                    title = stringResource(R.string.set_section_about),
                    items = listOfNotNull(
                        SettingsItem(
                            title = stringResource(R.string.set_privacy_options_title),
                            subtitle = stringResource(R.string.set_privacy_options_subtitle),
                            icon = Icons.Rounded.PrivacyTip,
                            onClick = { paywallViewModel.showPrivacyOptions(activity) }
                        ).takeIf { isPrivacyOptionsRequired },
                        SettingsItem(
                            title = stringResource(R.string.set_rate_title),
                            subtitle = stringResource(R.string.set_rate_subtitle),
                            icon = Icons.Rounded.StarRate,
                            trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { activity.openStoreListing() }
                        ),
                        SettingsItem(
                            title = stringResource(R.string.set_privacy_policy_title),
                            subtitle = stringResource(R.string.set_privacy_policy_subtitle),
                            icon = Icons.Rounded.Policy,
                            trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { uriHandler.openUri(LegalLinks.PRIVACY_POLICY) }
                        ),
                        SettingsItem(
                            title = stringResource(R.string.set_terms_title),
                            icon = Icons.Rounded.Gavel,
                            trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { uriHandler.openUri(LegalLinks.TERMS) }
                        ),
                        SettingsItem(
                            title = stringResource(R.string.font_licenses_title),
                            subtitle = stringResource(R.string.font_licenses_subtitle),
                            icon = Icons.Rounded.FontDownload,
                            onClick = { showFontLicenses = true }
                        ),
                        SettingsItem(
                            title = "Azyrn",
                            subtitle = stringResource(R.string.set_developer_subtitle),
                            avatarUrl = DEVELOPER_AVATAR_URL,
                            trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { uriHandler.openUri(DEVELOPER_URL) }
                        ),
                        SettingsItem(
                            title = "Telegram",
                            subtitle = stringResource(R.string.set_telegram_subtitle),
                            iconRes = R.drawable.ic_telegram,
                            trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { uriHandler.openUri(TELEGRAM_URL) }
                        )
                    )
                )
            }

            item {
                Text(
                    text = stringResource(R.string.set_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        }
    }

    if (showFontLicenses) {
        FontLicensesDialog(onDismiss = { showFontLicenses = false })
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
            onRetry = { paywallViewModel.refresh() }
        )
    }

    if (showRemoveKeyDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveKeyDialog = false },
            icon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            title = { Text(stringResource(R.string.set_remove_key_title)) },
            text = {
                Text(stringResource(R.string.set_remove_key_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        paywallViewModel.removeKey()
                        showRemoveKeyDialog = false
                    }
                ) {
                    Text(stringResource(R.string.set_remove_key_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveKeyDialog = false }) {
                    Text(stringResource(R.string.settings_language_cancel))
                }
            }
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            icon = { Icon(Icons.Rounded.PersonRemove, contentDescription = null) },
            title = { Text(stringResource(R.string.set_delete_account_dialog_title)) },
            text = {
                Text(stringResource(R.string.set_delete_account_dialog_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        authViewModel.deleteAccount(activity)
                        showDeleteAccountDialog = false
                    }
                ) {
                    Text(stringResource(R.string.chat_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) {
                    Text(stringResource(R.string.settings_language_cancel))
                }
            }
        )
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
            title = { Text(stringResource(R.string.set_sign_out_dialog_title)) },
            text = {
                Text(stringResource(R.string.set_sign_out_dialog_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        authViewModel.signOut(activity)
                        showSignOutDialog = false
                    }
                ) {
                    Text(stringResource(R.string.set_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(R.string.settings_language_cancel))
                }
            }
        )
    }

    if (showLanguageDialog) {
        LanguageDialog(
            current = languageTag,
            onSelect = { tag ->
                showLanguageDialog = false
                if (tag != languageTag) AppLanguage.set(activity, tag)
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showEngineDialog) {
        BarcodeEngineDialog(
            current = barcodeEngine,
            onSelect = { engine ->
                settingsViewModel.setString(SettingsKeys.BARCODE_ENGINE, engine.id)
                showEngineDialog = false
            },
            onDismiss = { showEngineDialog = false }
        )
    }
}

@Composable
private fun AccountMenuButton(
    expanded: Boolean,
    uid: String,
    onExpandedChange: (Boolean) -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current

    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.set_account_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.set_account_id_label), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = uid,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    context.copyAccountId(uid)
                    onExpandedChange(false)
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.set_sign_out)) },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                },
                onClick = onSignOut
            )
        }
    }
}

private fun Context.copyAccountId(uid: String) {
    getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Scanly account ID", uid))
    Toast.makeText(this, getString(R.string.set_account_id_copied), Toast.LENGTH_SHORT).show()
}

/** The Play listing's id; debug builds carry a ".debug" suffix that has no listing. */
private const val PLAY_LISTING_ID = "com.skeler.scanely"

/** Opens Scanly's Play listing in the Play Store app, or in the browser when there is none. */
private fun Context.openStoreListing() {
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=$PLAY_LISTING_ID".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(market)
    } catch (_: ActivityNotFoundException) {
        startActivity(
            Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$PLAY_LISTING_ID".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun LanguageDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf("" to stringResource(R.string.settings_language_system)) + AppLanguage.supported
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Language, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_language_title)) },
        text = {
            LazyColumn {
                items(options.size) { index ->
                    val (tag, name) = options[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onSelect(tag) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = tag.equals(current, ignoreCase = true),
                            onClick = { onSelect(tag) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_language_cancel)) }
        }
    )
}

@Composable
private fun BarcodeEngineDialog(
    current: BarcodeEngine,
    onSelect: (BarcodeEngine) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null) },
        title = { Text(stringResource(R.string.set_barcode_engine_title)) },
        text = {
            Column {
                BarcodeEngine.entries.forEach { engine ->
                    val description = when (engine) {
                        BarcodeEngine.ML_KIT -> stringResource(R.string.set_barcode_mlkit_description)
                        BarcodeEngine.ZXING_CPP -> stringResource(R.string.set_barcode_zxing_description)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onSelect(engine) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = engine == current,
                            onClick = { onSelect(engine) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = engine.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pdf_tool_done)) }
        }
    )
}
