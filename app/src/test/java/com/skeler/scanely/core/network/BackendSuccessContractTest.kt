package com.skeler.scanely.core.network

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.*

/** Shapes transcribed from worker/src/account.ts, routes/scan.ts, mistral.ts, paddle.ts,
 * routes/design.ts and worker/test/design.mjs. Synthetic values; no provider requests. */
class BackendSuccessContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private inline fun <reified T> fixture(name: String): T = json.decodeFromString(
        checkNotNull(javaClass.getResource("/backend/$name.json")).readText()
    )

    @Test fun `me preserves server limits null resets and catalogue`() {
        val me = fixture<MeResponse>("me")
        assertEquals(5242880L, me.maxUploadBytes)
        assertEquals(10, me.pagesRemainingMonth)
        assertEquals("free", me.plans.single().id)
        assertFalse(me.allowsDesign)
    }
    @Test fun `OCR accepts populated and null extraction`() {
        val ocr = fixture<OcrResponse>("ocr")
        assertEquals("10", ocr.extraction!!.fields.single().value)
        assertEquals(2, ocr.usage.pages)
        assertEquals("Total", ocr.pages.single().unclear.single())
        assertNull(fixture<OcrResponse>("ocr-no-extraction").extraction)
    }
    @Test fun `license routes accept null renewal and verification outcomes`() {
        val license = fixture<LicenseEntitlementDto>("license")
        assertTrue(license.grantsAccess)
        assertNull(license.currentPeriodEnd)
        assertEquals(3, license.maxActivations)
        assertTrue(fixture<LicenseRecoveryResponse>("license-mine").licenses.single().grantsAccess)
        val unverified = fixture<LicenseRecoveryResponse>("license-unverified")
        assertTrue(unverified.emailUnverified)
        assertTrue(unverified.licenses.isEmpty())
    }
    @Test fun `designer prompt and compile failure have distinct success bodies`() {
        assertEquals("user fixture", fixture<DesignPromptResponse>("design-prompt").user)
        val failure = fixture<DesignCompileFailure>("design-compile-error")
        assertFalse(failure.ok)
        assertTrue(failure.diagnostics.startsWith("error:"))
    }
    @Test fun `translate decodes provider SSE data chunks`() {
        assertEquals("Bonjour", fixture<ChatStreamChunk>("stream").choices.single().delta!!.content)
    }
    @Test fun `generation never sends caller Typst while explicit compile does`() {
        val generation = json.encodeToString(DesignRequest(prompt = "invoice", designId = "owned-id"))
        assertFalse(generation.contains("source"))
        assertTrue(json.encodeToString(DesignCompileRequest("= Invoice", "invoice")).contains("source"))
    }
    @Test fun `all Retrofit route methods match the worker router`() {
        val expected = mapOf("me" to "GET v1/me", "deleteAccount" to "DELETE v1/me",
            "ocr" to "POST v1/ocr", "transcribe" to "POST v1/transcribe",
            "translate" to "POST v1/translate",
            "design" to "POST v1/design", "designJob" to "GET v1/design/jobs/{id}",
            "designChat" to "POST v1/design", "designJobEvents" to "GET v1/design/jobs/{id}/events",
            "designOcr" to "POST v1/design/ocr", "designPrompt" to "POST v1/design/prompt",
            "designCompile" to "POST v1/design/compile", "activateLicense" to "POST v1/license/activate",
            "licenseStatus" to "POST v1/license/status", "deactivateLicense" to "POST v1/license/deactivate",
            "myLicenses" to "POST v1/license/mine")
        for (method in ScanlyApi::class.java.declaredMethods) {
            val route = method.getAnnotation(GET::class.java)?.let { "GET ${it.value}" }
                ?: method.getAnnotation(POST::class.java)?.let { "POST ${it.value}" }
                ?: method.getAnnotation(DELETE::class.java)?.let { "DELETE ${it.value}" }
            assertEquals(method.name, expected[method.name], route)
        }
        for (billed in listOf("designCompile", "transcribe")) {
            val method = ScanlyApi::class.java.declaredMethods.single { it.name == billed }
            assertTrue(billed, method.parameterAnnotations.flatten().filterIsInstance<Header>()
                .any { it.value == "X-Idempotency-Key" })
        }
        val public = ScanlyPublicApi::class.java.declaredMethods.associate { method ->
            method.name to (
                method.getAnnotation(GET::class.java)?.let { "GET ${it.value}" }
                    ?: method.getAnnotation(POST::class.java)?.let { "POST ${it.value}" }
                )
        }
        assertEquals(
            mapOf("sendPasswordReset" to "POST v1/auth/password-reset", "features" to "GET v1/features"),
            public
        )
    }
}
