package com.skeler.scanely

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.skeler.scanely.core.ads.RewardedAdManager
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.billing.ProEntitlementGate
import com.skeler.scanely.core.common.CompositionLocals
import com.skeler.scanely.core.license.LicenseRepository
import com.skeler.scanely.navigation.ScanelyNavHost
import com.skeler.scanely.settings.presentation.viewmodel.SettingsViewModel
import com.skeler.scanely.ui.theme.ScanelyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()

    @Inject lateinit var licenseRepository: LicenseRepository

    @Inject lateinit var entitlementRepository: EntitlementRepository

    @Inject lateinit var rewardedAdManager: RewardedAdManager

    @Inject lateinit var proEntitlementGate: ProEntitlementGate

    /** A chat a notification asked to open; the nav host opens it and clears this. */
    private val chatToOpen = MutableStateFlow<String?>(null)

    /** Starts ads only after entitlement is known to be non-Pro. */
    private fun startAdsIfNotPro() {
        lifecycleScope.launch {
            combine(
                entitlementRepository.entitlement,
                proEntitlementGate.isPro
            ) { entitlement, isPro -> entitlement != null && !isPro }
                .first { it }

            rewardedAdManager.initialize(this@MainActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // A configuration change also arrives with saved state, but only in a process that has
        // already hosted this activity. A fresh process plus saved state means the app was killed
        // in the background: the back stack survives, everything the ViewModels held does not.
        val restoredAfterProcessDeath = savedInstanceState != null && !processHasHostedActivity
        processHasHostedActivity = true

        // Avoid a blank first frame while settings load.
        installSplashScreen().setKeepOnScreenCondition {
            settingsViewModel.settingsUiState.value == null
        }
        super.onCreate(savedInstanceState)
        // Not on a recreation: the chat was opened then, and rotating must not open it again.
        if (savedInstanceState == null) chatToOpen.value = intent.chatToOpen()
        enableEdgeToEdge()
        startAdsIfNotPro()
        setContent {
            CompositionLocals {
                ScanelyTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        ScanelyNavHost(
                            restoredAfterProcessDeath = restoredAfterProcessDeath,
                            chatToOpen = chatToOpen
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.chatToOpen()?.let { chatToOpen.value = it }
    }

    /** Refreshes entitlement after a license may have changed while the app was backgrounded. */
    override fun onResume() {
        super.onResume()
        licenseRepository.refresh()
    }

    companion object {
        @JvmStatic
        private var processHasHostedActivity = false

        const val ACTION_OPEN_CHAT = "com.skeler.scanely.action.OPEN_CHAT"
        const val EXTRA_CHAT_ID = "chatId"

        /**
         * Opens the chat [chatId], bringing the running app forward (its own instance, via
         * onNewIntent) rather than starting a second one on top.
         */
        fun openChatIntent(context: Context, chatId: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_CHAT)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
    }
}

private fun Intent.chatToOpen(): String? =
    getStringExtra(MainActivity.EXTRA_CHAT_ID)?.takeIf { action == MainActivity.ACTION_OPEN_CHAT }
