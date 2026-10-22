package com.skeler.scanely.core.barcode

import com.google.mlkit.vision.barcode.common.Barcode
import com.skeler.scanely.core.actions.ScanAction
import com.skeler.scanely.core.scan.galleryActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import zxingcpp.BarcodeReader

/**
 * The same code must offer the same actions whether it was read by the live camera or from a
 * picked photo, and whichever engine read it. The two ML Kit paths used to map codes separately:
 * a product barcode from the gallery offered only "Copy" — no product lookup — and a Wi-Fi code no
 * "Copy SSID".
 *
 * ML Kit's Barcode is the input here, filled the way ML Kit fills it for each payload; the mapping
 * under test is real.
 */
class SharedBarcodeActionsTest {

    private fun mlKit(raw: String, format: Int, valueType: Int): Barcode = mock(Barcode::class.java).also {
        `when`(it.rawValue).thenReturn(raw)
        `when`(it.displayValue).thenReturn(raw)
        `when`(it.format).thenReturn(format)
        `when`(it.valueType).thenReturn(valueType)
    }

    private fun product(): Barcode = mlKit("4006381333931", Barcode.FORMAT_EAN_13, Barcode.TYPE_PRODUCT)

    private fun wifi(): Barcode = mlKit("WIFI:S:Home;T:WPA;P:secret;;", Barcode.FORMAT_QR_CODE, Barcode.TYPE_WIFI).also {
        val details = mock(Barcode.WiFi::class.java)
        `when`(details.ssid).thenReturn("Home")
        `when`(details.password).thenReturn("secret")
        `when`(details.encryptionType).thenReturn(Barcode.WiFi.TYPE_WPA)
        `when`(it.wifi).thenReturn(details)
    }

    private fun url(): Barcode = mlKit("https://example.com/menu", Barcode.FORMAT_QR_CODE, Barcode.TYPE_URL).also {
        val bookmark = mock(Barcode.UrlBookmark::class.java)
        `when`(bookmark.url).thenReturn("https://example.com/menu")
        `when`(it.url).thenReturn(bookmark)
    }

    @Test
    fun `a product barcode offers a product lookup from the gallery as from the camera`() {
        val gallery = galleryActions(listOf(product()))

        assertEquals(cameraActions(listOf(product())), gallery)
        assertTrue(gallery.any { it is ScanAction.LookupProduct })
    }

    @Test
    fun `a Wi-Fi code offers Copy SSID from the gallery as from the camera`() {
        val gallery = galleryActions(listOf(wifi()))

        assertEquals(cameraActions(listOf(wifi())), gallery)
        assertTrue(gallery.contains(ScanAction.CopyText("Home", "Copy SSID")))
    }

    @Test
    fun `a URL offers the same actions from the gallery as from the camera`() {
        assertEquals(cameraActions(listOf(url())), galleryActions(listOf(url())))
    }

    @Test
    fun `ML Kit and ZXing offer the same actions for the same product barcode`() {
        val zxing = BarcodeContentParser.parse(DecodedBarcode("4006381333931", BarcodeReader.Format.EAN_13))

        assertEquals(zxing, galleryActions(listOf(product())))
        assertEquals(zxing, cameraActions(listOf(product())))
    }
}
