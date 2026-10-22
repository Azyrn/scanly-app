@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.ui.viewmodel.PaywallState
import com.skeler.scanely.ui.viewmodel.PlanOption
import com.skeler.scanely.ui.viewmodel.SCANLY_SITE
import com.skeler.scanely.ui.viewmodel.upgradeNotice

/** Upgrade sheet. Do not add external purchase links or prices; see `docs/PADDLE.md` §10. */
@Composable
fun PaywallSheet(
    state: PaywallState,
    onDismiss: () -> Unit,
    onRedeem: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    reason: String? = null,
    isFeatureLock: Boolean = false,
    canWatchAd: Boolean = false,
    onWatchAd: () -> Unit = {},
    onRestore: () -> Unit = {},
    onConfirmEmail: () -> Unit = {},
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )

            Spacer(Modifier.height(16.dp))

            val isPaid = state.currentTier != "free"

            // There is something left to sell until the current plan is the largest one offered.
            // Checked by size rather than by name so a new tier needs no change here.
            val currentPages = state.plans.firstOrNull { it.isCurrent }?.plan?.pagesPerMonth ?: 0
            val onTopPlan = isPaid && state.plans.none { it.plan.pagesPerMonth > currentPages }

            Text(
                text = paywallHeadline(reason, isFeatureLock, isPaid, state.currentTier),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = when {
                    reason != null -> reason
                    state.isQuotaKnown ->
                        "You have ${state.pagesRemaining} of ${state.pagesPerMonth} credits left " +
                            "this month." + if (onTopPlan) "" else " Upgrade for more."
                    else -> "More credits, longer documents, and more file formats."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "AI Scan uses 1 credit per page. Other AI tools share your credit balance.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            when {
                state.isLoading -> {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.height(24.dp))
                }

                state.error != null -> {
                    PlansUnavailable(state.error, onRetry)
                    Spacer(Modifier.height(24.dp))
                }

                else -> state.plans.forEach { option ->
                    PlanCard(option)
                    Spacer(Modifier.height(16.dp))
                }
            }

            upgradeNotice(state.currentTier, state.plans)?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
            }

            when {
                state.license.isActive -> LicenseActive(state)

                onTopPlan -> Text(
                    text = "Your plan is active on this account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                else -> {
                    if (canWatchAd && state.rewardPagesPerAd > 0) {
                        WatchAdForPages(
                            pagesPerAd = state.rewardPagesPerAd,
                            onWatchAd = onWatchAd
                        )
                        Spacer(Modifier.height(20.dp))
                    }

                    WhereToBuy()

                    Spacer(Modifier.height(20.dp))

                    RedeemField(
                        isBusy = state.license.isBusy,
                        error = state.license.error,
                        onRedeem = onRedeem
                    )

                    LostYourKey(
                        isBusy = state.license.isBusy,
                        needsEmailConfirmation = state.license.needsEmailConfirmation,
                        onRestore = onRestore,
                        onConfirmEmail = onConfirmEmail
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.license.isActive || onTopPlan) "Done" else "Not now")
            }
        }
    }
}

@Composable
private fun PlansUnavailable(error: String, onRetry: () -> Unit) {
    Text(
        text = error,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(8.dp))

    OutlinedButton(onClick = onRetry) {
        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text("Try again")
    }
}

@Composable
private fun LicenseActive(state: PaywallState) {
    val entitlement = state.license.entitlement

    Text(
        text = state.message ?: "Your key is active on this device.",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center
    )

    if (entitlement != null && entitlement.maxActivations > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${entitlement.activationsUsed} of ${entitlement.maxActivations} devices used. " +
                "Manage your key in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WatchAdForPages(pagesPerAd: Int, onWatchAd: () -> Unit) {
    OutlinedButton(onClick = onWatchAd, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.Rounded.PlayCircleOutline,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text("Watch an ad for $pagesPerAd more credits")
    }
}

@Composable
private fun WhereToBuy() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Text(
        text = buildAnnotatedString {
            append("AI Scan includes a free monthly allowance. Paid plans are available on ")
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            ) {
                append(SCANLY_SITE)
            }
            append(". Purchases are not available in this app. Already subscribed? Restore your purchase below.")
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(8.dp))

    TextButton(
        onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Scanly website", SCANLY_SITE))
            copied = true
        }
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(if (copied) "Copied" else "Copy address")
    }
}

@Composable
private fun RedeemField(
    isBusy: Boolean,
    error: String?,
    onRedeem: (String) -> Unit
) {
    var key by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("License key") },
        placeholder = { Text("SCANLY-XXXXX-XXXXX-XXXXX-XXXXX") },
        singleLine = true,
        isError = error != null,
        enabled = !isBusy,
        visualTransformation = if (keyVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(
                    if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (keyVisible) "Hide key" else "Show key"
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done
        ),
        supportingText = error?.let { { Text(it) } }
    )

    Spacer(Modifier.height(12.dp))

    Button(
        onClick = { onRedeem(key) },
        enabled = !isBusy && key.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text("Redeem")
        }
    }
}

/**
 * The way back for someone who paid and no longer has the key. Not a purchase flow — it asks the
 * server for what this account already owns — so it is safe to show inside the app.
 */
@Composable
private fun LostYourKey(
    isBusy: Boolean,
    needsEmailConfirmation: Boolean,
    onRestore: () -> Unit,
    onConfirmEmail: () -> Unit
) {
    Spacer(Modifier.height(4.dp))

    TextButton(
        onClick = onRestore,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Lost your key? Restore purchase")
    }

    if (!needsEmailConfirmation) return

    Text(
        text = "Confirm your email address first — it is what proves the purchase is yours.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = onConfirmEmail,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Send confirmation email")
    }
}

@Composable
private fun PlanCard(option: PlanOption) {
    val plan = option.plan

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (option.isCurrent) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        border = if (option.isCurrent) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Scanly ${plan.id.replaceFirstChar(Char::titlecase)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (option.isCurrent) {
                    Text(
                        text = "Active",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            PlanFeature("${plan.pagesPerMonth} AI credits a month")
            PlanFeature("Up to ${plan.maxPagesPerDoc} pages in one document")
            if (plan.allowsOffice) PlanFeature("Word, PowerPoint and OpenDocument files")
            if (plan.allowsTranslation) PlanFeature("Translation")
            if (plan.allowsExtraction) {
                PlanFeature("Data extraction from receipts, invoices and cards")
            }
            if (plan.allowsDesign) PlanFeature(stringResource(R.string.plan_feature_build_documents))

            PlanFeature("Sign PDFs and verify digital signatures")
            PlanFeature("Edit text, compress and password-protect PDFs")
            PlanFeature("Connect your own AI provider")
        }
    }
}

@Composable
private fun PlanFeature(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The sheet's headline, which has to match why it opened. A Pro-only tool used to share "You've
 * hit your plan's limit" with running out of credits, so a free user tapping "Sign" was told they
 * had exhausted something they had never used.
 */
internal fun paywallHeadline(
    reason: String?,
    isFeatureLock: Boolean,
    isPaid: Boolean,
    currentTier: String
): String = when {
    reason != null && isFeatureLock -> "Part of Scanly Pro"
    reason != null -> "You've hit your plan's limit"
    isPaid -> "You're on ${currentTier.replaceFirstChar(Char::titlecase)}"
    else -> "Scan more with Pro"
}
