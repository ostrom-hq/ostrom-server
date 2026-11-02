// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.fakes.FakeSigner
import dev.ostrom.server.gates.infrastructure.approversSql
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/**
 * The yes queue's new request-path read under `EXPLAIN ANALYZE`, over tables where the org is a small part of the rows:
 * the approvers of a page of gates go through `gates_requests_by_gate`, never a scan of the table. The plan is written
 * to `build/reports/explain/gates.txt`, uploaded with the CI's reports.
 */
class GatesQueryPlansTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")

    // The server starts every route's dependencies, and the receipts module is not loaded here: its signer is a fake.
    private val overrides = module { single<GateReceiptSigner> { FakeSigner() } }

    // [count] pending gates of [org] with three requests each, written in the org (row level security) so that the
    // seed passes under the serving role too. No colon in the inserts: the driver would read `:x` as a parameter.
    private suspend fun Driver.seed(org: Uuid, count: Int) {
        transaction {
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", org.toString()))
                .getOrThrow()
            execute(
                "INSERT INTO gates_gates (id, org_id, gate_key, subject, label, mode, status, asked_by, asked_via, " +
                    "session_ids, launch_ids, shown_digest, evidence, created_at) SELECT " +
                    "CAST(md5('$org' || i) AS uuid), CAST('$org' AS uuid), 'k' || i, '{}', 'Push to main', " +
                    "'ENFORCED', 'PENDING', '[]', '[]', '[]', '[]', 'd', '[]', NOW() - i * INTERVAL '1 minute' " +
                    "FROM generate_series(1, $count) AS i",
            ).getOrThrow()
            execute(
                "INSERT INTO gates_requests (id, gate_id, org_id, position, approver_id, status, created_at) " +
                    "SELECT gen_random_uuid(), g.id, g.org_id, p, 'rev-' || p, 'PENDING', g.created_at " +
                    "FROM gates_gates g CROSS JOIN generate_series(0, 2) AS p WHERE g.org_id = CAST('$org' AS uuid)",
            ).getOrThrow()
        }
    }

    @Test
    fun theApproversOfAPageOfGatesAreReadThroughTheirIndex() = serverTest(listOf(GatesModule), overrides) { server ->
        val driver = server.driver
        driver.seed(orgB, 20_000)
        driver.seed(orgA, 2_000)
        driver.execute("VACUUM ANALYZE gates_gates").getOrThrow()
        driver.execute("VACUUM ANALYZE gates_requests").getOrThrow()
        val plan = driver.transaction {
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgA.toString()))
                .getOrThrow()
            val page = fetchAll("SELECT id FROM gates_gates WHERE org_id = CAST('$orgA' AS uuid) LIMIT 108")
                .getOrThrow().map { it.get("id").asUuid() }
            val explain = Statement.create("EXPLAIN (ANALYZE, BUFFERS) ${approversSql(page.size)}").bind("org", orgA)
            page.forEachIndexed { index, id -> explain.bind("g$index", id) }
            fetchAll(explain).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
        SystemFileSystem.createDirectories(Path("build/reports/explain"))
        SystemFileSystem.sink(Path("build/reports/explain/gates.txt")).buffered().use {
            it.writeString("-- approvers of the 108 gates of a yes queue (GatesRepository.approversOf)\n$plan")
        }
        assertFalse("Seq Scan on gates_requests" in plan, "the approvers' read scans the table:\n$plan")
    }
}
