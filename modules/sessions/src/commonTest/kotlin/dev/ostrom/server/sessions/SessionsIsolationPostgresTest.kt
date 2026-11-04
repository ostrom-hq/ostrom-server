// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions

import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository
import dev.ostrom.server.sessions.domain.services.SessionCosts
import dev.ostrom.server.sessions.fakes.SettableClock
import dev.ostrom.server.sessions.presentation.SessionResponse
import dev.ostrom.server.sessions.presentation.SessionsPage
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Another organization can neither keep alive, mark as seen nor reopen a session of the organization that started it:
 * each route answers 404 and the session is unchanged when its own organization reads it back. The other
 * organization's caller has the same person and launch as the session's, so only the organization tells them apart.
 */
class SessionsIsolationPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val clock = SettableClock()

    // Tokens are `<kind>:<org letter>:<id>`, as in SessionsPostgresTest: `user:a:lyes`; `agent:a:<launch>` is an
    // agent token of `lyes`.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.ALL_USER))
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.AGENT_SESSION))
                    else -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.READ))
                }
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? {
                    val (kind, org, id) = principal.subjectId.split(":").takeIf { it.size == 3 } ?: return null
                    val orgId = if (org == "a") orgA else orgB
                    return when (kind) {
                        "agent" ->
                            GateActor("agent-$id", orgId, ActorKind.AGENT_SESSION, launchId = id, personId = "lyes")
                        else -> GateActor.person(id, orgId, OrgRole.MEMBER)
                    }
                }
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
                    val org = principal.subjectId.split(":").getOrNull(1) ?: return null
                    return OrgPrincipal(if (org == "a") orgA else orgB, Uuid.random(), OrgRole.MEMBER)
                }
            }
        }
        single<Clock> { clock }
        single<SessionCosts> {
            object : SessionCosts {
                override suspend fun costOf(orgId: Uuid, launchId: String?, from: Instant, to: Instant): Double = 0.42
            }
        }
    }

    private fun sessionsTest(block: suspend (TestServer) -> Unit) = serverTest(
        listOf(InstanceModule, ReceiptsModule, GatesModule, SessionsModule),
        overrides,
    ) { server -> block(server) }

    private suspend fun TestServer.call(token: String, path: String, body: String? = null): HttpResponse =
        if (body == null) {
            client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
        } else {
            client.post(path) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    @Test
    fun anotherOrganizationNeitherKeepsAliveNorMarksSeenNorReopensTheSession() = sessionsTest { server ->
        val agentA = "agent:a:launch-1"
        val agentB = "agent:b:launch-1"
        val start = """{"id":"s-1","agent":"claude-code","repo":"acme/api"}"""
        val started = server.call(agentA, "/api/v1/sessions", start)
        assertEquals(HttpStatusCode.OK, started.status)
        val before = server.call("user:a:lyes", "/api/v1/sessions/s-1").body<SessionResponse>()

        clock.now += 1.minutes
        assertEquals(HttpStatusCode.NotFound, server.call(agentB, "/api/v1/sessions/s-1/alive", "{}").status)
        val seen = """{"kind":"skill","name":"deploy","via":"hook"}"""
        assertEquals(HttpStatusCode.NotFound, server.call(agentB, "/api/v1/sessions/s-1/seen", seen).status)
        assertEquals(
            before,
            server.call("user:a:lyes", "/api/v1/sessions/s-1").body<SessionResponse>(),
            "No sign of life of B is recorded on A's session",
        )
        assertEquals(
            emptyList(),
            server.koin.get<SessionsRepository>().get(orgA, "s-1")?.seen,
            "Nothing B reported is recorded",
        )

        assertEquals(HttpStatusCode.OK, server.call(agentA, "/api/v1/sessions/s-1/end", "{}").status)
        assertEquals(HttpStatusCode.NotFound, server.call(agentB, "/api/v1/sessions/s-1/reopen", "{}").status)
        val open = server.call("user:a:lyes", "/api/v1/sessions?open=true").body<SessionsPage>()
        assertTrue(open.items.isEmpty(), "B opened no segment after A's ended one")

        val reopened = server.call(agentA, "/api/v1/sessions/s-1/reopen", "{}").body<SessionResponse>()
        assertEquals(true to "s-1~r2", reopened.reopened to reopened.session.id, "A's own launcher still reopens it")
    }
}
