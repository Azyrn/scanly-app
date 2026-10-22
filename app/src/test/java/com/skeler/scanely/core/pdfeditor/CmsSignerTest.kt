package com.skeler.scanely.core.pdfeditor

import com.skeler.scanely.core.pdfeditor.signature.CmsSigner
import com.skeler.scanely.core.pdfeditor.signature.SigningIdentity
import org.bouncycastle.asn1.ess.SigningCertificateV2
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Checks the CMS blob against the same BouncyCastle path [SignatureValidator] verifies with, plus
 * the PAdES-specific attribute that distinguishes this from a plain adbe.pkcs7.detached signature.
 */
class CmsSignerTest {

    private val provider = BouncyCastleProvider()
    private val converter = JcaX509CertificateConverter().setProvider(provider)

    private fun at(year: Int, month: Int, day: Int): Date =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
        }.time

    private fun keyPair(algorithm: String, size: Int): KeyPair =
        KeyPairGenerator.getInstance(algorithm).apply { initialize(size) }.generateKeyPair()

    private fun certificate(
        subject: String,
        subjectKeys: KeyPair,
        issuer: String,
        issuerKeys: KeyPair,
        isCa: Boolean,
        signatureAlgorithm: String = "SHA256withRSA"
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$issuer"),
            BigInteger(64, SecureRandom()),
            at(2024, 1, 1),
            at(2034, 1, 1),
            X500Name("CN=$subject"),
            subjectKeys.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
        builder.addExtension(
            Extension.keyUsage,
            true,
            if (isCa) KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
            else KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation)
        )
        val signer = JcaContentSignerBuilder(signatureAlgorithm)
            .setProvider(provider)
            .build(issuerKeys.private)
        return converter.getCertificate(builder.build(signer))
    }

    private class Fixture(val identity: SigningIdentity, val root: X509Certificate)

    private fun fixture(): Fixture {
        val rootKeys = keyPair("RSA", 2048)
        val caKeys = keyPair("RSA", 2048)
        val signerKeys = keyPair("RSA", 2048)

        val root = certificate("Scanly Test Root", rootKeys, "Scanly Test Root", rootKeys, true)
        val ca = certificate("Scanly Test CA", caKeys, "Scanly Test Root", rootKeys, true)
        val signer = certificate("Asha Rao", signerKeys, "Scanly Test CA", caKeys, false)

        return Fixture(
            SigningIdentity("signer", signerKeys.private, listOf(signer, ca, root)),
            root
        )
    }

    private val document = "%PDF-1.7\nnot really a pdf, but bytes are bytes\n%%EOF".toByteArray()

    private fun signOf(identity: SigningIdentity, content: ByteArray) =
        CmsSigner.sign(identity) { ByteArrayInputStream(content) }

    @Test
    fun `the signature verifies against the certificate it names`() {
        val fixture = fixture()

        val cms = signOf(fixture.identity, document)

        val signedData = CMSSignedData(CMSProcessableByteArray(document), cms)
        val signer = signedData.signerInfos.signers.single()
        val verified = signer.verify(
            JcaSimpleSignerInfoVerifierBuilder()
                .setProvider(provider)
                .build(fixture.identity.certificate)
        )
        assertTrue(verified)
    }

    @Test
    fun `changing one byte of the document breaks the signature`() {
        val fixture = fixture()
        val cms = signOf(fixture.identity, document)

        val tampered = document.copyOf().also { it[12] = 'X'.code.toByte() }
        val signedData = CMSSignedData(CMSProcessableByteArray(tampered), cms)
        val signer = signedData.signerInfos.signers.single()

        val verified = runCatching {
            signer.verify(
                JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(provider)
                    .build(fixture.identity.certificate)
            )
        }.getOrDefault(false)
        assertTrue(!verified)
    }

    @Test
    fun `the content is detached, not carried inside the blob`() {
        val fixture = fixture()

        // Parsed on its own rather than with the content handed back in, which would simply
        // report the content it was given and prove nothing.
        val signedData = CMSSignedData(signOf(fixture.identity, document))

        assertNull(signedData.signedContent)
    }

    /** Without signingCertificateV2 this is adbe.pkcs7.detached, not PAdES. */
    @Test
    fun `the ESS signingCertificateV2 attribute names the signing certificate by hash`() {
        val fixture = fixture()
        val cms = signOf(fixture.identity, document)

        val signer = CMSSignedData(CMSProcessableByteArray(document), cms).signerInfos.signers.single()
        val attribute = signer.signedAttributes.get(PKCSObjectIdentifiers.id_aa_signingCertificateV2)
        assertNotNull("signingCertificateV2 is missing", attribute)

        val declared = SigningCertificateV2.getInstance(attribute.attributeValues.first())
            .certs
            .first()
            .certHash
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(fixture.identity.certificate.encoded)

        assertTrue(declared.contentEquals(actual))
    }

    @Test
    fun `a signing time is present for the validator to report`() {
        val fixture = fixture()
        val cms = signOf(fixture.identity, document)

        val signer = CMSSignedData(CMSProcessableByteArray(document), cms).signerInfos.signers.single()

        assertNotNull(
            signer.signedAttributes.get(
                org.bouncycastle.asn1.cms.CMSAttributes.signingTime
            )
        )
    }

    /**
     * The whole chain has to travel with the signature or the verifier has no way to reach a root,
     * which is the exact failure [CertPathTrust] exists to avoid.
     */
    @Test
    fun `every certificate in the chain travels inside the blob`() {
        val fixture = fixture()
        val cms = signOf(fixture.identity, document)

        val carried = CMSSignedData(CMSProcessableByteArray(document), cms)
            .certificates
            .getMatches(null)
            .filterIsInstance<X509CertificateHolder>()
            .map { converter.getCertificate(it).subjectX500Principal.name }

        assertEquals(
            setOf("CN=Asha Rao", "CN=Scanly Test CA", "CN=Scanly Test Root"),
            carried.toSet()
        )
    }

    @Test
    fun `an EC key signs with ECDSA rather than being rejected`() {
        val ecKeys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val cert = certificate(
            "EC Signer", ecKeys, "EC Signer", ecKeys, false, signatureAlgorithm = "SHA256withECDSA"
        )
        val identity = SigningIdentity("ec", ecKeys.private, listOf(cert))

        val cms = signOf(identity, document)

        val signer = CMSSignedData(CMSProcessableByteArray(document), cms).signerInfos.signers.single()
        assertTrue(
            signer.verify(
                JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(cert)
            )
        )
    }

    /**
     * The placeholder has to fit the blob. Too small truncates the signature; the check that
     * matters is that the reservation is derived from the chain that will actually be embedded.
     */
    @Test
    fun `the reserved space comfortably fits the blob it has to hold`() {
        val fixture = fixture()

        val reserved = CmsSigner.reservedBytesFor(fixture.identity)
        val actual = signOf(fixture.identity, document).size

        assertTrue("reserved $reserved but blob was $actual", actual < reserved)
    }

    @Test
    fun `a longer chain reserves more space than a shorter one`() {
        val fixture = fixture()
        val leafOnly = fixture.identity.copy(chain = listOf(fixture.identity.certificate))

        assertTrue(
            CmsSigner.reservedBytesFor(fixture.identity) >
                CmsSigner.reservedBytesFor(leafOnly)
        )
    }

    /** A big document must not need a bigger placeholder — only the chain drives the size. */
    @Test
    fun `document size does not change the blob size`() {
        val fixture = fixture()
        val large = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }

        val small = signOf(fixture.identity, document).size
        val big = signOf(fixture.identity, large).size

        assertTrue("small=$small big=$big", kotlin.math.abs(big - small) < 64)
    }
}
