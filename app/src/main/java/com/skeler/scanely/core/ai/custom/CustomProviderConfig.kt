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

/** Where an AI request that may use the user's own endpoint goes. */
internal sealed interface EndpointChoice {
    data class Own(val config: CustomProviderConfig) : EndpointChoice
    data object Scanly : EndpointChoice

    /**
     * The user switched their own endpoint on, but Pro, which it needs, was not confirmed. Neither
     * route: Scanly would spend credits the user did not choose to spend.
     */
    data object NeedsPro : EndpointChoice
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

    suspend fun resolve(): CustomProviderConfig? = (choose() as? EndpointChoice.Own)?.config

    suspend fun choose(): EndpointChoice {
        if (!settings.getBoolean(SettingsKeys.CUSTOM_PROVIDER_ENABLED).first()) return EndpointChoice.Scanly
        if (!settings.getBoolean(SettingsKeys.CUSTOM_PROVIDER_VERIFIED).first()) return EndpointChoice.Scanly

        // Settings come first so only a user who configured an endpoint ever waits here. The gate
        // reads false until the server answers (and stays false once Pro has ended); falling through
        // to Scanly on that would bill the user's credits for a request they sent to their endpoint.
        if (!proEntitlementGate.awaitPro()) return EndpointChoice.NeedsPro
        return configured()?.let(EndpointChoice::Own) ?: EndpointChoice.Scanly
    }

    private suspend fun configured(): CustomProviderConfig? {
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
