package com.skeler.scanely.core.ai.custom

sealed interface ConnectionFailure {

    val message: String

    data object EmptyUrl : ConnectionFailure {
        override val message =
            "Enter the endpoint URL. For most providers it ends in /v1/chat/completions."
    }

    data class MalformedUrl(val raw: String) : ConnectionFailure {
        override val message =
            "\"$raw\" isn't a valid URL. Use the full address, including https:// — " +
                "for example https://api.groq.com/openai/v1/chat/completions."
    }

    data object EmptyKey : ConnectionFailure {
        override val message = "Enter the API key your provider issued you."
    }

    data object EmptyAuthHeader : ConnectionFailure {
        override val message =
            "You chose a custom auth header, so name it — for example x-api-key."
    }

    data object EmptyModel : ConnectionFailure {
        override val message =
            "Enter the model name exactly as your provider spells it, for example " +
                "llama-3.3-70b-versatile."
    }

    data object Offline : ConnectionFailure {
        override val message =
            "No internet connection. Connect to a network and test again."
    }

    data class UnknownHost(val host: String) : ConnectionFailure {
        override val message =
            "Can't find \"$host\". Check the address for a typo, and check that you're online."
    }

    data class Timeout(val host: String) : ConnectionFailure {
        override val message =
            "$host didn't respond in time. It may be down, or blocked by your network — " +
                "try again, or check the address."
    }

    data class Unreachable(val host: String) : ConnectionFailure {
        override val message =
            "Couldn't reach $host. Check your connection and that the address is right."
    }

    data class InvalidKey(val status: Int) : ConnectionFailure {
        override val message =
            "Your API key was rejected (HTTP $status). Check you copied it in full, that it " +
                "hasn't expired, and that it belongs to this provider."
    }

    data object WrongEndpoint : ConnectionFailure {
        override val message =
            "That endpoint doesn't exist (HTTP 404). Check the path — most providers expect it " +
                "to end in /v1/chat/completions."
    }

    data object RateLimited : ConnectionFailure {
        override val message =
            "The provider is rate limiting you (HTTP 429). Your key and endpoint look right — " +
                "wait a moment and test again."
    }

    data class ModelRejected(val model: String, val detail: String?) : ConnectionFailure {
        override val message = buildString {
            append("The provider doesn't recognise the model \"$model\".")
            append(" Check the exact spelling in your provider's model list")
            append(", and that your account has access to it.")
            if (!detail.isNullOrBlank()) append("\n\nProvider said: $detail")
        }
    }

    data class ProviderError(val status: Int, val detail: String?) : ConnectionFailure {
        override val message = buildString {
            append(
                if (status in SERVER_ERRORS) {
                    "The provider had a server error (HTTP $status). That's on their side — " +
                        "try again shortly."
                } else {
                    "The provider rejected the request (HTTP $status)."
                }
            )
            if (!detail.isNullOrBlank()) append("\n\nProvider said: $detail")
        }
    }

    data object MalformedJson : ConnectionFailure {
        override val message =
            "The endpoint replied, but not with JSON. This usually means the URL points at a " +
                "web page rather than an API — check the path ends in /v1/chat/completions."
    }

    data object UnexpectedSchema : ConnectionFailure {
        override val message =
            "The endpoint replied with JSON that isn't an OpenAI-compatible chat completion. " +
                "Check the URL points at a /chat/completions route on an OpenAI-compatible API."
    }

    data class NetworkError(val host: String, val detail: String?) : ConnectionFailure {
        override val message = buildString {
            append("Couldn't complete the request to $host.")
            if (!detail.isNullOrBlank()) append(" $detail")
        }
    }

    private companion object {
        val SERVER_ERRORS = 500..599
    }
}
