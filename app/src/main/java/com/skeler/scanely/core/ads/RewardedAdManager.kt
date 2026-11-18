package com.skeler.scanely.core.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.skeler.scanely.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RewardedAdManager"

// AdMob rewarded ads expire after one hour.
private const val AD_REFRESH_MS = 55 * 60 * 1000L

/**
 * How long to wait before reloading after the [failures]th failed load in a row: doubling from
 * 30 s up to 8 minutes, then every 8 minutes for as long as loads keep failing. A load that failed
 * offline or for lack of fill is worth asking again, but not in a tight loop, which AdMob
 * penalises; stopping altogether left the app adless until a restart, since the ad button is
 * hidden while no ad is loaded. A successful load, or a load the user's own tap starts, resets it.
 */
internal fun adLoadRetryDelayMs(failures: Int): Long =
    AD_LOAD_FIRST_RETRY_MS shl (failures.coerceIn(1, AD_LOAD_DOUBLINGS + 1) - 1)

private const val AD_LOAD_FIRST_RETRY_MS = 30_000L
private const val AD_LOAD_DOUBLINGS = 4

// Rewards must be granted only through OnUserEarnedRewardListener.
@Singleton
class RewardedAdManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isAdAvailable = MutableStateFlow(false)
    val isAdAvailable: StateFlow<Boolean> = _isAdAvailable.asStateFlow()

    private val _isPrivacyOptionsRequired = MutableStateFlow(false)
    val isPrivacyOptionsRequired: StateFlow<Boolean> = _isPrivacyOptionsRequired.asStateFlow()

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false
    private var isStarted = false
    private var hasRequestedConsent = false
    private var refreshJob: Job? = null
    private var retryJob: Job? = null
    private var loadFailures = 0

    /** Initializes the SDK only after the consent flow completes. */
    fun initialize(activity: Activity) {
        // Prevent concurrent callers from opening multiple consent forms.
        if (hasRequestedConsent) return
        hasRequestedConsent = true

        val consent = UserMessagingPlatform.getConsentInformation(context)
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) Log.w(TAG, "Consent form failed: ${error.message}")
                    publishPrivacyOptions(consent)
                    start(consent)
                }
            },
            { error ->
                Log.w(TAG, "Consent lookup failed: ${error.message}")
                // A lookup that never landed leaves consent unknown, and unknown is denied. The
                // latch reopens so the next call retries rather than leaving this install adless
                // for the life of the process.
                hasRequestedConsent = false
                publishPrivacyOptions(consent)
                start(consent)
            }
        )
    }

    private fun publishPrivacyOptions(consent: ConsentInformation) {
        _isPrivacyOptionsRequired.value = consent.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    /** Consent is revocable on demand wherever the UMP form is required, which the GDPR needs. */
    fun showPrivacyOptionsForm(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.w(TAG, "Privacy options form failed: ${error.message}")
        }
    }

    private fun start(consent: ConsentInformation) {
        if (isStarted || !consent.canRequestAds()) return
        isStarted = true
        scope.launch(Dispatchers.IO) {
            MobileAds.initialize(context) {}
            launch(Dispatchers.Main) { preload() }
        }
    }

    fun preload() {
        if (!isStarted || isLoading || rewardedAd != null) return
        isLoading = true
        retryJob?.cancel()
        RewardedAd.load(
            context,
            BuildConfig.ADMOB_REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoading = false
                    loadFailures = 0
                    rewardedAd = ad
                    _isAdAvailable.value = true
                    scheduleRefresh()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    rewardedAd = null
                    _isAdAvailable.value = false
                    Log.w(TAG, "Rewarded ad failed to load: code=${error.code}")
                    scheduleRetry()
                }
            }
        )
    }

    /** Without this a failed load (offline at start, no fill) left the paywall adless for good. */
    private fun scheduleRetry() {
        val wait = adLoadRetryDelayMs(++loadFailures)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            preload()
        }
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(AD_REFRESH_MS)
            rewardedAd = null
            _isAdAvailable.value = false
            preload()
        }
    }

    /** Shows an ad for [uid]; the server grants pages through AdMob's signed callback. */
    fun show(activity: Activity, uid: String, onReward: () -> Unit) {
        val ad = rewardedAd ?: run {
            // A tap is a fresh reason to try: it gets its own run of retries.
            loadFailures = 0
            preload()
            return
        }
        rewardedAd = null
        _isAdAvailable.value = false
        refreshJob?.cancel()

        // Set per show, not per load: the signed-in account can change between the two.
        ad.setServerSideVerificationOptions(
            ServerSideVerificationOptions.Builder().setUserId(uid).build()
        )

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preload()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Rewarded ad failed to show: code=${error.code}")
                preload()
            }
        }
        ad.show(activity) { onReward() }
    }
}
