// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations

import run.ostrom.server.automations.fakes.ALICE
import run.ostrom.server.automations.fakes.testIdentities
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The waits MCP server, through its route, as an agent's MCP client calls it. */
class WaitsMcpTest {
    private suspend fun HttpClient.rpc(body: String, token: String = "agent", path: String = MCP): HttpResponse =
        post(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.json(body: String, token: String = "agent", path: String = MCP): JsonObject =
        Json.parseToJsonElement(rpc(body, token, path).bodyAsText()).jsonObject

    private suspend fun HttpClient.call(
        tool: String,
        arguments: String,
        token: String = "agent",
        path: String = MCP,
    ): JsonObject = json(
        """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
        token,
        path,
    )

    private fun JsonObject.text(): String =
        getValue("result").jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content

    private fun JsonObject.isError(): Boolean = getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean

    private fun JsonObject.errorCode(): Int = getValue("error").jsonObject.getValue("code").jsonPrimitive.int

    @Test
    fun theProtocolIsNegotiatedAndTheToolsListed() = serverTest(listOf(AutomationsModule), testIdentities) { server ->
        val client = server.client
        suspend fun initialize(params: String) =
            client.json("""{"jsonrpc":"2.0","id":1,"method":"initialize"$params}""").getValue("result").jsonObject
        val result = initialize(""","params":{"protocolVersion":"2024-11-05"}""")
        assertEquals("2024-11-05", result.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals("ostrom-waits", result.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("2025-06-18", initialize("").getValue("protocolVersion").jsonPrimitive.content)
        val future = initialize(""","params":{"protocolVersion":"2099"}""")
        assertEquals("2025-06-18", future.getValue("protocolVersion").jsonPrimitive.content)

        val notification = client.rpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(HttpStatusCode.Accepted, notification.status)
        assertTrue("\"result\":{}" in client.rpc("""{"jsonrpc":"2.0","id":3,"method":"ping"}""").bodyAsText())
        val tools = client.json("""{"jsonrpc":"2.0","id":4,"method":"tools/list"}""")
            .getValue("result").jsonObject.getValue("tools").jsonArray
        val names = tools.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(listOf("ask_human", "wait_for", "list_waits", "get_wait", "cancel_wait"), names)

        assertEquals(-32700, client.json("{nope").errorCode())
        assertEquals(-32600, client.json("[1]").errorCode())
        assertEquals(-32600, client.json("""{"id":9}""").errorCode())
        assertEquals(-32601, client.json("""{"jsonrpc":"2.0","id":5,"method":"resources/list"}""").errorCode())
        assertEquals(-32602, client.json("""{"jsonrpc":"2.0","id":6,"method":"tools/call"}""").errorCode())
        assertEquals(-32602, client.call("answer_wait", "{}").errorCode())
        assertEquals(-32602, client.call("ask_human", "{}").errorCode())
        assertEquals(HttpStatusCode.Unauthorized, client.post(MCP).status)
    }

    @Test
    fun anAgentAsksListsReadsAndCancelsItsSessionsWaits() =
        serverTest(listOf(AutomationsModule), testIdentities) { server ->
            val client = server.client
            val asked = client.call(
                "ask_human",
                """{"title":"Which region?","assignee":"$ALICE","fields":[{"id":"region","type":"choice",""" +
                    """"label":"Region","options":["eu","us"],"required":true}],"refs":[{"kind":"url",""" +
                    """"url":"https://example.com"}],"actions":[{"id":"ok","label":"OK"}],"extra":1}""",
            )
            assertFalse(asked.isError(), asked.text())
            val id = asked.text().substringAfter("wait ").substringBefore(" ")
            assertTrue("for member $ALICE" in asked.text())

            val listed = client.call("list_waits", "{}").text()
            assertTrue(listed.startsWith("$id  open  Which region?"), listed)
            assertEquals("No waits.", client.call("list_waits", """{"status":"cancelled"}""").text())
            assertEquals("No waits.", client.call("list_waits", "{}", path = OTHER_SESSION).text())
            val read = Json.parseToJsonElement(client.call("get_wait", """{"wait":"$id"}""").text()).jsonObject
            assertEquals("s1", read.getValue("sessionId").jsonPrimitive.content)
            assertEquals("r1", read.getValue("runId").jsonPrimitive.content)

            val elsewhere = client.call("get_wait", """{"wait":"$id"}""", path = OTHER_SESSION)
            assertTrue(elsewhere.isError())
            assertEquals("Wait not found.", elsewhere.text())
            assertTrue(client.call("cancel_wait", """{"wait":"$id"}""", path = OTHER_SESSION).isError())
            assertTrue(client.call("get_wait", """{"wait":"nope"}""").isError())

            assertEquals("Cancelled wait $id.", client.call("cancel_wait", """{"wait":"$id"}""").text())
            assertTrue(client.call("cancel_wait", """{"wait":"$id"}""").isError())
            val refused = client.call("ask_human", """{"title":"x"}""", token = "reader")
            assertTrue(refused.isError())
            assertEquals("This credential lacks the waits:ask scope.", refused.text())
            assertTrue(client.call("ask_human", """{"title":" "}""").isError())
            val unbound = client.call("ask_human", """{"title":"Alone"}""", path = "/api/v1/waits/mcp")
            assertTrue(unbound.text().startsWith("Asked: wait "))
            // A wait asked outside a session is read outside one, never from a session.
            val alone = unbound.text().substringAfter("wait ").substringBefore(" ")
            assertFalse(client.call("get_wait", """{"wait":"$alone"}""", path = "/api/v1/waits/mcp").isError())
            val listedAlone = client.call("list_waits", "{}", path = "/api/v1/waits/mcp").text()
            assertTrue("$alone  open  Alone" in listedAlone, listedAlone)
            assertEquals("Wait not found.", client.call("get_wait", """{"wait":"$alone"}""").text())
            val outside = client.call("get_wait", """{"wait":"$id"}""", path = "/api/v1/waits/mcp")
            assertEquals("Wait not found.", outside.text(), "Nor a session's wait outside a session")
        }

    private companion object {
        const val MCP = "/api/v1/waits/mcp?session=s1&run=r1"
        const val OTHER_SESSION = "/api/v1/waits/mcp?session=s2&run=r1"
    }
}
