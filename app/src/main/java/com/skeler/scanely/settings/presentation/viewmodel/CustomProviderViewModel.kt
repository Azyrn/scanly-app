package com.skeler.scanely.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.core.ai.custom.AuthStyle
import com.skeler.scanely.core.ai.custom.ConnectionFailure
import com.skeler.scanely.core.ai.custom.ConnectionTestResult
import com.skeler.scanely.core.ai.custom.CustomProviderTester
import com.skeler.scanely.core.ai.custom.ProviderProtocol
import com.skeler.scanely.core.billing.ProEntitlementGate
import com.skeler.scanely.settings.data.SettingsKeys
import com.skeler.scanely.settings.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProviderCredentials(
    val url: String = "",
    val apiKey: String = "",
    val model: String = "",
    val protocol: ProviderProtocol = ProviderProtocol.DEFAULT,
    val authStyle: AuthStyle = AuthStyle.DEFAULT,
    val customAuthHeader: String = ""
) {
    val isComplete: Boolean
        get() = url.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank() &&
            (authStyle != AuthStyle.CUSTOM || customAuthHeader.isNotBlank())
}

data class CustomProviderUiState(
    val credentials: ProviderCredentials = ProviderCredentials(),
    val isPro: Boolean = false,
    val isEnabled: Boolean = false,
    val isTesting: Boolean = false,
    val failure: ConnectionFailure? = null,
    val message: String? = null,
    /** Credentials validated by the latest successful connection test. */
    val verifiedFor: ProviderCredentials? = null
) {
    val isVerified: Boolean get() = verifiedFor != null && verifiedFor == credentials

    val canSave: Boolean get() = isPro && isVerified && !isTesting

    val canTest: Boolean get() = isPro && credentials.isComplete && !isTesting

    val canEdit: Boolean get() = isPro && !isTesting
}

@HiltViewModel
class CustomProviderViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val tester: CustomProviderTester,
    proEntitlementGate: ProEntitlementGate
) : ViewModel() {

    private val _state = MutableStateFlow(CustomProviderUiState())
    val state: StateFlow<CustomProviderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = ProviderCredentials(
                url = settings.getString(SettingsKeys.CUSTOM_PROVIDER_URL).first(),
                apiKey = settings.getString(SettingsKeys.CUSTOM_PROVIDER_KEY).first(),
                model = settings.getString(SettingsKeys.CUSTOM_PROVIDER_MODEL).first(),
                protocol = ProviderProtocol.fromName(
                    settings.getString(SettingsKeys.CUSTOM_PROVIDER_PROTOCOL).first()
                ),
                authStyle = AuthStyle.fromName(
                    settings.getString(SettingsKeys.CUSTOM_PROVIDER_AUTH_STYLE).first()
                ),
                customAuthHeader =
                settings.getString(SettingsKeys.CUSTOM_PROVIDER_AUTH_HEADER).first()
            )
            val wasVerified = settings.getBoolean(SettingsKeys.CUSTOM_PROVIDER_VERIFIED).first()
            _state.update {
                it.copy(
                    credentials = stored,
                    isEnabled = settings.getBoolean(SettingsKeys.CUSTOM_PROVIDER_ENABLED).first(),
                    verifiedFor = stored.takeIf { _ -> wasVerified && stored.isComplete }
                )
            }
        }
        viewModelScope.launch {
            proEntitlementGate.isPro.collect { isPro ->
                _state.update { it.copy(isPro = isPro) }
            }
        }
    }

    fun setUrl(value: String) = edit { it.copy(url = value) }

    fun setApiKey(value: String) = edit { it.copy(apiKey = value) }

    fun setModel(value: String) = edit { it.copy(model = value) }

    fun setCustomAuthHeader(value: String) = edit { it.copy(customAuthHeader = value) }

    fun setProtocol(value: ProviderProtocol) =
        edit { it.copy(protocol = value, authStyle = value.defaultAuth) }

    fun setAuthStyle(value: AuthStyle) = edit { it.copy(authStyle = value) }

    private fun edit(transform: (ProviderCredentials) -> ProviderCredentials) {
        _state.update {
            if (!it.canEdit) return@update it
            it.copy(credentials = transform(it.credentials), failure = null, message = null)
        }
    }

    fun testConnection() {
        val credentials = _state.value.credentials
        if (!_state.value.canTest) return

        _state.update { it.copy(isTesting = true, failure = null, message = null) }
        viewModelScope.launch {
            val result = tester.test(
                rawUrl = credentials.url,
                apiKey = credentials.apiKey,
                model = credentials.model,
                protocol = credentials.protocol,
                authStyle = credentials.authStyle,
                customAuthHeader = credentials.customAuthHeader
            )
            _state.update { current ->
                // Ignore results for credentials changed while the request was in flight.
                if (current.credentials != credentials) {
                    return@update current.copy(isTesting = false)
                }
                when (result) {
                    is ConnectionTestResult.Success -> current.copy(
                        isTesting = false,
                        verifiedFor = credentials,
                        failure = null,
                        message = "Connected. This endpoint is ready to save."
                    )
                    is ConnectionTestResult.Failure -> current.copy(
                        isTesting = false,
                        verifiedFor = null,
                        failure = result.failure
                    )
                }
            }
        }
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return

        viewModelScope.launch {
            val credentials = current.credentials
            settings.setString(SettingsKeys.CUSTOM_PROVIDER_URL, credentials.url.trim())
            settings.setString(SettingsKeys.CUSTOM_PROVIDER_KEY, credentials.apiKey.trim())
            settings.setString(SettingsKeys.CUSTOM_PROVIDER_MODEL, credentials.model.trim())
            settings.setString(SettingsKeys.CUSTOM_PROVIDER_PROTOCOL, credentials.protocol.name)
            settings.setString(SettingsKeys.CUSTOM_PROVIDER_AUTH_STYLE, credentials.authStyle.name)
            settings.setString(
                SettingsKeys.CUSTOM_PROVIDER_AUTH_HEADER,
                credentials.customAuthHeader.trim()
            )
            settings.setBoolean(SettingsKeys.CUSTOM_PROVIDER_VERIFIED, true)
            settings.setBoolean(SettingsKeys.CUSTOM_PROVIDER_ENABLED, true)
            _state.update { it.copy(isEnabled = true, message = "Saved. Scans will use this endpoint.") }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled && !(_state.value.isPro && _state.value.isVerified)) return
        viewModelScope.launch {
            settings.setBoolean(SettingsKeys.CUSTOM_PROVIDER_ENABLED, enabled)
            _state.update { it.copy(isEnabled = enabled) }
        }
    }

    fun clear() {
        viewModelScope.launch {
            settings.setString(SettingsKeys.CUSTOM_PROVIDER_URL, "")
            settings.setString(SettingsKeys.CUSTOM_PROVIDER_KEY, "")
            settings.setString(SettingsKeys.CUSTOM_PROVIDER_MODEL, "")
            settings.setString(SettingsKeys.CUSTOM_PROVIDER_PROTOCOL, ProviderProtocol.DEFAULT.name)
            settings.setString(SettingsKeys.CUSTOM_PROVIDER_AUTH_STYLE, AuthStyle.DEFAULT.name)
            settings.setString(SettingsKeys.CUSTOM_PROVIDER_AUTH_HEADER, "")
            settings.setBoolean(SettingsKeys.CUSTOM_PROVIDER_VERIFIED, false)
            settings.setBoolean(SettingsKeys.CUSTOM_PROVIDER_ENABLED, false)
            _state.value = CustomProviderUiState(isPro = _state.value.isPro, message = "Endpoint removed.")
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
