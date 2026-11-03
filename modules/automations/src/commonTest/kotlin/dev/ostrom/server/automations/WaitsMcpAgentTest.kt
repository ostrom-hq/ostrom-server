// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import dev.ostrom.server.automations.domain.WaitAgent
import dev.ostrom.server.automations.domain.WaitAgentResolver
import dev.ostrom.server.automations.fakes.ALICE
import dev.ostrom.server.automations.fakes.BOB
import dev.ostrom.server.automations.fakes.FOREIGN_SESSION
import dev.ostrom.server.automations.fakes.FakeWaitAgents
import dev.ostrom.server.automations.fakes.LAUNCH_SESSION
import dev.ostrom.server.automations.fakes.testIdentities
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An agent token on the waits MCP server (PARCOURS 4.3): the local relay of `ostrom claude` forwards its agent's MCP
 * calls with the launch's agent token and `?session=`. It asks, reads and cancels as the person who launched it, inside
 * its own launch's session only, and never reaches the REST waits routes.
 */
class WaitsMcpAgentTest {
    /** [FakeWaitAgents], counting its resolutions. */
    private class CountingWaitAgents : WaitAgentResolver {
        var resolutions = 0

        override suspend fun resolve(principal: AuthenticatedPrincipal, sessionId: String): WaitAgent? {
            resolutions++
            return FakeWaitAgents.resolve(principal, sessionId)
        }
    }

    private val agents = CountingWaitAgents()

    private val withAgents = module {
        includes(testIdentities)
        single<WaitAgentResolver> { agents }
    }

    private suspend fun HttpClient.rpc(body: String, path: String, token: String = "launch"): HttpResponse =
        post(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.call(tool: String, arguments: String, path: String, token: String = "launch") =
        Json.parseToJsonElement(
            rpc(
                """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
                path,
                token,
            ).bodyAsText(),
        ).jsonObject.getValue("result").jsonObject

    private fun JsonObject.text(): String =
        getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content

    private fun JsonObject.isError(): Boolean = getValue("isError").jsonPrimitive.boolean

    @Test
    fun anAgentTokenAsksReadsAndCancelsAsItsPersonInItsLaunchsSession() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            val initialized = client.rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""", OWN)
            assertEquals(HttpStatusCode.OK, initialized.status, initialized.bodyAsText())

            val asked = client.call("ask_human", """{"title":"Ship it?"}""", OWN)
            assertFalse(asked.isError(), asked.text())
            assertTrue("for member $BOB" in asked.text(), "Asked of the person who launched it: ${asked.text()}")
            val id = asked.text().substringAfter("wait ").substringBefore(" ")
            assertTrue(client.call("list_waits", "{}", OWN).text().startsWith("$id  open  Ship it?"))
            val read = Json.parseToJsonElement(client.call("get_wait", """{"wait":"$id"}""", OWN).text()).jsonObject
            assertEquals(LAUNCH_SESSION, read.getValue("sessionId").jsonPrimitive.content)
            assertEquals(BOB.toString(), read.getValue("createdBy").jsonPrimitive.content, "Its person asked it")
            val before = agents.resolutions
            assertEquals("Cancelled wait $id.", client.call("cancel_wait", """{"wait":"$id"}""", OWN).text())
            assertEquals(before + 1, agents.resolutions, "Read then cancelled with one reading of the token")
        }

    @Test
    fun anAgentTokenActsInNoOtherSessionAndNotOnceItsLaunchIsOver() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            val foreign = client.call("ask_human", """{"title":"Ship it?"}""", FOREIGN)
            assertTrue(foreign.isError())
            assertEquals("This session is not yours.", foreign.text(), "Refused as the gates refuse it")
            assertEquals("This session is not yours.", client.call("list_waits", "{}", FOREIGN).text())

            val ended = client.call("ask_human", """{"title":"Ship it?"}""", OWN, token = "ended")
            assertTrue(ended.isError())
            assertEquals("This credential belongs to no organization.", ended.text())
        }

