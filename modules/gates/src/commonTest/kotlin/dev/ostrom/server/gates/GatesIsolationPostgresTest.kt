// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import dev.ostrom.server.gates.presentation.AnswerApprovalRequest
import dev.ostrom.server.gates.presentation.ApprovalAnswer
import dev.ostrom.server.gates.presentation.ApprovalsPage
import dev.ostrom.server.gates.presentation.GateAskRequest
import dev.ostrom.server.gates.presentation.GatePush as PushDto
import dev.ostrom.server.gates.presentation.GatePushRef
import dev.ostrom.server.gates.presentation.GateResponse
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
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
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Another organization can neither reserve nor release a gate of the organization that asked it: every move answers
 * 404 and the gate keeps the state its own organization gave it (row-level security, the serving role).
 */
class GatesIsolationPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val session = "claude-5e5"
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    // Tokens are `<kind>:<org letter>:<id>`, as in GatesPostgresTest: `user:a:rev-b`, `agent:b:<session>`.
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
                    else -> AuthenticatedPrincipal(token, PrincipalKind.USER, emptySet())
                }
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? {
                    val (kind, org, id) = principal.subjectId.split(":").takeIf { it.size == 3 } ?: return null
                    val orgId = if (org == "a") orgA else orgB
                    return when (kind) {
                        "user" -> GateActor(id, orgId, ActorKind.USER, personId = id)
                        else -> GateActor("agent", orgId, ActorKind.AGENT_SESSION, launchId = id, personId = "lyes")
                    }
                }
            }
        }
        single<GateSessions> {
            object : GateSessions {
                override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) = Unit

                override suspend fun agentOf(orgId: Uuid, sessionId: String): String? = "claude-code"
            }
        }
        single<GateNodeResolver> {
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) =
                    GateNode("ship", "g-main", "Merge into main", listOf("lyes", "rev-b", "rev-c"), enforced = true)
                        .takeIf { actionClass == "push-main" || actionClass == "merge" }
            }
        }
        single<PullRequestHeads> {
            object : PullRequestHeads {
                override suspend fun head(orgId: Uuid, repo: String, number: Int) =
                    GatePullRequest(number, sha, "main").takeIf { number == 6 }
            }
        }
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }

    private fun gatesTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(GatesModule), overrides) { server -> block(server) }

    private suspend fun TestServer.call(
        token: String,
        path: String,
        body: Any? = null,
        post: Boolean = body != null,
    ): HttpResponse =
        if (!post) {
            client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
        } else {
            client.post(path) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                if (body != null) setBody(body)
            }
        }

    private fun push() = GateAskRequest(
        sessionId = session,
        repo = "ostrom-hq/gate-demo",
        cls = "push-main",
        command = "git push origin main",
        push = PushDto("git@github.com:ostrom-hq/gate-demo.git", listOf(GatePushRef("refs/heads/main", sha)), false),
        diff = "+ok",
    )

    private suspend fun TestServer.move(token: String, gate: GateResponse, move: String): HttpResponse =
        call(token, "/api/v1/gates/${gate.gate.id}/$move", post = true)

    @Test
    fun anotherOrganizationNeitherReservesNorReleasesTheGate() = gatesTest { server ->
        val agentA = "agent:a:$session"
        val agentB = "agent:b:$session"
        val opened = server.call(agentA, "/api/v1/gates", push()).body<GateResponse>()
        val approval = server.call("user:a:rev-b", "/api/v1/approvals?limit=100").body<ApprovalsPage>().items
            .first { it.group == opened.gate.id && it.requestedFrom == "rev-b" }
        val yes = server.call(
            "user:a:rev-b",
            "/api/v1/approvals/${approval.id}/answer",
            AnswerApprovalRequest(ApprovalAnswer.APPROVE, approval.shownDigest),
        )
        assertEquals(HttpStatusCode.OK, yes.status)

        assertEquals(HttpStatusCode.NotFound, server.move(agentB, opened, "reserve").status, "B never holds A's yes")
        val reserved = server.move(agentA, opened, "reserve")
        assertEquals(HttpStatusCode.OK, reserved.status, "A's yes was not held by B")
        assertEquals("approved", reserved.body<GateResponse>().gate.status)

        assertEquals(HttpStatusCode.NotFound, server.move(agentB, opened, "release").status, "B never gives it back")
        assertEquals(
            HttpStatusCode.Conflict,
            server.move(agentA, opened, "reserve").status,
            "A's yes is still held by A: B's release changed nothing",
        )
        assertEquals("approved", server.move(agentA, opened, "release").body<GateResponse>().gate.status)
    }
}
