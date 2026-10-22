package com.skeler.scanely.settings.presentation

/**
 * The privacy policy and terms, as the app opens them. `?app` is the site's in-app view: no header,
 * footer or pricing links, because on Play this is a consumption-only app and nothing it opens may
 * lead to a purchase. See `site/public/privacy.html`.
 */
internal object LegalLinks {
    const val PRIVACY_POLICY = "https://scanlyocr.com/privacy?app"
    const val TERMS = "https://scanlyocr.com/terms?app"
}
