package com.skeler.scanely.core.pdfeditor

import com.skeler.scanely.core.billing.ProEntitlementGate
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every editor capability, and whether it sits behind Pro.
 *
 * Every PDF tool is free, Office conversions included: they run on the device and cost nothing.
 * Only the optional online reading of difficult pages costs credits (core/ai/BackendPageReader).
 */
enum class PdfFeature(val requiresPro: Boolean, val title: String) {
    ANNOTATE(false, "Annotate"),
    HIGHLIGHT(false, "Highlight"),
    UNDERLINE(false, "Underline"),
    DELETE_PAGES(false, "Delete pages"),
    CROP_PAGES(false, "Crop pages"),
    ROTATE_PAGES(false, "Rotate pages"),
    MERGE(false, "Merge"),
    REMOVE_METADATA(false, "Remove metadata"),
    COMPRESS(false, "Compress"),
    EDIT_TEXT(false, "Edit text"),
    ENCRYPT(false, "Encrypt"),
    DECRYPT(false, "Decrypt"),
    SIGNATURE(false, "Signature"),
    VALIDATE_SIGNATURES(false, "Validate signatures"),
    REPAIR(false, "Repair"),
    WATERMARK(false, "Watermark"),

    /** PDF to and from Word, PowerPoint and Excel: on-device, so free like every other tool (marked Beta). */
    OFFICE_CONVERSION(false, "Office conversion")
}

/**
 * Resolves Pro access for the editor.
 *
 * Unlike OCR, editing happens entirely on-device, so there is no server checkpoint to enforce
 * this. The gate is a product decision, not a security boundary, and should not be described
 * as one.
 */
@Singleton
class PdfEntitlementGate @Inject constructor(
    private val proEntitlementGate: ProEntitlementGate
) {
    val isPro: StateFlow<Boolean> get() = proEntitlementGate.isPro
}
