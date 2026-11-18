package com.skeler.scanely.history

import android.content.Context
import com.skeler.scanely.history.data.HistoryManager
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

/**
 * HistoryManager is injected into view models Home builds in its first composition, on the main
 * thread. Its constructor must not touch the disk (getFilesDir stats and may create the directory;
 * mkdirs is a syscall): that waits for the first history read or write.
 */
class HistoryManagerStartupTest {

    @Test
    fun `building the manager does no file work`() {
        val context = mock(Context::class.java)

        HistoryManager(context)

        verifyNoInteractions(context)
    }
}
