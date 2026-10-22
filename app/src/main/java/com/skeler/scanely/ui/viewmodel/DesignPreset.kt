package com.skeler.scanely.ui.viewmodel

import androidx.annotation.StringRes
import com.skeler.scanely.R

/**
 * A one-tap instruction for an attached scan or file that is not a template: keep the document
 * what it is, and change only how it looks. [prompt] is sent to the model as written.
 */
data class DesignPreset(@param:StringRes val label: Int, val prompt: String)

/** Offered only with content attached — "clean it up" against nothing designs a blank page. */
val CONTENT_PRESETS = listOf(
    DesignPreset(
        label = R.string.chat_preset_clean_up,
        prompt = "Retypeset this exactly as it is, in a clean modern layout with clear headings " +
            "and comfortable spacing. Keep every line, figure and number; change the appearance " +
            "only."
    ),
    DesignPreset(
        label = R.string.chat_preset_one_page,
        prompt = "Fit all of this onto a single page. Tighten the spacing and the type size " +
            "rather than leaving anything out."
    )
)
