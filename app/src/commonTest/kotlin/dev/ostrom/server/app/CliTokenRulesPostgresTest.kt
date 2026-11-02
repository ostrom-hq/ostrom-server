// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.ORG_HEADER
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * `ostrom init` and `ostrom rules list` with the token of `ostrom login`, through every module of the server: the
 * CLI's device token reads its organization's rules, decisions and gate policy (`rules:read`), never another
 * organization's, and still writes no rule (a rule is written from the console only, H1).
 */
class CliTokenRulesPostgresTest {
    /** A person who owns a new organization with one rule: the organization, the rule's id and the CLI's token. */
    private class Owner(val orgId: Uuid, val ruleId: String, val cliToken: String)

    private suspend fun TestServer.owner(login: String, statement: String): Owner {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val now = Clock.System.now()
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)) { false }
        val session = koin.get<StartWebSessionUseCase>()(user.id)
        val rule = client.post("/api/v1/rules") {
            session(session)
            json("""{"statement": "$statement", "group": "Releases"}""")
        }
        assertEquals(HttpStatusCode.Created, rule.status, rule.bodyAsText())
        return Owner(org.id, rule.json().getValue("id").jsonPrimitive.content, deviceToken(session))
    }

    // A browser write: the session cookie, the CSRF header repeating its cookie, from the console's origin.
    private fun HttpRequestBuilder.session(started: StartedSession) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
        header(CSRF_HEADER, started.csrfToken)
        header(HttpHeaders.Origin, CONSOLE_ORIGIN)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    // The device flow as the CLI and the console run it: the token is the CLI's own, bound to no organization.
    private suspend fun TestServer.deviceToken(started: StartedSession): String {
        val codes = client.post("/api/v1/auth/device") { json("""{"clientName": "laptop"}""") }.json()
        val answered = client.post("/api/v1/auth/device/answer") {
            session(started)
            json("""{"userCode": "${codes.getValue("userCode").jsonPrimitive.content}", "approve": true}""")
        }
        assertEquals(HttpStatusCode.NoContent, answered.status, answered.bodyAsText())
        val polled = client.post("/api/v1/auth/device/token") {
            json("""{"deviceCode": "${codes.getValue("deviceCode").jsonPrimitive.content}"}""")
        }
        assertEquals(HttpStatusCode.OK, polled.status, polled.bodyAsText())
        return polled.json().getValue("token").jsonPrimitive.content
    }

    private suspend fun TestServer.statements(owner: Owner): List<String> {
        val rules = read("/api/v1/rules", owner.cliToken)
        assertEquals(HttpStatusCode.OK, rules.status, rules.bodyAsText())
        val items = rules.json().getValue("items").jsonArray
        return items.map { it.jsonObject.getValue("statement").jsonPrimitive.content }
    }

    private suspend fun TestServer.read(path: String, token: String, org: Uuid? = null): HttpResponse =
        client.get(path) {
            bearerAuth(token)
            org?.let { header(ORG_HEADER, "$it") }
        }

    @Test
    fun theCliTokenReadsItsOrganizationsRulesAndNothingOfAnothers() = serverTest(serverModules()) { server ->
        with(server) {
            val ada = owner("ada", "Ada ships on Fridays")
            val bob = owner("bob", "Bob never ships")

            assertEquals(listOf("Ada ships on Fridays"), statements(ada), "Not Bob's")
            assertEquals(HttpStatusCode.OK, read("/api/v1/rules/${ada.ruleId}", ada.cliToken).status)
            assertEquals(HttpStatusCode.OK, read("/api/v1/decisions", ada.cliToken).status)
            // The gate policy was already readable with gates:read; rules:read opens it too.
            val policy = read("/api/v1/gates/policy", ada.cliToken)
            assertEquals(HttpStatusCode.OK, policy.status)

            // Bob's organization, named or by the id of its rule: not found, as one that does not exist.
            assertEquals(HttpStatusCode.NotFound, read("/api/v1/rules", ada.cliToken, bob.orgId).status)
            assertEquals(HttpStatusCode.NotFound, read("/api/v1/rules/${bob.ruleId}", ada.cliToken).status)
            assertEquals(HttpStatusCode.NotFound, read("/api/v1/decisions", ada.cliToken, bob.orgId).status)

            val rule = read("/api/v1/rules/${ada.ruleId}", ada.cliToken).json()
            // Reading is all: no write of the rules or the policy is applied with the CLI's token, whether it is
            // refused or held for a confirmation in the console (H1). The state says so, not the status.
            val writes = listOf(
                HttpMethod.Post to ("/api/v1/rules" to """{"statement": "The agent may push", "group": "Releases"}"""),
                HttpMethod.Patch to ("/api/v1/rules/${ada.ruleId}" to """{"statement": "Ada ships any day"}"""),
                HttpMethod.Post to ("/api/v1/rules/bulk" to """{"ids": ["${ada.ruleId}"], "status": "rejected"}"""),
                HttpMethod.Put to ("/api/v1/gates/policy" to """{"selfApproval": "never"}"""),
                HttpMethod.Delete to ("/api/v1/rules/${ada.ruleId}" to "{}"),
            )
            for ((verb, call) in writes) {
                val (path, body) = call
                val write = client.request(path) {
                    method = verb
                    bearerAuth(ada.cliToken)
                    json(body)
                }
                assertTrue(write.status.value == 202 || write.status.value >= 400, "$verb $path: ${write.status}")
            }
            assertEquals(listOf("Ada ships on Fridays"), statements(ada), "No rule written or deleted")
            assertEquals(rule, read("/api/v1/rules/${ada.ruleId}", ada.cliToken).json(), "The rule is unchanged")
            assertEquals(policy.json(), read("/api/v1/gates/policy", ada.cliToken).json(), "The policy is unchanged")
        }
    }

    private companion object {
        /** The console's origin in tests: the default `OSTROM_CONSOLE_URL`. */
        const val CONSOLE_ORIGIN = "http://localhost:5173"
    }
}
