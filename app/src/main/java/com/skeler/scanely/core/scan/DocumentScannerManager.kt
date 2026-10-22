package com.skeler.scanely.core.scan

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning

object DocumentScannerManager {

    private const val PAGE_LIMIT = 20

    /** GMS module; false on degoogled devices — route to CameraX fallback. */
    fun isScannerAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** SCANNER_MODE_FULL + JPEG so we can re-filter pages ourselves. */
    fun getStartScanIntent(activity: Activity): Task<IntentSender> {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(PAGE_LIMIT)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        return GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
    }

    /** One PDF of up to [pageLimit] pages, for the document chat to rebuild. */
    fun getPdfScanIntent(activity: Activity, pageLimit: Int): Task<IntentSender> {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(pageLimit)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        return GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
    }
}
