package com.skeler.scanely.core.ocr.paddle

import android.content.Context
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * The pool is built with [PaddleOcrEngine], a singleton built wherever the OCR graph is injected,
 * which can be on the main thread (before P6, Home's first composition: HomeScreen ->
 * UnifiedScanViewModel -> UnifiedScanService -> TextOcrService -> PaddleOcrService ->
 * PaddleOcrEngine). Touching any ONNX Runtime class there loads the 30+ MB native library on main. On the JVM there is no such library, so loading
 * it fails: building the pool here proves construction does not load it.
 */
class OrtSessionPoolStartupTest {

    @Test
    fun `building the pool loads no native code`() {
        OrtSessionPool(mock(Context::class.java), mock(PaddleModelStore::class.java))
    }

    @Test
    fun `building the engine loads no native code`() {
        PaddleOcrEngine(mock(Context::class.java), mock(PaddleModelStore::class.java))
    }
}
