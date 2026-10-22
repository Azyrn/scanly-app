package com.skeler.scanely.ui.screens

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.skeler.scanely.core.scan.DocumentScannerManager
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.viewmodel.DocumentScanViewModel

/** Opens the ML Kit scanner straight from Home; degoogled devices fall back to the in-app camera. */
@Composable
fun rememberDocumentScanStarter(): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current as ComponentActivity
    val navigator = LocalNavigator.current
    val vm: DocumentScanViewModel = hiltViewModel(activity)

    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages?.map { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) {
                vm.loadPages(uris)
                navigator.go(Destination.ScanReview)
            }
        }
    }

    return {
        if (DocumentScannerManager.isScannerAvailable(context)) {
            DocumentScannerManager.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                .addOnFailureListener {
                    navigator.go(Destination.DocumentCapture)
                }
        } else {
            navigator.go(Destination.DocumentCapture)
        }
    }
}
