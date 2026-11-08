package com.skeler.scanely.core.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import kotlinx.coroutines.CancellationException

private const val TAG = "PlayUpdate"

/** The Play listing's id; debug builds carry a ".debug" suffix that has no listing. */
private const val PLAY_LISTING_ID = "com.skeler.scanely"

/**
 * True while Play has a newer version than the installed one. Asked again on every resume, so
 * after the user updates (or comes back from the Play page) it turns false on its own.
 */
@Composable
fun rememberPlayUpdateAvailable(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var available by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycle) {
        val manager = AppUpdateManagerFactory.create(context.applicationContext)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            available = try {
                manager.requestAppUpdateInfo().updateAvailability() ==
                    UpdateAvailability.UPDATE_AVAILABLE
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Not installed from Play, no Play Store, or offline: there is nothing to offer.
                Log.w(TAG, "Play update check failed", e)
                false
            }
        }
    }
    return available
}

/** Opens Scanly's Play listing in the Play Store app, or in the browser when there is none. */
fun Context.openStoreListing() {
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=$PLAY_LISTING_ID".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(market)
    } catch (_: ActivityNotFoundException) {
        startActivity(
            Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$PLAY_LISTING_ID".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
