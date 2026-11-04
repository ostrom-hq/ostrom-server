// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.infrastructure.GatesQueries
import dev.ostrom.server.gates.infrastructure.PostgresGatesRepository
import dev.ostrom.server.gates.infrastructure.approversSql
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The gates' request-path reads under `EXPLAIN ANALYZE`, as the serving role (no owner, no superuser: row
 * level security applies), over 50 organizations and 10 000 gates with two approval requests each, 2 000 of the gates
 * in the organization read. Each read goes through an index to the rows it serves: never a scan of a table, never the
 * organization's rows filtered one by one. The plans are written to `build/reports/explain/gates.txt`, uploaded with
 * the CI's reports.
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
    fun theGatesRequestPathReadsOnlyTheRowsItServes() = serverTest(listOf(GatesModule), signer) { server ->
        val repository = PostgresGatesRepository(server.driver)
        val ofA = seed(repository, orgA, (0 until GATES_OF_A).toList())
        for ((index, org) in others.withIndex()) {
            seed(repository, org, (GATES_OF_A + index until TOTAL_GATES step others.size).toList())
        }
        // A gate the GitHub check opened has no session: nothing to link.
        val unlinked = gateOf(others.first(), TOTAL_GATES).copy(sessionIds = emptyList())
        repository.transaction { insertGate(unlinked) }
        // Statistics are the owner's to gather; the reads run as the serving role, as every test does (#68).
        for (table in listOf("gates_gates", "gates_requests", "gates_sessions", "gates_session_ends")) {
            server.database.owner.execute("VACUUM ANALYZE $table").getOrThrow()
        }
        val plans = plansOf(server.driver, ofA)
        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        SystemFileSystem.createDirectories(Path("build/reports/explain"))
        SystemFileSystem.sink(Path("build/reports/explain/gates.txt")).buffered().use { it.writeString(report) }
        for ((name, plan) in plans) {
            assertFalse("Seq Scan on gates_" in plan, "$name scans a table:\n$plan")
            val filtered = Regex("Rows Removed by Filter: (\\d+)").findAll(plan).sumOf { it.groupValues[1].toInt() }
            assertTrue(filtered < MAX_FILTERED, "$name filters $filtered rows of the organization:\n$plan")
        }
    }

    private suspend fun plansOf(serving: Driver, ofA: List<Gate>): Map<String, String> {
        val first = ofA.first()
        val page = ofA.take(QUEUE_PAGE)
        val inbox = GatesQueries.approvals(approver = true, status = false)
        val pending = GatesQueries.approvals(approver = true, status = true)
        val recent = GatesQueries.approvals(approver = false, status = false)
        return linkedMapOf(
            "gates of an action, under its lock (asking)" to serving.explain(GatesQueries.BY_KEY) {
                bind("key", keyOf(7)).bind("limit", 20)
            },
            "one gate, locked (answer, reserve, use)" to serving.explain(GatesQueries.gate(lock = true)) {
                bind("id", first.id)
            },
            "requests of a gate (answer, receipt)" to serving.explain(GatesQueries.REQUESTS_OF) {
                bind("gate", first.id)
            },
            "one request (answer)" to serving.explain(GatesQueries.REQUEST) { bind("id", requestOf(first, 0).id) },
            "pending inbox of an approver" to serving.explain(pending) {
                bind("approver", "rev-5@acme.test").bind("status", RequestStatus.PENDING.name).bind("limit", 50)
            },
            "inbox of an approver" to serving.explain(inbox) { bind("approver", "rev-5@acme.test").bind("limit", 50) },
            "newest requests of the org" to serving.explain(recent) { bind("limit", 50) },
            "gates of a page of requests" to serving.explain(GatesQueries.gates(2)) {
                bind("g0", first.id).bind("g1", Uuid.random())
            },
            "approvers of the gates of a yes queue (approversOf)" to serving.explain(approversSql(page.size)) {
                page.foldIndexed(this) { index, statement, gate -> statement.bind("g$index", gate.id) }
            },
            "gates of a session (session receipt)" to serving.explain(GatesQueries.OF_SESSION) {
                bind("session", sessionOf(7))
            },
            "end of a session (asking, reserving)" to serving.explain(GatesQueries.SESSION_ENDED) {
                bind("session", sessionOf(7))
            },
        )
    }

    /** The plan of [sql] in org A, as the serving role reads it; `:org` is bound, [bind] adds the rest. */
    private suspend fun Driver.explain(sql: String, bind: Statement.() -> Statement): String = transaction {
        fetchAll(
            Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgA.toString()),
        ).getOrThrow()
        val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org", orgA).bind()
        fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
    }

    /** The gates [numbers] of [org], two requests each, in one transaction; returns them. */
    private suspend fun seed(repository: PostgresGatesRepository, org: Uuid, numbers: List<Int>): List<Gate> {
        val gates = numbers.map { gateOf(org, it) }
        repository.transaction {
            for (gate in gates) {
                insertGate(gate)
                insertRequests(listOf(requestOf(gate, 0), requestOf(gate, 1)))
            }
            // Every session of the organization has ended: the read of an end finds its row among them all.
            gates.flatMap { it.sessionIds }.distinct().forEach { markSessionEnded(org, it, epoch) }
        }
        return gates
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
