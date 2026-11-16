// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import run.ostrom.server.automations.AutomationsModule
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.launches.LaunchesModule
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.runners.RunnersModule
import run.ostrom.server.sessions.SessionsModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.benchTest
import run.ostrom.server.testing.plan
import run.ostrom.server.workflowruns.infrastructure.PostgresSessionRunIds
import run.ostrom.server.workflowruns.infrastructure.runsOfLaunchIds
import run.ostrom.server.workflowruns.infrastructure.runsOfSessions
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The two reads that name the run of a page of sessions (`runId`), under `EXPLAIN ANALYZE` as the serving role in the
 * organization's transaction, over 10 organizations of 5 000 launches each, half of them run in a cloud session an
 * agent node started, each run with two visits. A page of 50 cloud sessions reads the visits' session index, a page
 * of 50 segments the launches by their key and then that index: never a scan of a table, never the organization's
 * rows filtered one by one. The plans are written to `build/reports/explain/session-run-ids.txt`.
 */
class SessionRunIdsQueryPlansTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        RunnersModule, LaunchesModule, AutomationsModule, WorkflowRunsModule,
    )
    private val orgs = List(ORGS) { Uuid.random() }
    private val acme = orgs.first()

    @Test
    fun aPageOfSessionsFindsItsRunsThroughTheIndexes() = benchTest(modules, null) { server ->
        orgs.forEachIndexed { index, org -> server.seed(org, index) }
        for (table in listOf("launches_launches", "workflow_runs_visits")) {
            server.database.owner.execute("VACUUM ANALYZE $table").getOrThrow()
        }
        val database = server.koin.get<JdbcDatabase>()
        // The cloud sessions of the first 50 even launches, with their runs.
        val sessions = (1..PAGE).associate { n -> Uuid.fromLongs(0, n * 2L) to "wfr_${n * 2}" }
        val launches = (1..PAGE).map { "lch$it" }

        val plans = linkedMapOf(
            "runs of a page of cloud sessions" to
                database.withOrganization(acme) { plan { runsOfSessions(acme, sessions.keys).query } },
            "runs of a page of segments, through their launches" to
                database.withOrganization(acme) { plan { runsOfLaunchIds(acme, launches).query } },
        )

        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        SystemFileSystem.createDirectories(Path("build/reports/explain"))
        val file = Path("build/reports/explain/session-run-ids.txt")
        SystemFileSystem.sink(file).buffered().use { it.writeString(report) }
        for ((name, plan) in plans) {
            assertFalse("Seq Scan" in plan, "$name scans a table:\n$plan")
            assertTrue("workflow_runs_visits_session" in plan, "$name skips the visits' session index:\n$plan")
            val filtered = Regex("Rows Removed by Filter: (\\d+)").findAll(plan).sumOf { it.groupValues[1].toInt() }
            assertTrue(filtered < MAX_FILTERED, "$name filters $filtered rows of the organization:\n$plan")
        }
        val throughLaunches = plans.values.last()
        assertTrue("launches_launches_pkey" in throughLaunches, throughLaunches)
        // What the reads find: every cloud session of the page, the launches of the even ones, nothing elsewhere.
        val runs = PostgresSessionRunIds(database)
        assertEquals(sessions, runs.runsOf(acme, sessions.keys))
        val even = launches.filterIndexed { index, _ -> index % 2 == 1 }
        assertEquals(even.associateWith { "wfr_${it.removePrefix("lch")}" }, runs.runsOfLaunches(acme, launches))
        assertEquals(emptyMap(), runs.runsOf(orgs.last(), sessions.keys))
        // Row level security: in another organization's transaction, acme's rows are not there, whatever is asked.
        val elsewhere = database.withOrganization(orgs.last()) {
            runsOfSessions(acme, sessions.keys).rows() to runsOfLaunchIds(acme, launches).rows()
        }
        assertEquals(emptyList<Any>() to emptyList(), elsewhere)
        assertEquals(emptyMap(), runs.runsOfLaunches(acme, emptyList()))
        assertEquals(emptyMap(), runs.runsOf(acme, emptyList()))
    }

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter. A launch `i`
    // ran, when `i` is even, in the cloud session whose id is the organization's position then `i` in hexadecimal; its
    // run `wfr_i` visited the agent node that started it, then a gate node (no session).
    private suspend fun TestServer.seed(org: Uuid, index: Int) {
        val session = "CAST(lpad(to_hex($index), 16, '0') || lpad(to_hex(i), 16, '0') AS uuid)"
        val fixtures = database.superuser
        fixtures.execute(
            "INSERT INTO launches_launches (org_id, id, user_id, dev, agent, created_at, expires_at, " +
                "cloud_session_id) SELECT CAST('$org' AS uuid), 'lch' || i, CAST('$org' AS uuid), 'dev', " +
                "'claude-code', now(), now() + INTERVAL '1 hour', CASE WHEN i % 2 = 0 THEN $session END " +
                "FROM generate_series(1, $LAUNCHES_PER_ORG) AS i",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO workflow_runs_visits (org_id, run_id, seq, node_id, at, session_id) " +
                "SELECT CAST('$org' AS uuid), 'wfr_' || i, s, CASE WHEN s = 1 THEN 'build' ELSE 'review' END, " +
                "now(), CASE WHEN s = 1 AND i % 2 = 0 THEN $session END " +
                "FROM generate_series(1, $LAUNCHES_PER_ORG) AS i, generate_series(1, 2) AS s",
        ).getOrThrow()
    }

    private companion object {
        const val ORGS = 10
        const val LAUNCHES_PER_ORG = 5_000
        const val PAGE = 50
        // An index read filters nothing, or the few rows of a recheck: the organization's visits never.
        const val MAX_FILTERED = 100
    }
}
