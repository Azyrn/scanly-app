package com.skeler.scanely.history

import com.skeler.scanely.history.data.HistoryItem
import com.skeler.scanely.history.data.HistoryManager
import com.skeler.scanely.history.presentation.HistoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Collections
import java.util.concurrent.Executors

/** History's file is read, parsed and rewritten on every open and delete; that must not run on main. */
class HistoryViewModelThreadTest {

    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, MAIN_THREAD) }
    private val main = mainExecutor.asCoroutineDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() {
        Dispatchers.resetMain()
        main.close()
    }

    @Test fun `history file work runs off the main thread`() = runBlocking {
        val threads = Collections.synchronizedList(mutableListOf<String>())
        val item = HistoryItem(text = "receipt", imageUri = "")
        val history = mock(HistoryManager::class.java)
        `when`(history.getHistory()).thenAnswer {
            threads += Thread.currentThread().name
            listOf(item)
        }

        val viewModel = HistoryViewModel(history)
        withTimeout(TIMEOUT_MS) { viewModel.historyItems.first { it.isNotEmpty() } }
        viewModel.deleteItem(item.id)
        withTimeout(TIMEOUT_MS) { while (threads.size < 2) kotlinx.coroutines.delay(10) }

        // Coroutine debug mode appends " @coroutine#n" to the thread name.
        assertTrue("history file read on the main thread: $threads", threads.none { it.startsWith(MAIN_THREAD) })
    }

    private companion object {
        const val MAIN_THREAD = "test-main"
        const val TIMEOUT_MS = 5_000L
    }
}
