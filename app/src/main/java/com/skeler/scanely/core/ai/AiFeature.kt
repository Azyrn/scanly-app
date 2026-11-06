package com.skeler.scanely.core.ai

/**
 * Every AI feature, and where each one runs when the user has configured their own endpoint.
 *
 * This exists because the answer used to be decided separately inside each method of
 * [GenerativeAiService], and three of the four got it wrong. Extraction and page ranges were
 * dropped on the floor by a custom scan, translation silently went to the backend and billed quota
 * that Settings promises it would not, and design never consulted the setting at all. None of those
 * was visible at the call site: each method simply omitted an `if`, and an omission looks like
 * nothing.
 *
 * So routing is declared here as data, taken by [GenerativeAiService.route], and pinned by
 * `AiFeatureRoutingTest`, which fails when an entry point exists that no entry in this enum
 * covers. Adding a feature now forces the question to be answered rather than skipped.
 */
enum class AiFeature(val label: String, val routing: CustomRouting) {
    SCAN("AI Scan", CustomRouting.ON_YOUR_ENDPOINT),
    TRANSLATE("Translate", CustomRouting.ON_YOUR_ENDPOINT),

    /**
     * Always Scanly's designer, with a custom endpoint or without. The custom endpoint is for
     * scanning; documents are built by Scanly's own model and compiler.
     */
    DESIGN("Build Documents with AI", CustomRouting.SCANLY_ONLY),

    /**
     * Speech to text. A custom endpoint is an OpenAI-compatible *chat* API, which takes no audio,
     * so there is nothing of the user's to route this to: it always bills Scanly credits, and the
     * screen says so to anyone who has an endpoint switched on.
     */
    TRANSCRIBE("Audio to Text", CustomRouting.SCANLY_ONLY)
}

enum class CustomRouting {
    /** The request goes straight from the device to the user's provider. No Scanly quota. */
    ON_YOUR_ENDPOINT,

    /** Always Scanly's backend, whatever the user configured. */
    SCANLY_ONLY;

    val usesCustomEndpoint: Boolean get() = this != SCANLY_ONLY
}

/**
 * Marks a public entry point on [GenerativeAiService] as one whole AI feature.
 *
 * Read by `AiFeatureRoutingTest` rather than at runtime: the test enumerates the service's public
 * surface and fails on anything that is neither annotated nor listed as a deliberate non-feature.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AiEntryPoint(val feature: AiFeature)
