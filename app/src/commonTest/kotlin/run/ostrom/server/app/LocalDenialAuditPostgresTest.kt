// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartedSession
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.presentation.OrgAuditEventResponse
import run.ostrom.server.identity.presentation.OrgAuditPageResponse
import run.ostrom.server.kernel.auth.CSRF_COOKIE
import run.ostrom.server.kernel.auth.CSRF_HEADER
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every module together, on a real PostgreSQL (audit A6): a push the hook refused on the workstation is written to the
 * organization's signed audit log, which its owner reads (`GET /api/v1/audit`), and nowhere else. Another
 * organization's token reporting on that session gets a 404 and writes nothing in either log; a closed session a 409.
 */
class LocalDenialAuditPostgresTest {
    private fun HttpRequestBuilder.session(started: StartedSession) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
        header(CSRF_HEADER, started.csrfToken)
        header(HttpHeaders.Origin, CONSOLE_ORIGIN)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    /**
     * [login] signs in, creates their organization, and gets a token that starts sessions and asks gates. Each owner
     * has their own e-mail domain: a second free team of one domain is refused.
     */
    private suspend fun TestServer.owner(login: String, org: String): Pair<StartedSession, String> {
        val email = "$login@${org.lowercase()}.test"
        val identity = VerifiedIdentity(IdentityProvider.GITHUB, login, email, true, login, login = login)
        val started = koin.get<StartWebSessionUseCase>()(koin.get<ResolveUserUseCase>()(identity).id)
        val created = client.post("/api/v1/orgs") {
            session(started)
            json("""{"name":"$org"}""")
        }
        assertTrue(created.status.value in SUCCESS, created.bodyAsText())
        val token = client.post("/api/v1/tokens") {
            session(started)
            json("""{"name": "hook", "scopes": ["gates:ask", "sessions:write"], "expiresInDays": 1}""")
        }
        assertEquals(HttpStatusCode.Created, token.status, token.bodyAsText())
        val secret = Json.parseToJsonElement(token.bodyAsText()).jsonObject.getValue("secret").jsonPrimitive.content
        return started to secret
    }

    private suspend fun TestServer.report(secret: String, session: String): HttpResponse =
        client.post("/api/v1/gates/local-denials") {
            bearerAuth(secret)
            json(
                """{"sessionId": "$session", "class": "push-main", "reason": "redirected",
                   "command": "git --git-dir=../other/.git push origin main", "repo": "acme/api"}""",
            )
        }

    private suspend fun TestServer.denials(owner: StartedSession): List<OrgAuditEventResponse> {
        val log = client.get("/api/v1/audit") { session(owner) }
        assertEquals(HttpStatusCode.OK, log.status, log.bodyAsText())
        return log.body<OrgAuditPageResponse>().items.filter { it.event == "gate.denied_locally" }
    }

    @Test
    fun aLocalRefusalIsInItsOrganizationsAuditLogOnly() = serverTest(serverModules()) { server ->
        val (ada, adaToken) = server.owner("ada", "Acme")
        val (zoe, zoeToken) = server.owner("zoe", "Zeta")
        val started = server.client.post("/api/v1/sessions") {
            bearerAuth(adaToken)
            json("""{"id": "$SESSION", "agent": "claude-code", "repo": "acme/api"}""")
        }
        assertTrue(started.status.value in SUCCESS, started.bodyAsText())

        assertEquals(HttpStatusCode.Accepted, server.report(adaToken, SESSION).status)
        // Row-level security: Zeta's token does not find Acme's session, and writes nothing.
        assertEquals(HttpStatusCode.NotFound, server.report(zoeToken, SESSION).status)

        val line = server.denials(ada).single()
        assertEquals("ada", line.actor)
        assertEquals("session:$SESSION", line.subject)
        val meta = mapOf(
            "session" to SESSION,
            "class" to "push-main",
            "reason" to "redirected",
            "command" to "git --git-dir=../other/.git push origin main",
            "repo" to "acme/api",
        )
        assertEquals(meta, line.meta - "credential")
        assertEquals(emptyList(), server.denials(zoe), "Zeta's log holds nothing of Acme's session")

        // A closed session: 409, nothing more written.
        val ended = server.client.post("/api/v1/sessions/$SESSION/end") {
            bearerAuth(adaToken)
            json("{}")
        }
        assertTrue(ended.status.value in SUCCESS, ended.bodyAsText())
        assertEquals(HttpStatusCode.Conflict, server.report(adaToken, SESSION).status)
        assertEquals(1, server.denials(ada).size)
    }

    private companion object {
        const val CONSOLE_ORIGIN = "http://localhost:5173"
        const val SESSION = "claude-a6"
        val SUCCESS = 200..299
    }
}
