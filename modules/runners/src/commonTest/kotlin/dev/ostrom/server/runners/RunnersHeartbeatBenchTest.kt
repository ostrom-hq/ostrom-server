// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.testIdentitiesWith
import dev.ostrom.server.runners.presentation.RegisterRunnerRequest
import dev.ostrom.server.runners.presentation.RegisteredRunnerDto
import dev.ostrom.server.runners.presentation.RegisteredRunnerHeartbeatRequest
import dev.ostrom.server.runners.presentation.RegisteredRunnerHeartbeatResponse
import dev.ostrom.server.runners.presentation.ReportedExecutionDto
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.assertNoScan
import dev.ostrom.server.testing.benchTest
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.writeReadReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A registered runner's heartbeat (`ostrom-runner machine`, every 20 s by default) among 20 000 runners and 20 000
 * executions of 200 organizations: without a body, with an empty report, and with the report of a machine that runs 3
 * sessions resumed 20 times each. Every write is explained with the reads (the heartbeat writes on every call): none
 * scans a large table, and a machine's history adds no query. Queries, plans and database p95 go to
 * `build/reports/perf/runners.txt`; the HTTP p95 under 100 and 500 concurrent runners is ostrom-e2e's (`chart-load`).
 */
class RunnersHeartbeatBenchTest {
    private val overrides = testIdentitiesWith(
        mapOf(
            "OSTROM_RUNNERS_ALLOWED_ORGS" to ORG_A.toString(),
            "OSTROM_RUNNERS_DRIVER" to "registered",
            "OSTROM_RUNNERS_SERVER_URL" to "http://host.docker.internal:4600",
            "OSTROM_RUNNERS_IMAGE" to "ghcr.io/ostrom-hq/ostrom-runner:test",
        ),
    )

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter.
    private val others = listOf(
        "INSERT INTO runners_registered (id, org_id, name, labels, key_hash, registered_by, created_at, " +
            "last_seen_at) SELECT CAST(md5('runner' || i) AS uuid), CAST(md5('org' || (i % $ORGS)) AS uuid), " +
            "'machine-' || i, '[]', md5('key' || i), 'bench', now(), now() FROM generate_series(1, $OTHERS) AS i",
        sessions("CAST(md5('session' || i) AS uuid)", "CAST(md5('org' || (i % $ORGS)) AS uuid)", "i", "$OTHERS"),
        // One in ten waits for a runner of its organization.
        "INSERT INTO runners_assignments (session_id, epoch, org_id, runner_id, created_at, delivered_at, state) " +
            "SELECT CAST(md5('session' || i) AS uuid), 1, CAST(md5('org' || (i % $ORGS)) AS uuid), " +
            "CASE WHEN i % 10 <> 0 THEN CAST(md5('runner' || i) AS uuid) END, now(), " +
            "CASE WHEN i % 10 <> 0 THEN now() END, CASE WHEN i % 10 <> 0 THEN 'running' END " +
            "FROM generate_series(1, $OTHERS) AS i",
    )

    private fun sessions(id: String, org: String, name: String, count: String) =
        "INSERT INTO runners_sessions (id, org_id, owner_id, name, agent, state, desired_state, epoch, generation, " +
            "idle_timeout_minutes, max_run_minutes, restricted_egress, created_at, updated_at) SELECT $id, $org, " +
            "'dev', 's' || $name, 'claude-code', 'running', 'running', 1, 1, 30, 240, false, now(), now() " +
            "FROM generate_series(1, $count) AS i"

    /** [HELD] sessions of the machine, each resumed [HISTORY] times on it: the last execution of each runs. */
    private fun held(runner: String) = listOf(
        sessions("CAST(md5('held' || i) AS uuid)", "CAST('$ORG_A' AS uuid)", "i", "$HELD"),
        "INSERT INTO runners_assignments (session_id, epoch, org_id, runner_id, created_at, delivered_at, " +
            "stopped_at, state, holds_workspace) SELECT CAST(md5('held' || s) AS uuid), e, CAST('$ORG_A' AS uuid), " +
            "CAST('$runner' AS uuid), now() - (100 - e) * INTERVAL '1 minute', now(), " +
            "CASE WHEN e < $HISTORY THEN now() END, CASE WHEN e < $HISTORY THEN 'ended' ELSE 'running' END, true " +
            "FROM generate_series(1, $HELD) AS s, generate_series(1, $HISTORY) AS e",
    )

    @Test
    fun aHeartbeatScansNothingAndAMachinesHistoryAddsNoQuery() = benchTest(listOf(RunnersModule), overrides) { server ->
        val busy = server.register("machine-busy")
        val idle = server.register("machine-idle")
        val fixtures = server.database.superuser
        (others + held(busy.id)).forEach { fixtures.execute(it).getOrThrow() }
        listOf("runners_registered", "runners_sessions", "runners_assignments").forEach {
            fixtures.execute("VACUUM ANALYZE $it").getOrThrow()
        }
        val ids = fixtures.fetchAll(
            "SELECT CAST(session_id AS text) AS id FROM runners_assignments WHERE runner_id = '${busy.id}' " +
                "AND stopped_at IS NULL ORDER BY 1",
        ).getOrThrow().map { it.get("id").asString() }
        val reported = ids.map { ReportedExecutionDto(it, HISTORY.toLong(), "running") }
        val running = RegisteredRunnerHeartbeatRequest(reported, ids)

        val measures = listOf(
            server.measureRead("POST heartbeat (no body: no Docker)", writes = true) { beat(busy) },
            server.measureRead("POST heartbeat (empty report, nothing held)", writes = true) {
                beat(idle, RegisteredRunnerHeartbeatRequest())
            },
            server.measureRead("POST heartbeat ($HELD sessions running, $HISTORY executions each)", writes = true) {
                beat(busy, running)
            },
        )
        writeReadReport("runners", measures)
        measures.forEach { it.assertNoScan(setOf("runners_registered", "runners_sessions", "runners_assignments")) }
        val (_, empty, holding) = measures
        assertEquals(empty.queries, holding.queries, "A query per execution held:\n${holding.describe()}")
        val work = server.client.beat(busy, running).body<RegisteredRunnerHeartbeatResponse>()
        assertEquals(ids, work.executions.map { it.sessionId }.sorted())
        assertEquals(ids, work.workspaces.sorted())
    }

    private suspend fun TestServer.register(name: String): RegisteredRunnerDto {
        val response = client.post("/api/v1/runners") {
            bearerAuth("dana")
            contentType(ContentType.Application.Json)
            setBody(RegisterRunnerRequest(name, emptyList()))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.beat(
        runner: RegisteredRunnerDto,
        report: RegisteredRunnerHeartbeatRequest? = null,
    ) = post("/api/v1/runners/${runner.id}/heartbeat") {
        bearerAuth(runner.token)
        if (report != null) {
            contentType(ContentType.Application.Json)
            setBody(report)
        }
    }

    private companion object {
        const val ORGS = 200
        const val OTHERS = 20_000
        const val HELD = 3
        const val HISTORY = 20
    }
}
