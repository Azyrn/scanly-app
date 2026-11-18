package com.skeler.scanely.core.pdfeditor

import com.skeler.scanely.core.pdfeditor.signature.CmsSigner
import com.skeler.scanely.core.pdfeditor.signature.Integrity
import com.skeler.scanely.core.pdfeditor.signature.SignatureValidator
import com.skeler.scanely.core.pdfeditor.signature.SigningIdentity
import com.skeler.scanely.testing.EnglishStrings
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date

/**
 * The editor asks the validator whether a file is signed before a save that will void every
 * signature in it. Any answer of "none" for a signed file skips that warning, so whatever goes wrong
 * reading the file, a signature field must still come back as a signature.
 */
class SignatureValidatorTest {

    @get:Rule val folder = TemporaryFolder()

    private val validator = SignatureValidator(EnglishStrings.context())

    private val prefix = "%PDF-1.7\n1 0 obj <</Type/Sig/Contents ".toByteArray()
    private val placeholder = ("<" + "0".repeat(PLACEHOLDER_HEX) + ">").toByteArray()
    private val suffix = " >> endobj\n%%EOF\n".toByteArray()

    private fun identity(): SigningIdentity {
        val provider = BouncyCastleProvider()
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        val name = X500Name("CN=Asha Rao")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger(SERIAL_BITS, SecureRandom()), Date(now - DAY_MS), Date(now + DAY_MS), name, keys.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keys.private))
        val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
        return SigningIdentity("signer", keys.private, listOf(cert))
    }

    /** A file laid out like a signed PDF, and the JSON the native side reports for it. */
    private fun signedFile(contentsHex: String? = null): Pair<File, String> {
        val file = folder.newFile("signed.pdf")
        file.writeBytes(prefix + placeholder + suffix)
        val covered = prefix + suffix
        val hex = contentsHex ?: CmsSigner.sign(EnglishStrings.context(), identity()) {
            ByteArrayInputStream(covered)
        }.joinToString("") { "%02x".format(it) }

        val entry = JSONObject()
            .put("byteRange", JSONArray(listOf(0, prefix.size, prefix.size + placeholder.size, suffix.size)))
            .put("contents", hex)
            .put("subFilter", "ETSI.CAdES.detached")
            .put("field", "Signature1")
            .put("name", "Asha Rao")
        return file to JSONArray().put(entry).toString()
    }

    @Test
    fun `an intact signature verifies over its byte ranges`() {
        val (file, json) = signedFile()

        val result = validator.verifyAll(json, file).single()

        assertEquals(Integrity.INTACT, result.integrity)
        assertTrue(result.coversWholeDocument)
        assertEquals("Signature1", result.fieldName)
    }

    @Test
    fun `a change inside the signed range reads as tampered`() {
        val (file, json) = signedFile()
        file.writeBytes(prefix.copyOf().also { it[it.size - 2] = 'X'.code.toByte() } + placeholder + suffix)

        assertEquals(Integrity.TAMPERED, validator.verifyAll(json, file).single().integrity)
    }

    @Test
    fun `a signed file that cannot be read still reports its signature`() {
        val (file, json) = signedFile()
        file.setReadable(false)
        assumeFalse("running as root, the file stays readable", file.canRead())

        val results = validator.verifyAll(json, file)

        assertEquals(1, results.size)
        assertEquals(Integrity.UNVERIFIABLE, results.single().integrity)
        assertEquals("Signature1", results.single().fieldName)
    }

    @Test
    fun `running out of memory while reading the signed bytes still reports the signature`() {
        val (file, json) = signedFile()

        val results = validator.verifyAll(json, file) { throw OutOfMemoryError("large PDF") }

        assertEquals(1, results.size)
        assertEquals(Integrity.UNVERIFIABLE, results.single().integrity)
    }

    @Test
    fun `a byte range reaching past the end of the file still counts`() {
        val (file, json) = signedFile()
        file.writeBytes(prefix)

        assertEquals(Integrity.UNVERIFIABLE, validator.verifyAll(json, file).single().integrity)
    }

    @Test
    fun `a signature whose contents cannot be decoded still counts`() {
        val (file, json) = signedFile(contentsHex = "zz")

        val results = validator.verifyAll(json, file)

        assertEquals(1, results.size)
        assertEquals(Integrity.UNVERIFIABLE, results.single().integrity)
    }

    private companion object {
        const val PLACEHOLDER_HEX = 16
        const val KEY_BITS = 2048
        const val SERIAL_BITS = 64
        const val DAY_MS = 86_400_000L
    }
}
