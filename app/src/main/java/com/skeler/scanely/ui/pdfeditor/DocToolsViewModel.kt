package com.skeler.scanely.ui.pdfeditor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.core.common.UriNames
import com.skeler.scanely.core.pdf.ScanExporter
import com.skeler.scanely.core.pdfeditor.CompressionResult
import com.skeler.scanely.core.pdfeditor.MergeCandidate
import com.skeler.scanely.core.pdfeditor.PdfEditorSession
import com.skeler.scanely.core.pdfeditor.StageMergeResult
import com.skeler.scanely.core.pdfeditor.engine.CompressionLevel
import com.skeler.scanely.core.pdfeditor.engine.CompressionSettings
import com.skeler.scanely.core.pdfeditor.engine.PageGeometry
import com.skeler.scanely.core.pdfeditor.engine.PdfMetadata
import com.skeler.scanely.core.pdfeditor.engine.PdfPermissions
import com.skeler.scanely.core.pdfeditor.exportSigned
import com.skeler.scanely.core.pdfeditor.model.PdfEdit
import com.skeler.scanely.core.pdfeditor.signature.Pkcs12Loader
import com.skeler.scanely.core.pdfeditor.signature.Pkcs12Result
import com.skeler.scanely.core.pdfeditor.signature.SignRequest
import com.skeler.scanely.core.pdfeditor.signature.SignResult
import com.skeler.scanely.core.pdfeditor.signature.SignatureAppearance
import com.skeler.scanely.core.pdfeditor.signature.SignatureInfo
import com.skeler.scanely.core.pdfeditor.signature.SignatureStore
import com.skeler.scanely.core.pdfeditor.signature.SignatureValidator
import com.skeler.scanely.core.pdfeditor.signature.SigningIdentity
import com.skeler.scanely.core.pdfeditor.signature.StoredSignature
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Visibly lossy is rarely what someone wants by default; this is the "still looks fine" mark. */
private const val DEFAULT_CUSTOM_QUALITY = 60

data class CompressionEstimate(val level: CompressionLevel, val result: CompressionResult?)

/** The custom pane's live state: what the sliders say, and what that setting actually achieved. */
data class CustomCompression(
    val jpegQuality: Int = DEFAULT_CUSTOM_QUALITY,
    val minImageEdge: Int = CompressionSettings.DEFAULT_MIN_EDGE,
    val result: CompressionResult? = null,
    val isMeasuring: Boolean = false
) {
    val settings: CompressionSettings get() = CompressionSettings(jpegQuality, minImageEdge)
}

/** A target-size search in flight, or its outcome. */
data class TargetSearch(
    val targetBytes: Long,
    val probes: Int = 0,
    val isRunning: Boolean = false,
    val best: CompressionResult? = null
) {
    val reachedTarget: Boolean get() = (best?.effectiveBytes ?: Long.MAX_VALUE) <= targetBytes
}

/** Where a signature's visible mark sits on its page, as a fraction of the page box. */
enum class SignaturePosition(val label: String) {
    BOTTOM_RIGHT("Bottom right"),
    BOTTOM_LEFT("Bottom left"),
    TOP_RIGHT("Top right"),
    TOP_LEFT("Top left")
}

/**
 * The signing pane's state.
 *
 * [identity] holds a decrypted private key, so it lives only between unlocking the certificate and
 * the signing call that uses it. The PKCS#12 password is never held here at all — it is read
 * straight into [unlockCertificate] and dropped.
 */
