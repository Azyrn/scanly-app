package com.skeler.scanely.core.billing

import com.skeler.scanely.core.license.LicenseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the user has Pro, by either path Scanly sells through: a redeemed Paddle licence key, or
 * a signed-in account whose tier is not free. The licence is checked first because it works without
 * an account, which is the point of the licence model.
 */
@Singleton
class ProEntitlementGate @Inject constructor(
    licenseRepository: LicenseRepository,
    entitlementRepository: EntitlementRepository
) {
    private val scope = CoroutineScope(SupervisorJob())

    val isPro: StateFlow<Boolean> = combine(
        licenseRepository.state,
        entitlementRepository.entitlement
    ) { license, entitlement ->
        license.isActive || (entitlement?.tier?.equals(FREE_TIER, ignoreCase = true) == false)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Waits for [isPro] to turn true, up to [timeoutMs].
     *
     * Both sources start unresolved and emit `false` before the server has answered, so reading
     * [isPro] the moment the app launches says "not Pro" about a subscriber. Callers that would
     * take a different and irreversible path on that answer — spending quota, say — wait instead.
     */
    suspend fun awaitPro(timeoutMs: Long = RESOLVE_TIMEOUT_MS): Boolean =
        isPro.value || withTimeoutOrNull(timeoutMs) { isPro.first { it } } == true

    private companion object {
        const val FREE_TIER = "free"
        const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}
