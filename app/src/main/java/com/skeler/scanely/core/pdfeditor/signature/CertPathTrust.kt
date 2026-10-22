package com.skeler.scanely.core.pdfeditor.signature

import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Decides whether a signing certificate reaches a trusted root. Split from [SignatureValidator]
 * because it has to be right and has nothing to do with Android — no context, assets, or PDF.
 *
 * Two decisions that are each easy to get subtly wrong:
 * - the path is *built* from the certificates travelling with the signature. A signer is issued by
 *   a licensed CA, which is issued by the root; a one-certificate path can only validate for a root
 *   that signed the document itself, which no real PKI does.
 * - the path is judged at [at], signing time. Certificates last two or three years and documents
 *   outlive them by decades, so judging against the clock makes every older government PDF
 *   permanently untrusted.
 *
 * Rejection reasons go to [onPathFailure] for the caller to log.
 */
internal object CertPathTrust {

    fun evaluate(
        signerCert: X509Certificate,
        pool: List<X509Certificate>,
        anchors: Set<TrustAnchor>,
        at: Date,
        onPathFailure: (Throwable) -> Unit = {}
    ): TrustStatus {
        if (at.before(signerCert.notBefore)) return TrustStatus.NOT_YET_VALID
        if (at.after(signerCert.notAfter)) return TrustStatus.EXPIRED
        if (anchors.isEmpty()) return TrustStatus.UNTRUSTED_ISSUER

        return runCatching {
            val selector = X509CertSelector().apply { certificate = signerCert }
            val parameters = PKIXBuilderParameters(anchors, selector).apply {
                // No CRL or OCSP endpoint is reachable offline, and a document is as often checked
                // on a plane or in a queue as at a desk. Revocation is reported as unchecked rather
                // than faked.
                isRevocationEnabled = false
                date = at
                addCertStore(
                    CertStore.getInstance("Collection", CollectionCertStoreParameters(pool))
                )
            }
            CertPathBuilder.getInstance("PKIX").build(parameters)
            TrustStatus.TRUSTED
        }.onFailure(onPathFailure).getOrElse { TrustStatus.UNTRUSTED_ISSUER }
    }
}
