package com.skeler.scanely.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Runs a save into Downloads or Pictures. Android 10+ writes there through MediaStore with no
 * permission; Android 7–9 needs WRITE_EXTERNAL_STORAGE, so it is asked for at the moment of the
 * first save and the save runs only once it is granted. Without the grant a PDF would land in the
 * app's private cache while the app said "Saved to Downloads", and a gallery save would fail.
 */
@Composable
fun rememberStorageAccess(): (save: () -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val save = pending[0]
        pending[0] = null
        if (granted) {
            save?.invoke()
        } else {
            Toast.makeText(
                context,
                "Allow storage access in Settings to save files on this phone.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    return { save ->
        if (hasStorageAccess(context)) {
            save()
        } else {
            pending[0] = save
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

fun hasStorageAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
