package com.skeler.scanely.ui.screens

import android.net.Uri
import androidx.compose.runtime.saveable.SaverScope
import com.skeler.scanely.core.ai.AiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

/** The page-range dialog's pending pick has to survive the activity being recreated behind it. */
class PendingPdfScanSaverTest {

    private val scope = SaverScope { true }

    @Test
    fun `a pending pick round-trips through saved state`() {
        val pending = PendingPdfScan(mock(Uri::class.java), AiMode.EXTRACT_DATA, pageCount = 12)

        val saved = with(PendingPdfScanSaver) { scope.save(pending) }!!
        val restored = PendingPdfScanSaver.restore(saved)

        assertEquals(pending, restored)
    }

    @Test
    fun `nothing pending saves nothing`() {
        assertNull(with(PendingPdfScanSaver) { scope.save(null) })
    }
}
