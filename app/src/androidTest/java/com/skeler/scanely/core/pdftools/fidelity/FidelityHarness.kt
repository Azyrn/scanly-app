package com.skeler.scanely.core.pdftools.fidelity

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skeler.scanely.core.ocr.paddle.PaddleLineReader
import com.skeler.scanely.core.ocr.paddle.PaddleModelStore
import com.skeler.scanely.core.ocr.paddle.PaddleOcrEngine
import okhttp3.OkHttpClient
import com.skeler.scanely.core.pdfeditor.engine.CompressionLevel
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import com.skeler.scanely.core.pdfeditor.engine.QpdfEngine
import com.skeler.scanely.core.pdfeditor.engine.PdfiumLayer
import com.skeler.scanely.core.pdftools.ImportedFile
import com.skeler.scanely.core.pdftools.PdfToolEngine
import com.skeler.scanely.core.pdftools.ProbeResult
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import com.skeler.scanely.core.pdftools.ToolWorkspace
import com.skeler.scanely.core.pdftools.office2pdf.docx.DocxToPdfJob
import com.skeler.scanely.core.pdftools.office2pdf.pptx.PptxToPdfJob
import com.skeler.scanely.core.pdftools.office2pdf.xlsx.XlsxToPdfJob
import com.skeler.scanely.core.pdftools.pdf2office.CloudPageReader
import com.skeler.scanely.core.pdftools.pdf2office.ModelTables
import com.skeler.scanely.core.pdftools.pdf2office.OfficePage
import com.skeler.scanely.core.pdftools.pdf2office.OfficePageSource
import com.skeler.scanely.core.pdftools.pdf2office.PdfToExcelJob
import com.skeler.scanely.core.pdftools.pdf2office.PdfToPowerPointJob
import com.skeler.scanely.core.pdftools.pdf2office.PdfToWordJob
import com.skeler.scanely.core.pdftools.pdf2office.confirmTables
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPageSource
import com.skeler.scanely.core.pdftools.pdf2office.exact.OcrBlock
import com.skeler.scanely.core.pdftools.repair.RepairJob
import com.skeler.scanely.core.pdftools.stamp.StampRunner
import com.skeler.scanely.core.pdftools.stamp.WatermarkJob
import com.skeler.scanely.core.pdftools.stamp.WatermarkMark
import com.skeler.scanely.core.pdftools.stamp.WatermarkOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val PROTECT_PASSWORD = "Fidelity-Pass-1"
private const val OCR_FIXTURE_FIELDS = 5

/**
 * Driver for scripts/fidelity/run.py, not a regression test: it only runs when the script has
 * pushed a corpus and a job list into the app's cache (`cache/fidelity/jobs.txt`, one
 * "<tool> <file>" per line). Each job calls the real job class, the same one the tool screen runs,
 * and appends tool, file, wall time, peak PSS, progress-callback count and the outcome to
 * `results.tsv`, so the host can score the outputs page by page.
 */
@RunWith(AndroidJUnit4::class)
class FidelityHarness {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = File(context.cacheDir, "fidelity")
    private val reader = PdfPageReader(context)
    private val workspace = ToolWorkspace(context)
    private val qpdf = QpdfEngine()
    private val engine = PdfToolEngine(qpdf, reader, workspace)
    // One engine for every job, as the app's singleton: a new one per job held every job's sessions.
    private val ocr = PaddleOcrEngine(context, PaddleModelStore(context, dagger.Lazy { OkHttpClient() }))
    private val source = OfficePageSource(reader, ModelTables(reader, ocr))

    @Test
    fun runJobs() = runBlocking {
        val jobs = File(base, "jobs.txt")
        assumeTrue("no fidelity job list pushed", jobs.isFile)
        val out = File(base, "out").apply { mkdirs() }
        val log = File(base, "results.tsv")
        jobs.readLines().filter { it.isNotBlank() }.forEach { line ->
            val (tool, name) = line.split(' ', limit = 2)
            val run = File(out, "$tool-${name.substringBeforeLast('.')}").apply { deleteRecursively(); mkdirs() }
            log.appendText(runOne(tool, File(base, "in/$name"), run) + "\n")
        }
    }

