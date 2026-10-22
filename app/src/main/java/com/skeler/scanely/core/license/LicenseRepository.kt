package com.skeler.scanely.core.license

import android.util.Log
import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.di.BackendControl
import com.skeler.scanely.core.network.BackendError
import com.skeler.scanely.core.network.LicenseEntitlementDto
import com.skeler.scanely.core.network.LicenseRequest
import com.skeler.scanely.core.network.NO_CONNECTION_MESSAGE
import com.skeler.scanely.core.network.ScanlyApi
import com.skeler.scanely.core.network.TokenUnavailableException
import com.skeler.scanely.core.network.backendError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LicenseRepository"

data class LicenseState(
    /** Null when no key is stored. Present even while the server is refusing it, so it can be shown. */
    val key: String? = null,
    val entitlement: LicenseEntitlementDto? = null,
    val isBusy: Boolean = false,
    /** Set when the server rejected the key; cleared on the next attempt. */
    val error: String? = null,
    /**
     * The last restore found nothing because this account's email address is unconfirmed. The
     * address is what proves the purchase belongs to this person, so recovery cannot proceed on an
     * unproven one — offer to send the confirmation link rather than reporting a failure.
     */
    val needsEmailConfirmation: Boolean = false
) {
    val isActive: Boolean get() = entitlement?.grantsAccess == true
}

sealed interface RestoreOutcome {
    /** The key was found and this device is now bound to it. */
    data class Restored(val tier: String?) : RestoreOutcome

    /** The account is genuine and owns nothing — usually the wrong account, not a lost key. */
    data object NothingFound : RestoreOutcome

    data object NeedsEmailConfirmation : RestoreOutcome

    data class Failed(val message: String) : RestoreOutcome
}

/**
 * Redeems and re-checks the licence key bought on the website.
 *
 * Every call goes through [ScanlyApi], never a bare OkHttp client: that Retrofit instance carries
 * the Firebase ID token, and the Worker links `license_activations.uid` only when it sees that
 * header. An activation without it succeeds, writes a row, and still leaves the user on free.
 *
 * Nothing here decides entitlement. It reports what the server said and asks
 * [EntitlementRepository] to re-read `/v1/me`, the only tier the OCR path honours.
 */
