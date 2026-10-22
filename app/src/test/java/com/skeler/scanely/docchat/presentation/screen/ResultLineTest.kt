package com.skeler.scanely.docchat.presentation.screen

import com.skeler.scanely.docchat.data.AgentRow
import com.skeler.scanely.docchat.data.RowCut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultLineTest {
    private fun tool(id: String, state: String = AgentRow.DONE, parent: String? = null, text: String? = null, lines: Int? = null) =
        AgentRow(id, parent, AgentRow.TOOL, "Bash", "ls", state = state, text = text, lines = lines)

    @Test
    fun `the result line is the first line and how many more, the counts for reads and writes, denied and running as such`() {
        assertEquals(ResultLine.Text("Saved out/a.docx", 4, false), tool("a", text = "\nSaved out/a.docx\nb\nc\nd", lines = 5).resultLine())
        assertEquals(ResultLine.Text("Exit code 1", 2, true), tool("a", AgentRow.ERROR, text = "Exit code 1\nTrace\nErr", lines = 3).resultLine())
        assertEquals(ResultLine.Read(61), tool("a").copy(outcome = AgentRow.OUTCOME_READ, count = 61).resultLine())
        assertEquals(ResultLine.Image(76430), tool("a").copy(outcome = AgentRow.OUTCOME_IMAGE, count = 76430).resultLine())
        assertEquals(ResultLine.Wrote(12), tool("a").copy(outcome = AgentRow.OUTCOME_WRITE, count = 12, text = "a\nb", lines = 12).resultLine())
        assertEquals(ResultLine.Denied, tool("a", AgentRow.DENIED, text = "Permission to use Bash…", lines = 1).resultLine())
        assertEquals(ResultLine.Running, tool("a", AgentRow.RUNNING).resultLine())
        assertEquals(ResultLine.NoOutput, tool("a").resultLine())
        // A text the phone did not keep still says how much there was.
        assertEquals(ResultLine.Text("", 399, false), tool("a").copy(lines = 400, cut = RowCut(400, 51490)).resultLine())
    }

    @Test
    fun `a subagent's rows are one deeper than its agent call, and a broken chain stops`() {
        val rows = listOf(tool("agent"), tool("child", parent = "agent"), tool("grandchild", parent = "child"), tool("orphan", parent = "gone"))
        assertEquals(mapOf("agent" to 0, "child" to 1, "grandchild" to 2, "orphan" to 0), rowDepths(rows))
        val loop = listOf(tool("a", parent = "b"), tool("b", parent = "a"))
        assertEquals(setOf("a", "b"), rowDepths(loop).keys)
    }

    @Test
    fun `the status line names the newest running call, else none`() {
        assertEquals("b", currentCall(listOf(tool("a", AgentRow.RUNNING), tool("b", AgentRow.RUNNING), tool("c")))?.row)
        assertNull(currentCall(listOf(tool("a"))))
        assertEquals("Bash(ls)", tool("a").call())
        assertEquals("Read", tool("a").copy(tool = "Read", arg = null).call())
    }

    @Test
    fun `durations split into minutes and seconds`() {
        assertEquals(3L to 51L, minutesAndSeconds(231_400))
        assertEquals(0L to 6L, minutesAndSeconds(6_900))
    }
}
