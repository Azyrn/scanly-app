package com.skeler.scanely.core.pdfeditor

import com.skeler.scanely.core.pdfeditor.signature.CertPathTrust
import com.skeler.scanely.core.pdfeditor.signature.TrustStatus
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Exercises the trust decision against a chain shaped like the ones this feature exists for:
 * a signer issued by a licensed CA, issued in turn by a national root, on a document signed years
 * before it is read.
 */
class CertPathTrustTest {

    private val provider = BouncyCastleProvider()
    private val converter = JcaX509CertificateConverter().setProvider(provider)

    private fun at(year: Int, month: Int, day: Int): Date =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
        }.time

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun certificate(
        subject: String,
        subjectKeys: KeyPair,
        issuer: String,
        issuerKeys: KeyPair,
        from: Date,
        until: Date,
        isCa: Boolean
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$issuer"),
            BigInteger(64, java.security.SecureRandom()),
            from,
            until,
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
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(provider)
            .build(issuerKeys.private)
        return converter.getCertificate(builder.build(signer))
    }

    /** Root valid 2014-2024, licensed CA 2014-2024, signer 2018-2020 — e-Aadhaar's shape. */
    private class Chain(
        val root: X509Certificate,
        val intermediate: X509Certificate,
        val signer: X509Certificate
    )

    private fun chain(): Chain {
        val rootKeys = keyPair()
        val caKeys = keyPair()
        val signerKeys = keyPair()

        val root = certificate(
            "CCA India 2014", rootKeys, "CCA India 2014", rootKeys,
            at(2014, 3, 5), at(2024, 3, 5), isCa = true
        )
        val intermediate = certificate(
            "Licensed CA 2014", caKeys, "CCA India 2014", rootKeys,
            at(2014, 6, 1), at(2024, 1, 1), isCa = true
        )
        val signer = certificate(
            "Unique Identification Authority of India", signerKeys, "Licensed CA 2014", caKeys,
            at(2018, 1, 1), at(2020, 1, 1), isCa = false
        )
        return Chain(root, intermediate, signer)
    }

    private fun anchors(vararg certs: X509Certificate): Set<TrustAnchor> =
        certs.map { TrustAnchor(it, null) }.toSet()

    @Test
    fun `a signer reaches the root through the intermediate the signature carries`() {
        val chain = chain()

        val status = CertPathTrust.evaluate(
            signerCert = chain.signer,
            pool = listOf(chain.signer, chain.intermediate),
            anchors = anchors(chain.root),
            at = at(2019, 6, 1)
        )

        assertEquals(TrustStatus.TRUSTED, status)
    }

    /**
     * The regression that kept every government PDF on a yellow question mark: handing the
     * validator the signer alone, with no way to reach the root that is two hops up.
     */
    @Test
    fun `without the intermediate there is no path to the root`() {
        val chain = chain()

        val status = CertPathTrust.evaluate(
            signerCert = chain.signer,
            pool = listOf(chain.signer),
            anchors = anchors(chain.root),
            at = at(2019, 6, 1)
        )

        assertEquals(TrustStatus.UNTRUSTED_ISSUER, status)
    }

    /**
     * The other half of the same regression: a 2019 document read in 2026. The signer expired in
     * 2020 and the root in 2024, and neither says anything about whether the signature was good
     * when it was made.
     */
    @Test
    fun `a long-expired certificate is still trusted as of when it signed`() {
        val chain = chain()

        val status = CertPathTrust.evaluate(
            signerCert = chain.signer,
            pool = listOf(chain.signer, chain.intermediate),
            anchors = anchors(chain.root),
            at = at(2019, 6, 1)
        )

        assertEquals(TrustStatus.TRUSTED, status)
    }

    @Test
    fun `a signature made after the certificate expired is reported as expired`() {
        val chain = chain()

        val status = CertPathTrust.evaluate(
            signerCert = chain.signer,
            pool = listOf(chain.signer, chain.intermediate),
            anchors = anchors(chain.root),
            at = at(2021, 6, 1)
        )

        assertEquals(TrustStatus.EXPIRED, status)
    }

    @Test
    fun `a signature dated before the certificate existed is not yet valid`() {
        val chain = chain()

        val status = CertPathTrust.evaluate(
            signerCert = chain.signer,
            pool = listOf(chain.signer, chain.intermediate),
            anchors = anchors(chain.root),
            at = at(2017, 1, 1)
        )

        assertEquals(TrustStatus.NOT_YET_VALID, status)
    }

    @Test
    fun `an unrelated root does not vouch for the signer`() {
        val chain = chain()
        val strangerKeys = keyPair()
        val stranger = certificate(
            "Some Other Root", strangerKeys, "Some Other Root", strangerKeys,
            at(2014, 1, 1), at(2030, 1, 1), isCa = true
        )

        val status = CertPathTrust.evaluate(
            signerCert = chain.signer,
            pool = listOf(chain.signer, chain.intermediate),
            anchors = anchors(stranger),
            at = at(2019, 6, 1)
        )

        assertEquals(TrustStatus.UNTRUSTED_ISSUER, status)
    }

    @Test
    fun `with no anchors bundled nothing can be trusted`() {
        val chain = chain()

        val status = CertPathTrust.evaluate(
            signerCert = chain.signer,
            pool = listOf(chain.signer, chain.intermediate),
            anchors = emptySet(),
            at = at(2019, 6, 1)
        )

        assertEquals(TrustStatus.UNTRUSTED_ISSUER, status)
    }
}
