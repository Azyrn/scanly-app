package com.skeler.scanely.core.network

import kotlinx.serialization.Serializable

/**
 * Server-sent-event chunks from `/v1/translate`. The Worker streams the model's SSE straight
 * through rather than buffering it, so the app keeps the token-by-token translation UI it had
 * when it called the provider directly.
 */
@Serializable
data class ChatStreamChunk(
    val choices: List<StreamChoice> = emptyList()
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta? = null
)

@Serializable
data class StreamDelta(
    val content: String? = null
)
