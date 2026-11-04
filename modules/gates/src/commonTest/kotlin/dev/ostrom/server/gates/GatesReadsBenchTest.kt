// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.infrastructure.PostgresGatesRepository
import dev.ostrom.server.gates.presentation.ApprovalQueue
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.testing.assertWithinBudget
import dev.ostrom.server.testing.benchTest
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.writeReadReport
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `GET /api/v1/approvals/queue` on 10 organizations of 2 000 gates each, two approval requests per gate: the queue of
 * the approver of every gate (100 pending, the page's maximum), of the busiest of 20 reviewers (rev-1, 100 pending)
 * and of a reviewer with nothing waiting (rev-5). Each returns its pending requests, keeps the read budget
 * (ReadBench.kt) and sends a handful of queries; its queries, plans and p95 are written to
 * `build/reports/perf/gates.txt`.
 */
class GatesReadsBenchTest {
    private val orgs = List(ORGS) { Uuid.fromLongs(0, it.toLong() + 1) }
    private val acme = orgs.first()
    private val epoch = Instant.fromEpochSeconds(1_790_000_000)

    // The token is the person: a browser session of acme.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = AuthenticatedPrincipal(
                    token,
                    PrincipalKind.USER,
                    setOf(GateScopes.ALL_USER),
                    channel = PrincipalChannel.BROWSER,
                    authenticatedAt = Clock.System.now(),
                )
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor =
                    GateActor(principal.subjectId, acme, ActorKind.USER, personId = principal.subjectId)
            }
        }
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }

    @Test
    fun theApprovalQueueKeepsItsBudget() = benchTest(listOf(GatesModule), overrides) { server ->
        val gates = (0 until GATES_PER_ORG).map { gateOf(acme, it) }
        PostgresGatesRepository(server.driver).transaction {
            for (gate in gates) {
                insertGate(gate)
                insertRequests(listOf(requestOf(gate, 0), requestOf(gate, 1)))
            }
        }
        val fixtures = server.database.superuser
        orgs.drop(1).forEach { org -> copyOf(org).forEach { fixtures.execute(it).getOrThrow() } }
        for (table in listOf("gates_gates", "gates_requests", "gates_sessions")) {
            fixtures.execute("VACUUM ANALYZE $table").getOrThrow()
        }

        // Who reads, and the pending requests their page shows. The pending gates are one in ten, and their first
        // approver is rev-1 or rev-11: rev-1 is the busiest of the 20 reviewers, rev-5 has none waiting.
        val readers = listOf(
            Triple("approver of every gate", OPS, PENDING_PAGE),
            Triple("rev-1, the busiest of 20 reviewers", "rev-1@acme.test", PENDING_PAGE),
            Triple("rev-5, a reviewer with nothing waiting", "rev-5@acme.test", 0),
        )
        val measures = readers.map { (who, person, pending) ->
            val response = server.client.get("/api/v1/approvals/queue") { bearerAuth(person) }
            assertTrue(response.status.isSuccess(), "$who: ${response.status}")
            assertEquals(pending, response.body<ApprovalQueue>().pending.size, who)
            server.measureRead("GET /api/v1/approvals/queue ($who)") {
                get("/api/v1/approvals/queue") { bearerAuth(person) }
            }
        }
        writeReadReport("gates", measures)
        measures.forEach { it.assertWithinBudget(setOf("gates_gates", "gates_requests")) }
        // No N+1: a page of 100 requests sends a handful of queries, as a page of none does.
        measures.forEach { assertTrue(it.queries <= MAX_QUERIES, it.describe()) }
    }

    // acme's gates, requests and session links copied into [org], ids renamed: one org written through the repository
    // is enough, the nine others only fill the tables. No `::` cast and no colon: the driver reads `:x` as a parameter.
    private fun copyOf(org: Uuid): List<String> {
        fun renamed(column: String) = "CAST(md5(CAST($column AS text) || '$org') AS uuid)"
        val from = "WHERE org_id = CAST('$acme' AS uuid)"
        return listOf(
            "INSERT INTO gates_gates SELECT (jsonb_populate_record(g, jsonb_build_object('id', ${renamed("g.id")}, " +
                "'org_id', CAST('$org' AS text)))).* FROM gates_gates g $from",
            "INSERT INTO gates_requests (id, gate_id, org_id, position, approver_id, status, answered_at, channel, " +
                "created_at) SELECT ${renamed("id")}, ${renamed("gate_id")}, CAST('$org' AS uuid), position, " +
                "approver_id, status, answered_at, channel, created_at FROM gates_requests $from",
            "INSERT INTO gates_sessions (org_id, session_id, gate_id) SELECT CAST('$org' AS uuid), session_id, " +
                "${renamed("gate_id")} FROM gates_sessions $from",
        )
    }

    private fun gateOf(org: Uuid, n: Int) = Gate(
        id = Uuid.fromLongs(n.toLong() + 1, 0),
        orgId = org,
        key = "push-main:acme/api:${n % KEYS}",
        subject = GateSubject("acme/api", actionClass = "push-main", command = "git push", scope = "repo:acme/api"),
        node = null,
        label = "Push to main",
        mode = GateMode.ENFORCED,
        status = if (n % 10 == 0) GateStatus.PENDING else GateStatus.APPROVED,
        askedBy = listOf("dev-a"),
        askedVia = setOf(AskedVia.HOOK),
        sessionIds = listOf("claude-${n % SESSIONS}"),
        shownDigest = "sha256:$n",
        evidence = emptyList(),
        createdAt = epoch - n.minutes,
    )

    // A request id is its gate's, with the approver's position in the low half; the first approver is one of 20.
    private fun requestOf(gate: Gate, position: Int) = ApprovalRequest(
        id = gate.id.toLongs { high, _ -> Uuid.fromLongs(high, position.toLong() + 1) },
        gateId = gate.id,
        orgId = gate.orgId,
        approverId = if (position == 0) "rev-${gate.id.toLongs { high, _ -> high } % APPROVERS}@acme.test" else OPS,
        status = if (gate.status == GateStatus.PENDING) RequestStatus.PENDING else RequestStatus.APPROVED,
        createdAt = gate.createdAt,
    )

    private companion object {
        const val ORGS = 10
        const val GATES_PER_ORG = 2_000

        // The queue's page of pending requests (GetApprovalQueueUseCaseImpl).
        const val PENDING_PAGE = 100

        // Seven queries per read today (#206), one of margin.
        const val MAX_QUERIES = 8
        const val KEYS = 300
        const val SESSIONS = 1_000
        const val APPROVERS = 20
        const val OPS = "ops@acme.test"
    }
}