data class SigningState(
    val fileName: String? = null,
    val certificateBytes: ByteArray? = null,
    val identity: SigningIdentity? = null,
    val reason: String = "",
    val location: String = "",
    val appearanceId: String? = null,
    val pageIndex: Int = 0,
    val position: SignaturePosition = SignaturePosition.BOTTOM_RIGHT,
    val isSigning: Boolean = false,
    val error: String? = null
) {
    val awaitingPassword: Boolean get() = certificateBytes != null && identity == null

    /** Data class equality on a ByteArray compares references; spelled out to keep it honest. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SigningState) return false
        return fileName == other.fileName &&
            certificateBytes.contentEqualsOrBothNull(other.certificateBytes) &&
            identity == other.identity &&
            reason == other.reason &&
            location == other.location &&
            appearanceId == other.appearanceId &&
            pageIndex == other.pageIndex &&
            position == other.position &&
            isSigning == other.isSigning &&
            error == other.error
    }

    override fun hashCode(): Int {
        var result = fileName?.hashCode() ?: 0
        result = 31 * result + (certificateBytes?.contentHashCode() ?: 0)
        result = 31 * result + (identity?.hashCode() ?: 0)
        result = 31 * result + reason.hashCode()
        result = 31 * result + location.hashCode()
        result = 31 * result + (appearanceId?.hashCode() ?: 0)
        result = 31 * result + pageIndex
        result = 31 * result + position.hashCode()
        result = 31 * result + isSigning.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)

data class MergePasswordPrompt(val uri: Uri, val fileName: String, val isRetry: Boolean)

data class DocToolsUiState(
    val sizeBytes: Long = 0L,
    val pageCount: Int = 0,
    val isEncrypted: Boolean = false,
    val estimates: List<CompressionEstimate> = emptyList(),
    val isEstimating: Boolean = false,
    val custom: CustomCompression = CustomCompression(),
    val targetSearch: TargetSearch? = null,
    val merges: List<MergeCandidate> = emptyList(),
    /** Set while a picked merge file waits for its password. */
    val mergePassword: MergePasswordPrompt? = null,
    val metadata: PdfMetadata? = null,
    val signatures: List<SignatureInfo> = emptyList(),
    val hasValidated: Boolean = false,
    val isValidating: Boolean = false,
    val storedSignatures: List<StoredSignature> = emptyList(),
    val signing: SigningState = SigningState(),
    val savedUri: Uri? = null,
    val message: String? = null
) {
    val mergedPageCount: Int get() = pageCount + merges.sumOf { it.pageCount }

    /** Signing appends to the file; encrypting rewrites it. A document cannot have both. */
    val canSign: Boolean get() = !isEncrypted
}

/** A signature this wide reads clearly without dominating the page. */
private const val APPEARANCE_WIDTH_FRACTION = 0.28f
private const val APPEARANCE_MARGIN_FRACTION = 0.07f
private const val A4_WIDTH_PT = 595f
private const val A4_HEIGHT_PT = 842f

