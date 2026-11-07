package com.skeler.scanely.core.pdfeditor.engine

import android.content.Context
import android.graphics.Bitmap
import com.skeler.scanely.R
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

        try {
            val originalPageCount = pageReader.pageCount(source, password)
                ?: return@withContext PdfEditResult.WrongPassword
            val run = EditRun(workspace, source, password)

            val failure = runSteps(plan, run, originalPageCount, stampBitmaps)
            if (failure != null) return@withContext failure

            run.current.copyTo(output, overwrite = true)
            PdfEditResult.Saved(output)
        } catch (e: Exception) {
            PdfEditResult.Failed(e.message ?: context.getString(R.string.pe_err_edit_failed))
        } finally {
            workspace.deleteRecursively()
        }
    }

    /** The steps in their fixed order, stopping at the first that fails. */
    private suspend fun runSteps(
        plan: EditPlan,
        run: EditRun,
        originalPageCount: Int,
        stampBitmaps: Map<String, Bitmap>
    ): PdfEditResult? = decrypt(plan, run)
        ?: selectPages(plan, run, originalPageCount)
        ?: editPages(plan, run, stampBitmaps)
        ?: compress(plan, run)
        ?: stripMetadata(plan, run)
        ?: encrypt(plan, run)

    // Each step below returns null when it succeeded (or had nothing to do), else the result to
    // stop with.

    /** Decryption removes the password from the working copy, so later steps run unencrypted. */
    private suspend fun decrypt(plan: EditPlan, run: EditRun): PdfEditResult? {
        if (plan.structural.none { it is PdfEdit.Decrypt }) return null
        return run.rewrite { input, target -> qpdf.decrypt(input, target, run.password) }
            .also { if (it == null) run.password = "" }
    }

    /** Deletions and reorders as one page selection; sets [EditRun.remap] for the steps after. */
    private suspend fun selectPages(plan: EditPlan, run: EditRun, originalPageCount: Int): PdfEditResult? {
        val surviving = survivingOrder(plan.structural, originalPageCount)
        if (surviving.isEmpty()) return PdfEditResult.Failed(context.getString(R.string.pe_err_all_deleted))

        // Everything past this point addresses pages by their position in `surviving`.
        run.remap = surviving.withIndex().associate { (newIndex, old) -> old to newIndex }
        if (surviving == List(originalPageCount) { it }) return null
        return run.rewrite { input, target ->
            qpdf.selectPages(input, target, surviving.joinToString(",") { (it + 1).toString() }, run.password)
        }
    }

    private suspend fun editPages(plan: EditPlan, run: EditRun, stampBitmaps: Map<String, Bitmap>): PdfEditResult? {
        // Markings are authored against the page as the editor showed it: full size, upright.
        // Stamping them before the page is rotated or cropped is what keeps them where the
        // user put them — afterwards, the same coordinates would land off the page.
        val edited = applyMarkings(plan, run.remap, run.current, run.password, run.workspace, stampBitmaps, run::next)
            ?.let { applyCrops(plan, run.remap, it, run.password, run::next) }
            ?.let { applyAppends(plan, it, run::next) }
            ?.let { applyRotations(plan, run.remap, it, run.password, run::next) }
            ?: return failure()
        run.current = edited
        return null
    }

    private suspend fun compress(plan: EditPlan, run: EditRun): PdfEditResult? {
        val edit = plan.structural.filterIsInstance<PdfEdit.Compress>().lastOrNull() ?: return null
        val target = run.next()
        qpdf.compress(run.current, target, edit.settings, run.password).failure()?.let { return it }
        // A rewrite can come out larger than what it replaced — a file qpdf has already
        // packed optimally, or one whose images resist re-encoding. Compressing must never
        // be the thing that grew the document, so the larger result is simply discarded.
        if (target.length() in 1 until run.current.length()) run.current = target
        return null
    }

    private suspend fun stripMetadata(plan: EditPlan, run: EditRun): PdfEditResult? {
        val edit = plan.structural.filterIsInstance<PdfEdit.StripMetadata>().lastOrNull() ?: return null
        return run.rewrite { input, target -> qpdf.stripMetadata(input, target, edit.stripXmp, run.password) }
    }

    private suspend fun encrypt(plan: EditPlan, run: EditRun): PdfEditResult? {
        val edit = plan.structural.filterIsInstance<PdfEdit.Encrypt>().lastOrNull() ?: return null
        return run.rewrite { input, target ->
            qpdf.encrypt(
                input = input,
                output = target,
                userPassword = edit.userPassword,
                ownerPassword = edit.ownerPassword,
                permissions = edit.permissions,
                openPassword = run.password
            )
        }
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

    private fun failure() = PdfEditResult.Failed(
        Qpdf.nativeLastError().ifBlank { context.getString(R.string.pe_err_edit_failed) }
    )
}

/** The working copy as the edit steps rewrite it, one numbered file per step. */
private class EditRun(val workspace: File, var current: File, var password: String) {
    private var step = 0

    /** Original page index to its index after page selection; identity until then. */
    var remap: Map<Int, Int> = emptyMap()

    fun next(): File = File(workspace, "step-${step++}.pdf")

    /** Runs one qpdf rewrite of [current] into the next step file, which becomes current on success. */
    inline fun rewrite(op: (input: File, target: File) -> QpdfResult): PdfEditResult? {
        val target = next()
        val failure = op(current, target).failure()
        if (failure == null) current = target
        return failure
    }
}

private fun QpdfResult.failure(): PdfEditResult? = when (this) {
    is QpdfResult.Ok -> null
    is QpdfResult.WrongPassword -> PdfEditResult.WrongPassword
    is QpdfResult.BadUsage -> PdfEditResult.Failed(message)
    is QpdfResult.Failed -> PdfEditResult.Failed(message)
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
