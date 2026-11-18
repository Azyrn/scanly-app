package com.skeler.scanely.core.pdfeditor.signature

import android.content.Context
import android.util.Log
import com.skeler.scanely.core.pdfeditor.engine.Qpdf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.tsp.TimeStampToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SignatureValidator"

enum class Integrity {
    /** The bytes the signature covers are unchanged. */
    INTACT,

    /** The covered bytes no longer hash to what was signed — the content was altered. */
    TAMPERED,

    /** Structurally unreadable: unsupported SubFilter, malformed CMS, or a truncated file. */
    UNVERIFIABLE
}

enum class TrustStatus {
    /** The chain reaches a bundled root, and every certificate on it was valid when signed. */
    TRUSTED,

    /** Cryptographically fine, but the chain doesn't reach a root we bundle. */
    UNTRUSTED_ISSUER,

    /** The signing certificate had already expired when the signature was made. */
    EXPIRED,
    NOT_YET_VALID,
    UNKNOWN
}

/**
 * The one-glance answer, matching what a reader expects from Adobe's badge.
 *
 * [VALID] is a green tick, [UNKNOWN] the yellow question mark, [INVALID] the red cross.
 */
enum class SignatureVerdict { VALID, UNKNOWN, INVALID }

/** Where the time used to judge certificate validity came from, weakest source last. */
enum class TimeSource {
    /** An RFC 3161 token from a timestamping authority, verified against its own certificate. */
    TIMESTAMP,

    /** The signer's own claimed signing time, from the CMS signed attributes. */
    SIGNED_ATTRIBUTE,

    /** The /M entry in the signature dictionary — unauthenticated, anyone can write it. */
    DOCUMENT_DATE,

    /** Nothing in the file says when it was signed, so validity was judged as of now. */
    NONE
}

data class CertificateSummary(
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val notBefore: Date,
    val notAfter: Date
)

data class SignatureInfo(
    val fieldName: String,
    val signerName: String,
    val reason: String,
    val location: String,
    val signedAtMillis: Long?,
    val timeSource: TimeSource,
    val subFilter: String,
    val integrity: Integrity,
    val trust: TrustStatus,
    /**
     * False when bytes exist outside the signed range — an incremental update after signing.
     * A signature can be perfectly INTACT and still not vouch for what the reader is looking at,
     * which is the exact trick used to make a tampered document look signed.
     */
    val coversWholeDocument: Boolean,
    val certificates: List<CertificateSummary>
) {
    /** True when the signing certificate has since expired but was valid when it signed. */
    val expiredSinceSigning: Boolean
        get() = trust == TrustStatus.TRUSTED &&
            certificates.firstOrNull()?.notAfter?.before(Date()) == true

    val verdict: SignatureVerdict
        get() = when {
            integrity != Integrity.INTACT -> SignatureVerdict.INVALID
            trust == TrustStatus.TRUSTED && coversWholeDocument -> SignatureVerdict.VALID
            else -> SignatureVerdict.UNKNOWN
        }
}

/**
 * Verifies embedded PKCS#7/CMS signatures, as found in e-Aadhaar and other government PDFs.
 *
 * This half locates the pieces and checks the maths: which certificate signed, whether the bytes
 * still hash to what was signed, and when. Whether the signer reaches a believable root is
 * [CertPathTrust]'s job.
 *
 * Validity is judged at *signing time* — a verified timestamp token where there is one, the
 * signer's claimed time otherwise, never the clock. Certificates live two or three years and their
 * documents are kept for decades, so judging against the clock reports every older government PDF
 * as expired. That is the commonest reason a viewer shows a permanent yellow question mark.
 *
 * Trust anchors come from `assets/trust/`, which ships the India CCA roots; dropping another root
 * in trusts another country's PKI. BouncyCastle is passed as an explicit Provider rather than
 * registered — Android already publishes a cut-down provider under the name "BC".
 */
