// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.workflowruns.domain.models.CredentialScopeException
import run.ostrom.server.workflowruns.domain.models.InvalidDeliverableException
import run.ostrom.server.workflowruns.domain.models.NothingToDeliverException
import run.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException
import run.ostrom.server.workflowruns.domain.models.TodoOpenException
import run.ostrom.server.workflowruns.domain.usecases.DeliverWorkflowSessionUseCase
import run.ostrom.server.workflowruns.domain.usecases.StepCaller
import run.ostrom.server.workflowruns.fakes.ORG
import run.ostrom.server.workflowruns.presentation.WorkflowsMcpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The `ostrom-workflow` MCP server: its tool `deliver` and the protocol around it (TeamCode WorkflowMcpServer). */
class WorkflowsMcpServerTest {
    private class Deliveries : DeliverWorkflowSessionUseCase {
        var refusal: OstromException? = null
        val delivered = mutableListOf<Triple<StepCaller, Uuid?, JsonElement>>()

        override suspend fun invoke(
            caller: StepCaller,
            sessionId: Uuid?,
            output: JsonElement,
            person: Uuid?,
        ): WorkflowRun {
            refusal?.let { throw it }
            delivered += Triple(caller, sessionId, output)
            return RUN
        }
    }

    private val deliveries = Deliveries()
    private val server = WorkflowsMcpServer(deliveries)
    private var asked = 0

    private suspend fun send(body: String): JsonObject? = server.handle(body) {
        asked++
        CALLER to SESSION
    }

    private suspend fun deliver(arguments: String): JsonObject {
        val params = """{"name":"deliver","arguments":$arguments}"""
        val answer = send("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":$params}""")
        return assertNotNullObject(answer).at("result")
    }

    private fun assertNotNullObject(value: JsonObject?): JsonObject = value ?: error("An answer was expected.")

    private fun JsonObject.at(name: String): JsonObject = getValue(name).jsonObject

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.text(): String = getValue("content").jsonArray.single().jsonObject.string("text")

    private fun JsonObject.failed(): Boolean = getValue("isError").jsonPrimitive.boolean

    private fun JsonObject.code(): Int = at("error").getValue("code").jsonPrimitive.int

    @Test
    fun theAgentDeliversOnceAndIsToldToEndItsTurn() = runTest {
        val answer = deliver("""{"output":"https://github.com/acme/api/pull/1"}""")

        assertTrue(!answer.failed())
        val text = Json.parseToJsonElement(answer.text()).jsonObject
        assertEquals(
            listOf<Any?>(true, RUN.id, "running"),
            listOf(text.getValue("delivered").jsonPrimitive.boolean, text.string("run"), text.string("status")),
        )
        assertTrue("do not deliver again" in text.string("instruction"))
        val output: JsonElement = JsonPrimitive("https://github.com/acme/api/pull/1")
        assertEquals(listOf(Triple(CALLER, SESSION, output)), deliveries.delivered)
        // A JSON value is handed in as it is.
        deliver("""{"output":{"status":"ok"}}""")
        assertEquals(Json.parseToJsonElement("""{"status":"ok"}"""), deliveries.delivered.last().third)
    }

    @Test
    fun aRefusalIsAToolErrorThatSaysWhatToDo() = runTest {
        val refusals = listOf(
            InvalidDeliverableException("Deliver an https address.") to
                "Deliver an https address. Deliver exactly what the step asked for",
            NothingToDeliverException() to "This run is not waiting for a delivery. This session is not running",
            TodoOpenException("2 of 3 items remain.") to "2 of 3 items remain. Finish or drop the open items",
            CredentialScopeException() to "This credential cannot deliver a workflow step. This session's credential",
            RunSessionNotFoundException() to "Session not found. No workflow run is bound to this session for you.",
            OstromException(ErrorKind.FORBIDDEN, "This credential does not allow workflows:write.") to
                "This credential does not allow workflows:write.",
        )
        for ((refusal, text) in refusals) {
            deliveries.refusal = refusal
            val answer = deliver("""{"output":"x"}""")
            assertTrue(answer.failed())
            assertTrue(answer.text().startsWith(text), answer.text())
        }
        deliveries.refusal = null
        // Without an output nothing is asked of the server, nor the credential read.
        val before = asked
        for (arguments in listOf("""{}""", """{"output":null}""", "null")) {
            val missing = deliver(arguments)
            val expected = listOf<Any>(true, "output is required: hand in what the step asked for.")
            assertEquals(expected, listOf(missing.failed(), missing.text()), arguments)
        }
        assertEquals(before, asked)
        assertEquals(emptyList(), deliveries.delivered)
    }

    @Test
    fun theProtocolAroundTheToolIsMcps() = runTest {
        suspend fun initialize(params: String) =
            assertNotNullObject(send("""{"jsonrpc":"2.0","id":1,"method":"initialize"$params}""")).at("result")
        val result = initialize(""","params":{"protocolVersion":"2025-03-26"}""")
        assertEquals("2025-03-26", result.string("protocolVersion"))
        assertEquals("ostrom-workflow", result.at("serverInfo").string("name"))
        assertTrue("call `deliver` once" in result.string("instructions"))
        assertEquals("2025-06-18", initialize(""","params":{"protocolVersion":"1999"}""").string("protocolVersion"))
        assertEquals("2025-06-18", initialize("").string("protocolVersion"))
        val list = assertNotNullObject(send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))
        val tools = list.at("result").getValue("tools").jsonArray
        assertEquals(listOf("deliver"), tools.map { it.jsonObject.string("name") })
        val ping = assertNotNullObject(send("""{"jsonrpc":"2.0","id":3,"method":"ping"}"""))
        assertEquals(JsonObject(emptyMap()), ping.at("result"))
        assertNull(send("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""), "A notification has no answer")
        val errors = listOf(
            "not json" to -32700,
            "[]" to -32600,
            """{"jsonrpc":"2.0","id":4}""" to -32600,
            """{"jsonrpc":"2.0","id":5,"method":"resources/list"}""" to -32601,
            """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"ask_human"}}""" to -32602,
            """{"jsonrpc":"2.0","id":6,"method":"tools/call"}""" to -32602,
        )
        for ((body, code) in errors) assertEquals(code, assertNotNullObject(send(body)).code(), body)
        assertEquals(0, asked, "Nothing but the tool reads the credential")
    }

    private companion object {
        val CALLER = StepCaller(ORG, "ada", launchId = "lch_1")
        val SESSION: Uuid = Uuid.parse("0000000c-0000-0000-0000-000000000000")
        val RUN = WorkflowRun(
            id = "wfr_" + "e".repeat(32),
            workflowId = "ship",
            workflowName = "Ship",
            workflowVersion = 1,
            status = RunStatus.RUNNING,
            startedBy = "ada",
            source = "manual",
            startedAt = "2026-11-13T10:00:00.000Z",
        )
    }
}
