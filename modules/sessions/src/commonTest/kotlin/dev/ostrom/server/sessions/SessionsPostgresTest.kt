// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions

import org.koin.core.qualifier.named
import dev.ostrom.server.kernel.observability.CachedCount
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
import dev.ostrom.server.sessions.domain.models.SessionNotFoundException
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository
import dev.ostrom.server.sessions.domain.services.SessionCosts
import dev.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCase
import dev.ostrom.server.sessions.fakes.SettableClock
import dev.ostrom.server.sessions.infrastructure.SessionsGateGuard
import dev.ostrom.server.sessions.presentation.SessionDto
import dev.ostrom.server.sessions.presentation.SessionResponse
import dev.ostrom.server.sessions.presentation.SessionScopes
import dev.ostrom.server.sessions.presentation.SessionsPage
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SessionsPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val clock = SettableClock()

    // Tokens are `<kind>:<org letter>:<id>`: `user:a:rev-b`; `agent:a:<launch>` is an agent token of `lyes`.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.ALL_USER))
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.AGENT_SESSION))
                    "cli" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(SessionScopes.WRITE))
                    "reader" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(SessionScopes.READ))
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
                        else -> GateActor(id, orgId, ActorKind.USER, personId = id)
                    }
                }
            }
        }
        // The receipts routes read the organization through the orgs module's resolver: the token's org letter.
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
                    val org = principal.subjectId.split(":").getOrNull(1) ?: return null
                    return OrgPrincipal(if (org == "a") orgA else orgB, Uuid.random(), OrgRole.MEMBER)
                }
            }
        }
        single<Clock> { clock }
        // The server's measure of a session's cost (the usage module binds the real one).
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

    private suspend fun TestServer.call(token: String?, path: String, body: String? = null): HttpResponse =
        if (body == null) {
            client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        } else {
            client.post(path) {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun TestServer.start(token: String, id: String, repo: String = "acme/api"): SessionDto =
        call(token, "/api/v1/sessions", """{"id":"$id","agent":"claude-code","repo":"$repo","dev":"mallory"}""")
            .body<SessionResponse>().session

    @Test
    fun aSessionLivesEndsSignedAndTakesNoMoreGates() = sessionsTest { server ->
        val session = server.start("agent:a:launch-1", "s-1")
        assertEquals("lyes", session.dev, "The person is the credential's, never the body's")
        assertEquals("launch-1", session.launchId)

        clock.now += 1.minutes
        val alive = server.call("agent:a:launch-1", "/api/v1/sessions/s-1/alive", "{}").body<SessionDto>()
        assertEquals(clock.now.toString(), alive.lastSeenAt)
        assertEquals(false, alive.ended)
        val gate = server.call(
            "agent:a:launch-1",
            "/api/v1/gates",
            """{"sessionId":"s-1","class":"deploy","command":"kubectl apply -f prod.yaml"}""",
        )
        assertEquals(HttpStatusCode.OK, gate.status, gate.bodyAsText())
        val seen = """{"kind":"skill","name":"deploy","via":"hook"}"""
        val recorded = server.call("agent:a:launch-1", "/api/v1/sessions/s-1/seen", seen)
        assertEquals(HttpStatusCode.NoContent, recorded.status)

        // S2-48: the agent's own figure is ignored; the cost is the server's measure.
        val end = """{"exitCode":0,"tools":3,"costUsd":99.0}"""
        val ended = server.call("agent:a:launch-1", "/api/v1/sessions/s-1/end", end).body<SessionResponse>()
        assertEquals(0.42, ended.session.costUsd)
        val read = server.call("user:a:lyes", "/api/v1/sessions/s-1").body<SessionResponse>()
        assertEquals(0.42, read.session.costUsd, "read back from the database")
        val receipt = assertNotNull(ended.receipt)
        assertEquals("rcpt_session_s-1", ended.session.receiptId)
        assertEquals(1, ended.session.gates?.size)
        val verified = server.call("user:a:rev-b", "/api/v1/receipts/rcpt_session_s-1/verify").bodyAsText()
        assertTrue("\"ok\":true" in verified, verified)
        assertEquals(receipt, server.call("user:a:rev-b", "/api/v1/receipts/rcpt_session_s-1").body<JsonObject>())

        val refused = server.call(
            "agent:a:launch-1",
            "/api/v1/gates",
            """{"sessionId":"s-1","class":"deploy","command":"kubectl apply -f prod.yaml"}""",
        )
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("session closed: s-1 ended at" in refused.bodyAsText(), refused.bodyAsText())
        assertEquals(HttpStatusCode.Conflict, server.call("agent:a:launch-1", "/api/v1/sessions/s-1/seen", seen).status)
        val after = server.call("agent:a:launch-1", "/api/v1/sessions/s-1/alive", "{}").body<SessionDto>()
        assertEquals(true to false, after.ended to after.lost, "Ended by its launcher: not lost")
    }

    @Test
    fun theSweepClosesTheLostSessionsOfEveryOrganizationAndTheirLauncherReopens() = sessionsTest { server ->
        server.start("agent:a:launch-1", "s-1")
        server.start("agent:b:launch-2", "s-2")
        server.start("agent:a:launch-3", "s-3")
        server.call("agent:a:launch-1", "/api/v1/sessions/s-1/alive", "{}")
        server.call("agent:b:launch-2", "/api/v1/sessions/s-2/alive", "{}")
        clock.now += 3.minutes

        val closed = server.koin.get<SweepStaleSessionsUseCase>()()
        assertEquals(setOf("s-1", "s-2"), closed.toSet(), "Both organizations, only the followed sessions")
        val lost = server.call("agent:b:launch-2", "/api/v1/sessions/s-2/alive", "{}").body<SessionDto>()
        assertEquals(true to true, lost.ended to lost.lost)

        val reopened = server.call("agent:b:launch-2", "/api/v1/sessions/s-2/reopen", "{}").body<SessionResponse>()
        assertEquals(true, reopened.reopened)
        assertEquals("s-2~r2", reopened.session.id)
        assertEquals("rcpt_session_s-2", reopened.session.previousReceiptId)
        assertEquals(2, reopened.session.segment)
        assertEquals("s-2", reopened.session.agentSessionId)
        val again = server.call("agent:b:launch-2", "/api/v1/sessions/s-2/reopen", "{}").body<SessionResponse>()
        assertEquals(false to "s-2~r2", again.reopened to again.session.id)
        val guard = SessionsGateGuard(server.koin.get<SessionsRepository>())
        assertEquals("s-2~r2", guard.openSegment(orgB, "s-2", "launch-2"), "Found from the agent's session id")
        assertFailsWith<SessionNotFoundException>("Another organization's") { guard.openSegment(orgA, "s-2", null) }
        assertNull(server.koin.get<SessionsRepository>().last(orgA, "s-2"), "No segment of another organization")
        assertEquals(
            HttpStatusCode.Forbidden,
            server.call("agent:b:launch-9", "/api/v1/sessions/s-2/reopen", "{}").status,
        )
    }

    @Test
    fun twoLaunchesResumingAtOnceLeaveOneOpenSegment() = sessionsTest { server ->
        // S2-48: the first live launch keeps the session; the others are refused, nobody's yes is lost.
        val body = """{"id":"s-1","agent":"claude-code","repo":"acme/api"}"""
        val statuses = coroutineScope {
            (1..3).map { n -> async { server.call("agent:a:launch-$n", "/api/v1/sessions", body).status } }.awaitAll()
        }
        val expected = listOf(HttpStatusCode.OK, HttpStatusCode.Forbidden, HttpStatusCode.Forbidden)
        assertEquals(expected, statuses.sortedBy { it.value })

        val noOrg = server.call("user:nobody", "/api/v1/sessions?open=true")
        assertEquals(HttpStatusCode.NotFound, noOrg.status, "a credential of no organization")
        val page = server.call("user:a:lyes", "/api/v1/sessions?open=true").body<SessionsPage>()
        assertEquals(1, page.items.size, "One open segment")
        val ended = server.call("user:a:lyes", "/api/v1/sessions?open=false").body<SessionsPage>().items
        assertTrue(ended.isEmpty(), "No live segment was closed by another launch")
    }

    @Test
    fun sessionsAreListedByPageAndOrganization() = sessionsTest { server ->
        for (n in 1..5) {
            clock.now += 1.minutes
            server.start("agent:a:launch-$n", "s-$n", repo = if (n % 2 == 0) "acme/web" else "acme/api")
        }
        server.start("agent:b:launch-9", "s-9")

        val first = server.call("user:a:rev-b", "/api/v1/sessions?limit=2").body<SessionsPage>()
        assertEquals(listOf("s-5", "s-4"), first.items.map { it.id })
        val second = server.call("user:a:rev-b", "/api/v1/sessions?limit=2&before=${first.next}").body<SessionsPage>()
        assertEquals(listOf("s-3", "s-2"), second.items.map { it.id })
        val web = server.call("user:a:rev-b", "/api/v1/sessions?repo=acme/web&dev=lyes").body<SessionsPage>()
        assertEquals(listOf("s-4", "s-2"), web.items.map { it.id })
        assertNull(web.next)
        val own = server.call("agent:a:launch-3", "/api/v1/sessions").body<SessionsPage>()
        assertEquals(listOf("s-3"), own.items.map { it.id }, "An agent token lists its own launch's only")
        val orgBPage = server.call("user:b:x", "/api/v1/sessions").body<SessionsPage>()
        assertEquals(listOf("s-9"), orgBPage.items.map { it.id })

        assertEquals(HttpStatusCode.NotFound, server.call("user:b:x", "/api/v1/sessions/s-1").status)
        assertEquals(HttpStatusCode.NotFound, server.call("agent:a:launch-2", "/api/v1/sessions/s-1").status)
        assertEquals(HttpStatusCode.OK, server.call("user:a:rev-b", "/api/v1/sessions/s-1").status)
        assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/api/v1/sessions").status)
        assertEquals(HttpStatusCode.Forbidden, server.call("nobody", "/api/v1/sessions").status)
        // S2-03: a credential without a sessions scope is refused before its organization is looked up.
        val gatesOnly = server.call("gates:a:rev-b", "/api/v1/sessions")
        assertEquals(HttpStatusCode.Forbidden, gatesOnly.status)
        assertTrue(gatesOnly.bodyAsText().contains("does not allow sessions:read"))
        assertEquals(HttpStatusCode.OK, server.call("reader:a:rev-b", "/api/v1/sessions/s-1").status)
        assertEquals(HttpStatusCode.Forbidden, server.call("reader:a:rev-b", "/api/v1/sessions/s-1/alive", "{}").status)
        assertEquals(HttpStatusCode.OK, server.call("cli:a:rev-b", "/api/v1/sessions?limit=1").status)
        assertEquals(HttpStatusCode.Forbidden, server.call("user:a:rev-b", "/api/v1/sessions/s-1/end", "{}").status)
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:lyes", "/api/v1/sessions/s-1/end", "{}").status)
        val malformed = server.call("user:a:lyes", "/api/v1/sessions", """{"id":"a b","agent":"x"}""")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }

    @Test
    fun aPageSizeOutOfItsBoundsIsBroughtBackOrRefusedNeverAFailure() = sessionsTest { server ->
        // AUDIT-SECU-LECTURES: a huge or negative limit is brought within 1 to 100; one that is no Int is a 400.
        for (n in 1..3) {
            clock.now += 1.minutes
            server.start("agent:a:launch-$n", "s-$n")
        }
        val huge = server.call("user:a:rev-b", "/api/v1/sessions?limit=1000000").body<SessionsPage>()
        assertEquals(3, huge.items.size)
        val negative = server.call("user:a:rev-b", "/api/v1/sessions?limit=-5").body<SessionsPage>()
        assertEquals(listOf("s-3"), negative.items.map { it.id }, "brought up to one")
        for (wrong in listOf("99999999999", "abc", "1.5")) {
            val refused = server.call("user:a:rev-b", "/api/v1/sessions?limit=$wrong")
            assertEquals(HttpStatusCode.BadRequest, refused.status, wrong)
            // The fixed message only: never the parser's, which would quote the value.
            assertTrue("The request is malformed." in refused.bodyAsText(), refused.bodyAsText())
        }
    }

    @Test
    fun theDatabaseItselfKeepsOrganizationsApart() = sessionsTest { server ->
        server.start("agent:a:launch-1", "s-1")
        val count = "SELECT COUNT(*) AS n FROM sessions_sessions"
        // The test server's driver is the serving role, as in production: row level security applies to it.
        val seen = server.driver.transaction {
            val none = fetchAll(count).getOrThrow().first().get("n").asString()
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgB.toString()))
                .getOrThrow()
            val other = fetchAll(count).getOrThrow().first().get("n").asString()
            fetchAll("SELECT set_config('ostrom.sweep', 'lost-sessions', TRUE)").getOrThrow()
            val swept = fetchAll(count).getOrThrow().first().get("n").asString()
            val written = execute("UPDATE sessions_sessions SET dev = 'mallory'").getOrThrow()
            listOf(none, other, swept, written.toString())
        }
        // The old sweep setting opens nothing any more (RLS audit R4): only sessions_stale reads across organizations.
        assertEquals(listOf("0", "0", "0", "0"), seen, "No setting opens another organization, nothing is written")
        // The driver throws a refused write rather than returning it as a failure: both count as refused.
        val insert = try {
            server.driver.transaction {
                fetchAll(
                    Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgB.toString()),
                ).getOrThrow()
                execute(
                    Statement.create(
                        "INSERT INTO sessions_sessions (org_id, id, root_id, segment, agent, dev, started_at) " +
                            "VALUES (:org, 'x', 'x', 1, 'a', 'd', now())",
                    ).bind("org", orgA),
                ).getOrThrow()
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (expected: Exception) {
            Result.failure(expected)
        }
        assertTrue(insert.isFailure, "A row of another organization cannot be written")
        assertEquals("lyes", server.koin.get<SessionsRepository>().get(orgA, "s-1")?.dev)
    }

    @Test
    fun anAliveWithoutABodyIsASignOfLife() = sessionsTest { server ->
        server.start("agent:a:launch-1", "s-1")
        val alive = server.client.post("/api/v1/sessions/s-1/alive") {
            header(HttpHeaders.Authorization, "Bearer agent:a:launch-1")
        }
        assertEquals(HttpStatusCode.OK, alive.status)
        assertEquals("s-1", alive.body<JsonObject>().getValue("id").jsonPrimitive.content)
    }

    @Test
    fun theActiveCountSeesTheFollowedSessionsOfEveryOrganization() = sessionsTest { server ->
        server.start("agent:a:launch-1", "s-1")
        server.start("agent:b:launch-2", "s-2")
        server.start("agent:a:launch-3", "s-3")
        server.start("agent:a:launch-4", "s-4")
        listOf("a:launch-1" to "s-1", "b:launch-2" to "s-2", "a:launch-4" to "s-4").forEach { (launch, id) ->
            server.call("agent:$launch", "/api/v1/sessions/$id/alive", "{}")
        }
        server.call("agent:a:launch-4", "/api/v1/sessions/s-4/end", "{}")
        val active = server.koin.get<CachedCount>(named(SessionsModule.ACTIVE_SESSIONS))

        active.refresh()
        assertEquals(2, active.value(), "Both organizations; neither a hook alone (s-3) nor an ended session (s-4)")
        clock.now += 3.minutes
        active.refresh()
        assertEquals(0, active.value(), "A silent launcher is no running session")
    }
}
