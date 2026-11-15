// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateMode
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.infrastructure.approvalsPage
import run.ostrom.server.gates.infrastructure.approvedSessions
import run.ostrom.server.gates.infrastructure.approversRead
import run.ostrom.server.gates.infrastructure.approvesSession
import run.ostrom.server.gates.infrastructure.askersUpdate
import run.ostrom.server.gates.infrastructure.gateInsert
import run.ostrom.server.gates.infrastructure.gateRead
import run.ostrom.server.gates.infrastructure.gateUpdate
import run.ostrom.server.gates.infrastructure.gatesByIds
import run.ostrom.server.gates.infrastructure.gatesByKey
import run.ostrom.server.gates.infrastructure.requestRead
import run.ostrom.server.gates.infrastructure.requestUpdate
import run.ostrom.server.gates.infrastructure.requestsOfGate
import run.ostrom.server.gates.infrastructure.sessionEndInsert
import run.ostrom.server.gates.infrastructure.sessionEndRead
import run.ostrom.server.gates.infrastructure.sessionGatesRead
import run.ostrom.server.gates.infrastructure.sessionLink
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.benchTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.explain
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The gates' request-path statements under `EXPLAIN ANALYZE`, as Exposed sends them in `inOrg` (the tenant role, no
 * owner, no superuser: row level security applies), each write rolled back, over 50 organizations and 10 000 gates
 * with two approval requests each, 2 000 of the gates
 * in the organization read. Each read goes through an index to the rows it serves: never a scan of a table, never the
 * organization's rows filtered one by one. The plans are written to `build/reports/explain/gates.txt`, uploaded with
 * the CI's reports. It fills its tables row by row through the repository, which took over the minute of a
 * `serverTest` on a loaded runner (Build 41717982170, attempt 1): it runs within a bench's timeout.
 */
class GatesQueryPlansTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val others = (1..49).map { Uuid.fromLongs(0, it.toLong()) }
    private val epoch = Instant.fromEpochSeconds(1_790_000_000)

    // The routes need a receipt signer; no receipt is signed here.
    private val signer = module {
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }

    @Test
    fun theGatesRequestPathReadsOnlyTheRowsItServes() = benchTest(listOf(GatesModule), signer) { server ->
        val repository = server.koin.get<GatesRepository>()
        val ofA = seed(repository, orgA, (0 until GATES_OF_A).toList())
        for ((index, org) in others.withIndex()) {
            seed(repository, org, (GATES_OF_A + index until TOTAL_GATES step others.size).toList())
        }
        // A gate the GitHub check opened has no session: nothing to link.
        val unlinked = gateOf(others.first(), TOTAL_GATES).copy(sessionIds = emptyList())
        repository.transaction(unlinked.orgId) { insertGate(unlinked) }
        // Statistics are the owner's to gather; the reads run as the serving role, as every test does (#68).
        for (table in listOf("gates_gates", "gates_requests", "gates_sessions", "gates_session_ends")) {
            server.database.owner.execute("VACUUM ANALYZE $table").getOrThrow()
        }
        // Each statement as the repository sends it, in `inOrg` (the tenant role, org A set), each write rolled back.
        val database = server.koin.get<JdbcDatabase>()
        val plans = statementsOf(ofA).mapValues { (_, statement) ->
            database.inOrg(orgA) { plan(statement).also { rollback() } }
        }
        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        SystemFileSystem.createDirectories(Path("build/reports/explain"))
        SystemFileSystem.sink(Path("build/reports/explain/gates.txt")).buffered().use { it.writeString(report) }
        for ((name, plan) in plans) {
            assertFalse("Seq Scan on gates_" in plan, "$name scans a table:\n$plan")
            val filtered = Regex("Rows Removed by Filter: (\\d+)").findAll(plan).sumOf { it.groupValues[1].toInt() }
            assertTrue(filtered < MAX_FILTERED, "$name filters $filtered rows of the organization:\n$plan")
        }
    }

    private fun statementsOf(ofA: List<Gate>): Map<String, StatementBuilder.() -> Statement<*>> {
        val first = ofA.first()
        val page = ofA.take(QUEUE_PAGE).map { it.id }
        val reviewer = "rev-5@acme.test"
        val pending = RequestStatus.PENDING
        val answered = requestOf(first, 0).copy(status = RequestStatus.APPROVED, answeredAt = epoch)
        return linkedMapOf(
            "gates of an action, under its lock (asking)" to { gatesByKey(orgA, keyOf(7), 20).query },
            "one gate, locked (answer, reserve, use)" to { gateRead(orgA, first.id, lock = true).query },
            "requests of a gate (answer, receipt)" to { requestsOfGate(orgA, first.id).query },
            "one request (answer)" to { requestRead(orgA, requestOf(first, 0).id).query },
            "pending inbox of an approver" to { approvalsPage(orgA, reviewer, pending, 50, null).query },
            "inbox of an approver" to { approvalsPage(orgA, reviewer, null, 50, null).query },
            "newest requests of the org" to { approvalsPage(orgA, null, null, 50, null).query },
            "next page of the org's requests" to {
                approvalsPage(orgA, null, null, 50, requestOf(first, 0).id).query
            },
            "gates of a page of requests" to { gatesByIds(orgA, listOf(first.id, Uuid.random())).query },
            "approvers of the gates of a yes queue (approversOf)" to { approversRead(orgA, page).query },
            "gates of a session (session receipt)" to { sessionGatesRead(orgA, sessionOf(7)).query },
            "end of a session (asking, reserving)" to { sessionEndRead(orgA, sessionOf(7)).query },
            "an approver of a session's gates (receipts, TeamCode #238)" to {
                approvesSession(orgA, reviewer, sessionOf(7)).query
            },
            "the sessions of an approver's gates (receipts list)" to { approvedSessions(orgA, reviewer).query },
            "a new gate (asking)" to { gateInsert(gateOf(orgA, TOTAL_GATES + 1)) },
            "a gate's link to a session (asking)" to { sessionLink(first, "claude-new") },
            "a gate's askers (joining)" to { askersUpdate(first.copy(askedBy = listOf("dev-a", "dev-b"))) },
            "a gate's decision (answer, reserve, use)" to { gateUpdate(first.copy(status = GateStatus.APPROVED)) },
            "a request's answer (answer)" to { requestUpdate(answered) },
            "the end of a session (session end)" to { sessionEndInsert(orgA, "claude-new", epoch) },
        )
    }

    /** The gates [numbers] of [org], two requests each, in one transaction; returns them. */
    private suspend fun seed(repository: GatesRepository, org: Uuid, numbers: List<Int>): List<Gate> {
        val gates = numbers.map { gateOf(org, it) }
        repository.transaction(org) {
            for (gate in gates) {
                insertGate(gate)
                insertRequests(listOf(requestOf(gate, 0), requestOf(gate, 1)))
            }
            // Every session of the organization has ended: the read of an end finds its row among them all.
            gates.flatMap { it.sessionIds }.distinct().forEach { markSessionEnded(org, it, epoch) }
        }
        return gates
    }

    /** The plan of [statement] as Exposed sends it, under `EXPLAIN (ANALYZE, BUFFERS)`: a write runs (rolled back). */
    private fun JdbcTransaction.plan(statement: StatementBuilder.() -> Statement<*>): String =
        explain(analyze = true, options = "BUFFERS", body = statement).joinToString("\n") {
            it.toString().removePrefix("QUERY PLAN=")
        }

    private fun keyOf(n: Int) = "push-main:acme/api:${n % KEYS}"

    private fun sessionOf(n: Int) = "claude-${n % SESSIONS}"

    private fun gateOf(org: Uuid, n: Int) = Gate(
        // Gate numbers are unique across the organizations: so are the ids.
        id = Uuid.fromLongs(n.toLong() + 1, 0),
        orgId = org,
        key = keyOf(n),
        subject = GateSubject("acme/api", actionClass = "push-main", command = "git push", scope = "repo:acme/api"),
        node = null,
        label = "Push to main",
        mode = GateMode.ENFORCED,
        status = if (n % 10 == 0) GateStatus.PENDING else GateStatus.APPROVED,
        askedBy = listOf("dev-a"),
        askedVia = setOf(AskedVia.HOOK),
        sessionIds = listOf(sessionOf(n)),
        shownDigest = "sha256:$n",
        evidence = emptyList(),
        createdAt = epoch - n.minutes,
    )

    // A request id is its gate's, with the approver's position in the low half.
    private fun requestOf(gate: Gate, position: Int) = ApprovalRequest(
        id = gate.id.toLongs { high, _ -> Uuid.fromLongs(high, position.toLong() + 1) },
        gateId = gate.id,
        orgId = gate.orgId,
        // The first approver is one of 20 people, written with a capital: the inbox compares without case.
        approverId = if (position == 0) "Rev-${gate.id.toLongs { high, _ -> high } % APPROVERS}@acme.test" else OPS,
        status = if (gate.status == GateStatus.PENDING) RequestStatus.PENDING else RequestStatus.APPROVED,
        createdAt = gate.createdAt,
    )

    private companion object {
        const val TOTAL_GATES = 10_000
        const val GATES_OF_A = 2_000
        const val KEYS = 300
        const val SESSIONS = 1_000
        const val APPROVERS = 20
        const val QUEUE_PAGE = 108
        const val OPS = "ops@acme.test"
        // An index read filters nothing, or the few rows of a recheck: the org's 2 000 gates or 4 000 requests never.
        const val MAX_FILTERED = 100
    }
}
