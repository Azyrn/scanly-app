package com.skeler.scanely.core.common

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own UI language, independent of the system one.
 *
 * Android 13+ keeps the choice itself (LocaleManager, also editable from system settings).
 * Older versions have no per-app locale, so the tag is stored here and every Activity and the
 * Application wrap their base context with it.
 */
object AppLanguage {

    /** BCP 47 tag to the language's own name. An empty tag follows the system. */
    val supported: List<Pair<String, String>> = listOf(
        "en" to "English",
        "ar" to "العربية",
        "zh-CN" to "中文（简体）",
        "fr" to "Français",
        "de" to "Deutsch",
        "hi" to "हिन्दी",
        "ja" to "日本語",
        "ko" to "한국어",
        "pt-BR" to "Português (Brasil)",
        "ru" to "Русский",
        "es" to "Español",
        "tr" to "Türkçe",
        "uk" to "Українська"
    )

    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    /** The chosen tag, or "" when following the system. */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) "" else locales[0].toLanguageTag()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "").orEmpty()
        }

    /** Applies [tag] ("" = system). Android 13+ recreates activities itself; older ones are recreated here. */
    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, tag).commit()
            activity.recreate()
        }
    }

    /** Wraps [base] in the chosen locale on Android 12 and older; a no-op otherwise. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** The display name for [tag], matching on language when the region differs. */
    fun displayName(tag: String): String? =
        supported.firstOrNull { it.first.equals(tag, ignoreCase = true) }?.second
            ?: supported.firstOrNull {
                Locale.forLanguageTag(
                    it.first
                ).language == Locale.forLanguageTag(tag).language
            }?.second
}
