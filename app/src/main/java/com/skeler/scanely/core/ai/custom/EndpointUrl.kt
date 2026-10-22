package com.skeler.scanely.core.ai.custom

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Retrofit resolves a scheme-less `@Url` against its base URL, so an endpoint typed as
 * "my-host/v1/chat/completions" would POST the user's key to the base host instead. Only an
 * absolute http(s) URL may ever reach the client — that is the one rule here that is not a
 * convenience.
 */
internal object EndpointUrl {

    /**
     * Null when [raw] cannot be made into an absolute http(s) URL.
     *
     * [model] is only consulted for [ProviderProtocol.GEMINI], whose native API addresses the
     * model in the path rather than the body.
     */
    fun normalize(
        raw: String,
        protocol: ProviderProtocol = ProviderProtocol.DEFAULT,
        model: String = ""
    ): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        // A bare host defaults to https; localhost is the one place plain http is the norm, and
        // guessing https there would fail every self-hosted setup.
        val scheme = if (looksLocal(trimmed)) "http" else "https"
        val absolute = if (HAS_SCHEME.containsMatchIn(trimmed)) trimmed else "$scheme://$trimmed"
        val url = absolute.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null

        return when (protocol) {
            ProviderProtocol.OPENAI -> openAiPath(url.toString())
            ProviderProtocol.ANTHROPIC -> anthropicPath(url.toString())
            ProviderProtocol.GEMINI -> geminiPath(url.toString(), model)
        }
    }

    private fun openAiPath(url: String): String = when {
        url.endsWith(OPENAI_PATH) -> url
        url.trimEnd('/').endsWith("/v1") -> "$url$OPENAI_PATH"
        else -> url
    }

    private fun anthropicPath(url: String): String = when {
        url.endsWith(ANTHROPIC_PATH) -> url
        url.trimEnd('/').endsWith("/v1") -> "$url/messages"
        else -> url
    }

    /**
     * Gemini names the model in the path, so the model field and the URL cannot be independent.
     * Whatever the user pastes, the model they typed is the one that gets called.
     */
    private fun geminiPath(url: String, model: String): String {
        if (model.isBlank()) return url
        val base = url.substringBefore("/v1beta").substringBefore("/v1/").trimEnd('/')
        val existing = Regex("/(v1beta|v1)/models/[^:/]+:generateContent").find(url)
        if (existing != null) {
            return url.replace(existing.value, "/${existing.groupValues[1]}/models/$model:generateContent")
        }
        return "$base/v1beta/models/$model:generateContent"
    }

    fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host.orEmpty()

    /** Cloudflare Workers AI bakes the account id into the path, so the URL alone identifies it. */
    fun looksLikeCloudflare(raw: String): Boolean =
        raw.contains("api.cloudflare.com", ignoreCase = true)

    /**
     * Loopback and RFC1918 addresses. These are the only hosts the app permits over plain http
     * (see `network_security_config.xml`), because a self-hosted model server on your own LAN
     * rarely has a certificate.
     */
    fun looksLocal(raw: String): Boolean {
        val host = raw.trim()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')
            .lowercase()
        if (host == "localhost" || host.endsWith(".local")) return true
        return PRIVATE_IP.matches(host)
    }

    private const val OPENAI_PATH = "/chat/completions"
    private const val ANTHROPIC_PATH = "/messages"
    private val HAS_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    private val PRIVATE_IP = Regex(
        "^(127\\.\\d+\\.\\d+\\.\\d+" +
            "|10\\.\\d+\\.\\d+\\.\\d+" +
            "|192\\.168\\.\\d+\\.\\d+" +
            "|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+)$"
    )
}
