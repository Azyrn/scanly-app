package com.skeler.scanely.core.scan

import android.content.Context
import com.skeler.scanely.core.ocr.TextOcrService
import com.skeler.scanely.settings.domain.repository.SettingsRepository
import dagger.Lazy
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * HomeScreen's first composition builds UnifiedScanViewModel, and with it this singleton, on the
 * main thread. ML Kit clients cannot be created on the JVM, so building the service here proves
 * construction creates none: they are made on first use, on the scan's own dispatcher. Nor does it
 * build the offline-OCR graph behind TextOcrService (which reaches an OkHttpClient).
 */
class UnifiedScanStartupTest {

    @Test
    fun `building the service creates no ML Kit client`() {
        val service = UnifiedScanService(
            mock(Context::class.java),
            mock(SettingsRepository::class.java),
            Lazy<TextOcrService> { throw AssertionError("OCR graph built with the service") }
        )
        // Nothing was created, so there is nothing to close either.
        service.close()
    }
}