@HiltViewModel
class DocToolsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val session: PdfEditorSession,
    private val validator: SignatureValidator,
    private val signatureStore: SignatureStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocToolsUiState())
    val uiState: StateFlow<DocToolsUiState> = _uiState.asStateFlow()

    private val _signatureBitmaps = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val signatureBitmaps: StateFlow<Map<String, Bitmap>> = _signatureBitmaps.asStateFlow()

    init {
        viewModelScope.launch {
            session.document.collect { document ->
                _uiState.update {
                    it.copy(
                        sizeBytes = document?.sizeBytes ?: 0L,
                        pageCount = document?.pageCount ?: 0,
                        isEncrypted = document?.isEncrypted ?: false
                    )
                }
            }
        }
        // Every measured figure here describes the file as it was. Saving replaces that file, so
        // holding on to them would show the previous document's compression sizes, its metadata,
        // and — worst of the three — a signature verdict for a document that no longer exists.
        viewModelScope.launch {
            session.revision.drop(1).collect {
                _uiState.update {
                    it.copy(
                        estimates = emptyList(),
                        isEstimating = false,
                        custom = CustomCompression(),
                        targetSearch = null,
                        metadata = null,
                        signatures = emptyList(),
                        hasValidated = false,
                        isValidating = false
                    )
                }
            }
        }
        viewModelScope.launch {
            signatureStore.signatures.collect { stored ->
                _uiState.update { it.copy(storedSignatures = stored) }
                stored.forEach { entry ->
                    if (entry.id !in _signatureBitmaps.value) {
                        signatureStore.bitmapFor(entry.id)?.let { bitmap ->
                            _signatureBitmaps.update { current -> current + (entry.id to bitmap) }
                        }
                    }
                }
            }
        }
        viewModelScope.launch { signatureStore.load() }
    }

    /**
     * Runs every level up front so the three options can be compared at a glance. It costs one
     * pass per level, but a compression figure the user has to commit to before seeing is worse
     * than a second of waiting.
     */
    fun estimateCompression() {
        if (_uiState.value.isEstimating || _uiState.value.estimates.isNotEmpty()) return
        _uiState.update { it.copy(isEstimating = true) }
        viewModelScope.launch {
            val estimates = CompressionLevel.entries.map { level ->
                CompressionEstimate(level, session.compressedSize(level.settings))
            }
            _uiState.update { it.copy(estimates = estimates, isEstimating = false) }
        }
    }

    fun setCustomQuality(quality: Int) = _uiState.update {
        it.copy(custom = it.custom.copy(jpegQuality = quality, result = null))
    }

    fun setCustomMinEdge(edge: Int) = _uiState.update {
        it.copy(custom = it.custom.copy(minImageEdge = edge, result = null))
    }

    /**
     * Measured on demand rather than on every slider tick: a pass is a full rewrite of the
     * document, and running one per pixel of drag would make the slider unusable.
     */
    fun measureCustom() {
        val custom = _uiState.value.custom
        if (custom.isMeasuring || custom.result != null) return
        _uiState.update { it.copy(custom = it.custom.copy(isMeasuring = true)) }
        viewModelScope.launch {
            val result = session.compressedSize(custom.settings)
            _uiState.update { state ->
                // The sliders may have moved on while the pass ran; a stale figure next to a
                // different setting would be worse than none.
                if (state.custom.settings != custom.settings) {
                    state.copy(custom = state.custom.copy(isMeasuring = false))
                } else {
                    state.copy(custom = state.custom.copy(result = result, isMeasuring = false))
                }
            }
        }
    }

    fun searchForTarget(targetBytes: Long) {
        if (_uiState.value.targetSearch?.isRunning == true) return
        _uiState.update {
            it.copy(targetSearch = TargetSearch(targetBytes, isRunning = true))
        }
        viewModelScope.launch {
            val best = session.searchForTargetSize(targetBytes) {
                _uiState.update { state ->
                    state.copy(
                        targetSearch = state.targetSearch?.copy(
                            probes = state.targetSearch.probes + 1
                        )
                    )
                }
            }
            _uiState.update { state ->
                state.copy(
                    targetSearch = state.targetSearch?.copy(isRunning = false, best = best)
                )
            }
        }
    }

    fun clearTargetSearch() = _uiState.update { it.copy(targetSearch = null) }

    fun stageCompression(settings: CompressionSettings) {
        session.edits.push(PdfEdit.Compress(settings))
        _uiState.update { it.copy(message = "Compression staged. Save to apply it.") }
    }

    fun addMerge(uri: Uri, password: String = "") {
        viewModelScope.launch {
            val result = session.stageMerge(uri, password)
            _uiState.update {
                when (result) {
                    is StageMergeResult.Staged ->
                        it.copy(merges = it.merges + result.candidate, mergePassword = null)
                    is StageMergeResult.NeedsPassword -> it.copy(
                        mergePassword = MergePasswordPrompt(
                            uri = uri,
                            fileName = UriNames.displayName(appContext, uri) ?: uri.lastPathSegment.orEmpty(),
                            isRetry = password.isNotEmpty()
                        )
                    )
                    is StageMergeResult.Unreadable ->
                        it.copy(mergePassword = null, message = "Couldn't read that PDF.")
                }
            }
        }
    }

    /** The password goes to the session for this one file and is not kept in state. */
    fun submitMergePassword(password: String) {
        val prompt = _uiState.value.mergePassword ?: return
        addMerge(prompt.uri, password)
    }

    fun dismissMergePassword() = _uiState.update { it.copy(mergePassword = null) }

    fun removeMerge(candidate: MergeCandidate) =
        _uiState.update { it.copy(merges = it.merges - candidate) }

    fun stageMerge() {
        val merges = _uiState.value.merges
        if (merges.isEmpty()) return
        merges.forEach { session.edits.push(PdfEdit.AppendDocument(it.path)) }
        _uiState.update {
            it.copy(
                merges = emptyList(),
                message = "${merges.size} document(s) staged. Save to apply."
            )
        }
    }

    fun stageEncryption(userPassword: String, ownerPassword: String, permissions: PdfPermissions) {
        session.edits.push(
            PdfEdit.Encrypt(
                userPassword = userPassword,
                ownerPassword = ownerPassword.ifBlank { userPassword },
                permissions = permissions
            )
        )
        _uiState.update { it.copy(message = "Encryption staged. Save to apply it.") }
    }

    fun stageDecryption() {
        session.edits.push(PdfEdit.Decrypt)
        _uiState.update { it.copy(message = "Password removal staged. Save to apply it.") }
    }

    fun loadMetadata() {
        if (_uiState.value.metadata != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(metadata = session.metadata()) }
        }
    }

    fun stageMetadataStrip(stripXmp: Boolean) {
        session.edits.push(PdfEdit.StripMetadata(stripXmp))
        _uiState.update { it.copy(message = "Metadata removal staged. Save to apply it.") }
    }

    fun validateSignatures() {
        val document = session.document.value ?: return
        if (_uiState.value.isValidating) return
        _uiState.update { it.copy(isValidating = true) }
        viewModelScope.launch {
            val results = validator.validate(document.file, document.password)
            _uiState.update {
                it.copy(signatures = results, isValidating = false, hasValidated = true)
            }
        }
    }

    /** Reads the certificate into memory only. It is never copied into the cache. */
    fun pickCertificate(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            _uiState.update {
                if (bytes == null) {
                    it.copy(signing = it.signing.copy(error = "Couldn't read that certificate."))
                } else {
                    it.copy(
                        signing = SigningState(
                            fileName = UriNames.displayName(appContext, uri) ?: "Certificate",
                            certificateBytes = bytes,
                            appearanceId = it.signing.appearanceId,
                            reason = it.signing.reason,
                            location = it.signing.location
                        )
                    )
                }
            }
        }
    }

    /**
     * The password reaches the loader and goes no further: it is not held in state, not logged,
     * and the char array is wiped once the key is out.
     */
    fun unlockCertificate(password: String) {
        val bytes = _uiState.value.signing.certificateBytes ?: return
        viewModelScope.launch {
            val characters = password.toCharArray()
            val result = withContext(Dispatchers.Default) {
                Pkcs12Loader.load(bytes, characters)
            }
            characters.fill('\u0000')

            _uiState.update { state ->
                val signing = state.signing
                state.copy(
                    signing = when (result) {
                        is Pkcs12Result.Loaded ->
                            signing.copy(identity = result.identity, error = null)
                        is Pkcs12Result.WrongPassword ->
                            signing.copy(error = "That password didn't unlock the certificate.")
                        is Pkcs12Result.NoPrivateKey -> signing.copy(
                            error = "That file has no private key, so it can't sign."
                        )
                        is Pkcs12Result.Failed -> signing.copy(error = result.message)
                    }
                )
            }
        }
    }

    fun setSigningReason(reason: String) =
        _uiState.update { it.copy(signing = it.signing.copy(reason = reason)) }

    fun setSigningLocation(location: String) =
        _uiState.update { it.copy(signing = it.signing.copy(location = location)) }

    fun setSigningPage(pageIndex: Int) =
        _uiState.update { it.copy(signing = it.signing.copy(pageIndex = pageIndex)) }

    fun setSigningPosition(position: SignaturePosition) =
        _uiState.update { it.copy(signing = it.signing.copy(position = position)) }

    /** Null clears the visible mark, leaving a signature that is cryptographic only. */
    fun setSigningAppearance(id: String?) =
        _uiState.update { it.copy(signing = it.signing.copy(appearanceId = id)) }

    fun clearCertificate() = _uiState.update { it.copy(signing = SigningState()) }

    fun signDocument() {
        val state = _uiState.value
        val identity = state.signing.identity ?: return
        if (state.signing.isSigning) return

        _uiState.update { it.copy(signing = it.signing.copy(isSigning = true, error = null)) }
        viewModelScope.launch {
            val appearance = state.signing.appearanceId?.let { id ->
                signatureStore.bitmapFor(id)?.let { bitmap ->
                    SignatureAppearance(
                        pageIndex = state.signing.pageIndex,
                        box = appearanceBox(state.signing, bitmap),
                        bitmap = bitmap
                    )
                }
            }

            val result = session.signDocument(
                SignRequest(
                    identity = identity,
                    reason = state.signing.reason,
                    location = state.signing.location,
                    appearance = appearance
                )
            )

            when (result) {
                is SignResult.Saved -> {
                    val export = exportSigned(session.exportRetry, result.file) {
                        ScanExporter.exportPdfFile(appContext, it, signedName())
                    }
                    // Adopting emits the document, which is what re-reads Save's state in the studio.
                    session.adopt(result.file, password = "")
                    _uiState.update {
                        // The identity is dropped the moment it has been used; the key does not
                        // outlive the operation it was unlocked for.
                        it.copy(signing = SigningState(), savedUri = export.saved, message = export.message)
                    }
                }

                is SignResult.EncryptedNotSupported -> failSigning(
                    "A password-protected document can't be signed. Remove the password first, " +
                        "and don't stage a new one — encrypting rewrites the file and would " +
                        "break the signature."
                )

                is SignResult.SignatureTooLarge -> failSigning(
                    "This certificate's chain is too large to fit the signature. Try one with a " +
                        "shorter chain."
                )

                is SignResult.Failed -> failSigning(result.message)
            }
        }
    }

    private fun failSigning(message: String) = _uiState.update {
        it.copy(signing = it.signing.copy(isSigning = false, error = message))
    }

    /**
     * A signature sized as a fraction of the page rather than from the bitmap's pixels, so the same
     * stored mark lands the same size on A4 and on Letter. The aspect ratio is the bitmap's, so it
     * is never stretched.
     */
    private fun appearanceBox(signing: SigningState, bitmap: Bitmap): Rect {
        val geometry = session.document.value
            ?.geometries
            ?.getOrNull(signing.pageIndex)
            ?: PageGeometry(A4_WIDTH_PT, A4_HEIGHT_PT)

        val width = geometry.widthPt * APPEARANCE_WIDTH_FRACTION
        val height = width * bitmap.height / bitmap.width.coerceAtLeast(1)
        val margin = geometry.widthPt * APPEARANCE_MARGIN_FRACTION

        val left = when (signing.position) {
            SignaturePosition.BOTTOM_RIGHT, SignaturePosition.TOP_RIGHT ->
                geometry.widthPt - width - margin
            SignaturePosition.BOTTOM_LEFT, SignaturePosition.TOP_LEFT -> margin
        }
        val top = when (signing.position) {
            SignaturePosition.BOTTOM_RIGHT, SignaturePosition.BOTTOM_LEFT ->
                geometry.heightPt - height - margin
            SignaturePosition.TOP_RIGHT, SignaturePosition.TOP_LEFT -> margin
        }
        return Rect(left, top, left + width, top + height)
    }

    private fun signedName(): String {
        val name = session.document.value?.displayName ?: "Document.pdf"
        return name.substringBeforeLast('.', name) + "_signed"
    }

    fun consumeSaved() = _uiState.update { it.copy(savedUri = null) }

    fun deleteStoredSignature(id: String) {
        viewModelScope.launch {
            signatureStore.delete(id)
            _signatureBitmaps.update { it - id }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }
}