@Singleton
class LicenseRepository @Inject constructor(
    @param:BackendControl private val api: ScanlyApi,
    private val store: LicenseStore,
    private val deviceId: DeviceId,
    private val authRepository: AuthRepository,
    private val entitlementRepository: EntitlementRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(LicenseState())
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    init {
        scope.launch {
            store.key.first()?.let { _state.value = LicenseState(key = it) }
        }

        // Signing in is what gives the activation a uid to attach to, and `status` attaches it
        // with the same COALESCE the activate path uses. So a key redeemed under one account —
        // or before this account existed — starts resolving to Pro on the next check.
        scope.launch {
            authRepository.currentUser.collect { user ->
                if (user != null) refresh()
            }
        }
    }

    suspend fun activate(rawKey: String): Boolean {
        val key = normalizeLicenseKey(rawKey)
        if (key.isBlank()) {
            _state.value = _state.value.copy(error = "Enter your license key.")
            return false
        }
        if (!authRepository.isSignedIn) {
            _state.value = _state.value.copy(error = SIGN_IN_FIRST)
            return false
        }

        _state.value = _state.value.copy(isBusy = true, error = null)
        return try {
            val entitlement = api.activateLicense(
                LicenseRequest(key, deviceId.get(), deviceId.displayName)
            )
            store.save(key)
            _state.value = LicenseState(key = key, entitlement = entitlement)
            // The tier that gates OCR comes from /v1/me, not from this response.
            entitlementRepository.refresh()
            true
        } catch (e: Exception) {
            _state.value = _state.value.copy(isBusy = false, error = e.userMessage())
            false
        }
    }

    /**
     * Gets the key back for someone who no longer has it, and binds this device to it.
     *
     * The account is the credential: the server answers from an activation this account already
     * made, or from its confirmed email address. Both are proofs the buyer still holds after
     * losing the key itself, which is what the /success claim — single-use and gone in a day —
     * could not be.
     *
     * The session is refreshed first because `email_verified` rides inside the ID token, so a
     * confirmation link clicked a minute ago is invisible until the cached token is dropped.
     */
    suspend fun restore(): RestoreOutcome {
        if (!authRepository.isSignedIn) {
            _state.value = _state.value.copy(error = SIGN_IN_FIRST)
            return RestoreOutcome.Failed(SIGN_IN_FIRST)
        }

        _state.value = _state.value.copy(isBusy = true, error = null, needsEmailConfirmation = false)
        return try {
            authRepository.refreshSession()
            val response = api.myLicenses()
            val recovered = response.licenses.firstOrNull()

            when {
                recovered != null -> redeemRecovered(recovered.key)
                response.emailUnverified -> {
                    _state.value = _state.value.copy(isBusy = false, needsEmailConfirmation = true)
                    RestoreOutcome.NeedsEmailConfirmation
                }
                else -> {
                    _state.value = _state.value.copy(isBusy = false)
                    RestoreOutcome.NothingFound
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Restore failed", e)
            val message = e.userMessage()
            _state.value = _state.value.copy(isBusy = false, error = message)
            RestoreOutcome.Failed(message)
        }
    }

    private suspend fun redeemRecovered(key: String): RestoreOutcome =
        if (activate(key)) {
            RestoreOutcome.Restored(_state.value.entitlement?.tier)
        } else {
            RestoreOutcome.Failed(_state.value.error ?: NO_CONNECTION_MESSAGE)
        }

    /** Mails the confirmation link that unlocks the address half of [restore]. */
    suspend fun confirmEmail(): Result<Unit> = authRepository.sendEmailVerification()

    /**
     * Re-checks a stored key. On a definitive refusal the key is dropped: keeping it would mean
     * retrying a dead subscription on every launch and showing the user a licence that no longer
     * exists. A network failure is not a refusal and leaves it alone.
     *
     * A successful check re-reads `/v1/me` itself, since the check may have just linked the key to
     * this account. [thenEntitlement] asks for that re-read on every other path too, so a caller
     * that wants both answers gets each once instead of reading the plan twice.
     */
    fun refresh(thenEntitlement: Boolean = false) {
        scope.launch {
            val refreshedEntitlement = checkStoredKey()
            if (thenEntitlement && !refreshedEntitlement) entitlementRepository.refresh()
        }
    }

    /** True when it already asked [EntitlementRepository] to refresh. */
    private suspend fun checkStoredKey(): Boolean {
        val key = store.key.first() ?: return false
        // Without a token the Worker would not link the uid, and the interceptor would
        // refuse the call anyway. Keep the key and wait for sign-in.
        if (!authRepository.isSignedIn) {
            _state.value = _state.value.copy(key = key)
            return false
        }
        _state.value = _state.value.copy(key = key, isBusy = true)
        try {
            val entitlement = api.licenseStatus(
                LicenseRequest(key, deviceId.get(), deviceId.displayName)
            )
            _state.value = LicenseState(key = key, entitlement = entitlement)
            entitlementRepository.refresh()
            return true
        } catch (e: HttpException) {
            // Read once: Retrofit's error body is gone after the first read, and both the code
            // and the user's message come from it.
            val error = e.backendError()
            if (error?.code == "license_invalid" || error?.code == "license_inactive") {
                store.clear()
                _state.value = LicenseState(error = e.userMessage(error))
            } else {
                _state.value = _state.value.copy(isBusy = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "License status check failed", e)
            _state.value = _state.value.copy(isBusy = false)
        }
        return false
    }

    /**
     * Frees this device's slot server-side, then forgets the key. The local clear happens even if
     * the call failed, because a user who asked to remove the key must not be left holding one —
     * the slot can be freed from another device or the portal.
     */
    suspend fun deactivate() {
        val key = store.key.first() ?: return
        _state.value = _state.value.copy(isBusy = true, error = null)
        try {
            api.deactivateLicense(LicenseRequest(key, deviceId.get(), deviceId.displayName))
        } catch (e: Exception) {
            Log.w(TAG, "Deactivate call failed; clearing locally anyway", e)
        }
        store.clear()
        _state.value = LicenseState()
        entitlementRepository.refresh()
    }

    /**
     * The server writes its own prose — including the activation-limit message, which already
     * names the device count — so it is surfaced verbatim rather than re-worded here.
     */
    private fun Exception.userMessage(
        backendError: BackendError? = (this as? HttpException)?.backendError()
    ): String = when (this) {
        is HttpException -> backendError?.message ?: "Couldn't check that key. Try again."
        // The session was rejected; its message says to sign in again. Must precede IOException,
        // which it extends — "Couldn't reach Scanly" sent people to check a connection that worked.
        is TokenUnavailableException -> message ?: NO_CONNECTION_MESSAGE
        // Covers a token that expired between the sign-in check and the call.
        is IOException -> if (authRepository.isSignedIn) NO_CONNECTION_MESSAGE else SIGN_IN_FIRST
        else -> NO_CONNECTION_MESSAGE
    }

    private companion object {
        const val SIGN_IN_FIRST = "Sign in first, then redeem your key."
    }
}
