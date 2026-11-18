package com.skeler.scanely.core.ocr.paddle

import android.content.Context
import com.skeler.scanely.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.SocketException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

internal const val HF = "https://huggingface.co/PaddlePaddle"

private const val MIN_MODEL_BYTES = 1_000_000
private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * What the user reads when a model download fails: a sentence, never the exception's own text
 * ("Unable to resolve host …", "HTTP 503", "SHA-256 mismatch"), which is logged instead.
 */
internal fun downloadFailureMessage(context: Context, e: Throwable): String = context.getString(
    when (e) {
        is UnknownHostException, is SocketException, is InterruptedIOException, is SSLException ->
            R.string.ocr_err_download_offline
        else -> R.string.ocr_err_download
    }
)

/**
 * Fetches PaddlePaddle model files from Hugging Face. The client is built on first use: the store
 * that owns this is injected on the main thread.
 */
internal class HfModelDownloader(client: () -> OkHttpClient) {

    private val httpClient by lazy(client)

    fun fetch(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return response.body.bytes()
        }
    }

    /**
     * Streams the repo's inference.onnx to [dest], hashing on the fly and verifying
     * size + SHA-256 against the repo's own LFS pointer, so truncated or corrupted
     * transfers can never install. Nothing is buffered fully in memory.
     */
    fun downloadVerified(repo: String, dest: File, onProgress: (Float) -> Unit) {
        val pointer = PaddleModelStore.parseLfsPointer(fetch("$HF/$repo/raw/main/inference.onnx").decodeToString())
            ?: error("No LFS metadata for $repo")
        check(pointer.size > MIN_MODEL_BYTES) { "Model too small" }

        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val request = Request.Builder().url("$HF/$repo/resolve/main/inference.onnx").build()
            val read = httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                tmp.outputStream().use { out ->
                    response.body.byteStream().use { stream ->
                        copyHashing(stream, out, digest) { read ->
                            onProgress((read.toFloat() / pointer.size).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            check(read == pointer.size) { "Size mismatch: got $read, expected ${pointer.size}" }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            check(sha == pointer.sha256) { "SHA-256 mismatch" }
            dest.delete()
            check(tmp.renameTo(dest)) { "Could not move verified model into place" }
        } finally {
            tmp.delete()
        }
    }
}

/** Copies [input] to [output], feeding [digest]; reports and returns the bytes copied. */
private fun copyHashing(
    input: InputStream,
    output: OutputStream,
    digest: MessageDigest,
    onProgress: (Long) -> Unit
): Long {
    val buf = ByteArray(COPY_BUFFER_BYTES)
    var read = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        output.write(buf, 0, n)
        digest.update(buf, 0, n)
        read += n
        onProgress(read)
    }
    return read
}
