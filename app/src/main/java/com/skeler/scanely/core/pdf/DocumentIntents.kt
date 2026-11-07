package com.skeler.scanely.core.pdf

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.skeler.scanely.R

/** Opening and sharing exported files through other apps; every entry point never throws. */
object DocumentIntents {

    private const val TAG = "ScanExporter"

    /** Never throws; false if no viewer or launch rejected. */
    fun openPdf(context: Context, uri: Uri): Boolean {
        return openDocument(context, uri, ScanExporter.PDF_MIME_TYPE, context.getString(R.string.pdf_open_with))
    }

    /** Never throws; false if no app can open the exported file. */
    fun openDocument(context: Context, uri: Uri, mimeType: String, chooserTitle: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            clipData = ClipData.newRawUri("Export", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!hasHandler(context, view)) {
            Log.w(TAG, "No activity can view $mimeType")
            return false
        }

        grantToResolvers(context, view, uri)

        // The bare VIEW intent first: the system raises its own "Open with" picker when more than
        // one app matches. ACTION_CHOOSER is not resolvable on every ROM — where the intent
        // resolver has been stripped it throws ActivityNotFoundException even though the VIEW
        // intent underneath resolves — so the chooser is the fallback, not the default.
        return context.launch(view) || context.launch(
            Intent.createChooser(view, chooserTitle).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /** Never throws; false if no handler or launch rejected. */
    fun shareDocument(context: Context, uri: Uri, mimeType: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Export", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        grantToResolvers(context, send, uri)
        if (context !is Activity) send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Sharing genuinely wants the picker, so the chooser leads here — but it falls back to the
        // bare SEND intent for the ROMs where ACTION_CHOOSER cannot be resolved at all.
        val chooser = Intent.createChooser(send, context.getString(R.string.pdf_share_chooser)).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return context.launch(chooser) || context.launch(send)
    }

    /**
     * [shareDocument] for several files at once. [mimeType] is the type they share, or a wildcard
     * such as "image/" + "*" when they differ. Never throws; false if empty or the launch is rejected.
     */
    fun shareDocuments(context: Context, uris: List<Uri>, mimeType: String): Boolean {
        if (uris.isEmpty()) return false
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            // Every uri goes in the clip too: the grant flag only covers what the clip names.
            clipData = ClipData.newRawUri("Export", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        uris.forEach { grantToResolvers(context, send, it) }
        return launchChooserOrSend(context, send)
    }

    /** Launches [send] through the share chooser, falling back to the bare intent if that is rejected. */
    private fun launchChooserOrSend(context: Context, send: Intent): Boolean {
        if (context !is Activity) send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(send, context.getString(R.string.pdf_share_chooser)).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return context.launch(chooser) || context.launch(send)
    }

    private fun Context.launch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Unable to launch ${intent.action}", e)
        false
    }

    private fun hasHandler(context: Context, intent: Intent): Boolean =
        context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .isNotEmpty()

    private fun grantToResolvers(context: Context, intent: Intent, uri: Uri) {
        val resolvers = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in resolvers) {
            runCatching {
                context.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }
}
