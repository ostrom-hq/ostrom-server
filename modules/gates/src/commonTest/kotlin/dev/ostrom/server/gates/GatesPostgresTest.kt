package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.ActorKind
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
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import dev.ostrom.server.gates.presentation.AnswerDto
import dev.ostrom.server.gates.presentation.AnswerRequest
import dev.ostrom.server.gates.presentation.ApprovalsResponse
import dev.ostrom.server.gates.presentation.ChannelDto
import dev.ostrom.server.gates.presentation.GateActorResolver
import dev.ostrom.server.gates.presentation.GateResponse
import dev.ostrom.server.gates.presentation.OpenGateRequest
import dev.ostrom.server.gates.presentation.PushDto
import dev.ostrom.server.gates.presentation.PushRefDto
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
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
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The gates against a real PostgreSQL and real HTTP: every parcours of a gate, and the isolation of organizations. */
class GatesPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val session = Uuid.parse("00000000-0000-0000-0000-0000000005e5")
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val nextSha = "fedcba9876543210fedcba9876543210fedcba98"

    // Tokens are `<kind>:<org letter>:<id>`: `user:a:rev-b`, `agent:a:<session>`, `device:a:lyes`.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = AuthenticatedPrincipal(token, PrincipalKind.USER)
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? {
                    val (kind, org, id) = principal.subjectId.split(":").takeIf { it.size == 3 } ?: return null
                    val orgId = if (org == "a") orgA else orgB
                    return when (kind) {
                        "user" -> GateActor(id, orgId, ActorKind.USER, personId = id)
                        "agent" -> GateActor("agent", orgId, ActorKind.AGENT_SESSION, Uuid.parse(id), personId = "lyes")
                        else -> GateActor(id, orgId, ActorKind.DEVICE, personId = id)
                    }
                }
            }
        }
        single<GateSessions> {
            object : GateSessions {
                override suspend fun assertOpen(orgId: Uuid, sessionId: Uuid) = Unit
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

    private fun push(commit: String = sha, force: Boolean = false, diff: String = "+ok") = OpenGateRequest(
        sessionId = session.toString(),
        repo = "ostrom-hq/gate-demo",
        actionClass = "push-main",
        command = "git push origin main",
        push = PushDto("git@github.com:ostrom-hq/gate-demo.git", listOf(PushRefDto("refs/heads/main", commit)), force),
        commits = listOf(commit),
        diff = diff,
    )

    private suspend fun TestServer.answer(token: String, gate: GateResponse, approver: String, yes: Boolean = true) =
        call(
            token,
            "/v1/approvals/${gate.requests.first { it.approver == approver }.id}/answer",
            AnswerRequest(if (yes) AnswerDto.YES else AnswerDto.NO, gate.shownDigest, ChannelDto.MOBILE),
        )

    @Test
    fun aPushWaitsForAnotherApproverThenRunsOnce() = gatesTest { server ->
        val agent = "agent:a:$session"
        val opened = server.call(agent, "/v1/gates", push()).body<GateResponse>()
        assertEquals("pending", opened.status)
        assertEquals(listOf("lyes", "rev-b", "rev-c"), opened.requests.map { it.approver })
        assertEquals(listOf("rev-b", "rev-c"), opened.waitingFor)
        assertTrue(opened.evidence.any { it.label == "Diff" && it.value == "+ok" })

        val self = server.answer("user:a:lyes", opened, "lyes")
        assertEquals(HttpStatusCode.Forbidden, self.status, "The asker never approves while someone else can")
        assertTrue(self.bodyAsText().contains("rev-b or rev-c must say yes"))
        val admin = server.call(
            "user:a:admin",
            "/v1/approvals/${opened.requests[1].id}/answer",
            AnswerRequest(AnswerDto.YES, opened.shownDigest),
        )
        assertEquals(HttpStatusCode.Forbidden, admin.status, "An admin never answers in an approver's place")
        val device = server.answer("device:a:rev-b", opened, "rev-b")
        assertEquals(HttpStatusCode.Forbidden, device.status, "A device token never says yes")
        val stale = server.call(
            "user:a:rev-b",
            "/v1/approvals/${opened.requests[1].id}/answer",
            AnswerRequest(AnswerDto.YES, "not what was shown"),
        )
        assertEquals(HttpStatusCode.Conflict, stale.status)

        val approved = server.answer("user:a:rev-b", opened, "rev-b").body<GateResponse>()
        assertEquals("approved", approved.status)
        assertEquals(listOf("closed", "approved", "closed"), approved.requests.map { it.status })
        assertEquals("rcpt_${opened.id}", approved.receiptId)
        assertEquals(HttpStatusCode.Conflict, server.answer("user:a:rev-c", opened, "rev-c").status)

        assertEquals("reserved", server.call(agent, "/v1/gates/${opened.id}/reserve", post = true).body<GateResponse>().status)
        assertEquals(HttpStatusCode.Conflict, server.call(agent, "/v1/gates/${opened.id}/reserve", post = true).status)
        assertEquals("consumed", server.call(agent, "/v1/gates/${opened.id}/consume", post = true).body<GateResponse>().status)

        val again = server.call(agent, "/v1/gates", push()).body<GateResponse>()
        assertTrue(again.id != opened.id && again.status == "pending", "One yes covers one action")
    }

    @Test
    fun aForcePushOrAnotherDiffIsNotTheApprovedPush() = gatesTest { server ->
        val agent = "agent:a:$session"
        val opened = server.call(agent, "/v1/gates", push()).body<GateResponse>()
        server.answer("user:a:rev-b", opened, "rev-b")

        val forced = server.call(agent, "/v1/gates", push(force = true)).body<GateResponse>()
        val otherDiff = server.call(agent, "/v1/gates", push(diff = "+evil")).body<GateResponse>()
        val newCommit = server.call(agent, "/v1/gates", push(commit = nextSha)).body<GateResponse>()
        val same = server.call(agent, "/v1/gates", push()).body<GateResponse>()

        assertEquals(listOf("pending", "pending", "pending"), listOf(forced, otherDiff, newCommit).map { it.status })
        assertEquals(opened.id, same.id)
        assertEquals("approved", same.status)
    }

    @Test
    fun aMergeIsBoundToTheHeadReadFromGitHub() = gatesTest { server ->
        val merge = OpenGateRequest(
            sessionId = session.toString(),
            repo = "ostrom-hq/gate-demo",
            actionClass = "merge",
            command = "gh pr merge 6 --merge",
            pullRequest = 6,
        )
        val opened = server.call("agent:a:$session", "/v1/gates", merge).body<GateResponse>()
        assertEquals("#6@$sha->main", opened.pullRequest)
        assertEquals(listOf(sha), opened.commits)

        val unknown = server.call("agent:a:$session", "/v1/gates", merge.copy(pullRequest = 7, command = "gh pr merge 7"))
            .body<GateResponse>()
        assertEquals(null, unknown.pullRequest, "Unknown on GitHub: bound to the command in this session")
    }

    @Test
    fun aRedirectedPushAsksNobody() = gatesTest { server ->
        val response = server.call("agent:a:$session", "/v1/gates", push().copy(redirected = true))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val inbox = server.call("user:a:rev-b", "/v1/approvals").body<ApprovalsResponse>()
        assertTrue(inbox.requests.isEmpty())
    }

    @Test
    fun theFirstOfTwoSimultaneousYesWins() = gatesTest { server ->
        val opened = server.call("agent:a:$session", "/v1/gates", push()).body<GateResponse>()
        val statuses = coroutineScope {
            listOf("rev-b", "rev-c").map { approver ->
                async { server.answer("user:a:$approver", opened, approver).status }
            }.awaitAll()
        }
        assertEquals(listOf(HttpStatusCode.Conflict, HttpStatusCode.OK), statuses.sortedBy { it.value }.reversed())
    }

    @Test
    fun anotherOrganizationSeesNothing() = gatesTest { server ->
        val opened = server.call("agent:a:$session", "/v1/gates", push()).body<GateResponse>()
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:rev-b", "/v1/gates/${opened.id}").status)
        assertEquals(HttpStatusCode.NotFound, server.answer("user:b:rev-b", opened, "rev-b").status)
        assertTrue(server.call("user:b:rev-b", "/v1/approvals").body<ApprovalsResponse>().requests.isEmpty())
        assertEquals(1, server.call("user:a:rev-b", "/v1/approvals").body<ApprovalsResponse>().requests.size)
    }

    @Test
    fun rowLevelSecurityHidesOtherOrganizationsEvenWithoutAWhereClause() = gatesTest { server ->
        server.call("agent:a:$session", "/v1/gates", push()).body<GateResponse>()
        // The CI database user is a superuser, which bypasses row level security: the proof runs as a plain role.
        server.driver.execute(
            "DO \$\$ BEGIN CREATE ROLE ostrom_rls_probe NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$",
        ).getOrThrow()
        server.driver.execute("GRANT SELECT, INSERT ON gates_gates, gates_requests TO ostrom_rls_probe").getOrThrow()
        val counts = server.driver.transaction {
            execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
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
            execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
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
            shownDigest = "s",
            evidence = listOf(Evidence("l", "v")),
            decidedBy = "rev-b",
            decidedAt = at,
            selfApproved = true,
            receiptId = "rcpt",
            reservedBy = session,
            reservedAt = at,
            consumedAt = at,
            createdAt = at,
        )
        repository.transaction { insertGate(gate) }
        assertEquals(gate, repository.get(orgA, gate.id))
        assertEquals(null, repository.get(orgB, gate.id))
    }

    @Test
    fun withoutATokenEveryRouteAnswers401() = gatesTest { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/v1/approvals").status)
        assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/v1/gates", push()).status)
        assertEquals(HttpStatusCode.Forbidden, server.call("nobody", "/v1/approvals").status)
        assertEquals(HttpStatusCode.BadRequest, server.call("user:a:rev-b", "/v1/gates/not-a-uuid").status)
        assertEquals(HttpStatusCode.NotFound, server.call("agent:a:$session", "/v1/gates/${Uuid.random()}/explode", post = true).status)
    }
}
