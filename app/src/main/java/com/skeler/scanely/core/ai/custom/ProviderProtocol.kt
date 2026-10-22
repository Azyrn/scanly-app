package com.skeler.scanely.core.ai.custom

/**
 * The wire format an endpoint speaks.
 *
 * This is the real axis of compatibility — not the vendor. Most hosts speak [OPENAI], so that
 * stays the default; [ANTHROPIC] and [GEMINI] exist because their native APIs differ in all three
 * of URL shape, auth header, and body, and no amount of URL-typing gets you there.
 */
enum class ProviderProtocol(
    val label: String,
    val hint: String,
    val defaultAuth: AuthStyle
) {
    OPENAI(
        label = "OpenAI-compatible",
        hint = "Groq, OpenAI, OpenRouter, NVIDIA NIM, Together, Fireworks, vLLM, Ollama, LM Studio",
        defaultAuth = AuthStyle.BEARER
    ),
    ANTHROPIC(
        label = "Anthropic (Claude)",
        hint = "The native /v1/messages API",
        defaultAuth = AuthStyle.X_API_KEY
    ),
    GEMINI(
        label = "Google Gemini",
        hint = "The native generateContent API — the model goes in the URL, built for you",
        defaultAuth = AuthStyle.GOOG_API_KEY
    );

    companion object {
        val DEFAULT = OPENAI

        fun fromName(name: String?): ProviderProtocol =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * How the key is presented. Split from [ProviderProtocol] because they genuinely vary
 * independently: Azure OpenAI speaks OpenAI JSON behind an `api-key` header, and self-hosted
 * gateways put OpenAI JSON behind whatever header their operator chose.
 */
enum class AuthStyle(val label: String, val headerName: String?) {
    BEARER("Authorization: Bearer", "Authorization"),
    X_API_KEY("x-api-key", "x-api-key"),
    GOOG_API_KEY("x-goog-api-key", "x-goog-api-key"),
    API_KEY("api-key (Azure)", "api-key"),

    /** The header name comes from the user; see `CustomProviderConfig.customAuthHeader`. */
    CUSTOM("Custom header", null);

    fun headersFor(apiKey: String, customHeaderName: String): Map<String, String> {
        val value = if (this == BEARER) "Bearer $apiKey" else apiKey
        val name = headerName ?: customHeaderName.trim().ifBlank { return emptyMap() }
        return mapOf(name to value)
    }

    companion object {
        val DEFAULT = BEARER

        fun fromName(name: String?): AuthStyle =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
