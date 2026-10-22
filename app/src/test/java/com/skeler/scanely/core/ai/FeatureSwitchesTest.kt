package com.skeler.scanely.core.ai

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.skeler.scanely.core.network.ApiJson
import com.skeler.scanely.core.network.LocalWorker
import com.skeler.scanely.core.network.ScanlyPublicApi
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * A feature switched off on the server must leave the app, not fail on every tap. Runs the
 * production Retrofit/Json settings against a real local HTTP server speaking the Worker's answer.
 */
class FeatureSwitchesTest {

    private val worker = LocalWorker()

    @After fun tearDown() = worker.close()

    private val api: ScanlyPublicApi = Retrofit.Builder()
        .baseUrl(worker.baseUrl)
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ScanlyPublicApi::class.java)

    private class MemoryStore(var names: Set<String> = emptySet()) : DisabledFeatureStore {
        override suspend fun load() = names
        override suspend fun save(names: Set<String>) { this.names = names }
    }

    private fun answer(vararg off: String): Pair<Int, String> {
        val body = listOf("scan", "compose", "translate", "design", "transcribe")
            .joinToString(",") { "\"$it\":${it !in off}" }
        return 200 to """{"features":{$body}}"""
    }

    @Test
    fun `features the worker switches off are the ones reported disabled`() = runBlocking {
        worker.route("/v1/features") { answer("design", "transcribe") }
        val store = MemoryStore()
        val switches = FeatureSwitches(api, store)

        switches.refresh()

        assertEquals(setOf(AiFeature.DESIGN, AiFeature.TRANSCRIBE), switches.disabled.value)
        assertEquals(setOf("DESIGN", "TRANSCRIBE"), store.names)
    }

    @Test
    fun `the agent is opt-in, on only when the server says true, and remembered`() = runBlocking {
        worker.route("/v1/features") { 200 to """{"features":{"design":true}}""" }
        val store = MemoryStore()
        val off = FeatureSwitches(api, store)
        off.refresh()
        assertFalse("not mentioned means off for the agent", off.agentEnabled.value)

        worker.route("/v1/features") { 200 to """{"features":{"design":true,"agent":true}}""" }
        val on = FeatureSwitches(api, store)
        on.refresh()
        assertTrue(on.agentEnabled.value)
        assertEquals(emptySet<AiFeature>(), on.disabled.value)

        worker.route("/v1/features") { 500 to """{"error":{"code":"internal","message":"x"}}""" }
        val offline = FeatureSwitches(api, store)
        offline.refresh()
        assertTrue("the last answer stands offline", offline.agentEnabled.value)
    }

    @Test
    fun `a failed refresh keeps the last answer the server gave`() = runBlocking {
        worker.route("/v1/features") { 500 to """{"error":{"code":"internal","message":"x"}}""" }
        val switches = FeatureSwitches(api, MemoryStore(setOf("SCAN")))

        switches.refresh()

        assertEquals(setOf(AiFeature.SCAN), switches.disabled.value)
    }

    @Test
    fun `a feature the server does not name, or a name the app does not know, stays on`() {
        val parsed = disabledFeatures(
            com.skeler.scanely.core.network.FeaturesResponse(mapOf("scan" to true, "holograms" to false))
        )
        assertEquals(emptySet<AiFeature>(), parsed)
    }

    @Test
    fun `a switched-off feature is hidden unless the user's own endpoint serves it`() {
        val off = setOf(AiFeature.SCAN, AiFeature.TRANSCRIBE, AiFeature.DESIGN)

        assertFalse(AiFeature.SCAN.isOffered(off, customProvider = false))
        assertTrue(AiFeature.SCAN.isOffered(off, customProvider = true))
        // Transcription never runs on a custom endpoint, so nothing can stand in for it.
        assertFalse(AiFeature.TRANSCRIBE.isOffered(off, customProvider = true))
        // The designer's model is the user's; only the compile is Scanly's, and that is not switched.
        assertTrue(AiFeature.DESIGN.isOffered(off, customProvider = true))
        assertTrue(AiFeature.TRANSLATE.isOffered(off, customProvider = false))
    }

    @Test
    fun `the AI Scan card goes only when nothing in its sheet is left`() {
        assertTrue(showsAiScanCard(setOf(AiFeature.TRANSCRIBE)))
        assertFalse(showsAiScanCard(setOf(AiFeature.TRANSLATE)))
        assertFalse(showsAiScanCard(emptySet()))
    }
}
