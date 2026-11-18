package com.skeler.scanely.core.pdfeditor.engine

import android.content.Context
import com.skeler.scanely.core.pdftools.ToolWorkspace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File

class EditScratchTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `successive rewrites discard the previous step and keep the source`() {
        val workspace = folder.newFolder()
        val source = folder.newFile()
        val run = EditRun(workspace, source, "")
        val write = { _: File, output: File -> output.writeText("step"); QpdfResult.Ok }
        run.rewrite(write)
        val previous = run.current
        run.rewrite(write)
        assertFalse(previous.exists())
        assertTrue(run.current.isFile)
        assertTrue(source.isFile)
    }

    @Test fun `a new process reclaims abandoned pre-upgrade files`() {
        val cache = folder.newFolder()
        val edit = File(cache, "pdf-edit-123").apply { mkdir(); resolve("step-0.pdf").writeText("edit") }
        val glyph = File.createTempFile("glyph-ids", ".pdf", cache)
        val unrelated = File(cache, "statement.pdf").apply { writeText("keep") }
        val context = mock(Context::class.java)
        `when`(context.cacheDir).thenReturn(cache)
        ToolWorkspace(context)
        assertFalse(edit.exists())
        assertFalse(glyph.exists())
        assertTrue(unrelated.isFile)
    }
}
