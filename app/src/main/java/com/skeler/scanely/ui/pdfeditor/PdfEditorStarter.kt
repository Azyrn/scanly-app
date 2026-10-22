package com.skeler.scanely.ui.pdfeditor

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.skeler.scanely.navigation.Destination
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.components.OpenContent

/**
 * Picks a PDF and opens the editor on it. A non-null start tool is handed to the editor, which
 * goes straight to the first page with that tool armed once the document has loaded.
 *
 * No persistable permission is taken: the session copies the file into its own workspace as its
 * first act, and holding a long-lived grant on a document the user only meant to open once is more
 * access than the feature needs.
 */
@Composable
fun rememberPdfEditorStarter(): (startTool: EditorTool?) -> Unit {
    val activity = LocalActivity.current as ComponentActivity
    val navigator = LocalNavigator.current
    val vm: PdfStudioViewModel = hiltViewModel(activity)

    // The picker result arrives in a callback registered once, so the tool chosen at launch time
    // is carried across in state rather than captured by the callback.
    var startTool by rememberSaveable { mutableStateOf<EditorTool?>(null) }

    val picker = rememberLauncherForActivityResult(OpenContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.open(uri, startTool)
        navigator.go(Destination.PdfStudio)
    }

    return { tool ->
        startTool = tool
        picker.launch(arrayOf("application/pdf"))
    }
}
