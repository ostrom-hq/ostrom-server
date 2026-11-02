// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import dev.ostrom.server.gates.domain.usecases.AskGateUseCase
import dev.ostrom.server.gates.domain.usecases.ListSessionGatesUseCase
import dev.ostrom.server.gates.presentation.AnswerApprovalRequest
import dev.ostrom.server.gates.presentation.Approval
import dev.ostrom.server.gates.presentation.ApprovalAnswer
import dev.ostrom.server.gates.presentation.ApprovalViewResponse
import dev.ostrom.server.gates.presentation.ApprovalsPage
import dev.ostrom.server.gates.presentation.GateAskRequest
import dev.ostrom.server.gates.presentation.GatePullRequest as PullRequestDto
import dev.ostrom.server.gates.presentation.GatePush as PushDto
import dev.ostrom.server.gates.presentation.GatePushRef
import dev.ostrom.server.gates.presentation.GateResponse
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.gates.presentation.toAskGate
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** The gates against a real PostgreSQL and real HTTP: every parcours of a gate, and the isolation of organizations. */
class GatesPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val session = "claude-5e5"
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val nextSha = "fedcba9876543210fedcba9876543210fedcba98"

    // Tokens are `<kind>:<org letter>:<id>`: `user:a:rev-b`, `agent:a:<session>`, `device:a:lyes`, `reader:a:ops`.
    // A user's browser session holds every scope; a device holds `gates:answer` yet is no person (S2-31).
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    // A browser session signed in just now; `stale` signed in ten minutes ago (S2-54).
                    "user", "stale" -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.USER,
                        setOf(GateScopes.ALL_USER),
                        channel = PrincipalChannel.BROWSER,
                        authenticatedAt = Clock.System.now() - if (token.startsWith("stale")) 10.minutes else 0.minutes,
                    )
                    // The app: the biometric unlock is its fresh proof.
                    "app" -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.USER,
                        setOf(GateScopes.ANSWER),
                        channel = PrincipalChannel.MOBILE_APP,
                        authenticatedAt = Clock.System.now(),
                    )
                    // The app before its biometric unlock: it reads, it does not answer yet.
                    "locked" -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.USER,
                        setOf(GateScopes.READ),
                        channel = PrincipalChannel.MOBILE_APP,
                        authenticatedAt = Clock.System.now() - 1.hours,
                    )
                    // A personal token of the CLI: a person's, with every scope, yet readable by the agent (S2-31).
                    "cli" ->
                        AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.ALL_USER, GateScopes.ANSWER))
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.AGENT_SESSION))
                    // A browser session with the read scope only.
                    "reader" -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.USER,
                        setOf(GateScopes.READ),
                        channel = PrincipalChannel.BROWSER,
                    )
                    "device" -> AuthenticatedPrincipal(token, PrincipalKind.DEVICE, setOf(GateScopes.ANSWER))
                    "runner" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.ASK))
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
                        "user", "reader", "app", "locked", "cli", "stale" ->
                            GateActor(id, orgId, ActorKind.USER, personId = id)
                        "agent" -> GateActor("agent", orgId, ActorKind.AGENT_SESSION, launchId = id, personId = "lyes")
                        "runner" -> GateActor(id, orgId, ActorKind.SERVICE, personId = "lyes")
                        else -> GateActor(id, orgId, ActorKind.DEVICE, personId = id)
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

                // The receipts module is not loaded here: a stored receipt is its id.
                override suspend fun read(orgId: Uuid, receiptId: String?) =
                    receiptId?.let { JsonObject(mapOf("id" to JsonPrimitive(it))) }
            }
        }
    }

    private fun gatesTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(GatesModule), overrides) { server -> block(server) }

    private suspend fun TestServer.call(
        token: String?,
        path: String,
        body: Any? = null,
        post: Boolean = body != null,
    ): HttpResponse =
        if (!post) {
            client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        } else {
            client.post(path) {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                if (body != null) setBody(body)
            }
        }

    private fun push(commit: String = sha, force: Boolean = false, diff: String = "+ok") = GateAskRequest(
        sessionId = session,
        repo = "ostrom-hq/gate-demo",
        cls = "push-main",
        command = "git push origin main",
        dev = "someone-else",
        push = PushDto("git@github.com:ostrom-hq/gate-demo.git", listOf(GatePushRef("refs/heads/main", commit)), force),
        diff = diff,
    )

    // The approvals of a gate, read the way the console reads them: the organization's list.
    private suspend fun TestServer.approvalsOf(gate: GateResponse): List<Approval> =
        call("user:a:rev-b", "/api/v1/approvals?limit=100").body<ApprovalsPage>().items
            .filter { it.group == gate.gate.id }

    // Moves a gate with the agent's token: `reserve`, `release` or `consume`.
    private suspend fun TestServer.move(token: String, gate: GateResponse, move: String): HttpResponse =
        call(token, "/api/v1/gates/${gate.gate.id}/$move", post = true)

    // The status and `error.type`, the key of the contract (core api/openapi.yaml) that a client branches on.
    private suspend fun HttpResponse.assertRefused(expected: HttpStatusCode, type: String, why: String? = null) {
        assertEquals(expected, status, why)
        val error = Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject
        assertEquals(type, error.getValue("type").jsonPrimitive.content, why)
    }

    private suspend fun TestServer.answer(
        token: String,
        gate: GateResponse,
        approver: String,
        yes: Boolean = true,
    ): HttpResponse {
        val approval = approvalsOf(gate).first { it.requestedFrom == approver }
        return call(
            token,
            "/api/v1/approvals/${approval.id}/answer",
            AnswerApprovalRequest(if (yes) ApprovalAnswer.APPROVE else ApprovalAnswer.REJECT, approval.shownDigest),
        )
    }

    @Test
    fun aPushWaitsForAnotherApproverThenRunsOnce() = gatesTest { server ->
        val agent = "agent:a:$session"
        val opened = server.call(agent, "/api/v1/gates", push()).body<GateResponse>()
        assertEquals("pending", opened.gate.status)
        assertEquals("enforced", opened.gate.mode)
        assertEquals("lyes", opened.gate.dev, "The asker is the credential's person, never the body's `dev`")
        assertEquals(listOf("rev-b", "rev-c"), opened.waitingFor)
        assertEquals("Merge into main", opened.gate.gate?.label)
        val approvals = server.approvalsOf(opened)
        assertEquals(listOf("lyes", "rev-b", "rev-c"), approvals.map { it.requestedFrom }.sorted())
        val diff = dev.ostrom.server.gates.presentation.Evidence(
            "Diff (sent by the developer's machine, not checked by Ostrom)",
            "+ok",
        )
        assertTrue(approvals.all { diff in it.evidence })
        assertEquals(listOf(sha), approvals.first().commits)

        val self = server.answer("user:a:lyes", opened, "lyes")
        self.assertRefused(HttpStatusCode.Forbidden, "self_approval_refused", "The asker never approves")
        assertTrue(self.bodyAsText().contains("rev-b or rev-c must say yes"))
        val revB = approvals.first { it.requestedFrom == "rev-b" }
        val admin = server.call(
            "user:a:admin",
            "/api/v1/approvals/${revB.id}/answer",
            AnswerApprovalRequest(ApprovalAnswer.APPROVE, revB.shownDigest),
        )
        assertEquals(HttpStatusCode.Forbidden, admin.status, "An admin never answers in an approver's place")
        assertEquals(
            HttpStatusCode.Forbidden,
            server.answer("device:a:rev-b", opened, "rev-b").status,
            "A device never says yes",
        )
        assertEquals(HttpStatusCode.Forbidden, server.answer(agent, opened, "rev-b").status, "The agent never says yes")
        val stale = server.call(
            "user:a:rev-b",
            "/api/v1/approvals/${revB.id}/answer",
            AnswerApprovalRequest(ApprovalAnswer.APPROVE, "x"),
        )
        stale.assertRefused(HttpStatusCode.Conflict, "shown_digest_mismatch", "A yes names what was shown")

        val answered = server.answer("user:a:rev-b", opened, "rev-b").body<ApprovalViewResponse>()
        assertEquals("approved", answered.approval.status)
        val approved = server.call("user:a:rev-b", "/api/v1/gates/${opened.gate.id}").body<GateResponse>()
        assertEquals("approved", approved.gate.status)
        assertEquals("rev-b", approved.gate.decidedBy)
        assertEquals("rcpt_${opened.gate.id}", approved.gate.receiptId)
        assertEquals(revB.id, approved.gate.approvalId, "The caller's own approval")
        assertEquals(
            mapOf("lyes" to "expired", "rev-b" to "approved", "rev-c" to "expired"),
            server.approvalsOf(opened).associate { it.requestedFrom to it.status },
        )
        server.answer("user:a:rev-c", opened, "rev-c").assertRefused(HttpStatusCode.Conflict, "approval_closed")

        assertEquals("approved", server.move(agent, opened, "reserve").body<GateResponse>().gate.status)
        assertEquals(HttpStatusCode.Conflict, server.move(agent, opened, "reserve").status)
        assertEquals("approved", server.move(agent, opened, "release").body<GateResponse>().gate.status)
        assertEquals("consumed", server.move(agent, opened, "consume").body<GateResponse>().gate.status)
        assertEquals(HttpStatusCode.Conflict, server.move(agent, opened, "consume").status)


        val again = server.call(agent, "/api/v1/gates", push()).body<GateResponse>()
        assertTrue(again.gate.id != opened.gate.id && again.gate.status == "pending", "One yes covers one action")
    }

    @Test
    fun aYesIsReleasedOnceThenStillUsedForItsAction() = gatesTest { server ->
        val agent = "agent:a:$session"
        val opened = server.call(agent, "/api/v1/gates", push()).body<GateResponse>()
        assertEquals(HttpStatusCode.OK, server.answer("user:a:rev-b", opened, "rev-b").status)

        assertEquals("approved", server.move(agent, opened, "reserve").body<GateResponse>().gate.status)
        assertEquals("approved", server.move(agent, opened, "release").body<GateResponse>().gate.status)
        assertEquals("approved", server.move(agent, opened, "reserve").body<GateResponse>().gate.status)
        // A second release would let reserve, launch and release loop until the yes expires (AUDIT-GATES-2 M2).
        assertEquals(HttpStatusCode.Conflict, server.move(agent, opened, "release").status, "One release per yes")
        assertEquals("consumed", server.move(agent, opened, "consume").body<GateResponse>().gate.status)
    }

    @Test
    fun aForcePushOrAnotherDiffIsNotTheApprovedPush() = gatesTest { server ->
        val agent = "agent:a:$session"
        val opened = server.call(agent, "/api/v1/gates", push()).body<GateResponse>()
        assertEquals(HttpStatusCode.OK, server.answer("user:a:rev-b", opened, "rev-b").status)

        val forced = server.call(agent, "/api/v1/gates", push(force = true)).body<GateResponse>()
        val otherDiff = server.call(agent, "/api/v1/gates", push(diff = "+evil")).body<GateResponse>()
        val newCommit = server.call(agent, "/api/v1/gates", push(commit = nextSha)).body<GateResponse>()
        val same = server.call(agent, "/api/v1/gates", push()).body<GateResponse>()

        assertEquals(
            listOf("pending", "pending", "pending"),
            listOf(forced, otherDiff, newCommit).map { it.gate.status },
        )
        assertEquals(opened.gate.id, same.gate.id)
        assertEquals("approved", same.gate.status)
        assertEquals(true, forced.gate.push?.force)
        val forcedByCommand = server.call(agent, "/api/v1/gates", push().copy(command = "git push -f origin main"))
            .body<GateResponse>()
        assertEquals(forced.gate.id, forcedByCommand.gate.id, "Force read from the command too (audit S2-05)")
    }

    @Test
    fun aMergeIsBoundToTheHeadReadFromGitHub() = gatesTest { server ->
        val merge = GateAskRequest(
            sessionId = session,
            repo = "ostrom-hq/gate-demo",
            cls = "merge",
            command = "gh pr merge 6 --merge",
            pr = PullRequestDto("#6", "f".repeat(40), "develop"),
        )
        val opened = server.call("agent:a:$session", "/api/v1/gates", merge).body<GateResponse>()
        assertEquals(PullRequestDto("6", sha, "main"), opened.gate.pr, "Head and base come from GitHub, not the body")

        val unknown = server.call(
            "agent:a:$session",
            "/api/v1/gates",
            merge.copy(pr = PullRequestDto("7", sha), command = "gh pr merge 7"),
        )
        assertEquals(HttpStatusCode.ServiceUnavailable, unknown.status, "Never bound to the text (audit S2-06)")
        val noNumber = server.call("agent:a:$session", "/api/v1/gates", merge.copy(pr = null))
        assertEquals(HttpStatusCode.BadRequest, noNumber.status)
        val malformed = server.call("agent:a:$session", "/api/v1/gates", merge.copy(pr = PullRequestDto("six", sha)))
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }

    @Test
    fun aRedirectedOrOutdatedPushAsksNobody() = gatesTest { server ->
        val redirected = push().copy(command = "git -c remote.origin.pushurl=evil push origin main")
        assertEquals(HttpStatusCode.BadRequest, server.call("agent:a:$session", "/api/v1/gates", redirected).status)
        val outdated = server.call("agent:a:$session", "/api/v1/gates", push().copy(push = null))
        assertEquals(HttpStatusCode.BadRequest, outdated.status)
        assertTrue(outdated.bodyAsText().contains("launcher is out of date"))
        assertTrue(server.call("user:a:rev-b", "/api/v1/approvals?mine=true").body<ApprovalsPage>().items.isEmpty())
    }

    @Test
    fun theFirstOfTwoSimultaneousYesWins() = gatesTest { server ->
        val opened = server.call("agent:a:$session", "/api/v1/gates", push()).body<GateResponse>()
        val approvals = server.approvalsOf(opened)
        val statuses = coroutineScope {
            listOf("rev-b", "rev-c").map { approver ->
                val approval = approvals.first { it.requestedFrom == approver }
                async {
                    server.call(
                        "user:a:$approver",
                        "/api/v1/approvals/${approval.id}/answer",
                        AnswerApprovalRequest(ApprovalAnswer.APPROVE, approval.shownDigest),
                    ).status
                }
            }.awaitAll()
        }
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses.sortedBy { it.value })
    }

    @Test
    fun aSessionFindsItsGates() = gatesTest { server ->
        val agent = "agent:a:$session"
        val first = server.call(agent, "/api/v1/gates", push()).body<GateResponse>()
        server.answer("user:a:rev-b", first, "rev-b")
        val second = server.call(agent, "/api/v1/gates", push(commit = nextSha)).body<GateResponse>()
        server.call("agent:a:other", "/api/v1/gates", push().copy(sessionId = "other-session"))

        val list = server.koin.get<ListSessionGatesUseCase>()
        val gates = list(orgA, session)
        assertEquals(listOf(first.gate.id, second.gate.id), gates.map { it.id.toString() })
        assertEquals(listOf("rev-b"), gates.first().decisions.map { it.by })
        assertEquals(emptyList(), list(orgB, session), "Another organization's session has none of them")
    }

    @Test
    fun aHundredSimultaneousConsumesUseTheYesOnce() = gatesTest { server ->
        val agent = "agent:a:$session"
        val opened = server.call(agent, "/api/v1/gates", push()).body<GateResponse>()
        server.answer("user:a:rev-b", opened, "rev-b")
        val statuses = coroutineScope {
            List(100) { async { server.move(agent, opened, "consume").status } }.awaitAll()
        }
        val counted = statuses.groupingBy { it }.eachCount()
        assertEquals(mapOf(HttpStatusCode.OK to 1, HttpStatusCode.Conflict to 99), counted, "one yes, one use")
        assertEquals(HttpStatusCode.Conflict, server.move(agent, opened, "consume").status, "and never again")
    }

    @Test
    fun aWaitOnAnotherOrganizationsGateIsRefusedAtOnce() = gatesTest { server ->
        val opened = server.call("agent:a:$session", "/api/v1/gates", push()).body<GateResponse>()
        val started = TimeSource.Monotonic.markNow()
        val other = server.call("user:b:rev-b", "/api/v1/gates/${opened.gate.id}?wait=30")
        assertEquals(HttpStatusCode.NotFound, other.status)
        val agentB = server.call("agent:b:$session", "/api/v1/gates/${opened.gate.id}?wait=30")
        assertEquals(HttpStatusCode.NotFound, agentB.status)
        assertTrue(started.elapsedNow() < 5.seconds, "a 404 never waits: ${started.elapsedNow()}")
    }

    @Test
    fun twoHundredWaitingHooksHoldNoConnection() = gatesTest { server ->
        // The test pool has 4 connections: a wait that held one would stall every other request for its 20 s.
        val agent = "agent:a:$session"
        val opened = server.call(agent, "/api/v1/gates", push()).body<GateResponse>()
        val path = "/api/v1/gates/${opened.gate.id}"
        coroutineScope {
            val waiting = List(WAITERS) { async { server.call(agent, "$path?wait=20") } }
            delay(1.seconds)
            val reads = List(10) {
                val started = TimeSource.Monotonic.markNow()
                assertEquals(HttpStatusCode.OK, server.call("user:a:rev-c", path).status)
                started.elapsedNow()
            }
            assertTrue(reads.max() < 2.seconds, "a plain read waited behind the waiting ones: $reads")
            assertTrue(waiting.none { it.isCompleted }, "nothing answered yet")
            val answered = TimeSource.Monotonic.markNow()
            assertEquals(HttpStatusCode.OK, server.answer("user:a:rev-b", opened, "rev-b").status)
            val answers = waiting.awaitAll()
            assertTrue(answered.elapsedNow() < 5.seconds, "the waits end with the answer: ${answered.elapsedNow()}")
            assertEquals(setOf("approved"), answers.map { it.body<GateResponse>().gate.status }.toSet())
        }
    }

    @Test
    fun anotherOrganizationSeesNothing() = gatesTest { server ->
        val opened = server.call("agent:a:$session", "/api/v1/gates", push()).body<GateResponse>()
        val approval = server.approvalsOf(opened).first { it.requestedFrom == "rev-b" }
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:rev-b", "/api/v1/gates/${opened.gate.id}").status)
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:rev-b", "/api/v1/approvals/${approval.id}").status)
        val answer = AnswerApprovalRequest(ApprovalAnswer.APPROVE, approval.shownDigest)
        assertEquals(
            HttpStatusCode.NotFound,
            server.call("user:b:rev-b", "/api/v1/approvals/${approval.id}/answer", answer).status,
        )
        assertEquals(HttpStatusCode.NotFound, server.move("agent:b:$session", opened, "consume").status)
        assertTrue(server.call("user:b:rev-b", "/api/v1/approvals").body<ApprovalsPage>().items.isEmpty())
        assertEquals(approval, server.call("user:a:rev-b", "/api/v1/approvals/${approval.id}").body<Approval>())
        val mine = server.call("user:a:rev-b", "/api/v1/approvals?mine=true&status=pending").body<ApprovalsPage>()
        assertEquals(1, mine.items.size)
        val upper = server.call("user:a:Rev-B", "/api/v1/approvals?mine=true&status=pending").body<ApprovalsPage>()
        assertEquals(1, upper.items.size, "an identity is the same whatever its case")

        assertEquals(HttpStatusCode.BadRequest, server.call("user:a:rev-b", "/api/v1/approvals?status=maybe").status)
    }

    @Test
    fun rowLevelSecurityHidesOtherOrganizationsEvenWithoutAWhereClause() = gatesTest { server ->
        server.call("agent:a:$session", "/api/v1/gates", push()).body<GateResponse>()
        // The test server's driver is the serving role, as in production: row level security applies to it.
        val counts = server.driver.transaction {
            val none = fetchAll("SELECT COUNT(*) AS n FROM gates_gates").getOrThrow().first().get("n").asString()
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgB.toString()))
                .getOrThrow()
            val other = fetchAll("SELECT COUNT(*) AS n FROM gates_gates").getOrThrow().first().get("n").asString()
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgA.toString()))
                .getOrThrow()
            val own = fetchAll("SELECT COUNT(*) AS n FROM gates_gates").getOrThrow().first().get("n").asString()
            listOf(none, other, own)
        }
        assertEquals(listOf("0", "0", "1"), counts, "No org set: nothing; org B: nothing; org A: its gate")
        val forged = server.driver.transaction {
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgB.toString()))
                .getOrThrow()
            execute(
                Statement.create(
                    "INSERT INTO gates_requests (id, gate_id, org_id, position, approver_id, status, created_at) " +
                        "SELECT :id, id, :org, 9, 'x', 'PENDING', NOW() FROM gates_gates LIMIT 1",
                ).bind("id", Uuid.random()).bind("org", orgA),
            )
        }
        assertEquals(0L, forged.getOrThrow(), "Org B can neither see nor write org A's rows")
    }

    @Test
    fun theRepositoryKeepsEveryField() = gatesTest { server ->
        val repository = server.koin.get<GatesRepository>()
        val at = Instant.parse("2026-10-30T02:00:00Z")
        val gate = Gate(
            id = Uuid.random(),
            orgId = orgA,
            key = "k",
            subject = GateSubject(
                repo = "o/r",
                realRepo = "o/fork",
                actionClass = "push-main",
                command = "git push",
                scope = "session:s",
                push = GatePush("remote", listOf(PushRef("refs/heads/main", sha)), force = true),
                pullRequest = GatePullRequest(6, sha, "main"),
                commits = listOf(sha),
                diffDigest = "d",
            ),
            node = null,
            label = "Push",
            mode = GateMode.UNGATED,
            status = GateStatus.CONSUMED,
            askedBy = listOf("lyes", "pr-author"),
            askedVia = setOf(AskedVia.CI, AskedVia.HOOK, AskedVia.MCP),
            sessionIds = listOf(session),
            launchIds = listOf("launch-1"),
            shownDigest = "s",
            evidence = listOf(Evidence("l", "v")),
            decidedBy = "rev-b",
            decidedAt = at,
            selfApproved = true,
            receiptId = "rcpt",
            reservedBy = "launch-1",
            reservedAt = at,
            consumedAt = at,
            createdAt = at,
        )
        repository.transaction { insertGate(gate) }
        assertEquals(gate, repository.get(orgA, gate.id))
        assertEquals(null, repository.get(orgB, gate.id))

        // Joining writes the askers only: a status or a decision in the joiner's stale copy is never written back.
        val joiner = gate.copy(
            askedBy = gate.askedBy + "rev-c",
            askedVia = gate.askedVia - AskedVia.MCP,
            sessionIds = gate.sessionIds + "s-2",
            launchIds = gate.launchIds + "launch-2",
            status = GateStatus.PENDING,
            decidedBy = null,
        )
        repository.transaction { addAskers(joiner) }
        val joined = gate.copy(
            askedBy = joiner.askedBy,
            askedVia = joiner.askedVia,
            sessionIds = joiner.sessionIds,
            launchIds = joiner.launchIds,
        )
        assertEquals(joined, repository.get(orgA, gate.id))
    }

    @Test
    fun theApproversReadCapsEachGateNotTheWholePage() = gatesTest { server ->
        val repository = server.koin.get<GatesRepository>()
        val at = Instant.parse("2026-10-30T02:00:00Z")
        // Ordered by id, the crowded gate comes first: one limit for the whole read would leave the next gate out.
        val crowded = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val next = Uuid.parse("00000000-0000-0000-0000-000000000002")
        fun gateOf(id: Uuid) = Gate(
            id = id,
            orgId = orgA,
            key = "k-$id",
            subject = GateSubject(repo = "o/r", actionClass = "push-main", command = "git push", scope = "session:s"),
            node = null,
            label = "Push",
            mode = GateMode.UNGATED,
            status = GateStatus.PENDING,
            askedBy = listOf("lyes"),
            askedVia = setOf(AskedVia.HOOK),
            sessionIds = listOf(session),
            shownDigest = "s",
            evidence = emptyList(),
            createdAt = at,
        )
        fun requestsOf(gate: Uuid, count: Int) = (0 until count).map {
            ApprovalRequest(Uuid.random(), gate, orgA, "rev-$it", RequestStatus.PENDING, createdAt = at)
        }
        repository.transaction {
            insertGate(gateOf(crowded))
            insertGate(gateOf(next))
            insertRequests(requestsOf(crowded, 101) + requestsOf(next, 2))
        }

        val approvers = repository.approversOf(orgA, listOf(crowded, next))
        assertEquals((0 until 100).map { "rev-$it" }, approvers.getValue(crowded), "100 at most, in their order")
        assertEquals(listOf("rev-0", "rev-1"), approvers.getValue(next))
        assertEquals(emptyMap(), repository.approversOf(orgB, listOf(crowded, next)))
    }

    @Test
    fun aNoClosesTheGateAndTheListFiltersByStatus() = gatesTest { server ->
        val opened = server.call("agent:a:$session", "/api/v1/gates", push()).body<GateResponse>()
        val no = server.answer("user:a:rev-b", opened, "rev-b", yes = false)
        assertEquals(HttpStatusCode.OK, no.status)
        assertEquals("rejected", no.body<ApprovalViewResponse>().approval.status)
        val closed = server.call("user:a:rev-b", "/api/v1/gates/${opened.gate.id}").body<GateResponse>()
        assertEquals("rejected", closed.gate.status)

        suspend fun listed(status: String) =
            server.call("user:a:rev-b", "/api/v1/approvals?status=$status&limit=100").body<ApprovalsPage>().items
                .filter { it.group == opened.gate.id }
        assertEquals(listOf("rev-b"), listed("rejected").map { it.requestedFrom })
        assertEquals(listOf("lyes", "rev-c"), listed("EXPIRED").map { it.requestedFrom }.sorted())
        assertTrue(listed("pending").isEmpty() && listed("approved").isEmpty())
        val unknown = server.call("user:a:rev-b", "/api/v1/approvals?status=maybe")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals(HttpStatusCode.NotFound, server.call("user:a", "/api/v1/approvals").status, "no organization")
    }

    @Test
    fun anAnsweredRequestKeepsItsChannelAndTime() = gatesTest { server ->
        val opened = server.call("agent:a:$session", "/api/v1/gates", push()).body<GateResponse>()
        val repository = server.koin.get<GatesRepository>()
        val gateId = Uuid.parse(opened.gate.id)
        val at = Instant.parse("2026-10-30T02:00:00Z")
        val answered = ApprovalRequest(
            id = Uuid.random(),
            gateId = gateId,
            orgId = orgA,
            approverId = "rev-d",
            status = RequestStatus.APPROVED,
            answeredAt = at,
            channel = AnswerChannel.SLACK,
            createdAt = at,
        )
        repository.transaction { insertRequests(listOf(answered)) }
        assertEquals(answered, repository.requestsOf(orgA, gateId).single { it.approverId == "rev-d" })
        assertEquals(answered, repository.approval(orgA, answered.id)?.request)
        assertNull(repository.approval(orgB, answered.id))
    }

    @Test
    fun theLockedAppSeesItMaySayYesAndUnlocksToSayIt() = gatesTest { server ->
        val opened = server.call("agent:a:$session", "/api/v1/gates", push()).body<GateResponse>()
        val approval = server.approvalsOf(opened).first { it.requestedFrom == "rev-b" }
        // Read before the unlock: the person may answer, the app shows Approve.
        val read = server.call("locked:a:rev-b", "/api/v1/approvals/${approval.id}/view")
        assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
        assertEquals(true, read.body<ApprovalViewResponse>().you.canApprove, read.bodyAsText())
        // The yes asks for the unlock, with the contract's code.
        server.answer("locked:a:rev-b", opened, "rev-b").assertRefused(HttpStatusCode.Forbidden, "step_up_required")
        val still = server.call("user:a:rev-b", "/api/v1/gates/${opened.gate.id}").body<GateResponse>()
        assertEquals("pending", still.gate.status)
        // Unlocked, the same person's yes passes.
        assertEquals(HttpStatusCode.OK, server.answer("app:a:rev-b", opened, "rev-b").status, "the app, unlocked")
    }

    @Test
    fun theAnswerIsTheContractsApprovalViewWithItsReceipt() = gatesTest { server ->
        val opened = server.call("agent:a:$session", "/api/v1/gates", push()).body<GateResponse>()
        val answered = server.answer("user:a:rev-b", opened, "rev-b")
        assertEquals(HttpStatusCode.OK, answered.status, answered.bodyAsText())
        // `ApprovalView` of core api/openapi.yaml: `approval` and `you` required; `who`, `what`, `askedVia`, `receipt`.
        val body = Json.parseToJsonElement(answered.bodyAsText()).jsonObject
        for (key in listOf("approval", "you", "who", "what", "askedVia", "receipt")) {
            assertTrue(key in body, "answerApproval 200 has no `$key`: $body")
        }
        val view = answered.body<ApprovalViewResponse>()
        assertEquals("approved", view.approval.status)
        assertEquals(false, view.you.canApprove, "Answered: nothing left to say")
        assertEquals("rcpt_${opened.gate.id}", body.getValue("receipt").jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun s231OnlyABrowserSessionOrTheAppAfterBiometricsSaysYes() = gatesTest { server ->
        val opened = server.call("agent:a:$session", "/api/v1/gates", push()).body<GateResponse>()
        val cli = server.answer("cli:a:rev-b", opened, "rev-b")
        assertEquals(HttpStatusCode.Forbidden, cli.status, "a person's API token, even with every scope")
        assertTrue(cli.bodyAsText().contains("A yes is given in the browser or in the app."))
        val still = server.call("user:a:rev-b", "/api/v1/gates/${opened.gate.id}").body<GateResponse>()
        assertEquals("pending", still.gate.status)
        assertEquals(HttpStatusCode.OK, server.answer("app:a:rev-b", opened, "rev-b").status, "the app, unlocked")
    }

    @Test
    fun s254ABrowserSessionOlderThanFiveMinutesSignsInAgainBeforeAYes() = gatesTest { server ->
        val opened = server.call("agent:a:$session", "/api/v1/gates", push()).body<GateResponse>()
        val stale = server.answer("stale:a:rev-b", opened, "rev-b")
        stale.assertRefused(HttpStatusCode.Forbidden, "step_up_required")
        assertTrue(stale.bodyAsText().contains("Sign in again"))
        val still = server.call("user:a:rev-b", "/api/v1/gates/${opened.gate.id}").body<GateResponse>()
        assertEquals("pending", still.gate.status)
        assertEquals(HttpStatusCode.OK, server.answer("user:a:rev-b", opened, "rev-b").status, "signed in just now")
    }

    // The decision the hook waits on before every gated command: the ask use case on PostgreSQL (transaction, advisory
    // lock on the gate key, rows), at the 95th percentile, after a warm-up. The test HTTP client is left out: it is
    // the transport. The target is 20 ms, met on a quiet runner (runs 41026245553, 41030430506). A shared runner under
    // load moves the whole curve (a median of 19 ms, run 41030362738), so the gate of the CI is a regression bound,
    // twice and a half the target, on the best of three rounds; every round is shown on failure.
    @Test
    fun aGateDecisionStaysUnderItsRegressionBoundAtThe95thPercentile() = gatesTest { server ->
        val ask = server.koin.get<AskGateUseCase>()
        val actor = GateActor("agent", orgA, ActorKind.AGENT_SESSION, launchId = session, personId = "lyes")
        val request = push().toAskGate()
        repeat(WARM_UP) { ask(actor, request) }
        val rounds = (1..ROUNDS).map { _ ->
            val times = (1..SAMPLES).map { _ ->
                val start = TimeSource.Monotonic.markNow()
                ask(actor, request)
                start.elapsedNow()
            }.sorted()
            times[SAMPLES * 95 / 100 - 1] to times[SAMPLES / 2]
        }
        val shown = rounds.joinToString { (p95, median) -> "p95 = $p95, median = $median" }
        assertTrue(rounds.minOf { it.first } < 50.milliseconds, shown)
    }

    @Test
    fun aRunnerKeyAsksAndReadsAGateButOnlyASessionUsesItsYes() = gatesTest { server ->
        val opened = server.call("runner:a:ci", "/api/v1/gates", push()).body<GateResponse>()
        assertEquals(HttpStatusCode.OK, server.call("runner:a:ci", "/api/v1/gates/${opened.gate.id}").status)
        val reserve = server.move("runner:a:ci", opened, "reserve")
        assertEquals(HttpStatusCode.Forbidden, reserve.status)
        assertTrue(reserve.bodyAsText().contains("does not allow agent:session"))
    }

    @Test
    fun withoutATokenEveryRouteAnswers401() = gatesTest { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/api/v1/approvals").status)
        assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/api/v1/gates", push()).status)
        val nobody = server.call("nobody", "/api/v1/approvals")
        assertEquals(HttpStatusCode.Forbidden, nobody.status)
        // LOGIN-009: the refusal names the scope that reads, never gates:answer, which no token holds.
        assertTrue(nobody.bodyAsText().contains("does not allow gates:read"), nobody.bodyAsText())
        // Audit S2-03, S2-04: approvals are read and answered by a signed-in person, never by an agent or device token.
        assertEquals(HttpStatusCode.Forbidden, server.call("agent:a:$session", "/api/v1/approvals").status)
        assertEquals(HttpStatusCode.Forbidden, server.call("device:a:lyes", "/api/v1/approvals?mine=true").status)
        // S2-03: every gates route requires its scope; S2-31: only a person with the answer scope says yes.
        val opened = server.call("agent:a:$session", "/api/v1/gates", push()).body<GateResponse>()
        val approval = server.approvalsOf(opened).first { it.requestedFrom == "rev-b" }
        assertEquals(HttpStatusCode.OK, server.call("reader:a:rev-b", "/api/v1/approvals/${approval.id}").status)
        assertEquals(HttpStatusCode.OK, server.call("reader:a:rev-b", "/api/v1/gates/${opened.gate.id}").status)
        val readerAnswer = server.answer("reader:a:rev-b", opened, "rev-b")
        assertEquals(HttpStatusCode.Forbidden, readerAnswer.status)
        assertTrue(readerAnswer.bodyAsText().contains("does not allow gates:answer"))
        val deviceAnswer = server.answer("device:a:rev-b", opened, "rev-b")
        assertTrue(deviceAnswer.bodyAsText().contains("Only a person answers a gate."))
        val userAsks = server.call("user:a:rev-b", "/api/v1/gates", push())
        assertEquals(HttpStatusCode.Forbidden, userAsks.status, "A person's session asks no gate: hooks and CI do")
        assertTrue(userAsks.bodyAsText().contains("does not allow gates:ask"))
        assertEquals(HttpStatusCode.Forbidden, server.move("reader:a:rev-b", opened, "consume").status)
        assertEquals(HttpStatusCode.Forbidden, server.call("nobody", "/api/v1/gates/${opened.gate.id}").status)


        assertEquals(HttpStatusCode.BadRequest, server.call("user:a:rev-b", "/api/v1/gates/not-a-uuid").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            server.call("agent:a:$session", "/api/v1/gates", push().copy(sessionId = "")).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            server.call("agent:a:$session", "/api/v1/gates/${Uuid.random()}/explode", post = true).status,
        )
    }
}

// The decision benchmark: runs before the measure (JIT, connection pool, plans), then measured runs.
private const val WARM_UP = 50
private const val SAMPLES = 200
private const val WAITERS = 200
private const val ROUNDS = 3
