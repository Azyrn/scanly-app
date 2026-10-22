package com.skeler.scanely.core.barcode

import com.google.mlkit.vision.barcode.common.Barcode
import com.skeler.scanely.core.actions.ScanAction
import zxingcpp.BarcodeReader

/**
 * The actions ML Kit's codes offer, through the same [BarcodeContentParser] the ZXing engine uses.
 *
 * The live camera and a picked photo used to map ML Kit's codes separately, and disagreed: from the
 * gallery a product barcode offered only "Copy" — no product lookup — a URL no "Copy", and a Wi-Fi
 * code no "Copy SSID". One mapping for every path and both engines means the same code always
 * offers the same actions.
 */
internal fun mlKitActions(barcodes: List<Barcode>): List<ScanAction> =
    barcodes
        .mapNotNull { it.toDecoded() }
        .distinctBy { it.text }
        .flatMap { it.toActions() }
        .distinct()

/** ML Kit's read of a code, in the engine-neutral form [BarcodeContentParser] maps to actions. */
internal fun Barcode.toDecoded(): DecodedBarcode? {
    val text = rawValue?.takeIf { it.isNotBlank() } ?: return null
    val decodedFormat = if (valueType == Barcode.TYPE_ISBN) {
        BarcodeReader.Format.ISBN
    } else {
        ML_KIT_FORMATS[format] ?: BarcodeReader.Format.NONE
    }
    return DecodedBarcode(text, decodedFormat)
}

private val ML_KIT_FORMATS = mapOf(
    Barcode.FORMAT_QR_CODE to BarcodeReader.Format.QR_CODE,
    Barcode.FORMAT_AZTEC to BarcodeReader.Format.AZTEC,
    Barcode.FORMAT_DATA_MATRIX to BarcodeReader.Format.DATA_MATRIX,
    Barcode.FORMAT_PDF417 to BarcodeReader.Format.PDF_417,
    Barcode.FORMAT_EAN_13 to BarcodeReader.Format.EAN_13,
    Barcode.FORMAT_EAN_8 to BarcodeReader.Format.EAN_8,
    Barcode.FORMAT_UPC_A to BarcodeReader.Format.UPC_A,
    Barcode.FORMAT_UPC_E to BarcodeReader.Format.UPC_E,
    Barcode.FORMAT_CODE_128 to BarcodeReader.Format.CODE_128,
    Barcode.FORMAT_CODE_39 to BarcodeReader.Format.CODE_39,
    Barcode.FORMAT_CODE_93 to BarcodeReader.Format.CODE_93,
    Barcode.FORMAT_CODABAR to BarcodeReader.Format.CODABAR,
    Barcode.FORMAT_ITF to BarcodeReader.Format.ITF
)
