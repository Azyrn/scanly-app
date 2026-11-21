package com.skeler.scanely.core.network

import kotlinx.serialization.json.Json

// The app's Json configurations, one instance per distinct configuration; call sites share one
// only when they need identical settings. SettingsModule's injected Json and LookupJson are the
// other two.

/** Reading answers that may gain fields later: the Worker's, and OpenAI-compatible providers'. */
val TolerantJson: Json = Json { ignoreUnknownKeys = true }

/** Retrofit bodies to the Worker and custom providers: defaults are sent, nulls are left out. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Model output, which is only nearly JSON: unquoted keys and strings are accepted. */
val LenientJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Files the app writes and reads back, where a default left out of the file would be lost. */
val StoreJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
