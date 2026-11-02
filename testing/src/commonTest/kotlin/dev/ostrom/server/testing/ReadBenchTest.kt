// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.request.get
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The read bench: the server's pool records the statements of a request, on the pool and in its transactions, and only
 * while recording; they replay under `EXPLAIN` as the serving role; a route is timed under concurrent calls.
 */
class ReadBenchTest {
    @Test
    fun theHealthRouteSendsOneQueryAndIsTimed() = serverTest(emptyList()) { server ->
        val measure = server.measureRead("GET /health") { get("/health") }
        assertEquals(listOf("SELECT 1"), measure.statements.map { it.sql })
        assertEquals(1, measure.queries)
        assertTrue("Result" in measure.plans.single(), measure.plans.single())
        assertEquals(CONCURRENT_CALLS * 10, measure.calls)
        assertTrue(measure.p50 <= measure.p95)
        measure.assertWithinBudget(setOf("pg_class"))
        val report = writeReadReport("testing", listOf(measure))
        assertTrue("== GET /health" in report && "queries per call: 1" in report, report)
        val written = SystemFileSystem.source(Path("build/reports/perf/testing.txt")).buffered().use { it.readString() }
        assertEquals(report, written)
    }

    @Test
    fun aScanOrASlowPageBreaksTheBudget() {
        val plan = listOf("Seq Scan on big  (cost=0.00..1.00)")
        val scan = ReadMeasure("scan", emptyList(), plan, 1.milliseconds, 2.milliseconds, 1)
        assertFails { scan.assertWithinBudget(setOf("big")) }
        scan.assertWithinBudget(setOf("bi", "other"))
        val slow = ReadMeasure("slow", emptyList(), emptyList(), 1.milliseconds, READ_P95_BUDGET, 1)
        assertFails { slow.assertWithinBudget(emptySet()) }
    }

    @Test
    fun aRouteThatFailsIsNotMeasured() = serverTest(emptyList()) { server ->
        val failure = assertFailsWith<IllegalStateException> { server.measureRead("GET /nowhere") { get("/nowhere") } }
        assertTrue("404" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun everyWayToSendAStatementIsRecordedOnlyWhileRecording() = serverTest(emptyList()) { server ->
        val driver = server.driver
        driver.fetchAll("SELECT 0").getOrThrow()
        val sent = driver.record {
            driver.execute("SET LOCAL application_name = 'bench'").getOrThrow()
            driver.execute(Statement.create("SELECT set_config('ostrom.bench', :v, false)").bind("v", "1")).getOrThrow()
            driver.fetchAll("SELECT 2").getOrThrow()
            driver.fetchAll(Statement.create("SELECT CAST(:n AS int) AS n").bind("n", 3)).getOrThrow()
            driver.transaction {
                execute("SET LOCAL application_name = 'bench-tx'").getOrThrow()
                execute(Statement.create("SELECT set_config('ostrom.bench', :v, true)").bind("v", "2")).getOrThrow()
                fetchAll("SELECT 4").getOrThrow()
                fetchAll(Statement.create("SELECT CAST(:n AS int) AS n").bind("n", 5)).getOrThrow()
            }
        }
        driver.fetchAll("SELECT 6").getOrThrow()
        assertEquals(8, sent.size)
        val bound = sent.map { it.statement?.namedParametersValues?.keys?.single() }
        assertEquals(listOf(null, "v", null, "n", null, "v", null, "n"), bound)
        val plans = server.database.driver.explain(sent)
        assertEquals(4, plans.size, plans.joinToString("\n\n"))
        assertTrue(plans.all { "actual time" in it }, plans.joinToString("\n\n"))
    }

    @Test
    fun aNullValueIsReplayedAsNull() = serverTest(emptyList()) { server ->
        val statement = Statement.create("SELECT COALESCE(:v, 'none') AS v").bindNull("v", String::class)
        val plans = server.database.driver.explain(listOf(SentStatement(statement.sql, statement)))
        assertTrue("Result" in plans.single(), plans.single())
    }
}
