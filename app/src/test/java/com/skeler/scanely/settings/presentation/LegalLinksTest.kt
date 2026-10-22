package com.skeler.scanely.settings.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * Play requires the privacy policy inside the app, and the app is consumption-only, so what it opens
 * must be the site's in-app view (`?app`, no pricing or download links — `site/test/legal-in-app.mjs`
 * checks the pages themselves).
 */
class LegalLinksTest {

    @Test
    fun `both legal links open the site's in-app view over https`() {
        for ((link, path) in listOf(LegalLinks.PRIVACY_POLICY to "/privacy", LegalLinks.TERMS to "/terms")) {
            val uri = URI(link)
            assertEquals("https", uri.scheme)
            assertEquals("scanlyocr.com", uri.host)
            assertEquals(path, uri.path)
            assertTrue(link, uri.query == "app")
        }
    }
}
