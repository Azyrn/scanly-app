package com.skeler.scanely.docchat.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.skeler.scanely.core.network.ApiJson
import com.skeler.scanely.core.network.LocalWorker
import com.skeler.scanely.core.network.TolerantJson
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/** The agent's log as the Worker sends it (worker/src/agent-log.ts) and as a reply keeps it. */
class AgentLogTest {
    private val worker = LocalWorker()

    @After fun tearDown() = worker.close()

    /** What the gateway sent for recorded Claude Code turns (worker/test/fixtures/gateway-rows.json), as the Worker relays rows. */
    private fun recordedRows(name: String): List<AgentRow> {
        val all = Json.parseToJsonElement(File("../worker/test/fixtures/gateway-rows.json").readText()) as JsonObject
        val events = (all[name] as JsonArray).map { it as JsonObject }.filter { it["type"]!!.jsonPrimitive.content == "row" }
        val log = events.fold(AgentLog()) { log, event ->
            log.merged(listOf(TolerantJson.decodeFromString(AgentRow.serializer(), event.toString())), replace = false)
        }
        return log.rows
    }

    private fun row(id: String, text: String? = null, kind: String = AgentRow.TOOL) =
        AgentRow(id, kind = kind, tool = "Bash", arg = id, state = AgentRow.DONE, text = text, lines = text?.lines()?.size, chars = text?.length)

    @Test
    fun `a rows event, a tasks event and the status read as the worker sends them`() {
        val event = TolerantJson.decodeFromString(
            AgentEventDto.serializer(),
            """{"type":"rows","at":5,"rows":[{"row":"toolu_1","parent":null,"kind":"tool","tool":"Bash","arg":"ls",""" +
                """"state":"done","outcome":"output","text":"a\nb","lines":3,"chars":5,"cut":{"lines":1,"chars":1},"full":true}]}"""
        )
        assertEquals(
            AgentRow("toolu_1", null, "tool", "Bash", "ls", state = "done", outcome = "output", text = "a\nb", lines = 3, chars = 5,
                cut = RowCut(1, 1), full = true),
            event.rows.single()
        )
        val tasks = TolerantJson.decodeFromString(
            AgentEventDto.serializer(), """{"type":"tasks","at":5,"items":[{"id":"1","subject":"المهمة أ","state":"in_progress"}]}"""
        )
        assertEquals(listOf(AgentTask("1", "المهمة أ", AgentTask.IN_PROGRESS)), tasks.items)
        val status = TolerantJson.decodeFromString(AgentStatusDto.serializer(), """{"state":"running","rows":[{"row":"r","kind":"note","state":"done"}]}""")
        assertEquals("note", status.rows.single().kind)
    }

    @Test
    fun `recorded rows merge by id in the order they first came, a subagent's under its agent call`() {
        val rows = recordedRows("subagent")
        val agent = rows.single { it.tool == "Agent" }
        assertEquals(AgentRow.DONE, agent.state)
        val child = rows.single { it.arg == "echo from-sub" }
        assertEquals(agent.row, child.parent)
        assertTrue(rows.indexOf(agent) < rows.indexOf(child))
        val bash = recordedRows("long-output").single { it.tool == "Bash" }
        assertTrue(bash.full && bash.cut!!.chars == 51490 - bash.text!!.length)
        assertEquals(listOf(AgentRow.ERROR, AgentRow.ERROR), recordedRows("error").filter { it.kind == AgentRow.TOOL }.map { it.state })
        assertEquals(AgentRow.DENIED, recordedRows("permission-denied").single().state)
    }

    @Test
    fun `an update replaces its row in place and new rows go after, a replace is all of it`() {
        val log = AgentLog(listOf(row("a"), row("b")))
        val updated = log.merged(listOf(row("c"), row("a", "done")), replace = false)
        assertEquals(listOf("a", "b", "c"), updated.rows.map { it.row })
        assertEquals("done", updated.rows.first().text)
        assertEquals(listOf("z"), updated.merged(listOf(row("z")), replace = true).rows.map { it.row })
    }

    @Test
    fun `a kept log drops the oldest texts first, saying all of them were cut, then the oldest rows, counting the steps`() {
        val log = AgentLog((1..5).map { row("r$it", "x".repeat(100)) }, listOf(AgentTask("1", "t", AgentTask.PENDING)))
        assertEquals(log, log.kept(100_000))
        val trimmed = log.kept(800)
        assertTrue(messageJson.encodeToString(AgentLog.serializer(), trimmed).length <= 800)
        assertNull(trimmed.rows.first().text)
        assertEquals(RowCut(1, 100), trimmed.rows.first().cut)
        assertEquals("x".repeat(100), trimmed.rows.last().text)
        assertEquals(5, trimmed.steps)
        val small = log.kept(250)
        assertTrue(small.droppedRows > 0 && small.rows.size + small.droppedRows == 5)
        assertEquals(5, small.steps)
        assertEquals(log.tasks, small.tasks)
    }

    @Test
    fun `a finished reply keeps its log and turn through a round trip, and an older one with terminal lines reads with none`() {
        val body = AiTextBody("Here it is.", log = AgentLog(listOf(row("r", "<b>out</b>"))), agentTurnId = "t1")
        assertEquals(body, decodeBody(body.encode()))
        val old = decodeBody("""{"type":"error","message":"Failed.","terminal":[{"kind":"cmd","text":"ls"}]}""") as ErrorBody
        assertNull(old.log)
    }

    @Test
    fun `a finished turn's steps end with DONE at its finish, so its time is the whole turn`() {
        val working = WorkingBody(startedAt = 1_000, steps = listOf(StepRecord(DesignStep.THINKING, 1_000), StepRecord(DesignStep.WRITING_CODE, 2_000)))
        val steps = working.finishedSteps(now = 211_000)
        assertEquals(StepRecord(DesignStep.DONE, 211_000), steps.last())
        assertEquals(210_000L, com.skeler.scanely.docchat.presentation.screen.stepDurations(steps, null).sum())
        assertEquals(steps, working.copy(steps = steps).finishedSteps(now = 999_999))
    }

    private val api =
        Retrofit.Builder().baseUrl(worker.baseUrl).addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build().create(ChatSyncApi::class.java)
    private val client = AgentClient(api, api)

    @Test
    fun `a whole output is fetched while the turn is kept, and says expired once it is not`() = runBlocking {
        worker.route("/v1/agent/turns/t1/outputs/toolu_1") { 200 to """{"text":"all of it","cut":5}""" }
        worker.route("/v1/agent/turns/t1/outputs/toolu_2") {
            410 to """{"error":{"code":"output_expired","message":"The full output is no longer kept."}}"""
        }
        assertEquals(RowOutput.Text("all of it", 5), client.output("t1", "toolu_1"))
        assertEquals(RowOutput.Expired, client.output("t1", "toolu_2"))
        assertEquals(RowOutput.Unavailable, client.output("t1", "toolu_3"))
    }
}
