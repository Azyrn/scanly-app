package com.skeler.scanely.core.pdfeditor.signature

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.ess.ESSCertIDv2
import org.bouncycastle.asn1.ess.SigningCertificateV2
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Builds the detached CMS blob for a PDF's /Contents. Bytes in, bytes out, no Android and no PDF —
 * split from [PdfSigner] so it can be tested against a real chain through the validator's own
 * BouncyCastle path.
 *
 * Output is PAdES B-B: detached SHA-256 CMS carrying the ESS signingCertificateV2 signed attribute.
 * That attribute is not decoration — it binds the CMS to one specific certificate. Without it this
 * is plain adbe.pkcs7.detached, and anyone holding the blob can swap in another certificate with
 * the same public key and the maths still checks out.
 *
 * No RFC 3161 timestamp, so the only time present is the signer's claimed signingTime, which
 * [SignatureValidator] reports as [TimeSource.SIGNED_ATTRIBUTE] rather than as an authority's.
 */
object CmsSigner {

    private val provider by lazy { BouncyCastleProvider() }

    /** Content is streamed rather than buffered: the bytes to digest are the whole PDF. */
    fun sign(identity: SigningIdentity, content: () -> InputStream): ByteArray {
        val certificate = identity.certificate
        val generator = CMSSignedDataGenerator()

        val contentSigner = JcaContentSignerBuilder(signatureAlgorithmFor(identity))
            .setProvider(provider)
            .build(identity.privateKey)

        val digestProvider = JcaDigestCalculatorProviderBuilder()
            .setProvider(provider)
            .build()

        val signedAttributes = ASN1EncodableVector().apply {
            add(signingCertificateV2Of(certificate))
        }

        generator.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(digestProvider)
                .setSignedAttributeGenerator(
                    DefaultSignedAttributeTableGenerator(AttributeTable(signedAttributes))
                )
                .build(contentSigner, certificate)
        )
        generator.addCertificates(JcaCertStore(identity.chain))

        // encapsulate = false: the PDF holds the content, the CMS holds only the signature over it.
        return generator.generate(StreamedContent(content), false).encoded
    }

    /**
     * The size to reserve in /Contents, in bytes.
     *
     * Derived from the chain rather than fixed, because the certificates travel inside the blob and
     * a three-certificate chain is several kilobytes larger than a self-issued one. The headroom
     * covers the signature, the signed attributes and DER framing, none of which scale with the
     * document.
     */
    fun reservedBytesFor(identity: SigningIdentity): Int {
        val certificateBytes = identity.chain.sumOf {
            runCatching { it.encoded.size }.getOrDefault(FALLBACK_CERT_BYTES)
        }
        return (certificateBytes + SIGNATURE_HEADROOM_BYTES).coerceAtMost(MAX_RESERVED_BYTES)
    }

    private fun signingCertificateV2Of(certificate: java.security.cert.X509Certificate): Attribute {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return Attribute(
            PKCSObjectIdentifiers.id_aa_signingCertificateV2,
            DERSet(SigningCertificateV2(ESSCertIDv2(digest)))
        )
    }

    private fun signatureAlgorithmFor(identity: SigningIdentity): String =
        when (val algorithm = identity.privateKey.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            "DSA" -> "SHA256withDSA"
            "ED25519" -> "Ed25519"
            else -> throw UnsupportedOperationException(
                "Signing with a $algorithm key isn't supported."
            )
        }

    /**
     * Lets BouncyCastle digest the document straight off the PDF without it being held in memory
     * first. [CMSProcessableByteArray] would mean a second full copy of every file signed.
     */
    private class StreamedContent(private val open: () -> InputStream) : CMSTypedData {
        override fun getContentType(): ASN1ObjectIdentifier = CMSObjectIdentifiers.data

        override fun getContent(): Any = open

        override fun write(out: OutputStream) {
            open().use { it.copyTo(out) }
        }
    }

    private const val SIGNATURE_HEADROOM_BYTES = 8192
    private const val FALLBACK_CERT_BYTES = 2048
    private const val MAX_RESERVED_BYTES = 64 * 1024
}
