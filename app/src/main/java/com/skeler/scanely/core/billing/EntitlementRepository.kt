package com.skeler.scanely.core.billing

import android.content.Context
import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.di.BackendControl
import com.skeler.scanely.core.network.MeResponse
import com.skeler.scanely.core.network.ScanlyApi
import com.skeler.scanely.core.network.TokenUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EntitlementRepository"

/**
 * How the last fetch went. Separate from the entitlement itself so a screen can tell "not asked
 * yet" from "asked and refused" — without it the paywall spins forever on any failure.
 */
data class EntitlementStatus(
    val isRefreshing: Boolean = false,
    val error: String? = null
)

/**
 * What the user is entitled to, as told by the server. Nothing is cached to disk or computed
 * locally: tier and remaining pages decide what may be spent, so a value the device could edit —
 * or that goes stale on a renewal, refund, or scan from another phone — is worse than none.
 */
@Singleton
class EntitlementRepository internal constructor(
    private val context: Context,
    private val api: ScanlyApi,
    private val authRepository: AuthRepository,
    private val scope: CoroutineScope
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        @BackendControl api: ScanlyApi,
        authRepository: AuthRepository
    ) : this(context, api, authRepository, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val _entitlement = MutableStateFlow<MeResponse?>(null)

    /** Null until the first successful fetch; the UI shows a skeleton rather than a wrong number. */
    val entitlement: StateFlow<MeResponse?> = _entitlement.asStateFlow()

    private val _status = MutableStateFlow(EntitlementStatus())

    val status: StateFlow<EntitlementStatus> = _status.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshVersion = 0L
    private var accountUid: String? = null

    init {
        scope.launch {
            authRepository.currentUser.collect { refresh() }
        }
    }

    /** Call after any scan: only the server knows the remaining balance. */
    @Synchronized
    fun refresh() {
        val version = ++refreshVersion
        refreshJob?.cancel()
        val uid = authRepository.currentUser.value?.uid
        if (uid != accountUid) {
            _entitlement.value = null
            accountUid = uid
        }
        if (uid == null) {
            _status.value = EntitlementStatus(error = context.getString(R.string.plan_sign_in_to_see))
            return
        }
        _status.value = EntitlementStatus(isRefreshing = true)
        refreshJob = scope.launch {
            try {
                val response = api.me()
                synchronized(this@EntitlementRepository) {
                    if (version == refreshVersion && authRepository.currentUser.value?.uid == uid) {
                        _entitlement.value = response
                        _status.value = EntitlementStatus()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(this@EntitlementRepository) {
                    if (version == refreshVersion && authRepository.currentUser.value?.uid == uid) {
                        // Retain only this account's last server response on a transient failure.
                        Log.w(TAG, "Failed to refresh entitlement", e)
                        _status.value = EntitlementStatus(error = e.userMessage())
                    }
                }
            }
        }
    }

    private fun Exception.userMessage(): String = when (this) {
        // Written for the user already: which token could not be minted, and what to do about it.
        is TokenUnavailableException -> message ?: context.getString(R.string.net_no_connection)
        is IOException -> context.getString(R.string.net_no_connection)
        else -> context.getString(R.string.plan_load_failed)
    }
}
