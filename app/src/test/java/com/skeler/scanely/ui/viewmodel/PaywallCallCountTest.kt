package com.skeler.scanely.ui.viewmodel

import android.app.Activity
import com.skeler.scanely.auth.domain.model.AuthUser
import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.ads.RewardedAdManager
import com.skeler.scanely.core.ai.custom.CustomProviderResolver
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.license.DeviceId
import com.skeler.scanely.core.license.LicenseRepository
import com.skeler.scanely.core.license.LicenseStore
import com.skeler.scanely.core.network.LocalWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many times the paywall's two flows hit the Worker, read from the Worker's own request log.
 *
 * Everything between the ViewModel and the socket is production code: PaywallViewModel,
 * LicenseRepository, EntitlementRepository, Retrofit, OkHttp. Stubbed are the edges that are not
 * under test — the ad SDK (the test plays the ad's completion itself), DataStore, and sign-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaywallCallCountTest {

    private val worker = LocalWorker()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /** What `/v1/me` reports; AdMob's server-side callback is what lowers it. */
    private val rewardsRemaining = AtomicInteger(5)
    private val activity = mock(Activity::class.java)
    private val ads = mock(RewardedAdManager::class.java)

    @Before fun setUp() {
        Dispatchers.setMain(main)
        worker.route("/v1/me") { LocalWorker.me(rewardsRemaining.get()) }
        // A licence check does a D1 write; it is not instant, and the paywall's first /v1/me is
        // already on the wire before it answers.
        worker.route("/v1/license/status", delayMs = 300) { LocalWorker.licenseActive }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        main.close()
        worker.close()
    }

    private fun paywall(): PaywallViewModel = runBlocking {
        val user = mock(AuthUser::class.java)
        `when`(user.uid).thenReturn("reader")
        val auth = mock(AuthRepository::class.java)
        `when`(auth.currentUser).thenReturn(MutableStateFlow(user))
        `when`(auth.isSignedIn).thenReturn(true)
        val store = mock(LicenseStore::class.java)
        `when`(store.key).thenReturn(flowOf("SCAN-LY00-KEY0-0001"))
        val deviceId = mock(DeviceId::class.java)
        `when`(deviceId.get()).thenReturn("device-1")
        `when`(deviceId.displayName).thenReturn("Test phone")

        `when`(ads.isAdAvailable).thenReturn(MutableStateFlow(true))
        `when`(ads.isPrivacyOptionsRequired).thenReturn(MutableStateFlow(false))
        val resolver = mock(CustomProviderResolver::class.java)
        `when`(resolver.isSwitchedOn).thenReturn(flowOf(false))

        val api = worker.api()
        val entitlements = EntitlementRepository(api, auth)
        val license = LicenseRepository(api, store, deviceId, auth, entitlements)
        val viewModel = PaywallViewModel(license, entitlements, ads, resolver, auth)

        // Start-up traffic (sign-in triggers both repositories) is not what is being counted.
        worker.awaitQuiet()
        worker.log.clear()
        viewModel
    }

    /** The ad plays to the end: AdMob's signed callback reaches the Worker (or not), then it closes. */
    private fun finishAd(rewardLands: Boolean) {
        val show = mockingDetails(ads).invocations.single { it.method.name == "show" }
        if (rewardLands) rewardsRemaining.decrementAndGet()
        @Suppress("UNCHECKED_CAST")
        (show.arguments[2] as () -> Unit).invoke()
    }

    @Test fun `retry on the paywall reads the plan once and the licence once`() {
        val viewModel = paywall()

        viewModel.refresh()
        worker.awaitQuiet()
        println("paywall retry request log: ${worker.log}")

        assertEquals("request log: ${worker.log}", 1, worker.count("/v1/license/status"))
        assertEquals("request log: ${worker.log}", 1, worker.count("/v1/me"))
    }

    @Test fun `once the reward shows up the ad poll stops asking`() {
        val viewModel = paywall()

        viewModel.watchAd(activity)
        finishAd(rewardLands = true)
        worker.awaitQuiet(quietMs = 3_000)
        println("ad poll (reward landed) request log: ${worker.log}")

        assertEquals("request log: ${worker.log}", 1, worker.count("/v1/me"))
    }

    @Test fun `a reward that never shows up is still polled a bounded number of times`() {
        val viewModel = paywall()

        viewModel.watchAd(activity)
        finishAd(rewardLands = false)
        worker.awaitQuiet(quietMs = 3_000)
        println("ad poll (no reward) request log: ${worker.log}")

        assertEquals("request log: ${worker.log}", 3, worker.count("/v1/me"))
    }
}
