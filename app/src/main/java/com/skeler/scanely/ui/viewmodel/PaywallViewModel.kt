package com.skeler.scanely.ui.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.ads.RewardedAdManager
import com.skeler.scanely.core.ai.custom.CustomProviderResolver
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.license.LicenseRepository
import com.skeler.scanely.core.license.LicenseState
import com.skeler.scanely.core.license.RestoreOutcome
import com.skeler.scanely.core.network.ExtractionPresetDto
import com.skeler.scanely.core.network.PlanDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One plan as the paywall shows it — what it gives you, and whether you already have it.
 *
 * No price. Scanly is sold on the website by Paddle, and Play's payments policy forbids leading
 * users to an alternative payment method from inside the app. The amount lives where the purchase
 * does. What the plan *includes* still comes from the server, so limits change without a release.
 */
data class PlanOption(
    val plan: PlanDto,
    val isCurrent: Boolean
)

data class PaywallState(
    val plans: List<PlanOption> = emptyList(),
    val currentTier: String = "free",
    val pagesRemaining: Int = 0,
    val pagesPerMonth: Int = 0,
    /** False while the page counts below are placeholders rather than the server's numbers. */
    val isQuotaKnown: Boolean = false,
    /** True only while the first fetch is still in flight. Never true once it has failed. */
    val isLoading: Boolean = true,
    /** Why the plans could not be loaded, if they could not. Offer a retry alongside it. */
    val error: String? = null,
    /** Pages one rewarded ad is worth, and how many more the server will honour today. */
    val rewardPagesPerAd: Int = 0,
    val rewardsRemainingToday: Int = 0,
    /** An ad is loaded, the user is on the free tier, and they have rewards left today. */
    val canWatchAd: Boolean = false,
    /** Structured extraction: whether this plan has it, and which document kinds it offers. */
    val allowsExtraction: Boolean = false,
    val extractionPresets: List<ExtractionPresetDto> = emptyList(),
    val extractionPageCost: Int = 1,
    /**
     * Designing a PDF. [designMaxPageCost] is the hold the account must be able to cover, not the
     * price — it is what explains a `quota_exceeded` when [designPageCost] looks affordable.
     * Every tier designs on its credits; the free tier's hold is capped at its balance server-side.
     */
    val allowsDesign: Boolean = false,
    val designPageCost: Int = 2,
    val designMaxPageCost: Int = 16,
    /** What a design costs when the user's own model writes it and only the compile is Scanly's. */
    val designCompilePageCost: Int = 1,
    /** The user has switched on their own AI endpoint, so most work costs them nothing here. */
    val usesCustomEndpoint: Boolean = false,
    val license: LicenseState = LicenseState(),
    val message: String? = null
) {
    /**
     * The number to put in front of the user before they start a design.
     *
     * A design on a custom endpoint is billed for the compile alone, which is a fraction of the
     * Gemini path. Quoting the larger figure to someone who will be charged the smaller one is the
     * same failure as quoting the smaller to someone charged the larger — it just fails politely.
     */
    val quotedDesignPageCost: Int
        get() = if (usesCustomEndpoint) designCompilePageCost else designPageCost
}