    private suspend fun runOne(tool: String, file: File, run: File): String {
        val imported = ImportedFile(file, file.name, file.length())
        val progressCalls = AtomicInteger()
        val peak = AtomicLong()
        val sampling = AtomicBoolean(true)
        val sampler = Thread {
            while (sampling.get()) {
                peak.accumulateAndGet(Debug.getPss(), ::maxOf)
                Thread.sleep(SAMPLE_MS)
            }
        }.apply { start() }
        val started = System.nanoTime()
        val outcome = try {
            if (tool == "tables") {
                tablesReport(open(imported), File(run, "tables.tsv"))
            } else if (tool == "dump") {
                pageDump(file, run)
            } else {
                describe(execute(tool, imported, run) { progressCalls.incrementAndGet() })
            }
        } catch (e: ToolException) {
            "FAIL\t${e.failure}\t"
        } catch (e: Exception) {
            "FAIL\t${e.javaClass.simpleName}: ${e.message}\t"
        }
        val ms = (System.nanoTime() - started) / NANOS_PER_MILLI
        sampling.set(false)
        sampler.join()
        return "$tool\t${file.name}\t$ms\t${peak.get() / KB_PER_MB}\t${progressCalls.get()}\t$outcome"
    }

    /**
     * The phone's pdfium reading of every page, in hostdump.py's format (characters, rules, Arabic
     * font programs; no pictures), so hostconv can convert exactly what the phone reads and a diff
     * against the host's dump shows where the two pdfium builds differ.
     */
    private fun pageDump(file: File, run: File): String {
        val layer = PdfiumLayer.open(context, file) ?: return "FAIL\tunreadable\t"
        layer.use {
            var pages = 0
            while (true) {
                val page = layer.readPage(pages) ?: break
                val fonts = page.fontPrograms.mapIndexed { i, data ->
                    "font$pages-$i.ttf".also { File(run, it).writeBytes(data) }
                }
                File(run, "p$pages.txt").bufferedWriter().use { out ->
                    out.write("${page.widthPt} ${page.heightPt} ${page.chars.size} 0 0\n")
                    page.chars.forEach { c ->
                        val fields = listOf(
                            c.code, c.originX, c.originY, c.left, c.bottom, c.right, c.top, c.size, c.matrixA,
                            c.matrixB, c.matrixC, c.matrixD, c.font, c.fontFlags, c.weight, c.argb.toLong() and 0xFFFFFFFFL,
                            c.flags, c.renderMode, c.inkLeft, c.inkBottom, c.inkRight, c.inkTop, c.fontProgram
                        )
                        out.write(fields.joinToString("\t") + "\n")
                    }
                    page.rules.forEach { out.write("rule\t${it.left}\t${it.bottom}\t${it.right}\t${it.top}\n") }
                    fonts.forEach { out.write("font\t$it\n") }
                }
                pages++
            }
            File(run, "pages").writeText("$pages")
            return "OK\tdump\t$pages pages"
        }
    }

    private suspend fun execute(tool: String, imported: ImportedFile, run: File, onProgress: (Float?) -> Unit): ToolResult {
        fun input(document: ToolDocument) = ToolJobInput(listOf(document), listOf(imported), run, onProgress)
        return when (tool) {
            "word" -> PdfToWordJob(context, source, workspace, PaddleLineReader(ocr), cloudFixture(imported, run))
                .convert(input(open(imported)))
            "ppt" -> PdfToPowerPointJob(context, source, workspace, PaddleLineReader(ocr)).convert(input(open(imported)))
            "excel" -> PdfToExcelJob(context, source, workspace).convert(input(open(imported)))
            "docx2pdf" -> DocxToPdfJob(context, workspace).convert(imported, run, onProgress)
            "pptx2pdf" -> PptxToPdfJob(workspace, qpdf).convert(imported, run, onProgress)
            "xlsx2pdf" -> XlsxToPdfJob(context, workspace).convert(imported, run, onProgress)
            "compress-lossless" -> engine.compress(open(imported), CompressionLevel.LOSSLESS, run)
            "compress-balanced" -> engine.compress(open(imported), CompressionLevel.BALANCED, run)
            "compress-smallest" -> engine.compress(open(imported), CompressionLevel.SMALLEST, run)
            "protect" -> engine.protect(open(imported), PROTECT_PASSWORD, run)
            "unlock" -> unlockRoundTrip(imported, run)
            "repair" -> RepairJob(context, reader, workspace).repair(ToolJobInput(emptyList(), listOf(imported), run, onProgress))
            "watermark" -> WatermarkJob(context, StampRunner(reader, workspace))
                .watermark(input(open(imported)), WatermarkOptions(WatermarkMark.Text("CONFIDENTIEL سري")))
            else -> error("unknown tool $tool")
        }
    }

