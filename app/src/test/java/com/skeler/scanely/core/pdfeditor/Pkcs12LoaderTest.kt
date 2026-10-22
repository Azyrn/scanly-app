package com.skeler.scanely.core.pdfeditor

import com.skeler.scanely.core.pdfeditor.signature.Pkcs12Loader
import com.skeler.scanely.core.pdfeditor.signature.Pkcs12Result
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Exercises the loader against PKCS#12 files built here rather than checked in, so the fixtures
 * cannot silently expire and the shapes that matter — a full chain, a bare leaf, an
 * encryption-only certificate — are each explicit.
 */
class Pkcs12LoaderTest {

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
        isCa: Boolean,
        keyUsage: KeyUsage? = null
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
        keyUsage?.let { builder.addExtension(Extension.keyUsage, true, it) }
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(provider)
            .build(issuerKeys.private)
        return converter.getCertificate(builder.build(signer))
    }

    private fun p12(
        password: String,
        entries: List<Triple<String, KeyPair, Array<X509Certificate>>>
    ): ByteArray {
        val store = KeyStore.getInstance("PKCS12", provider).apply { load(null, null) }
        entries.forEach { (alias, keys, chain) ->
            store.setKeyEntry(alias, keys.private, password.toCharArray(), chain)
        }
        return ByteArrayOutputStream().also { store.store(it, password.toCharArray()) }.toByteArray()
    }

    private class Chain(
        val rootKeys: KeyPair,
        val caKeys: KeyPair,
        val signerKeys: KeyPair,
        val root: X509Certificate,
        val intermediate: X509Certificate,
        val signer: X509Certificate
    )

    private fun chain(): Chain {
        val rootKeys = keyPair()
        val caKeys = keyPair()
        val signerKeys = keyPair()

        val root = certificate(
            "Scanly Test Root", rootKeys, "Scanly Test Root", rootKeys, isCa = true,
            keyUsage = KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )
        val intermediate = certificate(
            "Scanly Test CA", caKeys, "Scanly Test Root", rootKeys, isCa = true,
            keyUsage = KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )
        val signer = certificate(
            "Asha Rao", signerKeys, "Scanly Test CA", caKeys, isCa = false,
            keyUsage = KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation)
        )
        return Chain(rootKeys, caKeys, signerKeys, root, intermediate, signer)
    }

    @Test
    fun `a signer and its full chain load in issuer order`() {
        val chain = chain()
        val bytes = p12(
            "secret",
            listOf(
                Triple(
                    "signer",
                    chain.signerKeys,
                    arrayOf(chain.signer, chain.intermediate, chain.root)
                )
            )
        )

        val result = Pkcs12Loader.load(bytes, "secret".toCharArray())

        assertTrue("$result", result is Pkcs12Result.Loaded)
        val identity = (result as Pkcs12Result.Loaded).identity
        assertEquals(chain.signerKeys.private, identity.privateKey)
        assertEquals(
            listOf("CN=Asha Rao", "CN=Scanly Test CA", "CN=Scanly Test Root"),
            identity.chain.map { it.subjectX500Principal.name }
        )
        assertEquals("CN=Asha Rao", identity.subject)
        assertEquals("CN=Scanly Test CA", identity.issuer)
    }

    @Test
    fun `a file holding only the leaf still loads, with a one-certificate chain`() {
        val chain = chain()
        val bytes = p12("secret", listOf(Triple("signer", chain.signerKeys, arrayOf(chain.signer))))

        val result = Pkcs12Loader.load(bytes, "secret".toCharArray())

        assertTrue("$result", result is Pkcs12Result.Loaded)
        assertEquals(1, (result as Pkcs12Result.Loaded).identity.chain.size)
    }

    @Test
    fun `the wrong password is reported as the wrong password, not a broken file`() {
        val chain = chain()
        val bytes = p12("secret", listOf(Triple("signer", chain.signerKeys, arrayOf(chain.signer))))

        val result = Pkcs12Loader.load(bytes, "wrong".toCharArray())

        assertEquals(Pkcs12Result.WrongPassword, result)
    }

    @Test
    fun `something that is not a PKCS12 fails rather than throwing`() {
        val result = Pkcs12Loader.load("%PDF-1.7 not a keystore".toByteArray(), "x".toCharArray())

        assertTrue("$result", result is Pkcs12Result.Failed)
    }

    @Test
    fun `a keystore with no private key is reported as such`() {
        val chain = chain()
        val store = KeyStore.getInstance("PKCS12", provider).apply { load(null, null) }
        store.setCertificateEntry("just-a-cert", chain.signer)
        val bytes = ByteArrayOutputStream()
            .also { store.store(it, "secret".toCharArray()) }
            .toByteArray()

        val result = Pkcs12Loader.load(bytes, "secret".toCharArray())

        assertEquals(Pkcs12Result.NoPrivateKey, result)
    }

    /**
     * Signing with an encryption-only certificate produces a signature every verifier rejects.
     * Picking whichever entry the bag listed first is how that happens.
     */
    @Test
    fun `an encryption-only entry is passed over in favour of the signing one`() {
        val chain = chain()
        val encryptionKeys = keyPair()
        val encryptionOnly = certificate(
            "Asha Rao Encryption", encryptionKeys, "Scanly Test CA", chain.caKeys, isCa = false,
            keyUsage = KeyUsage(KeyUsage.keyEncipherment)
        )

        val bytes = p12(
            "secret",
            listOf(
                Triple("aaa-encryption", encryptionKeys, arrayOf(encryptionOnly, chain.intermediate)),
                Triple("zzz-signing", chain.signerKeys, arrayOf(chain.signer, chain.intermediate))
            )
        )

        val result = Pkcs12Loader.load(bytes, "secret".toCharArray())

        assertTrue("$result", result is Pkcs12Result.Loaded)
        assertEquals("CN=Asha Rao", (result as Pkcs12Result.Loaded).identity.subject)
    }

    @Test
    fun `a certificate with no key usage extension may sign`() {
        val chain = chain()
        val plainKeys = keyPair()
        val plain = certificate(
            "Plain Signer", plainKeys, "Scanly Test CA", chain.caKeys, isCa = false, keyUsage = null
        )
        val bytes = p12("secret", listOf(Triple("plain", plainKeys, arrayOf(plain))))

        val result = Pkcs12Loader.load(bytes, "secret".toCharArray())

        assertTrue("$result", result is Pkcs12Result.Loaded)
        assertTrue((result as Pkcs12Result.Loaded).identity.canSign)
    }

    @Test
    fun `validity is judged against the certificate's own window`() {
        val chain = chain()
        val bytes = p12("secret", listOf(Triple("signer", chain.signerKeys, arrayOf(chain.signer))))

        val identity = (Pkcs12Loader.load(bytes, "secret".toCharArray()) as Pkcs12Result.Loaded)
            .identity

        assertTrue(identity.isValidAt(at(2026, 6, 1)))
        assertTrue(!identity.isValidAt(at(2023, 6, 1)))
        assertTrue(!identity.isValidAt(at(2035, 6, 1)))
    }
}