@HiltViewModel
class PaywallViewModel @Inject internal constructor(
    private val licenseRepository: LicenseRepository,
    private val entitlementRepository: EntitlementRepository,
    private val rewardedAdManager: RewardedAdManager,
    private val customProviderResolver: CustomProviderResolver,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)

    val isPrivacyOptionsRequired: StateFlow<Boolean> = rewardedAdManager.isPrivacyOptionsRequired

    fun showPrivacyOptions(activity: Activity) {
        rewardedAdManager.showPrivacyOptionsForm(activity)
    }

    /** Paired so the five-argument `combine` still covers everything the sheet reads. */
    private val adAndEndpoint = combine(
        rewardedAdManager.isAdAvailable,
        customProviderResolver.isSwitchedOn
    ) { isAdAvailable, usesCustomEndpoint -> isAdAvailable to usesCustomEndpoint }

    val state: StateFlow<PaywallState> = combine(
        entitlementRepository.entitlement,
        entitlementRepository.status,
        licenseRepository.state,
        _message,
        adAndEndpoint
    ) { entitlement, status, license, message, (isAdAvailable, usesCustomEndpoint) ->
        val tier = entitlement?.tier ?: "free"

        PaywallState(
            plans = entitlement?.plans
                // Paid tiers only. `/v1/me` lists the free tier too, which is what a reader of this
                // sheet already has, so offering it is not an offer. Everything paid is now sold:
                // Paddle carries a price per tier and the webhook mints a licence at whichever
                // tier the price names.
                ?.filter { it.priceUsd > 0 }
                ?.map { PlanOption(plan = it, isCurrent = it.id == tier) }
                .orEmpty(),
            currentTier = tier,
            pagesRemaining = entitlement?.pagesRemainingMonth ?: 0,
            pagesPerMonth = entitlement?.pagesPerMonth ?: 0,
            isQuotaKnown = entitlement != null,
            rewardPagesPerAd = entitlement?.rewardPagesPerAd ?: 0,
            rewardsRemainingToday = entitlement?.rewardsRemainingToday ?: 0,
            // Excluded here rather than at the call sites, so no screen can offer an ad to someone
            // who is paying not to see one. The licence check is not redundant with the tier: a key
            // redeemed without an account leaves the server tier reading "free".
            canWatchAd = isAdAvailable &&
                tier == "free" &&
                !license.isActive &&
                (entitlement?.rewardsRemainingToday ?: 0) > 0,
            isLoading = entitlement == null && status.error == null,
            // A failure only reaches the user when there is nothing to show instead; last known
            // numbers beat an apology.
            error = status.error.takeIf { entitlement == null },
            allowsExtraction = entitlement?.allowsExtraction == true,
            extractionPresets = entitlement?.extractionPresets.orEmpty(),
            extractionPageCost = entitlement?.extractionPageCost ?: 1,
            allowsDesign = entitlement?.allowsDesign == true,
            designPageCost = entitlement?.designPageCost ?: 2,
            designMaxPageCost = entitlement?.designMaxPageCost ?: 16,
            designCompilePageCost = entitlement?.designCompilePageCost ?: 1,
            // The tier matters as well as the switch: a custom endpoint is Pro-only, so a free
            // account with one configured is still routed to Scanly and must be quoted Scanly's
            // price. This mirrors the Pro check inside CustomProviderResolver.resolve.
            usesCustomEndpoint = usesCustomEndpoint && (tier != "free" || license.isActive),
            license = license,
            message = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PaywallState()
    )

    fun redeem(key: String) {
        viewModelScope.launch {
            if (licenseRepository.activate(key)) {
                // Naming a tier we could not read would be a guess, and there is now more than one
                // it could be.
                val tier = licenseRepository.state.value.entitlement?.tier
                _message.value = if (tier == null) {
                    "Your key is active. Enjoy."
                } else {
                    "You're on ${tier.replaceFirstChar(Char::titlecase)}. Enjoy."
                }
            }
        }
    }

    /**
     * Gets a lost key back from the account that bought it.
     *
     * Deliberately not worded as a purchase restore in the failure case: someone signed into the
     * wrong account will read "nothing to restore" as "my subscription is gone", so the message
     * names the thing they can actually check.
     */
    fun restorePurchase() {
        viewModelScope.launch {
            _message.value = restoreMessage(licenseRepository.restore())
        }
    }

    /** Sends the confirmation link, then leaves the user to tap Restore again. */
    fun confirmEmail() {
        viewModelScope.launch {
            _message.value = licenseRepository.confirmEmail().fold(
                onSuccess = {
                    "Check your inbox, open the link, then tap Restore purchase again."
                },
                onFailure = { it.message ?: "Couldn't send the confirmation email. Try again." }
            )
        }
    }

    /**
     * Plays a rewarded ad, then re-reads the page count.
     *
     * AdMob's signed callback to the Worker races the ad closing and usually loses, so a single
     * refresh at the end reliably reads the old number and the reward looks like it never arrived.
     * Hence the poll — which stops as soon as the server's count of today's rewards drops, since
     * that is the reward having landed and every read after it would return the same answer.
     */
    fun watchAd(activity: Activity) {
        val uid = authRepository.currentUser.value?.uid ?: return
        val rewardsBefore = entitlementRepository.entitlement.value?.rewardsRemainingToday
        rewardedAdManager.show(activity, uid) {
            viewModelScope.launch {
                var landed = false
                repeat(REWARD_POLL_ATTEMPTS) {
                    if (landed) return@repeat
                    delay(REWARD_POLL_INTERVAL_MS)
                    entitlementRepository.refresh()
                    entitlementRepository.status.first { !it.isRefreshing }
                    val rewardsNow = entitlementRepository.entitlement.value?.rewardsRemainingToday
                    landed = rewardsBefore != null && rewardsNow != null && rewardsNow < rewardsBefore
                }
            }
        }
    }

    fun removeKey() {
        viewModelScope.launch {
            licenseRepository.deactivate()
            _message.value = "Key removed from this device."
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * Both the tier and the licence can change on another device; re-read on resume. The licence
     * check re-reads the tier itself — after the check, which is when it can have changed — so
     * asking the entitlement repository here as well read `/v1/me` twice.
     */
    fun refresh() {
        licenseRepository.refresh(thenEntitlement = true)
    }

    private companion object {
        const val REWARD_POLL_ATTEMPTS = 3
        const val REWARD_POLL_INTERVAL_MS = 1_500L
    }
}

/** Where a key is bought. Rendered as plain text — never as a link. See `docs/PADDLE.md` §10. */
const val SCANLY_SITE = "scanlyocr.com"

/**
 * What a paying user who could still move up a plan is told before they go and buy it.
 *
 * Buying a bigger plan on the website is a second Paddle subscription, billed alongside the one
 * they have, not an upgrade of it. The website's pricing page stops a browser that holds a key and
 * offers the Paddle portal; this is the in-app half, and it is words only. A link, button or portal
 * hand-off from here is what Play forbids a reader app (`docs/PADDLE.md` §10, deleted in d401ce2),
 * so the site is named in plain text and the change itself happens there.
 *
 * Checked by plan size rather than name, like the paywall's own top-plan test, so a new tier needs
 * no change. Null when there is no bigger plan, or when the plans have not loaded.
 */
internal fun upgradeNotice(currentTier: String, plans: List<PlanOption>): String? {
    if (currentTier.equals("free", ignoreCase = true)) return null
    val currentPages = plans.firstOrNull { it.isCurrent }?.plan?.pagesPerMonth ?: return null
    if (plans.none { it.plan.pagesPerMonth > currentPages }) return null

    return "To move to a bigger plan, go to $SCANLY_SITE/upgrade and sign in with the same account " +
        "you use here: it changes the plan you have. Buying another plan on the pricing page starts " +
        "a second subscription, billed alongside this one."
}

/**
 * What to tell the user after a restore. Null means the sheet already says it: the two silent
 * outcomes leave their own prose in [LicenseState], as an affordance or an inline error, and
 * repeating it in a snackbar would say the same thing twice.
 */
internal fun restoreMessage(outcome: RestoreOutcome): String? = when (outcome) {
    is RestoreOutcome.Restored ->
        outcome.tier?.let { "Your ${it.replaceFirstChar(Char::titlecase)} key is back." }
            ?: "Your key is back on this device."

    // Not "nothing to restore": someone signed into the wrong account reads that as a subscription
    // that has vanished, and the thing they can actually check is which address they paid with.
    RestoreOutcome.NothingFound ->
        "No subscription on this account. If you paid with a different email address, sign in " +
            "with that one and try again."

    RestoreOutcome.NeedsEmailConfirmation -> null
    is RestoreOutcome.Failed -> null
}

/** The Settings row that names the user's plan. */
data class PlanRow(val title: String, val subtitle: String)

/**
 * Before `/v1/me` has answered, [PaywallState.currentTier] is a placeholder "free". Settings used
 * to print it as "Free plan", so a subscriber whose plan failed to load was told they were on Free.
 * An active key on this device still names its tier; otherwise the row says what is happening.
 */
internal fun planRow(state: PaywallState): PlanRow {
    if (state.isQuotaKnown) {
        return PlanRow(
            title = state.currentTier.replaceFirstChar(Char::titlecase) + " plan",
            subtitle = if (state.pagesPerMonth > 0) {
                "${state.pagesRemaining} of ${state.pagesPerMonth} AI credits left this month"
            } else {
                "Tap to see plans"
            }
        )
    }
    val keyTier = state.license.entitlement?.tier?.takeIf { state.license.isActive }
    val title = keyTier?.let { it.replaceFirstChar(Char::titlecase) + " plan" } ?: "Your plan"
    val subtitle = if (state.error != null) {
        "Couldn't load your plan. Tap to try again."
    } else {
        "Checking your plan…"
    }
    return PlanRow(title, subtitle)
}
