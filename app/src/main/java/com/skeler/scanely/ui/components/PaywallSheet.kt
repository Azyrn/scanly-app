@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
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
                text = paywallHeadline(LocalContext.current, reason, isFeatureLock, isPaid, state.currentTier),
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
                        if (onTopPlan) {
                            pluralStringResource(
                                R.plurals.cmp_paywall_credits_left,
                                state.pagesPerMonth,
                                state.pagesRemaining,
                                state.pagesPerMonth
                            )
                        } else {
                            pluralStringResource(
                                R.plurals.cmp_paywall_credits_left_upgrade,
                                state.pagesPerMonth,
                                state.pagesRemaining,
                                state.pagesPerMonth
                            )
                        }
                    else -> stringResource(R.string.cmp_paywall_more_credits)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.cmp_paywall_ai_scan_credit),
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

            upgradeNotice(LocalContext.current, state.currentTier, state.plans)?.let { notice ->
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
                    text = stringResource(R.string.cmp_paywall_plan_active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                else -> {
                    // An ad grants credits, not Pro tools: offered for a locked tool, the user would
                    // watch it and be refused again.
                    if (canWatchAd && !isFeatureLock && state.rewardPagesPerAd > 0) {
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
                Text(
                    if (state.license.isActive || onTopPlan) {
                        stringResource(R.string.chat_step_done)
                    } else {
                        stringResource(R.string.chat_ask_dismiss)
                    }
                )
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
        Text(stringResource(R.string.pdf_tool_try_again))
    }
}

@Composable
private fun LicenseActive(state: PaywallState) {
    val entitlement = state.license.entitlement

    Text(
        text = state.message ?: stringResource(R.string.cmp_paywall_key_active_here),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center
    )

    if (entitlement != null && entitlement.maxActivations > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = pluralStringResource(
                R.plurals.cmp_paywall_devices_used,
                entitlement.maxActivations,
                entitlement.activationsUsed,
                entitlement.maxActivations
            ),
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
        Text(pluralStringResource(R.plurals.cmp_paywall_watch_ad, pagesPerAd, pagesPerAd))
    }
}

@Composable
private fun WhereToBuy() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val whereToBuyBefore = stringResource(R.string.cmp_paywall_where_to_buy_before)
    val whereToBuyAfter = stringResource(R.string.cmp_paywall_where_to_buy_after)

    Text(
        text = buildAnnotatedString {
            append(whereToBuyBefore)
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            ) {
                append(SCANLY_SITE)
            }
            append(whereToBuyAfter)
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
            imageVector = if (copied) Icons.Rounded.Check else CopyIcon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(if (copied) stringResource(R.string.cmp_copied) else stringResource(R.string.cmp_copy_address))
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
        label = { Text(stringResource(R.string.cmp_license_key)) },
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
                    contentDescription = if (keyVisible) {
                        stringResource(R.string.cmp_hide_key)
                    } else {
                        stringResource(R.string.cmp_show_key)
                    }
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
            Text(stringResource(R.string.cmp_redeem))
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
        Text(stringResource(R.string.cmp_lost_key_restore))
    }

    if (!needsEmailConfirmation) return

    Text(
        text = stringResource(R.string.cmp_confirm_email_first),
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
        Text(stringResource(R.string.cmp_send_confirmation_email))
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
                    text = stringResource(R.string.cmp_paywall_plan_name, plan.id.replaceFirstChar(Char::titlecase)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (option.isCurrent) {
                    Text(
                        text = stringResource(R.string.cmp_active),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            PlanFeature(stringResource(R.string.cmp_paywall_feature_credits, plan.pagesPerMonth))
            PlanFeature(
                pluralStringResource(R.plurals.cmp_paywall_feature_pages, plan.maxPagesPerDoc, plan.maxPagesPerDoc)
            )
            if (plan.allowsOffice) PlanFeature(stringResource(R.string.cmp_paywall_feature_office))
            if (plan.allowsTranslation) PlanFeature(stringResource(R.string.cmp_paywall_feature_translation))
            if (plan.allowsExtraction) {
                PlanFeature(stringResource(R.string.cmp_paywall_feature_extraction))
            }
            if (plan.allowsDesign) PlanFeature(stringResource(R.string.plan_feature_build_documents))

            PlanFeature(stringResource(R.string.cmp_paywall_feature_sign))
            PlanFeature(stringResource(R.string.cmp_paywall_feature_edit))
            PlanFeature(stringResource(R.string.cmp_paywall_feature_ai_provider))
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
    context: Context,
    reason: String?,
    isFeatureLock: Boolean,
    isPaid: Boolean,
    currentTier: String
): String = when {
    reason != null && isFeatureLock -> context.getString(R.string.pay_headline_pro_feature)
    reason != null -> context.getString(R.string.pay_headline_limit)
    isPaid -> context.getString(R.string.pay_headline_on_tier, currentTier.replaceFirstChar(Char::titlecase))
    else -> context.getString(R.string.pay_headline_upsell)
}
