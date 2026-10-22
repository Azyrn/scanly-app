package com.skeler.scanely.core.license

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.skeler.scanely.settings.data.provider.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device identity a licence activation binds to.
 *
 * `ANDROID_ID` over a generated UUID because it survives reinstall: a key allows three devices, and
 * burning a slot on every data clear would send those users to support. It is scoped to signing key
 * + user + device, so it is not a cross-app identifier.
 *
 * The UUID fallback covers null and the `9774d56d682e549c` a batch of 2.2-era devices shipped with.
 * Those installs do burn a slot on reinstall, which beats colliding with every other such device.
 */
@Singleton
class DeviceId @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val fallbackKey = stringPreferencesKey("license_device_id")

    @Volatile
    private var cached: String? = null

    suspend fun get(): String = cached ?: resolve().also { cached = it }

    @SuppressLint("HardwareIds")
    private suspend fun resolve(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()

        if (!androidId.isNullOrBlank() && androidId != KNOWN_BAD_ANDROID_ID) return androidId

        val store = context.settingsDataStore
        store.data.first()[fallbackKey]?.takeIf { it.isNotBlank() }?.let { return it }

        val generated = UUID.randomUUID().toString()
        store.edit { it[fallbackKey] = generated }
        return generated
    }

    /** Shown in the licence's device list, so the user can tell which slot to free. */
    val displayName: String
        get() {
            val vendor = Build.MANUFACTURER.orEmpty().replaceFirstChar(Char::titlecase)
            val model = Build.MODEL.orEmpty()
            return "$vendor $model".trim().ifBlank { "Android device" }
        }

    private companion object {
        const val KNOWN_BAD_ANDROID_ID = "9774d56d682e549c"
    }
}
