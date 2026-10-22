package com.skeler.scanely.core.ocr

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.ocr.paddle.PaddleModelStore
import com.skeler.scanely.core.ocr.paddle.PaddleOcrEngine
import com.skeler.scanely.core.ocr.paddle.PaddleOcrService
import com.skeler.scanely.settings.data.SettingsKeys
import com.skeler.scanely.settings.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.system.measureTimeMillis

/** Collects offline PaddleOCR output and timing data for the Arabic benchmark assets. */
@RunWith(AndroidJUnit4::class)
class PaddleOcrBenchTest {

    private val benchmarkAssetDir = "ocr_bench_current"
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val appCtx = instr.targetContext
    private val testCtx = instr.context

    private class FixedSettings(private val values: Map<SettingsKeys, Any>) : SettingsRepository {
        override fun getBoolean(key: SettingsKeys): Flow<Boolean> =
            flowOf(values[key] as? Boolean ?: (key.default as? Boolean ?: false))
        override fun getInt(key: SettingsKeys): Flow<Int> =
            flowOf(values[key] as? Int ?: (key.default as? Int ?: 0))
        override fun getFloat(key: SettingsKeys): Flow<Float> =
            flowOf(values[key] as? Float ?: (key.default as? Float ?: 0f))
        override fun getString(key: SettingsKeys): Flow<String> =
            flowOf(values[key] as? String ?: (key.default as? String ?: ""))
        override fun getStringSet(key: SettingsKeys): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun setBoolean(key: SettingsKeys, value: Boolean) = Unit
        override suspend fun toggleSetting(key: SettingsKeys) = Unit
        override suspend fun setInt(key: SettingsKeys, value: Int) = Unit
        override suspend fun setFloat(key: SettingsKeys, value: Float) = Unit
        override suspend fun setString(key: SettingsKeys, value: String) = Unit
        override suspend fun setStringSet(key: SettingsKeys, value: Set<String>) = Unit
    }

    private fun service(): PaddleOcrService {
        val engine = PaddleOcrEngine(appCtx, PaddleModelStore(appCtx, OkHttpClient()))
        val settings = FixedSettings(
            mapOf(
                SettingsKeys.PADDLE_SCRIPT to "arabic",
                SettingsKeys.PADDLE_DOC_ORIENTATION to false,
                SettingsKeys.PADDLE_DOC_UNWARP to false,
                SettingsKeys.PADDLE_LINE_ORIENTATION to false,
                SettingsKeys.PADDLE_STRUCTURE to false
            )
        )
        return PaddleOcrService(engine, settings, PdfRendererHelper(appCtx))
    }

    private fun writeDownload(
        context: Context,
        relativeDir: String,
        name: String,
        mime: String,
        bytes: ByteArray
    ) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: throw IOException("Couldn't create $name in $relativeDir")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("Couldn't open $name for writing")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun clearDownloadDir(context: Context, relativeDir: String) {
        val resolver = context.contentResolver
        val collection =
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val normalized = relativeDir.trimEnd('/')
        val relativePath = MediaStore.Downloads.RELATIVE_PATH
        val selection = "$relativePath = ? OR $relativePath = ? OR $relativePath LIKE ?"
        val selectionArgs = arrayOf(normalized, "$normalized/", "$normalized/%")
        val uris = mutableListOf<Uri>()

        resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            while (cursor.moveToNext()) {
                uris.add(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
            }
        }
        uris.forEach { resolver.delete(it, null, null) }
    }

    @Test
    fun runBenchmark() {
        val svc = service()
        val names = testCtx.assets.list(benchmarkAssetDir).orEmpty().sorted()
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/ocr_bench_current"
        val outDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            clearDownloadDir(appCtx, relativeDir)
            null
        } else {
            File(appCtx.getExternalFilesDir(null), "ocr_bench").apply { mkdirs() }
        }
        val manifest = StringBuilder("base\tstatus\tconfidence\tmillis\tchars\n")
        var count = 0

        for (name in names) {
            val bmp = testCtx.assets.open("$benchmarkAssetDir/$name").use {
                checkNotNull(BitmapFactory.decodeStream(it))
            }
            lateinit var result: OcrResult
            val millis = try {
                measureTimeMillis {
                    result = runBlocking { svc.recognize(bmp) }
                }
            } finally {
                bmp.recycle()
            }
            val completed = result
            val text = when (completed) {
                is OcrResult.Success -> completed.text
                is OcrResult.Error -> "ERROR: ${completed.message}"
                OcrResult.Empty -> ""
            }
            val status = when (completed) {
                is OcrResult.Success -> "OK"
                is OcrResult.Error -> "ERROR"
                OcrResult.Empty -> "EMPTY"
            }
            val confidence = (completed as? OcrResult.Success)?.confidence ?: 0f
            val base = name.substringBeforeLast('.', name)

            val bytes = text.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeDownload(appCtx, relativeDir, "$base.txt", "text/plain", bytes)
            } else {
                File(checkNotNull(outDir), "$base.txt").writeBytes(bytes)
            }
            manifest.append(base)
                .append('\t').append(status)
                .append('\t').append(confidence)
                .append('\t').append(millis)
                .append('\t').append(text.length)
                .append('\n')
            count++
        }

        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeDownload(
                appCtx,
                relativeDir,
                "_manifest.tsv",
                "text/tab-separated-values",
                manifestBytes
            )
        } else {
            File(checkNotNull(outDir), "_manifest.tsv").writeBytes(manifestBytes)
        }
        println("REPORT_PATH=$relativeDir")
        assertTrue(count > 0)
    }
}
