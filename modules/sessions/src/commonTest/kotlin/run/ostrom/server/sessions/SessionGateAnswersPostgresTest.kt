// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sessions

import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.gates.presentation.AnswerApprovalRequest
import run.ostrom.server.gates.presentation.ApprovalAnswer
import run.ostrom.server.gates.presentation.ApprovalsPage
import run.ostrom.server.gates.presentation.GateAskRequest
import run.ostrom.server.gates.presentation.GatePush
import run.ostrom.server.gates.presentation.GatePushRef
import run.ostrom.server.gates.presentation.GateResponse
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.sessions.presentation.SessionResponse
import run.ostrom.server.sessions.presentation.SessionScopes
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
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
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The thread of a session (`ostrom logs`) shows the answer to each gate the session asked, to the device's token that
 * reads sessions and not gates: yes or no, who answered, the receipt. Nothing of another session's gates, and nothing
 * at all to another organization or to another launch's agent token. Real PostgreSQL, real HTTP, real gates.
 */
class SessionGateAnswersPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")

    // Tokens are `<kind>:<org letter>:<id>`: `user:a:rev-b` is a browser signed in just now, `device:a:lyes` the
    // device of `ostrom login` (sessions only, no gates), `agent:a:<launch>` an agent token of `lyes`.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user" -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.USER,
                        setOf(GateScopes.ALL_USER),
                        channel = PrincipalChannel.BROWSER,
                        authenticatedAt = Clock.System.now(),
                    )
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.AGENT_SESSION))
                    else -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(SessionScopes.READ))
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
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
                    val org = principal.subjectId.split(":").getOrNull(1) ?: return null
                    return OrgPrincipal(if (org == "a") orgA else orgB, Uuid.random(), OrgRole.MEMBER)
                }
            }
        }
        // A push to main waits for a yes of `lyes`, `rev-b` or `rev-c`; the asker never answers their own.
        single<GateNodeResolver> {
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) =
                    GateNode("ship", "g-main", "Merge into main", listOf("lyes", "rev-b", "rev-c"), enforced = true)
                        .takeIf { actionClass == "push-main" }
            }
        }
    }

    private fun answersTest(block: suspend (TestServer) -> Unit) = serverTest(
        listOf(InstanceModule, ReceiptsModule, GatesModule, SessionsModule),
        overrides,
    ) { server -> block(server) }

    private suspend fun TestServer.call(token: String, path: String, body: Any? = null): HttpResponse =
        if (body == null) {
            client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
        } else {
            client.post(path) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    /** Session [id] of [launch] asks to push [sha] to main: the gate, pending. */
    private suspend fun TestServer.askPush(launch: String, id: String, sha: String): GateResponse {
        val agent = "agent:a:$launch"
        val started = call(agent, "/api/v1/sessions", """{"id":"$id","agent":"claude-code","repo":"acme/api"}""")
        assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())
        val push = GatePush("git@github.com:acme/api.git", listOf(GatePushRef("refs/heads/main", sha)), false)
        val ask = GateAskRequest(
            sessionId = id, repo = "acme/api", cls = "push-main", command = "git push origin main", push = push,
            diff = "+ok",
        )
        val asked = call(agent, "/api/v1/gates", ask)
        assertEquals(HttpStatusCode.OK, asked.status, asked.bodyAsText())
        return asked.body<GateResponse>().also { assertEquals("pending", it.gate.status) }
    }

    /** `rev-b` answers [gate] from the console. */
    private suspend fun TestServer.answer(gate: GateResponse, yes: Boolean) {
        val approval = call("user:a:rev-b", "/api/v1/approvals?limit=100").body<ApprovalsPage>().items
            .first { it.group == gate.gate.id && it.requestedFrom == "rev-b" }
        val choice = if (yes) ApprovalAnswer.APPROVE else ApprovalAnswer.REJECT
        val answer = AnswerApprovalRequest(choice, approval.shownDigest)
        val answered = call("user:a:rev-b", "/api/v1/approvals/${approval.id}/answer", answer)
        assertEquals(HttpStatusCode.OK, answered.status, answered.bodyAsText())
    }

    @Test
    fun theThreadOfASessionShowsTheAnswersToItsOwnGatesOnly() = answersTest { server ->
        val first = server.askPush("launch-1", "s-1", "0123456789abcdef0123456789abcdef01234567")
        val second = server.askPush("launch-2", "s-2", "fedcba9876543210fedcba9876543210fedcba98")
        server.answer(first, yes = true)
        server.answer(second, yes = false)

        val device = "device:a:lyes"
        assertEquals(
            HttpStatusCode.Forbidden,
            server.call(device, "/api/v1/gates/${first.gate.id}").status,
            "The device reads no gate",
        )
        val one = server.call(device, "/api/v1/sessions/s-1").body<SessionResponse>().session
        val yes = assertNotNull(one.gateAnswers).single()
        assertEquals(
            listOf(first.gate.id, "push-main", "approved", "rev-b"),
            listOf(yes.gateId, yes.actionClass, yes.outcome, yes.by),
        )
        assertNotNull(yes.receiptId, "The gate's receipt")
        val two = server.call(device, "/api/v1/sessions/s-2").body<SessionResponse>().session
        val no = assertNotNull(two.gateAnswers).single()
        assertEquals(listOf(second.gate.id, "refused", "rev-b"), listOf(no.gateId, no.outcome, no.by))

        // Another launch's agent of the same organization, and another organization: nothing of s-1 at all.
        assertEquals(HttpStatusCode.NotFound, server.call("agent:a:launch-2", "/api/v1/sessions/s-1").status)
        assertEquals(HttpStatusCode.NotFound, server.call("device:b:lyes", "/api/v1/sessions/s-1").status)
    }
}
