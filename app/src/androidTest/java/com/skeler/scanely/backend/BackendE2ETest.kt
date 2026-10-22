package com.skeler.scanely.backend

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.skeler.scanely.BuildConfig
import com.skeler.scanely.core.ai.BackendClient
import com.skeler.scanely.core.ai.FatalAiException
import com.skeler.scanely.core.ai.PayloadFactory
import com.skeler.scanely.core.network.BackendAuthInterceptor
import com.skeler.scanely.core.network.ScanlyApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives the real app code against the live Worker, on a real device.
 *
 * Covers what curl could not: [BackendAuthInterceptor] attaching a Firebase ID token, the multipart
 * upload, and [BackendClient]'s mapping of backend error codes onto the app's failure types.
 *
 * Talks to production and spends real Mistral credit — about $0.008 a run.
 */
@RunWith(AndroidJUnit4::class)
class BackendE2ETest {

    companion object {
        private lateinit var api: ScanlyApi
        private lateinit var client: BackendClient

        /**
         * One stable account, reused across runs. A throwaway account per run trips Firebase
         * Auth's anti-abuse throttle ("Too many attempts") after a handful of runs from the same
         * device. Nothing here depends on a *full* quota anyway — consumption is asserted as a
         * delta, which holds whatever the starting balance is.
         */
        @BeforeClass
        @JvmStatic
        fun signIn(): Unit = runBlocking {
            val auth = FirebaseAuth.getInstance()
            val email = "e2e-android@scanly.test"
            val password = "TestPass!2026"

            try {
                auth.signInWithEmailAndPassword(email, password).await()
            } catch (_: Exception) {
                // First run on a fresh Firebase project: the account does not exist yet.
                auth.createUserWithEmailAndPassword(email, password).await()
            }
            checkNotNull(auth.currentUser) { "sign-in produced no user" }

            api = Retrofit.Builder()
                .baseUrl(BuildConfig.SCANLY_API_BASE_URL)
                .client(
                    OkHttpClient.Builder()
                        .addInterceptor(BackendAuthInterceptor(auth))
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(300, TimeUnit.SECONDS)
                        .writeTimeout(120, TimeUnit.SECONDS)
                        .build()
                )
                .addConverterFactory(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                        explicitNulls = false
                    }.asConverterFactory("application/json".toMediaType())
                )
                .build()
                .create(ScanlyApi::class.java)

            client = BackendClient(api)
        }

        private fun asset(name: String): ByteArray =
            InstrumentationRegistry.getInstrumentation().context.assets
                .open(name).use { it.readBytes() }

        private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
            addOnSuccessListener { cont.resume(it) }
            addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    /** Reaching /v1/me at all proves the ID token was attached and accepted. */
    @Test
    fun newAccountStartsOnFreeTier() = runBlocking {
        val me = api.me()
        assertEquals("free", me.tier)
        assertEquals(10, me.pagesPerMonth)
        assertEquals(3, me.maxPagesPerDoc)
        // Deliberately no assertion on pages *remaining*: JUnit does not run test methods in
        // source order, so a sibling test may already have spent one. The other tests assert
        // consumption as a delta, which is order-independent.
    }

    /** An image OCRs to real text and costs exactly one page. */
    @Test
    fun imageScanReturnsTextAndBillsOnePage() = runBlocking {
        val before = api.me().pagesRemainingMonth

        val response = client.ocr(
            PayloadFactory.Payload.File(asset("single.jpg"), "image/jpeg", "scan.jpg"),
            UUID.randomUUID().toString()
        )

        assertTrue("expected OCR text, got: '${response.text}'", response.text.contains("SCANLY"))
        assertTrue(response.text.contains("INV-2026-0714"))
        assertEquals(1, response.pagesBilled)
        assertEquals(before - 1, api.me().pagesRemainingMonth)
    }

    /**
     * The free tier caps a document at 3 pages, so a 6-page PDF must surface as a fatal error the
     * paywall can key off — and must not cost a page. The page count in the message is the
     * server's own, derived from the bytes.
     */
    @Test
    fun oversizedDocumentIsRejectedWithoutCharge() = runBlocking {
        val before = api.me().pagesRemainingMonth

        try {
            client.ocr(
                PayloadFactory.Payload.File(asset("six-page.pdf"), "application/pdf", "doc.pdf"),
                UUID.randomUUID().toString()
            )
            fail("a 6-page PDF must be refused on the free tier")
        } catch (e: FatalAiException) {
            assertEquals("doc_too_large", e.result.code)
            assertTrue(
                "message should name the real page count, was: ${e.result.message}",
                e.result.message.contains("6 pages")
            )
            assertTrue("should route the user to an upgrade", e.result.isUpgradePath)
        }

        assertEquals("a refused scan must not be billed", before, api.me().pagesRemainingMonth)
    }
}
