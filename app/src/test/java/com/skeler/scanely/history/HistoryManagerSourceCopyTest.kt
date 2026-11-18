package com.skeler.scanely.history

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.skeler.scanely.history.data.HistoryManager
import com.skeler.scanely.history.data.historyToJson
import com.skeler.scanely.history.data.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Every history entry used to copy its whole source — a 100 MB PDF included — into
 * filesDir/history_images, which nothing ever reads. Entries now keep the source URI only; copies
 * left by older versions must still be removed when their entry goes.
 */
class HistoryManagerSourceCopyTest {

    @get:Rule val folder = TemporaryFolder()

    private fun mockedUris(block: () -> Unit) {
        mockStatic(Log::class.java).use {
            mockStatic(Uri::class.java).use { uris ->
                uris.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { call ->
                    val text = call.getArgument<String>(0)
                    mock(Uri::class.java).also { uri ->
                        `when`(uri.scheme).thenReturn(text.substringBefore(':'))
                        `when`(uri.path).thenReturn(text.substringAfter("://"))
                        `when`(uri.toString()).thenReturn(text)
                    }
                }
                block()
            }
        }
    }

    @Test
    fun `saving an entry does not copy its source`() = mockedUris {
        val filesDir = folder.newFolder("files")
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.openInputStream(org.mockito.ArgumentMatchers.any())).thenAnswer {
            ByteArrayInputStream(ByteArray(4096))
        }
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.contentResolver).thenReturn(resolver)

        val source = "content://media/doc.pdf"
        val item = HistoryManager(context).saveItem("text", source)

        val copies = File(filesDir, "history_images").listFiles().orEmpty()
        assertTrue("source copied into history_images: ${copies.map { it.name }}", copies.isEmpty())
        assertEquals(source, item.imageUri)
    }

    @Test
    fun `deleting an old entry removes its legacy copy`() = mockedUris {
        val filesDir = folder.newFolder("files")
        val imagesDir = File(filesDir, "history_images").apply { mkdirs() }
        val copy = File(imagesDir, "old.jpg").apply { writeBytes(ByteArray(16)) }
        val old = HistoryItem(text = "old", imageUri = "file://${copy.absolutePath}")
        File(filesDir, "scan_history.json").writeText(historyToJson(listOf(old)))
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)

        HistoryManager(context).deleteItem(old.id)

        assertFalse("legacy copy left behind", copy.exists())
    }

    @Test
    fun `clearing history removes legacy copies`() = mockedUris {
        val filesDir = folder.newFolder("files")
        val imagesDir = File(filesDir, "history_images").apply { mkdirs() }
        val copy = File(imagesDir, "old.jpg").apply { writeBytes(ByteArray(16)) }
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)

        HistoryManager(context).clearHistory()

        assertFalse("legacy copy left behind", copy.exists())
    }
}