    /**
     * The cloud OCR the host read for [imported] (`ocrblocks/<name>/<page>.tsv`, pushed by run.py
     * from the host loop's cached Mistral responses), so a run scores the fallback without a signed-in
     * app or a billed call. The pages asked for are written to `cloud-pages.txt` in [run].
     */
    private fun cloudFixture(imported: ImportedFile, run: File) = CloudPageReader { _, pages, _ ->
        File(run, "cloud-pages.txt").writeText(pages.joinToString(" "))
        val dir = File(base, "ocrblocks/${imported.displayName.substringBeforeLast('.')}")
        pages.associateWith { page ->
            File(dir, "$page.tsv").takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
                val f = line.split('\t', limit = OCR_FIXTURE_FIELDS)
                if (f.size < OCR_FIXTURE_FIELDS) null else OcrBlock(f[0].toFloat(), f[1].toFloat(), f[2].toFloat(), f[3].toFloat(), f[4])
            }
        }
    }

    /**
     * Protects [imported] with a known password, then unlocks that copy the way the tool does: the
     * probe must refuse it without the password and open it with it, and the unlocked output must
     * open with no password at all.
     */
    private suspend fun unlockRoundTrip(imported: ImportedFile, run: File): ToolResult {
        val locked = engine.protect(open(imported), PROTECT_PASSWORD, run).outputs.single()
        val lockedFile = ImportedFile(locked.file, locked.fileName, locked.sizeBytes)
        check(engine.probe(lockedFile) == ProbeResult.NeedsPassword) { "protected copy opened without a password" }
        val opened = engine.probe(lockedFile, PROTECT_PASSWORD) as ProbeResult.Opened
        check(opened.document.isEncrypted) { "protected copy was not reported as encrypted" }
        val result = engine.unlock(opened.document, run)
        val unlocked = result.outputs.single()
        val reopened = engine.probe(ImportedFile(unlocked.file, unlocked.fileName, unlocked.sizeBytes))
        check(reopened is ProbeResult.Opened && !reopened.document.isEncrypted) { "unlocked copy still needs a password" }
        check(reopened.document.pageCount == opened.document.pageCount) { "unlock changed the page count" }
        return result
    }

    private suspend fun open(imported: ImportedFile): ToolDocument =
        (engine.probe(imported) as ProbeResult.Opened).document

    /**
     * The phone's side of hostconv's table report: per page, the layout model's TABLE regions, then
     * each table the flowing (Excel) and exact (Word/PowerPoint) paths keep, after the model check —
     * "page<TAB>path<TAB>RxC<TAB>first row". Diff it against the host report to find where they part.
     */
    private suspend fun tablesReport(document: ToolDocument, out: File): String {
        val scratch = File(out.parentFile, "scratch").apply { mkdirs() }
        var tables = 0
        out.bufferedWriter().use { w ->
            ExactPageSource.open(context, document.file, document.password, scratch).use { exact ->
                for (index in 0 until document.pageCount) {
                    val regions = source.tableRegions(document, index)
                    w.write("$index\tmodel\t${regions?.joinToString(" ") { "%.0f,%.0f,%.0f,%.0f".format(it.left, it.top, it.right, it.bottom) }}\n")
                    val flowing = (source.read(document, index) as? OfficePage.Text)?.layout?.tables.orEmpty()
                    flowing.forEach { w.write("$index\tflowing\t${it.rows.size}x${it.rows.first().size}\t${it.rows.first().joinToString(" | ").take(80)}\n") }
                    val page = exact?.read(index, scratch)?.let { confirmTables(source, document, exact, index, it) }
                    page?.layout?.tables.orEmpty().forEach { t ->
                        w.write("$index\texact\t${t.rows.size}x${t.rows.first().cells.size}\t${t.rows.first().cells.joinToString(" | ") { it.text }.take(80)}\n")
                    }
                    tables += flowing.size
                }
            }
        }
        return "OK\t${out.name}:${out.length()}\tflowing=$tables"
    }

    private fun describe(result: ToolResult): String {
        val notes = result.notes.joinToString(",") {
            context.resources.getResourceEntryName(it.res) + if (it.plural) "=${it.count}" else ""
        }
        return "OK\t" + result.outputs.joinToString(",") { "${it.fileName}:${it.sizeBytes}" } + "\t$notes"
    }

    private companion object {
        const val SAMPLE_MS = 150L
        const val NANOS_PER_MILLI = 1_000_000L
        const val KB_PER_MB = 1024
    }
}
