package com.skeler.scanely.history

import android.content.Context
import android.util.Log
import com.skeler.scanely.history.data.HistoryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Saves, edits and deletes come from several view models on IO threads while History deletes from
 * its own. Each is a read-modify-write of one JSON file through one shared .tmp, so two at once
 * lost each other's change or interleaved into a corrupt file — which the next read sets aside as
 * scan_history.corrupt.json, showing the user an empty history.
 */
class HistoryManagerConcurrencyTest {

    @get:Rule val folder = TemporaryFolder()

    private fun manager(filesDir: File): HistoryManager {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        return HistoryManager(context)
    }

    @Test
    fun `concurrent edits are neither lost nor corrupt the file`() {
        mockStatic(Log::class.java).use {
            val filesDir = folder.newFolder("files")
            val history = manager(filesDir)
            val ids = (0 until ITEMS).map { history.saveItem("original $it", "").id }

            val pool = Executors.newFixedThreadPool(THREADS)
            val start = CountDownLatch(1)
            try {
                ids.forEachIndexed { index, id ->
                    pool.execute {
                        start.await()
                        history.updateItemText(id, "edited $index")
                    }
                }
                start.countDown()
            } finally {
                pool.shutdown()
                pool.awaitTermination(1, TimeUnit.MINUTES)
            }

            assertFalse("history was set aside as corrupt", File(filesDir, "scan_history.corrupt.json").exists())
            val texts = history.getHistory().associate { it.id to it.text }
            assertEquals(ITEMS, texts.size)
            ids.forEachIndexed { index, id -> assertEquals("edit to item $index lost", "edited $index", texts[id]) }
        }
    }

    private companion object {
        const val ITEMS = 40
        const val THREADS = 8
    }
}
