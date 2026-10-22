package com.skeler.scanely.core.ai.custom

import com.skeler.scanely.core.billing.ProEntitlementGate
import com.skeler.scanely.settings.data.SettingsKeys
import com.skeler.scanely.settings.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

internal data class CustomProviderConfig(
    val url: String,
    val apiKey: String,
    val model: String,
    val protocol: ProviderProtocol = ProviderProtocol.DEFAULT,
    val authStyle: AuthStyle = AuthStyle.DEFAULT,
    val customAuthHeader: String = ""
) {
    fun headers(): Map<String, String> = buildMap {
        putAll(authStyle.headersFor(apiKey, customAuthHeader))
        // Anthropic rejects native requests without this header.
        if (protocol == ProviderProtocol.ANTHROPIC) put("anthropic-version", ANTHROPIC_VERSION)
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

/** Resolves a usable custom-provider configuration, otherwise falling back to the backend. */
@Singleton
internal class CustomProviderResolver @Inject constructor(
    private val settings: SettingsRepository,
    private val proEntitlementGate: ProEntitlementGate
) {
    /**
     * Whether the user has switched their own endpoint on, for screens that need to say so.
     *
     * Deliberately not the Pro check [resolve] makes: this drives copy and quoted prices, and a
     * flow that blocks on the server answering would leave both blank on every cold start. What
     * gets *routed* is still decided by [resolve] alone.
     */
    val isSwitchedOn: Flow<Boolean> = combine(
        settings.getBoolean(SettingsKeys.CUSTOM_PROVIDER_ENABLED),
        settings.getBoolean(SettingsKeys.CUSTOM_PROVIDER_VERIFIED)
    ) { enabled, verified -> enabled && verified }

    suspend fun resolve(): CustomProviderConfig? {
        if (!settings.getBoolean(SettingsKeys.CUSTOM_PROVIDER_ENABLED).first()) return null
        if (!settings.getBoolean(SettingsKeys.CUSTOM_PROVIDER_VERIFIED).first()) return null

        // Settings come first so only a user who configured an endpoint ever waits here. The gate
        // reads false until the server answers, and falling through on that would bill a scan to
        // the Scanly quota that the user set their own endpoint up to take.
        if (!proEntitlementGate.awaitPro()) return null

        val protocol = ProviderProtocol.fromName(
            settings.getString(SettingsKeys.CUSTOM_PROVIDER_PROTOCOL).first()
        )
        val model = settings.getString(SettingsKeys.CUSTOM_PROVIDER_MODEL).first().trim()
        val url = EndpointUrl.normalize(
            settings.getString(SettingsKeys.CUSTOM_PROVIDER_URL).first(),
            protocol,
            model
        ) ?: return null
        val apiKey = settings.getString(SettingsKeys.CUSTOM_PROVIDER_KEY).first().trim()
        if (apiKey.isBlank() || model.isBlank()) return null

        return CustomProviderConfig(
            url = url,
            apiKey = apiKey,
            model = model,
            protocol = protocol,
            authStyle = AuthStyle.fromName(
                settings.getString(SettingsKeys.CUSTOM_PROVIDER_AUTH_STYLE).first()
            ),
            customAuthHeader = settings.getString(SettingsKeys.CUSTOM_PROVIDER_AUTH_HEADER).first()
        )
    }
}
