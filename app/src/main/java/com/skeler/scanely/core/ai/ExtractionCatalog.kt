package com.skeler.scanely.core.ai

import com.skeler.scanely.core.ai.custom.ExtractionJob
import com.skeler.scanely.core.billing.EntitlementRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns an extraction preset id into the job a custom endpoint can actually run.
 *
 * `/v1/ocr` only ever needs the id — it holds the schemas itself. An endpoint the user configured
 * never reaches that route, so the prompt and schema have to travel with the request, and they
 * arrive from `/v1/me` rather than being written here. Serving them is what lets a preset added to
 * the Worker work on both paths without an app release.
 */
@Singleton
internal class ExtractionCatalog @Inject constructor(
    private val entitlementRepository: EntitlementRepository
) {
    fun jobFor(presetId: String?): ExtractionJob? {
        if (presetId.isNullOrBlank()) return null

        val preset = entitlementRepository.entitlement.value
            ?.extractionPresets
            ?.firstOrNull { it.id == presetId }
            ?: return null

        val schema = preset.schema ?: return null
        if (preset.prompt.isBlank()) return null

        return ExtractionJob(
            label = preset.label,
            prompt = preset.prompt,
            schema = schema.toString()
        )
    }
}
