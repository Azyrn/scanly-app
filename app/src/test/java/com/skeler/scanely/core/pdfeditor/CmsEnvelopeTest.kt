package com.skeler.scanely.core.pdfeditor

import com.skeler.scanely.core.pdfeditor.signature.CmsEnvelope
import com.skeler.scanely.core.pdfeditor.signature.CmsSigner
import com.skeler.scanely.core.pdfeditor.signature.SigningIdentity
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Covers the trap that made every signature Scanly produced report as tampered: /Contents is
 * zero-padded, and a BER indefinite-length CMS blob ends in zero bytes of its own.
 */
class CmsEnvelopeTest {

    private val provider = BouncyCastleProvider()
    private val converter = JcaX509CertificateConverter().setProvider(provider)

    private val document = "%PDF-1.7\npretend document\n%%EOF".toByteArray()

    private fun at(year: Int): Date =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, 0, 1)
        }.time

    private fun identity(): SigningIdentity {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=Asha Rao"),
            BigInteger(64, SecureRandom()),
            at(2024),
            at(2034),
            X500Name("CN=Asha Rao"),
            keys.public
        ).addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider)
            .build(keys.private)
        val certificate = converter.getCertificate(builder.build(signer))
        return SigningIdentity("signer", keys.private, listOf(certificate))
    }

    /** How the blob sits in a real file: written into a fixed slot and padded out with zeros. */
    private fun paddedTo(cms: ByteArray, slot: Int) = cms.copyOf(slot)

    @Test
    fun `a padded blob parses back to the signature it holds`() {
        val identity = identity()
        val cms = CmsSigner.sign(identity) { ByteArrayInputStream(document) }

        val parsed = CmsEnvelope.parse(paddedTo(cms, cms.size + 4096))

        val signer = CMSSignedData(CMSProcessableByteArray(document), parsed)
            .signerInfos.signers.single()
        assertTrue(
            signer.verify(
                JcaSimpleSignerInfoVerifierBuilder().setProvider(provider)
                    .build(identity.certificate)
            )
        )
    }

    @Test
    fun `an unpadded blob parses just the same`() {
        val identity = identity()
        val cms = CmsSigner.sign(identity) { ByteArrayInputStream(document) }

        val parsed = CmsEnvelope.parse(cms)

        assertTrue(
            CMSSignedData(CMSProcessableByteArray(document), parsed)
                .signerInfos.signers.single()
                .verify(
                    JcaSimpleSignerInfoVerifierBuilder().setProvider(provider)
                        .build(identity.certificate)
                )
        )
    }

    /**
     * The regression itself. BouncyCastle's CMS output ends in end-of-contents octets, so trimming
     * trailing zeros to strip the padding cuts into the structure.
     */
    @Test
    fun `the blob really does end in zero bytes of its own`() {
        val cms = CmsSigner.sign(identity()) { ByteArrayInputStream(document) }

        assertTrue(
            "expected BER end-of-contents octets at the end of the blob",
            cms.last() == 0.toByte()
        )

        val naivelyTrimmed = cms.copyOf(cms.size - countTrailingZeros(cms))
        assertTrue(
            "trimming trailing zeros must lose bytes that matter",
            naivelyTrimmed.size < cms.size
        )
        assertTrue(
            "a trimmed blob must fail to parse, which is what looked like tampering",
            runCatching { CmsEnvelope.parse(naivelyTrimmed) }.isFailure
        )
    }

    private fun countTrailingZeros(bytes: ByteArray): Int {
        var count = 0
        while (count < bytes.size && bytes[bytes.size - 1 - count] == 0.toByte()) count++
        return count
    }
}
