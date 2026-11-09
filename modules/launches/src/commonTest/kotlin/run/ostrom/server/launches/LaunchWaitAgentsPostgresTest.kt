// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches

import run.ostrom.server.automations.AutomationsModule
import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.CLI_SCOPES
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.launches.presentation.LaunchResponse
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An agent asks a person through Ostrom's own MCP server (PARCOURS 4.3), on the real identity, orgs, gates, sessions,
 * launches and automations modules and PostgreSQL: the agent token of `ostrom claude` asks, reads and cancels in its
 * launch's session as the person who launched it, and reaches no other launch's session, nor another organization's.
 */
class LaunchWaitAgentsPostgresTest {
    private val modules = launchModules(AutomationsModule)
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** A person who owns a new organization, their member id, and a CLI token of theirs bound to it. */
    private class Person(val memberId: Uuid, val cliToken: String)

    private suspend fun TestServer.person(login: String): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        val member = Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        val secret = "osc_" + Uuid.random().toHexString()
        val token = ApiToken(
            Uuid.random(), user.id, org.id, "cli", TokenKind.CLI, CLI_SCOPES, "hint", null, now, now + 1.hours, null,
            null,
        )
        koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
        return Person(member.id, secret)
    }

    private suspend fun TestServer.post(token: String, path: String, body: String): HttpResponse =
        client.post(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** A launch of [person], and its agent token's session `s-1` opened. */
    private suspend fun TestServer.running(person: Person, session: String = "s-1"): LaunchResponse {
        val created = post(person.cliToken, "/api/v1/launches", LAUNCH)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val launch = created.body<LaunchResponse>()
        val body = """{"id":"$session","agent":"claude-code","repo":"acme/api","dev":"mallory"}"""
        val started = post(launch.agentToken, "/api/v1/sessions", body)
        assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())
        return launch
    }

    private suspend fun TestServer.tool(token: String, session: String, name: String, arguments: String): JsonObject {
        val body = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""
        val answer = post(token, "/api/v1/waits/mcp?session=$session", body)
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        return Json.parseToJsonElement(answer.bodyAsText()).jsonObject.getValue("result").jsonObject
    }

    private fun JsonObject.text(): String =
        getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content

    private fun JsonObject.isError(): Boolean = getValue("isError").jsonPrimitive.boolean

    @Test
    fun anAgentTokenAsksItsPersonInItsLaunchsSession() = serverTest(modules) { server ->
        with(server) {
            val ada = person("ada")
            val agent = running(ada).agentToken

            val asked = tool(agent, "s-1", "ask_human", """{"title":"Ship it?"}""")
            assertFalse(asked.isError(), asked.text())
            assertTrue("for member ${ada.memberId}" in asked.text(), "Asked of the person who launched it")
            val id = asked.text().substringAfter("wait ").substringBefore(" ")
            assertTrue(tool(agent, "s-1", "list_waits", "{}").text().startsWith("$id  open  Ship it?"))
            val read = Json.parseToJsonElement(tool(agent, "s-1", "get_wait", """{"wait":"$id"}""").text()).jsonObject
            assertEquals("s-1", read.getValue("sessionId").jsonPrimitive.content)
            assertEquals(ada.memberId.toString(), read.getValue("createdBy").jsonPrimitive.content, "Its person asked")
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/waits") { bearerAuth(agent) }.status)

            assertEquals("Cancelled wait $id.", tool(agent, "s-1", "cancel_wait", """{"wait":"$id"}""").text())
        }
    }

    @Test
    fun anAgentTokenAsksInTheSegmentItsLauncherReopenedWithoutTheRelay() = serverTest(modules) { server ->
        with(server) {
            val ada = person("ada")
            val agent = running(ada).agentToken
            val before = tool(agent, "s-1", "ask_human", """{"title":"Before the sleep"}""").text()
                .substringAfter("wait ").substringBefore(" ")
            // The launcher's lifeline, not the relay: the segment ends while the machine sleeps, then it reopens it.
            assertEquals(HttpStatusCode.OK, post(agent, "/api/v1/sessions/s-1/end", "{}").status)
            val reopened = post(agent, "/api/v1/sessions/s-1/reopen", "{}")
            assertEquals(HttpStatusCode.OK, reopened.status, reopened.bodyAsText())

            // The relay still names `s-1`, the agent's session id: the server finds the segment open now.
            val asked = tool(agent, "s-1", "ask_human", """{"title":"Ship it?"}""")
            assertFalse(asked.isError(), asked.text())
            val id = asked.text().substringAfter("wait ").substringBefore(" ")
            val read = Json.parseToJsonElement(tool(agent, "s-1", "get_wait", """{"wait":"$id"}""").text()).jsonObject
            assertEquals("s-1~r2", read.getValue("sessionId").jsonPrimitive.content, "Asked in the open segment")
            val earlier = tool(agent, "s-1~r2", "get_wait", """{"wait":"$before"}""").text()
            assertEquals(
                "s-1",
                Json.parseToJsonElement(earlier).jsonObject.getValue("sessionId").jsonPrimitive.content,
                "What it asked before the sleep is still its own",
            )
            val listed = tool(agent, "s-1", "list_waits", "{}").text().lines().map { it.substringBefore(" ") }
            assertEquals(listOf(id, before), listed, "Both segments' waits, newest first")
            assertEquals("Cancelled wait $before.", tool(agent, "s-1", "cancel_wait", """{"wait":"$before"}""").text())

            val second = running(ada, session = "s-2").agentToken
            assertEquals(
                "This session is not yours.",
                tool(second, "s-1", "list_waits", "{}").text(),
                "Another launch never reaches the segment through its first id",
            )

            // Resumed by another launch (`claude --resume` keeps the id): its segment, and only what it asks there.
            assertEquals(HttpStatusCode.OK, post(agent, "/api/v1/sessions/s-1~r2/end", "{}").status)
            val resumed = running(ada).agentToken
            val again = tool(resumed, "s-1", "ask_human", """{"title":"Resumed"}""").text()
                .substringAfter("wait ").substringBefore(" ")
            val mine = tool(resumed, "s-1", "list_waits", """{"status":"all"}""").text().lines()
                .map { it.substringBefore(" ") }
            assertEquals(listOf(again), mine, "Not what the first launch asked")
            assertTrue(tool(resumed, "s-1", "get_wait", """{"wait":"$id"}""").isError(), "The first launch's wait")
        }
    }

    @Test
    fun anAgentTokenReachesNoOtherLaunchsSessionNorAnotherOrganizations() = serverTest(modules) { server ->
        with(server) {
            val ada = person("ada")
            running(ada)
            val second = running(ada, session = "s-2").agentToken
            val foreign = tool(second, "s-1", "ask_human", """{"title":"Ship it?"}""")
            assertTrue(foreign.isError())
            assertEquals("This session is not yours.", foreign.text(), "Another launch's session, as for a gate")
            assertEquals("Session not found.", tool(second, "s-9", "list_waits", "{}").text())

            val bob = running(person("bob"), session = "s-3").agentToken
            assertEquals(
                "Session not found.",
                tool(bob, "s-1", "list_waits", "{}").text(),
                "Another organization's session is not found",
            )

            val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
            val unbound = post(second, "/api/v1/waits/mcp", ping)
            assertEquals(HttpStatusCode.OK, unbound.status, "Answered before its session is known")
            assertEquals(HttpStatusCode.Forbidden, post(second, "/api/v1/waits/mcp?session=s-2&run=r", ping).status)
        }
    }

    private companion object {
        const val LAUNCH = """{"repo":"acme/api","agent":"claude-code","model":"claude-opus-4-1"}"""
    }
}
