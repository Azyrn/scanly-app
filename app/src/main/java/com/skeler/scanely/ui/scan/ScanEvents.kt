package com.skeler.scanely.ui.scan

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.viewmodel.DocumentScanViewModel
import com.skeler.scanely.ui.viewmodel.IdCardViewModel

/**
 * Acts on the session's one-shot [event] from whichever scan screen is on top: a toast, the hand-over of a
 * finished session to review or the ID copy, or (crop only, [onBackToCamera]) the way back to the camera.
 */
@Composable
fun ScanEventHandler(vm: ScanSessionViewModel, event: ScanEvent?, onBackToCamera: (() -> Unit)? = null) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current as ComponentActivity
    val navigator = LocalNavigator.current
    val review: DocumentScanViewModel = hiltViewModel(activity)
    val idCopy: IdCardViewModel = hiltViewModel(activity)
    val backToCamera by rememberUpdatedState(onBackToCamera)
    LaunchedEffect(event) {
        when (event) {
            null -> Unit
            is ScanEvent.Message -> {
                vm.consumeEvent()
                Toast.makeText(context, resources.getString(event.text), Toast.LENGTH_SHORT).show()
            }
            ScanEvent.BackToCamera -> backToCamera?.let {
                vm.consumeEvent()
                it()
            }
            is ScanEvent.Finished -> {
                vm.consumeEvent()
                when (val result = event.result) {
                    is ScanResult.Review -> {
                        review.loadPages(result.pages, result.filter)
                        navigator.goInPlaceOf(Destination.ScanReview, Destination.ScanCamera::class)
                    }
                    is ScanResult.IdCopy -> {
                        idCopy.start(result.kind, result.sides)
                        navigator.goInPlaceOf(Destination.IdCard, Destination.ScanCamera::class)
                    }
                    is ScanResult.ReplaceSide -> {
                        idCopy.replaceSide(result.index, result.side)
                        navigator.backPast(Destination.ScanCamera::class)
                    }
                }
            }
        }
    }
}
