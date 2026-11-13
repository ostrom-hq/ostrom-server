// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.WaitAgent
import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.automations.fakes.ALICE
import run.ostrom.server.automations.fakes.BOB
import run.ostrom.server.automations.fakes.FOREIGN_SESSION
import run.ostrom.server.automations.fakes.FakeWaitAgents
import run.ostrom.server.automations.fakes.LAUNCH_SESSION
import run.ostrom.server.automations.fakes.testIdentities
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.testing.serverTest
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
    /**
     * [FakeWaitAgents], counting its resolutions; [reopened] is the segment the sessions module finds open, if set, and
     * [segments] those of its launch (by default the first one and [reopened]).
     */
    private class CountingWaitAgents : WaitAgentResolver {
        var resolutions = 0
        var reopened: String? = null
        var segments: List<String>? = null

        override suspend fun resolve(principal: AuthenticatedPrincipal, sessionId: String): WaitAgent? {
            resolutions++
            val agent = FakeWaitAgents.resolve(principal, sessionId)
            val open = reopened ?: return agent
            return agent?.copy(sessionId = open, segments = segments ?: listOf(LAUNCH_SESSION, open))
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
    fun anAgentTokenAsksInTheSegmentTheServerFoundAndKeepsWhatItAskedBefore() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            val before = client.call("ask_human", """{"title":"Before the sleep"}""", OWN).text()
                .substringAfter("wait ").substringBefore(" ")
            // The launcher reopened the session while the machine slept; the relay still names its first id.
            agents.reopened = "$LAUNCH_SESSION~r2"
            val id = client.call("ask_human", """{"title":"Ship it?"}""", OWN).text()
                .substringAfter("wait ").substringBefore(" ")
            val read = Json.parseToJsonElement(client.call("get_wait", """{"wait":"$id"}""", OWN).text()).jsonObject
            assertEquals("$LAUNCH_SESSION~r2", read.getValue("sessionId").jsonPrimitive.content, "The open segment")
            val earlier = client.call("get_wait", """{"wait":"$before"}""", OWN)
            assertFalse(earlier.isError(), "What it asked before the sleep is still its own: ${earlier.text()}")
            val listed = client.call("list_waits", "{}", OWN).text().lines().map { it.substringBefore(" ") }
            assertEquals(listOf(id, before), listed, "Both segments' waits: its launch opened both")
            assertEquals("Cancelled wait $before.", client.call("cancel_wait", """{"wait":"$before"}""", OWN).text())

            // Another launch resumed the session: what the first launch asked is not its own.
            agents.reopened = "$LAUNCH_SESSION~r3"
            agents.segments = listOf("$LAUNCH_SESSION~r3")
            assertTrue(client.call("get_wait", """{"wait":"$id"}""", OWN).isError(), "The first launch's wait")
            assertEquals("No waits.", client.call("list_waits", """{"status":"all"}""", OWN).text())
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
    fun anAgentTokenAsksAnyActiveMemberByIdOrUsername() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            val other = client.call("ask_human", """{"title":"Ship it?","assignee":"$ALICE"}""", OWN)
            assertFalse(other.isError(), other.text())
            assertTrue("for member $ALICE." in other.text(), other.text())
            val named = client.call("ask_human", """{"title":"Ship it?","assignee":"alice"}""", OWN)
            assertTrue("for member $ALICE." in named.text(), named.text())
            val own = client.call("ask_human", """{"title":"Ship it?","assignee":"$BOB"}""", OWN)
            assertFalse(own.isError(), own.text())
            val stranger = client.call("ask_human", """{"title":"Ship it?","assignee":"carol"}""", OWN)
            assertTrue(stranger.isError(), stranger.text())
            assertTrue("member id or a username" in stranger.text(), stranger.text())
        }

    @Test
    fun anAgentTokenNeitherSeesNorCancelsWhatSomeoneElseAskedItsPerson() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            // alice may not ask in bob's session (her answer would reach his agent); she asks bob in hers
            val toBob = """{"title":"Why this change?","assignee":"$BOB"}"""
            val refused = client.call("ask_human", toBob, OWN, "alicekey")
            assertEquals("Only the person who runs this session asks a wait in it.", refused.text())
            val asked = client.call("ask_human", toBob, ALICES, "alicekey")
            assertFalse(asked.isError(), asked.text())
            val id = asked.text().substringAfter("wait ").substringBefore(" ")

            assertEquals("No waits.", client.call("list_waits", "{}", OWN).text(), "Not listed to the agent")
            val read = client.call("get_wait", """{"wait":"$id"}""", OWN)
            assertTrue(read.isError(), read.text())
            val cancelled = client.call("cancel_wait", """{"wait":"$id"}""", OWN)
            assertTrue(cancelled.isError(), cancelled.text())
            val still = client.get("/api/v1/waits/$id") { bearerAuth("alicekey") }
            val status = Json.parseToJsonElement(still.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content
            assertEquals("open", status, "Still open")
        }

    @Test
    fun anAgentTokenSeesAndCancelsWhatItsPersonAskedSomeoneElse() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            // bob, the agent's person, asks alice from the console about the agent's session; alice asks bob too
            val asked = client.call("ask_human", """{"title":"Review it?","assignee":"$ALICE"}""", OWN, "bob")
            val id = asked.text().substringAfter("wait ").substringBefore(" ")
            client.call("ask_human", """{"title":"Why this change?","assignee":"$BOB"}""", ALICES, "alicekey")

            assertTrue(client.call("list_waits", "{}", OWN).text().startsWith("$id  open  Review it?"))
            assertEquals(1, client.call("list_waits", "{}", OWN).text().lines().size, "Only what its person asked")
            assertFalse(client.call("get_wait", """{"wait":"$id"}""", OWN).isError())
            assertEquals("Cancelled wait $id.", client.call("cancel_wait", """{"wait":"$id"}""", OWN).text())
            val person = client.call("list_waits", """{"status":"all"}""", OWN, "bob").text().lines()
            assertEquals(1, person.size, "Only this session's waits, not what alice asked in hers: $person")
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
        const val ALICES = "/api/v1/waits/mcp?session=alice-1"
        const val FOREIGN = "/api/v1/waits/mcp?session=$FOREIGN_SESSION"
    }
}
