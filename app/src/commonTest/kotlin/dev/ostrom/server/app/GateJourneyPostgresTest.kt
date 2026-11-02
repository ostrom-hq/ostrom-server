// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.orgs.domain.Invitation
import dev.ostrom.server.orgs.domain.InvitationsRepository
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
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * The gate journey of the load and end-to-end benches (`ostrom-e2e/scripts/load-setup.ts`, E2E-AUTH-007), through
 * every module of the server: an owner (dev-a) and an admin (rev-b) of one organization, an accepted workflow posted
 * by dev-a's browser session whose gate node names rev-b as the approver of pushes to main of `acme/api`, then a push
 * to main asked with dev-a's personal token. The gate waits for rev-b, and rev-b finds the request in their inbox.
 */
class GateJourneyPostgresTest {
    private suspend fun TestServer.signIn(login: String): Pair<User, StartedSession> {
        val identity = VerifiedIdentity(
            IdentityProvider.GITHUB,
            login,
            "$login@example.com",
            true,
            login,
            login = login,
        )
        val user = koin.get<ResolveUserUseCase>()(identity)
        return user to koin.get<StartWebSessionUseCase>()(user.id)
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

    /** dev-a owns the organization, rev-b joins it as an admin. */
    private suspend fun TestServer.team(devA: User, revB: User) {
        val now = Clock.System.now()
        val org = Org(Uuid.random(), "acme", "Acme", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, devA.id, OrgRole.OWNER, now)) { false }
        val invitations = koin.get<InvitationsRepository>()
        val expires = now + 1.hours
        val email = "rev-b@example.com"
        val invitation = Invitation(Uuid.random(), org.id, email, OrgRole.ADMIN, devA.id, now, expires, 1, 0, null)
        invitations.create(invitation, "hash-of-rev-b")
        invitations.accept(invitation, Member(Uuid.random(), org.id, revB.id, OrgRole.ADMIN, now), now)
    }

    private fun push(cls: String) = """
        {"repo": "acme/api", "class": "$cls", "tool": "Bash", "command": "git push origin main",
         "push": {"remote": "origin", "refs": [{"ref": "refs/heads/main", "sha": "${"a".repeat(SHA_LENGTH)}"}]}}
    """.trimIndent()

    @Test
    fun aPushToMainWaitsForTheApproverTheAcceptedWorkflowNames() = serverTest(serverModules()) { server ->
        val (devA, devASession) = server.signIn("dev-a")
        val (revB, revBSession) = server.signIn("rev-b")
        server.team(devA, revB)
        val token = server.client.post("/api/v1/tokens") {
            session(devASession)
            json("""{"name": "k6 requester", "scopes": ["gates:ask", "gates:read"], "expiresInDays": 1}""")
        }
        assertEquals(201, token.status.value, token.bodyAsText())
        val secret = token.json().getValue("secret").jsonPrimitive.content
        val workflow = server.client.post("/api/v1/workflows") {
            session(devASession)
            json(
                """
                {"id": "k6-push-main", "name": "k6 push to main", "domain": "delivery", "status": "accepted",
                 "foundAt": "2026-11-02T17:00:00Z", "edges": [],
                 "nodes": [{"id": "push-main", "kind": "gate", "label": "Push to main", "approvers": ["rev-b"],
                            "actions": [{"class": "push-main", "label": "Push to the main branch", "patterns": [],
                                         "repos": ["acme/api"]}]}]}
                """.trimIndent(),
            )
        }
        assertTrue(workflow.status.value < 300, "workflow → ${workflow.status} ${workflow.bodyAsText()}")

        // The benches ask the coarse class `git.push`; the CLI asks the catalogue's `push-main`. Both are this push.
        for (cls in listOf("git.push", "push-main")) {
            val asked = server.client.post("/api/v1/gates") {
                bearerAuth(secret)
                json(push(cls))
            }
            assertEquals(200, asked.status.value, asked.bodyAsText())
            val gate = asked.json().getValue("gate").jsonObject
            assertEquals("pending", gate.getValue("status").jsonPrimitive.content, "$cls: ${asked.bodyAsText()}")
        }

        val inbox = server.client.get("/api/v1/approvals?mine=true&status=pending") { session(revBSession) }
        assertEquals(200, inbox.status.value, inbox.bodyAsText())
        val items = inbox.json().getValue("items").jsonArray
        assertEquals(1, items.size, "rev-b's inbox: ${inbox.bodyAsText()}")
    }

    private companion object {
        const val CONSOLE_ORIGIN = "http://localhost:5173"
        const val SHA_LENGTH = 40
    }
}
