package com.skeler.scanely.core.pdfeditor.signature

import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.cms.ContentInfo
import java.io.ByteArrayInputStream

/**
 * Reads the CMS structure out of a signature dictionary's /Contents.
 *
 * /Contents is a fixed-width, zero-padded slot, so the blob is almost always followed by padding
 * that is not part of it. Where the object ends has to come from the ASN.1 itself.
 *
 * Trimming trailing zeros is the obvious approach and is wrong: BouncyCastle emits BER with
 * indefinite lengths, which legitimately ends in `00 00` end-of-contents octets. Trimming eats
 * those, truncates the structure, and the signature reports as *tampered* rather than unreadable —
 * a wrong answer that looks like a verdict. `ASN1Primitive.fromByteArray` rejects the padding as
 * trailing data.
 *
 * Reading one object off a stream handles both: the parser stops where the encoding says.
 */
internal object CmsEnvelope {

    fun parse(contents: ByteArray): ContentInfo =
        ASN1InputStream(ByteArrayInputStream(contents)).use {
            ContentInfo.getInstance(it.readObject())
        }
}
