package com.skeler.scanely.core.transcribe

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.transcriptionDataStore by preferencesDataStore(name = "transcription")

/**
 * The options a user sets once and expects remembered: the language and the key-term
 * glossary — names and jargon that bias recognition, up to xAI's 100.
 *
 * The language is stored only once the user picks one. Until then it is null and
 * [TranscribeViewModel] offers the device's own language when the transcriber supports it,
 * because auto-detect with formatting costs a second pass.
 */
@Singleton
class TranscriptionPrefs @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val store get() = context.transcriptionDataStore
    private val listSerializer = ListSerializer(String.serializer())

    data class Saved(
        /** Null: never chosen. [AUTO]: explicitly auto-detect. */
        val language: String?,
        val keyTerms: List<String>
    )

    val saved: Flow<Saved> = store.data.map { p ->
        Saved(
            language = p[LANGUAGE],
            keyTerms = p[KEY_TERMS]?.let {
                runCatching { Json.decodeFromString(listSerializer, it) }.getOrNull()
            }.orEmpty()
        )
    }

    suspend fun setLanguage(code: String) = store.edit { it[LANGUAGE] = code }
    suspend fun setKeyTerms(terms: List<String>) =
        store.edit { it[KEY_TERMS] = Json.encodeToString(listSerializer, terms) }

    companion object {
        const val AUTO = "auto"
        private val LANGUAGE = stringPreferencesKey("language")
        private val KEY_TERMS = stringPreferencesKey("key_terms")

        /**
         * Normalises a glossary entry the way the server will: control characters out, runs of
         * whitespace collapsed. Returns null for what the server would refuse or ignore.
         */
        fun cleanKeyTerm(raw: String): String? {
            val term = raw.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").replace(Regex("\\s+"), " ").trim()
            return term.takeIf { it.isNotEmpty() && it.length <= TranscribeOptions.MAX_KEY_TERM_CHARS }
        }
    }
}
