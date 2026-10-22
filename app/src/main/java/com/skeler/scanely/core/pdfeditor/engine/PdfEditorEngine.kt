package com.skeler.scanely.core.pdfeditor.engine

import android.content.Context
import android.graphics.Bitmap
import com.skeler.scanely.core.pdfeditor.model.EditPlan
import com.skeler.scanely.core.pdfeditor.model.PdfEdit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PdfEditResult {
    data class Saved(val file: File) : PdfEditResult
    data object NothingToDo : PdfEditResult
    data object WrongPassword : PdfEditResult
    data class Failed(val message: String) : PdfEditResult
}

/**
 * Applies a whole [EditPlan] in one pass.
 *
 * The order below is fixed and is not the order the user made the edits in. Page selection has to
 * settle before anything is painted (a marking names the page the user was looking at, so its
 * index must be remapped through the surviving page order first), and encryption has to come last
 * or every later step would need the password again.
 */
@Singleton
class PdfEditorEngine @Inject constructor(
    private val qpdf: QpdfEngine,
    private val overlayComposer: OverlayComposer,
    private val pageReader: PdfPageReader,
    @param:ApplicationContext private val context: Context
) {

    suspend fun apply(
        source: File,
        plan: EditPlan,
        output: File,
        password: String = "",
        stampBitmaps: Map<String, Bitmap> = emptyMap()
    ): PdfEditResult = withContext(Dispatchers.IO) {
        if (plan.isEmpty) return@withContext PdfEditResult.NothingToDo

        val workspace = File(context.cacheDir, "pdf-edit-${System.nanoTime()}")
            .apply { mkdirs() }
        var current = source
        var step = 0
        // Decryption removes the password from the working copy, so later steps run unencrypted.
        var activePassword = password

        fun next(): File = File(workspace, "step-${step++}.pdf")

        try {
            val originalPageCount = pageReader.pageCount(source, password)
                ?: return@withContext PdfEditResult.WrongPassword

            if (plan.structural.any { it is PdfEdit.Decrypt }) {
                val target = next()
                qpdf.decrypt(current, target, activePassword).orReturn { return@withContext it }
                current = target
                activePassword = ""
            }

            val surviving = survivingOrder(plan.structural, originalPageCount)
            if (surviving.isEmpty()) {
                return@withContext PdfEditResult.Failed("Every page would be deleted.")
            }
            if (surviving != List(originalPageCount) { it }) {
                val target = next()
                qpdf.selectPages(
                    current,
                    target,
                    surviving.joinToString(",") { (it + 1).toString() },
                    activePassword
                ).orReturn { return@withContext it }
                current = target
            }

            // Everything past this point addresses pages by their position in `surviving`.
            val remap = surviving.withIndex().associate { (newIndex, old) -> old to newIndex }

            // Markings are authored against the page as the editor showed it: full size, upright.
            // Stamping them before the page is rotated or cropped is what keeps them where the
            // user put them — afterwards, the same coordinates would land off the page.
            current = applyMarkings(
                plan,
                remap,
                current,
                activePassword,
                workspace,
                stampBitmaps,
                ::next
            ) ?: return@withContext failure()
            current = applyCrops(plan, remap, current, activePassword, ::next)
                ?: return@withContext failure()
            current = applyAppends(plan, current, ::next)
                ?: return@withContext failure()
            current = applyRotations(plan, remap, current, activePassword, ::next)
                ?: return@withContext failure()

            plan.structural.filterIsInstance<PdfEdit.Compress>().lastOrNull()?.let {
                val target = next()
                qpdf.compress(current, target, it.settings, activePassword)
                    .orReturn { result -> return@withContext result }
                // A rewrite can come out larger than what it replaced — a file qpdf has already
                // packed optimally, or one whose images resist re-encoding. Compressing must never
                // be the thing that grew the document, so the larger result is simply discarded.
                if (target.length() in 1 until current.length()) current = target
            }

            plan.structural.filterIsInstance<PdfEdit.StripMetadata>().lastOrNull()?.let {
                val target = next()
                qpdf.stripMetadata(current, target, it.stripXmp, activePassword)
                    .orReturn { result -> return@withContext result }
                current = target
            }

            plan.structural.filterIsInstance<PdfEdit.Encrypt>().lastOrNull()?.let {
                val target = next()
                qpdf.encrypt(
                    input = current,
                    output = target,
                    userPassword = it.userPassword,
                    ownerPassword = it.ownerPassword,
                    permissions = it.permissions,
                    openPassword = activePassword
                ).orReturn { result -> return@withContext result }
                current = target
            }

            current.copyTo(output, overwrite = true)
            PdfEditResult.Saved(output)
        } catch (e: Exception) {
            PdfEditResult.Failed(e.message ?: "PDF edit failed.")
        } finally {
            workspace.deleteRecursively()
        }
    }

    /**
     * Folds deletions and reorders into one list of surviving original page indices, in final
     * order, so both become a single qpdf `--pages` selection instead of two rewrites.
     */
    private fun survivingOrder(
        structural: List<PdfEdit.Structural>,
        pageCount: Int
    ): List<Int> {
        var order = List(pageCount) { it }
        structural.forEach { edit ->
            order = when (edit) {
                is PdfEdit.DeletePages -> order.filterNot { it in edit.pageIndices }
                is PdfEdit.ReorderPages -> edit.order.filter { it in order }
                else -> order
            }
        }
        return order
    }

    private suspend fun applyRotations(
        plan: EditPlan,
        remap: Map<Int, Int>,
        input: File,
        password: String,
        next: () -> File
    ): File? {
        // Rotations of the same page compose, so sum them rather than applying one call each.
        val byPage = plan.structural
            .filterIsInstance<PdfEdit.RotatePage>()
            .mapNotNull { edit -> remap[edit.pageIndex]?.let { it to edit.degrees } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, degrees) -> degrees.sum() % 360 }
            .filterValues { it != 0 }

        var current = input
        byPage.entries.groupBy({ it.value }, { it.key }).forEach { (degrees, pages) ->
            val target = next()
            val spec = pages.sorted().joinToString(",") { (it + 1).toString() }
            if (qpdf.rotatePages(current, target, degrees, spec, password) !is QpdfResult.Ok) {
                return null
            }
            current = target
        }
        return current
    }

    private suspend fun applyCrops(
        plan: EditPlan,
        remap: Map<Int, Int>,
        input: File,
        password: String,
        next: () -> File
    ): File? {
        val crops = plan.structural
            .filterIsInstance<PdfEdit.CropPage>()
            .mapNotNull { edit -> remap[edit.pageIndex]?.let { it to edit.box } }
            .toMap()
        if (crops.isEmpty()) return input

        val heights = pageReader.pageGeometries(input, password)
            ?.mapIndexed { index, geometry -> index to geometry.heightPt }
            ?.toMap()
            ?: return null

        val target = next()
        return target.takeIf {
            qpdf.setCropBoxes(input, it, crops, heights, password) is QpdfResult.Ok
        }
    }

    private suspend fun applyAppends(
        plan: EditPlan,
        input: File,
        next: () -> File
    ): File? {
        val appends = plan.structural.filterIsInstance<PdfEdit.AppendDocument>()
        if (appends.isEmpty()) return input

        val sources = buildList {
            add(input)
            appends.forEach { add(File(it.sourceUri)) }
        }
        val target = next()
        return target.takeIf { qpdf.merge(sources, it) is QpdfResult.Ok }
    }

    private suspend fun applyMarkings(
        plan: EditPlan,
        remap: Map<Int, Int>,
        input: File,
        password: String,
        workspace: File,
        stampBitmaps: Map<String, Bitmap>,
        next: () -> File
    ): File? {
        val remapped = plan.markings
            .mapNotNull { (page, marks) -> remap[page]?.let { it to marks } }
            .toMap()
        if (remapped.isEmpty()) return input

        val geometries = pageReader.pageGeometries(input, password) ?: return null
        val stamp = overlayComposer.compose(
            pages = geometries,
            markings = remapped,
            stampBitmaps = stampBitmaps,
            output = File(workspace, "overlay.pdf")
        ) ?: return input

        val target = next()
        return target.takeIf {
            qpdf.overlay(input, stamp, it, password = password) is QpdfResult.Ok
        }
    }

    private fun failure() = PdfEditResult.Failed(Qpdf.nativeLastError().ifBlank { "PDF edit failed." })

    private inline fun QpdfResult.orReturn(onFailure: (PdfEditResult) -> Unit) {
        when (this) {
            is QpdfResult.Ok -> Unit
            is QpdfResult.WrongPassword -> onFailure(PdfEditResult.WrongPassword)
            is QpdfResult.BadUsage -> onFailure(PdfEditResult.Failed(message))
            is QpdfResult.Failed -> onFailure(PdfEditResult.Failed(message))
        }
    }
}