@Singleton
class SignatureValidator @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val provider by lazy { BouncyCastleProvider() }

    private val converter by lazy { JcaX509CertificateConverter().setProvider(provider) }

    private val trustAnchors: Set<TrustAnchor> by lazy {
        val factory = CertificateFactory.getInstance("X.509")
        runCatching {
            context.assets.list(TRUST_DIR).orEmpty()
                .filter { name -> CERT_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } }
                .mapNotNull { name ->
                    runCatching {
                        context.assets.open("$TRUST_DIR/$name").use {
                            TrustAnchor(factory.generateCertificate(it) as X509Certificate, null)
                        }
                    }.onFailure { Log.w(TAG, "Trust anchor $name could not be read", it) }
                        .getOrNull()
                }
                .toSet()
        }.getOrElse { emptySet() }
    }

    /**
     * Every signature in [file], one entry per signed field.
     *
     * The editor's save asks this whether a save is about to void signatures, so an entry the file
     * has is never dropped: whatever stops a signature being checked — an unreadable file, a
     * malformed /ByteRange or /Contents, running out of memory — it comes back
     * [Integrity.UNVERIFIABLE] rather than missing. Empty only when the file has no signed fields,
     * or when qpdf could not open it at all (and then the save cannot rewrite it either).
     */
    suspend fun validate(file: File, password: String = ""): List<SignatureInfo> =
        withContext(Dispatchers.Default) {
            val json = Qpdf.nativeExtractSignatures(file.absolutePath, password)
                ?: return@withContext emptyList()
            verifyAll(json, file)
        }

    /**
     * Checks the signatures qpdf located ([json]) against [file]'s bytes. The signed spans are
     * streamed through [openSpans]; the file is never read whole, because a PDF the size of a phone
     * scan bundle does not fit in the heap twice.
     */
    internal fun verifyAll(
        json: String,
        file: File,
        openSpans: (List<ByteSpan>) -> InputStream = { ByteRangeInputStream(file, it) }
    ): List<SignatureInfo> {
        // The native side writes "[]" for an unsigned file, so text that does not parse still
        // means it found a signature to describe.
        val entries = runCatching { JSONArray(json) }.getOrElse {
            Log.w(TAG, "Signature list could not be parsed", it)
            return listOf(unverifiable(JSONObject()))
        }
        val fileLength = file.length()

        return (0 until entries.length()).map { index ->
            val entry = entries.optJSONObject(index) ?: JSONObject()
            runCatching { verify(entry, fileLength, openSpans) }
                .onFailure { Log.w(TAG, "Signature $index could not be read", it) }
                .getOrElse { unverifiable(entry) }
        }
    }

    /** What one CMS blob yielded, before the signature dictionary's own fields are folded in. */
    private data class CmsOutcome(
        val integrity: Integrity,
        val trust: TrustStatus,
        val certificates: List<CertificateSummary>,
        val signedAt: Date?,
        val timeSource: TimeSource
    )

    private fun verify(
        entry: JSONObject,
        fileLength: Long,
        openSpans: (List<ByteSpan>) -> InputStream
    ): SignatureInfo {
        val spans = spansOf(entry.optJSONArray("byteRange"), fileLength)
        val cms = decodeHex(entry.optString("contents"))

        val outcome = if (spans != null && cms != null) inspect(cms) { openSpans(spans) } else UNREADABLE
        return describe(entry, outcome, coversWholeDocument = spans != null && spans.last().end >= fileLength)
    }

    /** A signature that is there but could not be checked at all. */
    private fun unverifiable(entry: JSONObject): SignatureInfo =
        describe(entry, UNREADABLE, coversWholeDocument = false)

    private fun describe(entry: JSONObject, outcome: CmsOutcome, coversWholeDocument: Boolean): SignatureInfo {
        val signedAt = outcome.signedAt ?: parsePdfDate(entry.optString("date"))
        val timeSource = when {
            outcome.timeSource != TimeSource.NONE -> outcome.timeSource
            signedAt != null -> TimeSource.DOCUMENT_DATE
            else -> TimeSource.NONE
        }

        return SignatureInfo(
            fieldName = entry.optString("field"),
            signerName = entry.optString("name"),
            reason = entry.optString("reason"),
            location = entry.optString("location"),
            signedAtMillis = signedAt?.time,
            timeSource = timeSource,
            subFilter = entry.optString("subFilter"),
            integrity = outcome.integrity,
            trust = outcome.trust,
            coversWholeDocument = coversWholeDocument,
            certificates = outcome.certificates
        )
    }

    private fun inspect(cms: ByteArray, openSigned: () -> InputStream): CmsOutcome = runCatching {
        val signedData = CMSSignedData(StreamedContent(openSigned), CmsEnvelope.parse(cms))
        val pool = signedData.certificates.getMatches(null)
            .filterIsInstance<X509CertificateHolder>()
            .mapNotNull { runCatching { converter.getCertificate(it) }.getOrNull() }
        val signer: SignerInformation = signedData.signerInfos.signers.firstOrNull()
            ?: return@runCatching UNREADABLE

        // Matched by serial rather than by passing signer.sid as a Selector: the generic
        // signature of Store.getMatches has shifted across Bouncy Castle releases, and a serial
        // comparison does not depend on it.
        val signerCert = pool.firstOrNull { it.serialNumber == signer.sid.serialNumber }
            ?: pool.firstOrNull()
            ?: return@runCatching UNREADABLE

        verifiedOutcome(signer, signerCert, pool)
    }.getOrElse {
        Log.w(TAG, "CMS verification failed", it)
        if (it.isReadFailure()) {
            // The signed bytes never reached the digest, so nothing is known about them.
            UNREADABLE
        } else {
            // A signature that parses but fails its digest check throws here too; the bytes were
            // read, so the content not matching is the likeliest cause.
            CmsOutcome(Integrity.TAMPERED, TrustStatus.UNKNOWN, emptyList(), null, TimeSource.NONE)
        }
    }

    private fun verifiedOutcome(
        signer: SignerInformation,
        signerCert: X509Certificate,
        pool: List<X509Certificate>
    ): CmsOutcome {
        val timestamp = timestampOf(signer)
        val signedAt = timestamp ?: signingTimeOf(signer)
        val verified = signer.verify(
            JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(signerCert)
        )

        return CmsOutcome(
            integrity = if (verified) Integrity.INTACT else Integrity.TAMPERED,
            trust = if (verified) {
                trustOf(signerCert, pool, signedAt ?: Date())
            } else {
                TrustStatus.UNKNOWN
            },
            certificates = orderedChain(signerCert, pool).map { it.summarise() },
            signedAt = signedAt,
            timeSource = when {
                timestamp != null -> TimeSource.TIMESTAMP
                signedAt != null -> TimeSource.SIGNED_ATTRIBUTE
                else -> TimeSource.NONE
            }
        )
    }

    /**
     * The verified generation time of an embedded RFC 3161 token.
     *
     * Only returned once the token's own signature checks out against the certificate it carries.
     * An unverified timestamp is an attacker-controlled string, and it is the value everything
     * else is judged against — accepting it unchecked would let a forged token revive a revoked
     * or long-expired certificate.
     */
    private fun timestampOf(signer: SignerInformation): Date? = runCatching {
        val attribute: Attribute = signer.unsignedAttributes
            ?.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken)
            ?: return null
        val value = attribute.attributeValues.firstOrNull() ?: return null
        val token = TimeStampToken(ContentInfo.getInstance(value))

        // Selected by serial rather than by handing the SignerId to getMatches, for the same
        // reason as the signer certificate below: that overload's generic type has moved between
        // Bouncy Castle releases and a serial comparison has not.
        val holders = token.certificates.getMatches(null).filterIsInstance<X509CertificateHolder>()
        val tsaCert = (
            holders.firstOrNull { it.serialNumber == token.sid.serialNumber }
                ?: holders.firstOrNull()
            )
            ?.let { converter.getCertificate(it) }
            ?: return null

        token.validate(
            JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(tsaCert)
        )
        token.timeStampInfo.genTime
    }.getOrNull()

    private fun signingTimeOf(signer: SignerInformation): Date? = runCatching {
        val attribute = signer.signedAttributes?.get(CMSAttributes.signingTime) ?: return null
        attribute.attributeValues.firstOrNull()?.let { Time.getInstance(it).date }
    }.getOrNull()

    private fun trustOf(
        signerCert: X509Certificate,
        pool: List<X509Certificate>,
        at: Date
    ): TrustStatus = CertPathTrust.evaluate(signerCert, pool, trustAnchors, at) {
        Log.d(TAG, "No path from ${signerCert.subjectX500Principal.name} to a trusted root", it)
    }

    /** Signer first, then each issuer in turn, so the report reads top-down like a chain. */
    private fun orderedChain(
        signerCert: X509Certificate,
        pool: List<X509Certificate>
    ): List<X509Certificate> {
        val ordered = mutableListOf(signerCert)
        val remaining = pool.toMutableList().apply { remove(signerCert) }
        while (true) {
            val issuer = remaining.firstOrNull {
                it.subjectX500Principal == ordered.last().issuerX500Principal
            } ?: break
            ordered += issuer
            remaining -= issuer
        }
        return ordered + remaining
    }

    private fun X509Certificate.summarise() = CertificateSummary(
        subject = subjectX500Principal.name,
        issuer = issuerX500Principal.name,
        serialNumber = serialNumber.toString(HEX_RADIX),
        notBefore = notBefore,
        notAfter = notAfter
    )

    /**
     * PDF dates look like `D:YYYYMMDDHHmmSS+HH'mm'`. Parsed by hand rather than with java.time,
     * which needs API 26 or desugaring — this module has to work at minSdk 24.
     */
    private fun parsePdfDate(value: String): Date? {
        val digits = value.removePrefix("D:").takeWhile { it.isDigit() }
        if (digits.length < PDF_DATE_MIN_DIGITS) return null

        return runCatching {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(
                    digits.substring(0, 4).toInt(),
                    digits.substring(4, 6).toInt() - 1,
                    digits.substring(6, 8).toInt(),
                    digits.substringSafe(8, 10),
                    digits.substringSafe(10, 12),
                    digits.substringSafe(12, 14)
                )
            }
            val offsetSign = value.getOrNull(digits.length + 2)
            val offset = value.drop(digits.length + 3)
            val millis = if (offsetSign == '+' || offsetSign == '-') {
                val hours = offset.take(2).toIntOrNull() ?: 0
                val minutes = offset.drop(3).take(2).toIntOrNull() ?: 0
                val shift = (hours * 60 + minutes) * 60_000L
                calendar.timeInMillis - if (offsetSign == '+') shift else -shift
            } else {
                calendar.timeInMillis
            }
            Date(millis)
        }.getOrNull()
    }

    private fun String.substringSafe(start: Int, end: Int): Int =
        if (length >= end) substring(start, end).toIntOrNull() ?: 0 else 0

    private companion object {
        const val TRUST_DIR = "trust"
        val CERT_EXTENSIONS = listOf(".pem", ".cer", ".crt", ".der")

        val UNREADABLE = CmsOutcome(
            integrity = Integrity.UNVERIFIABLE,
            trust = TrustStatus.UNKNOWN,
            certificates = emptyList(),
            signedAt = null,
            timeSource = TimeSource.NONE
        )
    }
}

