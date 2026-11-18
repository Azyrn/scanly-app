package com.skeler.scanely.ui.viewmodel

import com.skeler.scanely.core.ocr.TextOcrService
import com.skeler.scanely.history.data.HistoryManager
import dagger.Lazy
import org.junit.Test
import org.mockito.Mockito.mock

/** Home builds this in its first composition; the OCR graph behind it waits for a scan. */
class OcrViewModelStartupTest {

    @Test
    fun `building the view model builds no OCR service`() {
        OcrViewModel(
            Lazy<TextOcrService> { throw AssertionError("OCR graph built with the view model") },
            mock(HistoryManager::class.java)
        )
    }
}
