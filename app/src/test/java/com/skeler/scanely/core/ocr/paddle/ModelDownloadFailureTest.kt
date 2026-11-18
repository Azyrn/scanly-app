package com.skeler.scanely.core.ocr.paddle

import android.content.Context
import com.skeler.scanely.R
import com.skeler.scanely.testing.EnglishStrings
import dagger.Lazy
import java.io.File
import java.net.UnknownHostException
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * What the Text recognition screen prints under a model that failed to download. It used to be
 * the exception's own text ("Unable to resolve host …", "HTTP 503", "SHA-256 mismatch").
 */
class ModelDownloadFailureTest {

    private val filesDir: File = createTempDirectory("model-store").toFile()

    @After fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun context(): Context {
        val strings = EnglishStrings.context()
        return mock(Context::class.java) { call ->
            if (call.method.name == "getFilesDir") filesDir else call.method.invoke(strings, *call.arguments)
        }
    }

    /** A client whose every call fails the way [failure] says, before anything reaches the network. */
    private fun failingClient(failure: Exception): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { throw failure }.build()

    private fun store(failure: Exception) = PaddleModelStore(context(), Lazy { failingClient(failure) })

    @Test fun `an offline pack download shows a sentence, not the exception`() = runBlocking {
        val raw = "Unable to resolve host \"huggingface.co\": No address associated with hostname"
        val store = store(UnknownHostException(raw))

        assertTrue(store.download(ScriptPack.KOREAN).isFailure)

        val state = store.states.value.getValue(ScriptPack.KOREAN)
        assertTrue("state: $state", state is PackState.Failed)
        val message = (state as PackState.Failed).message
        assertFalse("message: $message", message.contains("huggingface"))
        assertEquals(EnglishStrings.get(R.string.ocr_err_download_offline), message)
    }

    @Test fun `a failed optional model download shows a sentence, not the exception`() = runBlocking {
        val store = store(IllegalStateException("HTTP 503"))

        assertTrue(store.uvdoc.download().isFailure)

        val state = store.uvdoc.state.value
        assertTrue("state: $state", state is PackState.Failed)
        val message = (state as PackState.Failed).message
        assertFalse("message: $message", message.contains("HTTP"))
        assertEquals(EnglishStrings.get(R.string.ocr_err_download), message)
    }
}