/** /ByteRange is a flat list of (offset, length) pairs. */
private const val BYTE_RANGE_PAIR = 2
private const val HEX_RADIX = 16
private const val NIBBLE_BITS = 4

/** YYYYMMDD, the shortest date a PDF writes. */
private const val PDF_DATE_MIN_DIGITS = 8

/**
 * The (offset, length) pairs of /ByteRange — ISO 32000-1 §12.8.1 allows any number of them,
 * normally two either side of the /Contents placeholder. Null when the array is malformed or
 * reaches past the end of the file.
 */
private fun spansOf(ranges: JSONArray?, fileLength: Long): List<ByteSpan>? {
    if (ranges == null || ranges.length() < BYTE_RANGE_PAIR || ranges.length() % BYTE_RANGE_PAIR != 0) {
        return null
    }
    val spans = (0 until ranges.length() step BYTE_RANGE_PAIR).map {
        ByteSpan(ranges.getLong(it), ranges.getLong(it + 1))
    }
    val valid = spans.all { it.start >= 0 && it.length >= 0 && it.end <= fileLength }
    return spans.takeIf { valid }
}

/** Null for empty, odd-length or non-hex text: there is no CMS blob to check. */
private fun decodeHex(hex: String): ByteArray? {
    val clean = hex.filter { !it.isWhitespace() }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    if (clean.any { it.digitToIntOrNull(HEX_RADIX) == null }) return null
    return ByteArray(clean.length / 2) { i ->
        ((clean[i * 2].digitToInt(HEX_RADIX) shl NIBBLE_BITS) or clean[i * 2 + 1].digitToInt(HEX_RADIX)).toByte()
    }
}

/**
 * Bouncy Castle wraps an IOException from the content stream in a CMSException; running out of
 * memory or stack arrives unwrapped. Neither says anything about the document.
 */
private fun Throwable.isReadFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is IOException || it is VirtualMachineError }
