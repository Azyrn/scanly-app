@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.settings.presentation.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.QrCodeScanner
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.skeler.scanely.BuildConfig
import com.skeler.scanely.R
import com.skeler.scanely.auth.presentation.viewmodel.AuthViewModel
import com.skeler.scanely.core.barcode.BarcodeEngine
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

    val textEngineId by settingsViewModel.getString(SettingsKeys.TEXT_OCR_ENGINE)
        .collectAsState(initial = TextOcrEngine.DEFAULT.id)
    val textEngine = TextOcrEngine.fromId(textEngineId)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
                    title = "Account",
                    items = buildList {
                        if (user == null) {
                            add(
                                SettingsItem(
                                    title = "Sign in",
                                    subtitle = "Required for AI scans",
                                    icon = Icons.AutoMirrored.Rounded.Login,
                                    onClick = { navigator.go(Destination.Auth) }
                                )
                            )
                        } else {
                            add(
                                SettingsItem(
                                    title = user.label,
                                    subtitle = user.email ?: "Signed in",
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
                            val plan = planRow(paywallState)
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
                                        title = "Scanly Pro key",
                                        subtitle = if (entitlement != null &&
                                            entitlement.maxActivations > 0
                                        ) {
                                            "Active · ${entitlement.activationsUsed} of " +
                                                "${entitlement.maxActivations} devices"
                                        } else {
                                            "Active on this device"
                                        },
                                        icon = Icons.Rounded.Key,
                                        trailingIcon = Icons.Rounded.Delete,
                                        onClick = { showRemoveKeyDialog = true }
                                    )
                                )
                            }

                            add(
                                SettingsItem(
                                    title = "Delete account",
                                    subtitle = "Permanently erase your account and data",
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
                    title = "Customize",
                    items = listOf(
                        SettingsItem(
                            title = "Look & Feel",
                            subtitle = "Color palette, dark theme, pure black",
                            iconRes = R.drawable.ic_look_and_feel,
                            onClick = { navigator.go(Destination.LookAndFeel) }
                        ),
                        SettingsItem(
                            title = "Text Recognition",
                            subtitle = "${textEngine.displayName} · scripts & preprocessing",
                            icon = Icons.Rounded.TextFields,
                            onClick = { navigator.go(Destination.TextRecognition) }
                        ),
                        SettingsItem(
                            title = "Barcode Scanner Engine",
                            subtitle = barcodeEngine.displayName,
                            icon = Icons.Rounded.QrCodeScanner,
                            onClick = { showEngineDialog = true }
                        ),
                        SettingsItem(
                            title = "Custom endpoint",
                            subtitle = "Connect your own AI provider · Pro",
                            icon = Icons.Rounded.Hub,
                            onClick = { navigator.go(Destination.CustomProvider) }
                        )
                    )
                )
            }

            item {
                SettingsListSection(
                    title = "About",
                    items = listOfNotNull(
                        SettingsItem(
                            title = "Privacy options",
                            subtitle = "Manage ad consent choices",
                            icon = Icons.Rounded.PrivacyTip,
                            onClick = { paywallViewModel.showPrivacyOptions(activity) }
                        ).takeIf { isPrivacyOptionsRequired },
                        SettingsItem(
                            title = "Privacy policy",
                            subtitle = "What Scanly keeps, and who processes it",
                            icon = Icons.Rounded.Policy,
                            trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { uriHandler.openUri(LegalLinks.PRIVACY_POLICY) }
                        ),
                        SettingsItem(
                            title = "Terms of service",
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
                            subtitle = "@Azyrn · Developer",
                            avatarUrl = DEVELOPER_AVATAR_URL,
                            trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { uriHandler.openUri(DEVELOPER_URL) }
                        ),
                        SettingsItem(
                            title = "Telegram",
                            subtitle = "Report bugs or request features",
                            iconRes = R.drawable.ic_telegram,
                            trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                            onClick = { uriHandler.openUri(TELEGRAM_URL) }
                        )
                    )
                )
            }

            item {
                Text(
                    text = "Scanly ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
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
            title = { Text("Remove your key?") },
            text = {
                Text(
                    "This device drops back to the free plan and its slot is freed, so you can " +
                        "use the key on another device. The key itself keeps working."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        paywallViewModel.removeKey()
                        showRemoveKeyDialog = false
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveKeyDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            icon = { Icon(Icons.Rounded.PersonRemove, contentDescription = null) },
            title = { Text("Delete account?") },
            text = {
                Text(
                    "This permanently erases your Scanly account, your sign-in details, and " +
                        "your usage records on our servers. It cannot be undone.\n\n" +
                        "Scans saved on this device are not affected, and an active Pro " +
                        "subscription must be cancelled separately through Paddle."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        authViewModel.deleteAccount(activity)
                        showDeleteAccountDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "AI scans will stop working until you sign in again. Your scan history " +
                        "and offline text recognition stay on this device."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        authViewModel.signOut(activity)
                        showSignOutDialog = false
                    }
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            }
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
                contentDescription = "Account options",
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
                        Text("Account ID", style = MaterialTheme.typography.bodyMedium)
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
                text = { Text("Sign out") },
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
    Toast.makeText(this, "Account ID copied", Toast.LENGTH_SHORT).show()
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
        title = { Text("Barcode Scanner Engine") },
        text = {
            Column {
                BarcodeEngine.entries.forEach { engine ->
                    val description = when (engine) {
                        BarcodeEngine.ML_KIT -> "Google's on-device scanner with auto-zoom"
                        BarcodeEngine.ZXING_CPP -> "Open-source decoder, works without GMS"
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
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
