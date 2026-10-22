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
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.tsp.TimeStampToken
import org.json.JSONArray
import java.io.File
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

    suspend fun validate(file: File, password: String = ""): List<SignatureInfo> =
        withContext(Dispatchers.Default) {
            val json = Qpdf.nativeExtractSignatures(file.absolutePath, password)
                ?: return@withContext emptyList()

            val entries = runCatching { JSONArray(json) }.getOrNull() ?: return@withContext emptyList()
            val fileBytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext emptyList()

            (0 until entries.length()).mapNotNull { index ->
                runCatching { verify(entries.getJSONObject(index), fileBytes) }
                    .onFailure { Log.w(TAG, "Signature $index could not be read", it) }
                    .getOrNull()
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

    private fun verify(entry: org.json.JSONObject, fileBytes: ByteArray): SignatureInfo {
        val ranges = entry.getJSONArray("byteRange")
        val signed = signedBytes(fileBytes, ranges)
        val cms = decodeHex(entry.getString("contents"))

        val outcome = signed?.let { inspect(it, cms) } ?: UNREADABLE
        val signedAt = outcome.signedAt ?: parsePdfDate(entry.optString("date"))
        val timeSource = when {
            outcome.timeSource != TimeSource.NONE -> outcome.timeSource
            signedAt != null -> TimeSource.DOCUMENT_DATE
            else -> TimeSource.NONE
        }

        val lastCovered = ranges.getLong(2) + ranges.getLong(3)

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
            coversWholeDocument = lastCovered >= fileBytes.size,
            certificates = outcome.certificates
        )
    }

    private fun inspect(signed: ByteArray, cms: ByteArray): CmsOutcome = runCatching {
        val signedData = CMSSignedData(CMSProcessableByteArray(signed), CmsEnvelope.parse(cms))
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

        val timestamp = timestampOf(signer)
        val signedAt = timestamp ?: signingTimeOf(signer)
        val verified = signer.verify(
            JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(signerCert)
        )

        CmsOutcome(
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
    }.getOrElse {
        Log.w(TAG, "CMS verification failed", it)
        // A signature that parses but fails its digest check throws here too; the range read
        // succeeded, so the content not matching is the likeliest cause.
        CmsOutcome(Integrity.TAMPERED, TrustStatus.UNKNOWN, emptyList(), null, TimeSource.NONE)
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

    /** The two spans either side of the /Contents placeholder, concatenated. */
    private fun signedBytes(fileBytes: ByteArray, ranges: JSONArray): ByteArray? {
        if (ranges.length() < 4) return null
        val start1 = ranges.getLong(0).toInt()
        val length1 = ranges.getLong(1).toInt()
        val start2 = ranges.getLong(2).toInt()
        val length2 = ranges.getLong(3).toInt()

        if (start1 < 0 || length1 < 0 || start2 < 0 || length2 < 0) return null
        if (start1 + length1 > fileBytes.size || start2 + length2 > fileBytes.size) return null

        return ByteArray(length1 + length2).also { out ->
            fileBytes.copyInto(out, 0, start1, start1 + length1)
            fileBytes.copyInto(out, length1, start2, start2 + length2)
        }
    }

    private fun X509Certificate.summarise() = CertificateSummary(
        subject = subjectX500Principal.name,
        issuer = issuerX500Principal.name,
        serialNumber = serialNumber.toString(16),
        notBefore = notBefore,
        notAfter = notAfter
    )

    private fun decodeHex(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() }
        return ByteArray(clean.length / 2) { i ->
            ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    /**
     * PDF dates look like `D:YYYYMMDDHHmmSS+HH'mm'`. Parsed by hand rather than with java.time,
     * which needs API 26 or desugaring — this module has to work at minSdk 24.
     */
    private fun parsePdfDate(value: String): Date? {
        val digits = value.removePrefix("D:").takeWhile { it.isDigit() }
        if (digits.length < 8) return null

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
