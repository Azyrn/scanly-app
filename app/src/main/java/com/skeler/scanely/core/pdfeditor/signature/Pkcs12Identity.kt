package com.skeler.scanely.core.pdfeditor.signature

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * A private key and its certificate chain, ready to sign with.
 *
 * [chain] is signer first, then each issuer in turn. The root is included when the file carried it
 * and absent when it did not; a PKCS#12 is under no obligation to ship the whole path, and a
 * signature whose chain stops at an intermediate is still a valid signature.
 */
data class SigningIdentity(
    val alias: String,
    val privateKey: PrivateKey,
    val chain: List<X509Certificate>
) {
    val certificate: X509Certificate get() = chain.first()

    val subject: String get() = certificate.subjectX500Principal.name

    val issuer: String get() = certificate.issuerX500Principal.name

    val notBefore: Date get() = certificate.notBefore

    val notAfter: Date get() = certificate.notAfter

    fun isValidAt(instant: Date): Boolean =
        !instant.before(notBefore) && !instant.after(notAfter)

    /**
     * True when the certificate says it may be used to sign.
     *
     * A certificate with no keyUsage extension is unconstrained, so it passes. One that names its
     * usages must include digitalSignature or nonRepudiation — an encryption-only certificate is a
     * common thing to find sitting in the same file as a signing one.
     */
    val canSign: Boolean
        get() = certificate.keyUsage?.let {
            it.getOrElse(KEY_USAGE_DIGITAL_SIGNATURE) { false } ||
                it.getOrElse(KEY_USAGE_NON_REPUDIATION) { false }
        } ?: true

    private companion object {
        const val KEY_USAGE_DIGITAL_SIGNATURE = 0
        const val KEY_USAGE_NON_REPUDIATION = 1
    }
}

sealed interface Pkcs12Result {
    data class Loaded(val identity: SigningIdentity) : Pkcs12Result

    /** The MAC did not check out. Almost always the password, occasionally a truncated file. */
    data object WrongPassword : Pkcs12Result

    /** Read fine, but holds no private key — usually a bare certificate saved with the wrong extension. */
    data object NoPrivateKey : Pkcs12Result

    data class Failed(val message: String) : Pkcs12Result
}

/**
 * Reads a PKCS#12 (.p12/.pfx) file into something that can sign.
 *
 * Bouncy Castle's KeyStore, not the platform's: Android rejects key bags using PBES2/AES, which is
 * OpenSSL 3's default, so a certificate that works everywhere else would look corrupt here.
 *
 * Android-free so the decisions that matter — which key entry is picked, how the chain is ordered —
 * can be tested against a real PKCS#12 rather than inspected by eye.
 */
object Pkcs12Loader {

    private val provider by lazy { BouncyCastleProvider() }

    fun load(bytes: ByteArray, password: CharArray): Pkcs12Result {
        val store = runCatching {
            KeyStore.getInstance("PKCS12", provider).apply {
                load(ByteArrayInputStream(bytes), password)
            }
        }.getOrElse { error ->
            return if (error.looksLikeWrongPassword()) {
                Pkcs12Result.WrongPassword
            } else {
                Pkcs12Result.Failed(error.message ?: "That file could not be read as a PKCS#12.")
            }
        }

        return runCatching { pickIdentity(store, password) }
            .getOrElse { error ->
                if (error.looksLikeWrongPassword()) {
                    Pkcs12Result.WrongPassword
                } else {
                    Pkcs12Result.Failed(error.message ?: "The certificate could not be read.")
                }
            }
    }

    private fun pickIdentity(store: KeyStore, password: CharArray): Pkcs12Result {
        val pool = store.aliases().toList().mapNotNull { alias ->
            store.getCertificate(alias) as? X509Certificate
        }

        val candidates = store.aliases().toList()
            .filter { store.isKeyEntry(it) }
            .mapNotNull { alias ->
                val key = store.getKey(alias, password) as? PrivateKey ?: return@mapNotNull null
                val chain = store.getCertificateChain(alias)
                    .orEmpty()
                    .filterIsInstance<X509Certificate>()
                val leaf = chain.firstOrNull()
                    ?: store.getCertificate(alias) as? X509Certificate
                    ?: return@mapNotNull null
                SigningIdentity(alias, key, orderedChain(leaf, chain + pool))
            }

        if (candidates.isEmpty()) return Pkcs12Result.NoPrivateKey

        // A file holding both a signing and an encryption certificate is ordinary; picking the
        // first entry would sign with whichever the bag happened to list first.
        val chosen = candidates.firstOrNull { it.canSign } ?: return Pkcs12Result.Failed(
            "This certificate is not allowed to sign documents."
        )
        return Pkcs12Result.Loaded(chosen)
    }

    /** Signer first, then each issuer in turn, matching how [SignatureValidator] reports a chain. */
    private fun orderedChain(
        leaf: X509Certificate,
        pool: List<X509Certificate>
    ): List<X509Certificate> {
        val ordered = mutableListOf(leaf)
        val remaining = pool.distinctBy { it.serialNumber to it.issuerX500Principal }
            .toMutableList()
            .apply { removeAll { it == leaf } }

        while (true) {
            val last = ordered.last()
            if (last.subjectX500Principal == last.issuerX500Principal) break
            val issuer = remaining.firstOrNull {
                it.subjectX500Principal == last.issuerX500Principal
            } ?: break
            ordered += issuer
            remaining -= issuer
        }
        return ordered
    }

    /**
     * Bouncy Castle reports a failed MAC as a plain IOException carrying this wording; there is no
     * distinct exception type to catch, and the alternative is reporting every wrong password as a
     * corrupt file.
     */
    private fun Throwable.looksLikeWrongPassword(): Boolean =
        generateSequence(this) { it.cause }.any { error ->
            error.message?.lowercase()?.let { message ->
                "wrong password" in message ||
                    "mac invalid" in message ||
                    "password was incorrect" in message ||
                    "invalid password" in message
            } == true
        }
}
