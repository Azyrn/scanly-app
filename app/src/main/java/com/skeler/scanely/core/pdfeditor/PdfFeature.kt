package com.skeler.scanely.core.pdfeditor

import com.skeler.scanely.core.billing.ProEntitlementGate
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every editor capability, and whether it sits behind Pro.
 *
 * Free covers the everyday markup a scanning app is expected to do; Pro covers the tools that
 * replace a desktop utility.
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

    COMPRESS(true, "Compress"),
    EDIT_TEXT(true, "Edit text"),
    ENCRYPT(true, "Encrypt"),
    DECRYPT(true, "Decrypt"),
    SIGNATURE(true, "Signature"),
    VALIDATE_SIGNATURES(true, "Validate signatures"),
    REPAIR(true, "Repair"),
    WATERMARK(true, "Watermark"),

    /** PDF to and from Word, PowerPoint and Excel. */
    OFFICE_CONVERSION(true, "Office conversion")
}

/**
 * Resolves Pro access for the editor.
 *
 * Worth being clear-eyed about: unlike OCR, editing happens entirely on-device, so there is no
 * server checkpoint to enforce this. The gate is a product decision, not a security boundary, and
 * it should not be described as one.
 */
@Singleton
class PdfEntitlementGate @Inject constructor(
    private val proEntitlementGate: ProEntitlementGate
) {
    val isPro: StateFlow<Boolean> get() = proEntitlementGate.isPro
}
