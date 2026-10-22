package com.skeler.scanely.core.pdfeditor.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.compose.ui.geometry.Rect
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSigProperties
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSignDesigner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PdfSigner"

/**
 * Where the drawn mark goes. [box] is in PDF points with the origin at the page's top-left, the
 * same convention every `PdfEdit.Marking` uses, so a placement made on the page canvas needs no
 * translation to get here.
 */
data class SignatureAppearance(
    val pageIndex: Int,
    val box: Rect,
    val bitmap: Bitmap
)

data class SignRequest(
    val identity: SigningIdentity,
    val reason: String = "",
    val location: String = "",
    val contactInfo: String = "",
    val appearance: SignatureAppearance? = null
)

sealed interface SignResult {
    data class Saved(val file: File) : SignResult

    /**
     * PDFBox cannot append to an encrypted document, and re-encrypting afterwards would rewrite
     * the file and destroy the signature that was just made. The two are mutually exclusive by
     * construction rather than by omission.
     */
    data object EncryptedNotSupported : SignResult

    /** The CMS blob outgrew the space reserved for it, so the file was not written. */
    data object SignatureTooLarge : SignResult

    data class Failed(val message: String) : SignResult
}

/**
 * Signs a PDF by appending, leaving every existing byte untouched.
 *
 * The one place in the editor that avoids qpdf. Every other operation rewrites the document, which
 * shifts object offsets and voids any signature already in the file — fine for editing, fatal for
 * signing. PDFBox's [PDDocument.saveIncrementalForExternalSigning] appends a revision instead, so
 * an existing signature keeps covering its own bytes.
 *
 * Nothing may touch the file with qpdf between [PDDocument.load] and the incremental save
 * completing: one rewrite moves the offsets /ByteRange was computed against, and the signature then
 * verifies as tampered.
 *
 * What this is not: the certificate is a file the user imported, not a hardware token from a
 * licensed CA. Real cryptographic signature, not an accredited DSC or an eIDAS qualified signature,
 * and no UI copy should suggest otherwise.
 */
@Singleton
class PdfSigner @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    @Volatile private var resourcesReady = false

    suspend fun sign(
        source: File,
        output: File,
        request: SignRequest
    ): SignResult = withContext(Dispatchers.IO) {
        if (source.absolutePath == output.absolutePath) {
            return@withContext SignResult.Failed("Can't sign a document onto itself.")
        }
        ensureResources()

        var options: SignatureOptions? = null
        try {
            PDDocument.load(source).use { document ->
                if (document.isEncrypted) return@withContext SignResult.EncryptedNotSupported

                val signature = PDSignature().apply {
                    setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
                    name = commonNameOf(request.identity)
                    request.reason.takeIf { it.isNotBlank() }?.let { reason = it }
                    request.location.takeIf { it.isNotBlank() }?.let { location = it }
                    request.contactInfo.takeIf { it.isNotBlank() }?.let { contactInfo = it }
                    signDate = Calendar.getInstance()
                }

                options = SignatureOptions().apply {
                    preferredSignatureSize = CmsSigner.reservedBytesFor(request.identity)
                }
                request.appearance?.let { appearance ->
                    val applied = applyAppearance(document, appearance, request, options)
                    if (!applied) {
                        return@withContext SignResult.Failed(
                            "That page couldn't take a visible signature."
                        )
                    }
                }

                document.addSignature(signature, options)

                output.outputStream().buffered().use { out ->
                    val external = document.saveIncrementalForExternalSigning(out)
                    val cms = CmsSigner.sign(request.identity) { external.content }
                    external.setSignature(cms)
                }
            }
            SignResult.Saved(output)
        } catch (e: Exception) {
            output.delete()
            Log.w(TAG, "Signing failed", e)
            if (e.looksLikeNotEnoughSpace()) {
                SignResult.SignatureTooLarge
            } else {
                SignResult.Failed(e.message ?: "The document couldn't be signed.")
            }
        } finally {
            runCatching { options?.close() }
        }
    }

    /**
     * Builds the widget the viewer draws, and returns false when the page it names does not exist.
     *
     * The box arrives in the space the editor displayed, which already has the page's /Rotate
     * applied; a widget rectangle is in the page's own unrotated space. [AppearancePlacement] is
     * what reconciles the two.
     */
    private fun applyAppearance(
        document: PDDocument,
        appearance: SignatureAppearance,
        request: SignRequest,
        options: SignatureOptions
    ): Boolean {
        if (appearance.pageIndex !in 0 until document.numberOfPages) return false
        val page = document.getPage(appearance.pageIndex)
        val mediaBox = page.mediaBox
        val rotation = page.rotation
        val quarterTurned = ((rotation % 360) + 360) % 360 in listOf(90, 270)

        val displayedWidth = if (quarterTurned) mediaBox.height else mediaBox.width
        val displayedHeight = if (quarterTurned) mediaBox.width else mediaBox.height

        val placed = AppearancePlacement.map(
            boxLeftPt = appearance.box.left,
            boxTopPt = appearance.box.top,
            boxWidthPt = appearance.box.width,
            boxHeightPt = appearance.box.height,
            displayedWidthPt = displayedWidth,
            displayedHeightPt = displayedHeight,
            pageRotationDegrees = rotation
        )

        val designer = PDVisibleSignDesigner(
            document,
            appearance.bitmap.turned(placed.bitmapRotationDegrees),
            appearance.pageIndex + 1
        )
            .xAxis(placed.xPt)
            .yAxis(placed.yFromTopPt)
            .width(placed.widthPt)
            .height(placed.heightPt)

        val properties = PDVisibleSigProperties()
            .signerName(commonNameOf(request.identity))
            .signerLocation(request.location)
            .signatureReason(request.reason)
            .page(appearance.pageIndex + 1)
            .visualSignEnabled(true)
            .setPdVisibleSignature(designer)
        properties.buildSignature()

        options.setPage(appearance.pageIndex)
        options.setVisualSignature(properties.visibleSignature)
        return true
    }

    private fun Bitmap.turned(degrees: Int): Bitmap {
        if (degrees % 360 == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    /** The signer's CN if the certificate has one, otherwise the whole subject line. */
    private fun commonNameOf(identity: SigningIdentity): String {
        val subject = identity.subject
        val common = subject.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substringAfter('=')
        return common?.takeIf { it.isNotBlank() } ?: subject
    }

    /**
     * PDFBox reports an overrun placeholder as a plain IOException. Reserving from the chain makes
     * this close to unreachable, but a truncated signature looks fine until someone else opens it,
     * so it is reported rather than written.
     */
    private fun Throwable.looksLikeNotEnoughSpace(): Boolean =
        generateSequence(this) { it.cause }.any {
            it.message?.contains("not enough space", ignoreCase = true) == true
        }

    private fun ensureResources() {
        if (resourcesReady) return
        synchronized(this) {
            if (resourcesReady) return
            // Loads the font and glyph-list assets PDFBox resolves lazily. Cheap, but it needs a
            // Context, so it cannot happen inside the signing call graph.
            PDFBoxResourceLoader.init(context)
            resourcesReady = true
        }
    }
}
