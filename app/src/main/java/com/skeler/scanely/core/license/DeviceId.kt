package com.skeler.scanely.core.license

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.skeler.scanely.settings.data.provider.settingsDataStore
import com.google.firebase.auth.FirebaseAuth
import com.skeler.scanely.core.di.BackendControl
import com.skeler.scanely.core.network.InstallRequest
import com.skeler.scanely.core.network.ScanlyApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal suspend fun cachedInstallId(context: Context, uid: String): String? =
    context.settingsDataStore.data.first()[stringPreferencesKey("license_server_install_$uid")]

/** Maps an installation-local nonce to the server's ID for the signed-in account. */
@Singleton
class DeviceId @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:BackendControl private val api: ScanlyApi,
    private val firebaseAuth: FirebaseAuth
) {
    private val nonceKey = stringPreferencesKey("license_install_nonce")

    suspend fun get(): String {
        val uid = requireNotNull(firebaseAuth.currentUser?.uid)
        val store = context.settingsDataStore
        val installKey = stringPreferencesKey("license_server_install_$uid")
        cachedInstallId(context, uid)?.let { return it }
        val nonce = requireNotNull(store.edit { prefs ->
            if (prefs[nonceKey] == null) prefs[nonceKey] = UUID.randomUUID().toString()
        }[nonceKey])
        val install = api.registerInstall(InstallRequest(nonce)).id
        check(firebaseAuth.currentUser?.uid == uid)
        store.edit { it[installKey] = install }
        return install
    }

    /** Shown in the licence's device list, so the user can tell which slot to free. */
    val displayName: String
        get() {
            val vendor = Build.MANUFACTURER.orEmpty().replaceFirstChar(Char::titlecase)
            val model = Build.MODEL.orEmpty()
            return "$vendor $model".trim().ifBlank { "Android device" }
        }

}
