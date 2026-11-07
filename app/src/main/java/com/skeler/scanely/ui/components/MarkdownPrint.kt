package com.skeler.scanely.ui.components

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.skeler.scanely.core.text.MarkdownHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Prints through the system print dialog (which also offers Save as PDF).
 * [asMarkdown] renders the text's Markdown structure; otherwise it prints verbatim.
 */
@Composable
fun rememberMarkdownPrinter(): (text: String, asMarkdown: Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        {
                text, asMarkdown ->
            scope.launch { print(context, text, asMarkdown) }
        }
    }
}

private suspend fun print(context: Context, text: String, asMarkdown: Boolean) {
    if (text.isBlank()) return
    val html = withContext(Dispatchers.Default) {
        if (asMarkdown) MarkdownHtml.render(text) else MarkdownHtml.renderPlain(text)
    }
    val jobName = "Scanly_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    // Nothing else holds the WebView while it loads, so this coroutine keeps it (as a local) until
    // the page is handed to the print framework, whose adapter then holds it until onFinish.
    val webView = WebView(context)
    suspendCancellableCoroutine { done ->
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!done.isActive) return
                val printManager = context.getSystemService(PrintManager::class.java)
                printManager.print(
                    jobName,
                    releasingAdapter(view.createPrintDocumentAdapter(jobName), view),
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .build()
                )
                done.resume(Unit)
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}

/** Frees the WebView (and the Activity it references) once the print job is done with it. */
private fun releasingAdapter(delegate: PrintDocumentAdapter, view: WebView) =
    object : PrintDocumentAdapter() {
        override fun onStart() = delegate.onStart()

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?
        ) = delegate.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?
        ) = delegate.onWrite(pages, destination, cancellationSignal, callback)

        override fun onFinish() {
            delegate.onFinish()
            view.destroy()
        }
    }
