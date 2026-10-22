package com.skeler.scanely.core.ai

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bug class that produced three separate defects at once: an AI feature that quietly
 * ignores the user's own endpoint.
 *
 * None of the three was visible in review. Extraction and page ranges were dropped because a custom
 * scan called a method that took neither; translation went to the backend because nobody wrote the
 * `if`; design skipped the resolver on purpose but said so only in a comment. An omission has no
 * shape, so nothing catches it by reading.
 *
 * This does, by refusing to let the service's public surface and [AiFeature] drift apart. A fifth
 * entry point fails [everyPublicEntryPointIsAccountedFor] until it is either annotated — which
 * forces a routing decision — or listed below as a deliberate non-feature, which forces someone to
 * write down why.
 */
class AiFeatureRoutingTest {

    /**
     * Public members that are not AI features. Anything added here should be a genuine utility;
     * an actual feature parked here to silence the test is the failure this test exists to catch.
     */
    private val notFeatures = setOf(
        // Reads the setting rather than acting on it: the paywall asks before offering an upgrade.
        "usesCustomProvider"
    )

    private fun publicApi(): List<Method> = GenerativeAiService::class.java.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) }
        .filterNot { it.isSynthetic || it.isBridge }
        // Kotlin emits `name$default` bridges for default arguments and `access$x` for private
        // members reached from a lambda. Neither is part of the surface a caller sees.
        .filterNot { it.name.contains('$') }

    @Test
    fun everyPublicEntryPointIsAccountedFor() {
        val unaccounted = publicApi()
            .filter { it.getAnnotation(AiEntryPoint::class.java) == null }
            .map { it.name }
            .toSet() - notFeatures

        assertEquals(
            "These are public on GenerativeAiService but declare no AiFeature. Annotate each with " +
                "@AiEntryPoint — which is where you decide whether it runs on the user's own " +
                "endpoint — or add it to `notFeatures` with a reason.",
            emptySet<String>(),
            unaccounted
        )
    }

    @Test
    fun everyFeatureHasExactlyOneEntryPoint() {
        val covered = publicApi()
            .mapNotNull { it.getAnnotation(AiEntryPoint::class.java)?.feature }

        assertEquals(
            "Every AiFeature needs an entry point, and no two may share one.",
            AiFeature.entries.toSet(),
            covered.toSet()
        )
        assertEquals(AiFeature.entries.size, covered.size)
    }

    /**
     * The promise on the custom-endpoint settings screen, as a test. Translation used to break it:
     * it reserved and settled quota pages on `/v1/translate` while the screen said it would not.
     */
    @Test
    fun everythingBillableToTheUsersOwnProviderActuallyRoutesThere() {
        val stayingOnScanly = AiFeature.entries
            .filter { it.routing == CustomRouting.SCANLY_ONLY }
            .map { it.label }

        // Transcription is the one exception, and the settings sentence names it: a custom
        // endpoint is a chat API and takes no audio.
        assertEquals(
            "Settings tells the user their endpoint handles scans, extraction, translation, " +
                "drafts and designs, and that Audio to Text alone stays on Scanly AI. A feature " +
                "that stays on Scanly has to be named in that sentence first.",
            listOf(AiFeature.TRANSCRIBE.label),
            stayingOnScanly
        )
    }

    @Test
    fun designIsTheOnlyFeatureThatStillCostsQuotaOnACustomEndpoint() {
        val billed = AiFeature.entries
            .filter { it.routing == CustomRouting.YOUR_MODEL_OUR_COMPILER }

        // A chat endpoint returns text, and only Scanly's container turns Typst into a PDF. Any
        // other feature landing here would mean quota is being charged for work we did not do.
        assertEquals(listOf(AiFeature.DESIGN), billed)
    }

    @Test
    fun customRoutingKnowsWhichModesUseTheEndpoint() {
        assertTrue(CustomRouting.ON_YOUR_ENDPOINT.usesCustomEndpoint)
        assertTrue(CustomRouting.YOUR_MODEL_OUR_COMPILER.usesCustomEndpoint)
        assertTrue(!CustomRouting.SCANLY_ONLY.usesCustomEndpoint)
    }
}
