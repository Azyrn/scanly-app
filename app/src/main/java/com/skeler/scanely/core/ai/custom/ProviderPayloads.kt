package com.skeler.scanely.core.ai.custom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the request body each protocol expects.
 *
 * JSON is assembled directly rather than through typed DTOs because the three shapes share almost
 * nothing, and a sealed hierarchy covering all of them would be more scaffolding than the
 * difference deserves.
 */
internal object ProviderRequest {

    private const val PROBE_TEXT = "ping"
    private const val PROBE_MAX_TOKENS = 1
    private const val SCAN_MAX_TOKENS = 8192

    /** The smallest request that still exercises the key, the path, and the model. */
    fun probe(protocol: ProviderProtocol, model: String): JsonObject = when (protocol) {
        ProviderProtocol.OPENAI -> buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", PROBE_TEXT)
                    }
                )
            }
            put("max_tokens", PROBE_MAX_TOKENS)
            put("stream", false)
        }
        ProviderProtocol.ANTHROPIC -> buildJsonObject {
            put("model", model)
            put("max_tokens", PROBE_MAX_TOKENS)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", PROBE_TEXT)
                    }
                )
            }
        }
        // The model is already in the URL for Gemini, so it is absent from the body by design.
        ProviderProtocol.GEMINI -> buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", PROBE_TEXT) }) }
                    }
                )
            }
        }
    }

    fun completion(
        protocol: ProviderProtocol,
        model: String,
        systemInstruction: String,
        prompt: String,
        imagesBase64: List<String>
    ): JsonObject = when (protocol) {
        ProviderProtocol.OPENAI -> buildJsonObject {
            put("model", model)
            put("temperature", 1.0)
            put("stream", false)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", systemInstruction)
                    }
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", openAiContent(prompt, imagesBase64))
                    }
                )
            }
        }
        ProviderProtocol.ANTHROPIC -> buildJsonObject {
            put("model", model)
            put("max_tokens", SCAN_MAX_TOKENS)
            put("system", systemInstruction)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", anthropicContent(prompt, imagesBase64))
                    }
                )
            }
        }
        ProviderProtocol.GEMINI -> buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemInstruction) }) }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("parts", geminiParts(prompt, imagesBase64))
                    }
                )
            }
        }
    }

    private fun openAiContent(prompt: String, images: List<String>): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", prompt)
            }
        )
        images.forEach { image ->
            add(
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$image") }
                }
            )
        }
    }

    private fun anthropicContent(prompt: String, images: List<String>): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", prompt)
            }
        )
        images.forEach { image ->
            add(
                buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", image)
                    }
                }
            )
        }
    }

    private fun geminiParts(prompt: String, images: List<String>): JsonArray = buildJsonArray {
        add(buildJsonObject { put("text", prompt) })
        images.forEach { image ->
            add(
                buildJsonObject {
                    putJsonObject("inline_data") {
                        put("mime_type", "image/jpeg")
                        put("data", image)
                    }
                }
            )
        }
    }
}

/** Pulls the generated text out of whichever envelope came back. */
internal object ProviderResponse {

    fun extractText(protocol: ProviderProtocol, root: JsonObject): String? = when (protocol) {
        ProviderProtocol.OPENAI -> {
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            val message = choice?.get("message") as? JsonObject
            (message?.get("content") as? JsonPrimitive)?.content
                ?: ((choice?.get("delta") as? JsonObject)?.get("content") as? JsonPrimitive)?.content
        }
        ProviderProtocol.ANTHROPIC -> (root["content"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.filter { (it["type"] as? JsonPrimitive)?.content == "text" }
            ?.mapNotNull { (it["text"] as? JsonPrimitive)?.content }
            ?.joinToString("")
        ProviderProtocol.GEMINI -> {
            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            (candidate?.get("content") as? JsonObject)
                ?.let { it["parts"] as? JsonArray }
                ?.filterIsInstance<JsonObject>()
                // Thinking models emit reasoning parts that are not the transcription.
                ?.filter { (it["thought"] as? JsonPrimitive)?.content != "true" }
                ?.mapNotNull { (it["text"] as? JsonPrimitive)?.content }
                ?.joinToString("")
        }
    }

    /** True when the body is recognisably this protocol's success envelope. */
    fun matchesSchema(protocol: ProviderProtocol, root: JsonObject): Boolean = when (protocol) {
        ProviderProtocol.OPENAI -> {
            val choices = root["choices"] as? JsonArray
            val first = choices?.firstOrNull() as? JsonObject
            first != null &&
                (
                    (first["message"] as? JsonObject)?.containsKey("content") == true ||
                        (first["delta"] as? JsonObject) != null
                    )
        }
        ProviderProtocol.ANTHROPIC -> (root["content"] as? JsonArray)?.isNotEmpty() == true
        ProviderProtocol.GEMINI -> {
            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            (candidate?.get("content") as? JsonObject)?.get("parts") is JsonArray
        }
    }
}