    @Test
    fun anAgentTokenConnectsBeforeItsSessionIsKnownButAsksNothingOutsideIt() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            // Claude Code connects its MCP servers at start-up, maybe before its SessionStart hook told the relay the
            // session: the handshake is answered, a tool names the missing session.
            val initialized = client.rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""", UNBOUND)
            assertEquals(HttpStatusCode.OK, initialized.status, initialized.bodyAsText())
            val listed = client.rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", UNBOUND)
            assertTrue("ask_human" in listed.bodyAsText(), listed.bodyAsText())
            val asked = client.call("ask_human", """{"title":"Ship it?"}""", UNBOUND)
            assertTrue(asked.isError())
            assertTrue("?session=" in asked.text(), "Says what is missing: ${asked.text()}")
            assertTrue(client.call("list_waits", "{}", UNBOUND).isError())
        }

    @Test
    fun anAgentTokenNamesNoRunBeforeAnyMessageIsRead() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
            val run = client.rpc(ping, "/api/v1/waits/mcp?session=$LAUNCH_SESSION&run=r1")
            assertEquals(HttpStatusCode.Forbidden, run.status)
            assertTrue("forbidden" in run.bodyAsText(), run.bodyAsText())
            assertEquals(HttpStatusCode.Forbidden, client.rpc(ping, "/api/v1/waits/mcp?run=r1").status)

            val person = client.rpc(ping, UNBOUND, token = "agent")
            assertEquals(HttpStatusCode.OK, person.status, "A person's credential still asks outside a session")
        }

    @Test
    fun anAgentTokenAsksOnlyThePersonWhoLaunchedIt() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            val other = client.call("ask_human", """{"title":"Ship it?","assignee":"$ALICE"}""", OWN)
            assertTrue(other.isError(), other.text())
            assertEquals("An agent asks only the person who launched it.", other.text())
            val own = client.call("ask_human", """{"title":"Ship it?","assignee":"$BOB"}""", OWN)
            assertFalse(own.isError(), own.text())
        }

    @Test
    fun anAgentTokenNeitherSeesNorCancelsWhatSomeoneElseAskedItsPerson() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            // alice asks bob, the agent's person, about the agent's session
            val asked = client.call("ask_human", """{"title":"Why this change?","assignee":"$BOB"}""", OWN, "alicekey")
            assertFalse(asked.isError(), asked.text())
            val id = asked.text().substringAfter("wait ").substringBefore(" ")

            assertEquals("No waits.", client.call("list_waits", "{}", OWN).text(), "Not listed to the agent")
            val read = client.call("get_wait", """{"wait":"$id"}""", OWN)
            assertTrue(read.isError(), read.text())
            val cancelled = client.call("cancel_wait", """{"wait":"$id"}""", OWN)
            assertTrue(cancelled.isError(), cancelled.text())
            val still = client.get("/api/v1/waits/$id") { bearerAuth("alicekey") }
            assertTrue("\"open\"" in still.bodyAsText(), "Still open: ${still.bodyAsText()}")
        }

    @Test
    fun anAgentTokenNeverReachesTheWaitsRestRoutes() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            val asked = client.call("ask_human", """{"title":"Ship it?"}""", OWN)
            val id = asked.text().substringAfter("wait ").substringBefore(" ")
            val listed = client.get("/api/v1/waits?sessionId=$LAUNCH_SESSION") { bearerAuth("launch") }
            assertEquals(HttpStatusCode.Forbidden, listed.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/waits/$id") { bearerAuth("launch") }.status)
            val answered = client.post("/api/v1/waits/$id/answer") {
                bearerAuth("launch")
                contentType(ContentType.Application.Json)
                setBody("""{"action":"ok"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, answered.status, "An agent never answers a wait")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/v1/waits/$id/cancel") { bearerAuth("launch") }.status,
            )
        }

    @Test
    fun withoutTheLaunchesModuleNoAgentTokenIsAnyone() =
        serverTest(listOf(AutomationsModule), testIdentities) { server ->
            val refused = server.client.call("ask_human", """{"title":"Ship it?"}""", OWN)
            assertTrue(refused.isError())
            assertEquals("This credential belongs to no organization.", refused.text())
        }

    private companion object {
        const val UNBOUND = "/api/v1/waits/mcp"
        const val OWN = "/api/v1/waits/mcp?session=$LAUNCH_SESSION"
        const val FOREIGN = "/api/v1/waits/mcp?session=$FOREIGN_SESSION"
    }
}
