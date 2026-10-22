package com.skeler.scanely.settings.data

import androidx.appcompat.app.AppCompatDelegate
import com.skeler.scanely.ui.theme.SeedPalettes

enum class SettingsKeys(val default: Any?) {
    THEME_MODE(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    IS_OLED_MODE_ENABLED(false),
    OCR_LANGUAGES(emptySet<String>()),
    USE_DYNAMIC_COLORS(true),
    SEED_COLOR_INDEX(SeedPalettes.DEFAULT_INDEX),
    LAST_AI_REQUEST_TIMESTAMP(0L),
    AI_REQUEST_COUNT(0),
    BARCODE_ENGINE("mlkit"),
    READING_TEXT_SCALE(1.0f),
    TEXT_OCR_ENGINE("paddle"),
    PADDLE_SCRIPT("universal"),
    PADDLE_DOC_ORIENTATION(true),
    PADDLE_DOC_UNWARP(false),
    PADDLE_LINE_ORIENTATION(false),
    PADDLE_STRUCTURE(true),

    CUSTOM_PROVIDER_URL(""),
    CUSTOM_PROVIDER_KEY(""),
    CUSTOM_PROVIDER_MODEL(""),
    CUSTOM_PROVIDER_VERIFIED(false),
    CUSTOM_PROVIDER_ENABLED(false),
    CUSTOM_PROVIDER_PROTOCOL("OPENAI"),
    CUSTOM_PROVIDER_AUTH_STYLE("BEARER"),
    CUSTOM_PROVIDER_AUTH_HEADER("")
}
