package com.skeler.scanely.core.license

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.skeler.scanely.settings.data.provider.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The licence key, and only the key. The entitlement it buys is not persisted, for the reason
 * [com.skeler.scanely.core.billing.EntitlementRepository] caches nothing: a stored "you are Pro"
 * goes stale on a renewal, refund, or remote deactivation. The key is a credential, not a claim,
 * so it is safe to keep — it proves nothing on its own.
 */
@Singleton
class LicenseStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val keyPreference = stringPreferencesKey("license_key")

    val key: Flow<String?> = context.settingsDataStore.data
        .map { it[keyPreference]?.takeIf { stored -> stored.isNotBlank() } }

    suspend fun save(key: String) {
        context.settingsDataStore.edit { it[keyPreference] = key }
    }

    suspend fun clear() {
        context.settingsDataStore.edit { it.remove(keyPreference) }
    }
}

/**
 * Canonicalises what the user typed, mirroring `normalizeKey` in `worker/src/paddle.ts`.
 *
 * Separators and case are decoration: ungrouped or lowercased is the same key. Doing it here too
 * means the stored key matches the server's, so the periodic status check compares equal.
 */
fun normalizeLicenseKey(raw: String): String {
    val compact = raw
        .uppercase()
        .replace(NON_KEY_CHARS, "")
        .removePrefix("SCANLY")

    if (compact.length != KEY_GROUPS * KEY_GROUP_LEN) return compact

    val groups = (0 until KEY_GROUPS).map {
        compact.substring(it * KEY_GROUP_LEN, (it + 1) * KEY_GROUP_LEN)
    }
    return "SCANLY-${groups.joinToString("-")}"
}

private val NON_KEY_CHARS = Regex("[^0-9A-Z]")
private const val KEY_GROUPS = 4
private const val KEY_GROUP_LEN = 5
