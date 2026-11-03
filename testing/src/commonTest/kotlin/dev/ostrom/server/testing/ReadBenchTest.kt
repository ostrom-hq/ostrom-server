// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
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
import kotlin.time.Duration.Companion.seconds

/**
 * The read bench: the server's pool records the statements of a request, on the pool and in its transactions, and only
 * while recording; they replay under `EXPLAIN` as the serving role; a route is timed under concurrent calls.
 */
class ReadBenchTest {
    @Test
    fun theHealthRouteSendsOneQueryAndIsTimed() = benchTest(emptyList(), null) { server ->
        val measure = server.measureRead("GET /health") { get("/health") }
        assertEquals(listOf("SELECT 1"), measure.statements.map { it.sql })
        assertEquals(1, measure.queries)
        assertTrue("Result" in measure.plans.single(), measure.plans.single())
        assertEquals(CONCURRENT_CALLS * 10, measure.calls)
        assertTrue(measure.p50 <= measure.p95 && measure.http.isPositive())
        measure.assertWithinBudget(setOf("pg_class"), maxQueries = 1)
        val report = writeReadReport("testing", listOf(measure))
        assertTrue("== GET /health" in report && "queries per call: 1" in report, report)
        val written = SystemFileSystem.source(Path("build/reports/perf/testing.txt")).buffered().use { it.readString() }
        assertEquals(report, written)
        val writing = server.measureRead("GET /health", writes = true) { get("/health") }
        assertEquals(1, writing.queries, "A read route reads the same with its writes explained")
    }

    @Test
    fun aScanOrAnNPlusOneBreaksThePageButASlowRunnerOnlyPastTenTimes() {
        val plan = listOf("Seq Scan on big  (cost=0.00..1.00)")
        val scan = ReadMeasure("scan", emptyList(), plan, listOf(1.milliseconds), 3.milliseconds)
        val scanned = assertFailsWith<AssertionError> { scan.assertWithinBudget(setOf("big"), maxQueries = 1) }
        assertTrue("scans [big]" in scanned.message.orEmpty(), scanned.message)
        scan.assertWithinBudget(setOf("bi", "other"), maxQueries = 1)
        val perRow = ReadMeasure("per row", emptyList(), List(51) { "Index Scan" }, listOf(1.milliseconds), 1.seconds)
        val nPlusOne = assertFailsWith<AssertionError> { perRow.assertWithinBudget(emptySet(), maxQueries = 2) }
        assertTrue("sends 51 queries per call, over 2" in nPlusOne.message.orEmpty(), nPlusOne.message)
        val slow = ReadMeasure("slow", emptyList(), emptyList(), listOf(READ_P95_BUDGET * BLOCK_FACTOR), 3.milliseconds)
        slow.assertWithinBudget(emptySet(), maxQueries = 0)
        val blocked = ReadMeasure("blocked", emptyList(), emptyList(), listOf(READ_P95_BUDGET * 11), 3.milliseconds)
        val block = assertFailsWith<AssertionError> { blocked.assertWithinBudget(emptySet(), maxQueries = 0) }
        assertTrue("blocked: database p95: 550ms, over 10 times its budget of 50ms" in block.message.orEmpty())
    }

    @Test
    fun aReportIsHeldByItsShapeAndItsTimesOnlyPastTenTimes() {
        val alone = { ms: Double -> listOf("Result\nPlanning Time: 0.000 ms\nExecution Time: $ms ms") }
        val loaded = listOf(REPORT_P95_CEILING * BLOCK_FACTOR)
        val report = ReadMeasure("report", emptyList(), alone(500.0), loaded, 3.milliseconds)
        report.assertReportWithinBudget(emptySet(), maxQueries = 1)
        assertFails { report.assertReportWithinBudget(emptySet(), maxQueries = 0) }
        val slowAlone = ReadMeasure("slow alone", emptyList(), alone(501.0), listOf(1.milliseconds), 3.milliseconds)
        assertFails { slowAlone.assertReportWithinBudget(emptySet(), maxQueries = 1) }
        val over = ReadMeasure("over", emptyList(), alone(1.0), listOf(REPORT_P95_CEILING * 11), 3.milliseconds)
        assertFails { over.assertReportWithinBudget(emptySet(), maxQueries = 1) }
        val scan = ReadMeasure("scan", emptyList(), listOf("Seq Scan on big"), listOf(1.milliseconds), 3.milliseconds)
        assertFails { scan.assertReportWithinBudget(setOf("big"), maxQueries = 1) }
    }

    @Test
    fun aTimeIsWrittenWithItsBudgetAndFailsOnlyPastTenTimesIt() {
        val file = Path(TIME_BUDGETS_FILE)
        val before = if (SystemFileSystem.exists(file)) read(file).lines().size else 1
        checkTime("a measure", 120.milliseconds, 50.milliseconds)
        assertEquals("a measure\t120.0\t50.0", read(file).lines()[before - 1])
        val blocked = assertFailsWith<AssertionError> { checkTime("blocked", 501.milliseconds, 50.milliseconds) }
        assertEquals("blocked: 501ms, over 10 times its budget of 50ms:\n", blocked.message)
        val shown = assertFailsWith<AssertionError> { checkTime("shown", 2.seconds, 1.milliseconds) { "the plans" } }
        assertTrue(shown.message.orEmpty().endsWith("the plans"), shown.message)
        assertFailsWith<IllegalArgumentException> { checkTime("a\ttab", 1.milliseconds, 1.milliseconds) }
        assertFailsWith<IllegalArgumentException> { checkTime("two\nlines", 1.milliseconds, 1.milliseconds) }
    }

    private fun read(file: Path): String = SystemFileSystem.source(file).buffered().use { it.readString() }

    @Test
    fun theMedianTrialIsKeptNotTheBestNorTheWorst() {
        val fast = List(20) { 1.milliseconds }
        val loaded = List(20) { 70.milliseconds }
        val usual = List(19) { 2.milliseconds } + 30.milliseconds
        assertEquals(usual, medianTrial(listOf(loaded, usual.reversed(), fast)))
        assertEquals(fast, medianTrial(listOf(fast)))
        assertFailsWith<IllegalArgumentException> { medianTrial(listOf(fast, usual)) }
        assertFailsWith<IllegalArgumentException> { medianTrial(listOf(emptyList())) }
    }

    @Test
    fun theDatabaseTimeIsWhatPostgresReports() {
        val plan = "Result  (actual time=0.002..0.002)\nPlanning Time: 0.500 ms\nExecution Time: 1.25 ms"
        val plans = listOf(plan, "Result")
        assertEquals(1.75.milliseconds, databaseTime(plans))
        assertEquals(0.milliseconds, databaseTime(emptyList()))
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
        assertFails { driver.record { error("a failed request") } }
        driver.fetchAll("SELECT 7").getOrThrow()
        assertEquals(listOf("SELECT 8"), driver.record { driver.fetchAll("SELECT 8").getOrThrow() }.map { it.sql })
        assertEquals(8, sent.size)
        val bound = sent.map { it.statement?.namedParametersValues?.keys?.single() }
        assertEquals(listOf(null, "v", null, "n", null, "v", null, "n"), bound)
        assertEquals(4, sent.take(4).map { it.transaction }.distinct().size, "Each on the pool in its own")
        assertEquals(1, sent.drop(4).map { it.transaction }.distinct().size, "One transaction")
        assertTrue(sent[4].transaction !in sent.take(4).map { it.transaction })
        val plans = server.database.driver.replay(sent, explain = true)
        assertEquals(4, plans.size, plans.joinToString("\n\n"))
        assertTrue(plans.all { "actual time" in it }, plans.joinToString("\n\n"))
        assertEquals(emptyList(), server.database.driver.replay(sent, explain = false))
    }

    @Test
    fun writesAreExplainedOnlyWhenAsked() = serverTest(emptyList()) { server ->
        // The owner creates the table, as a migration would; the serving role writes it by default privileges.
        val owner = server.database.owner
        owner.execute("CREATE TABLE bench_writes (n INTEGER PRIMARY KEY, seen INTEGER)").getOrThrow()
        owner.execute("INSERT INTO bench_writes VALUES (1, 0)").getOrThrow()
        val driver = server.database.driver
        val sent = listOf("UPDATE bench_writes SET seen = 1 WHERE n = 1", "SELECT seen FROM bench_writes WHERE n = 1")
            .map { SentStatement(it, Statement.create(it), 1) }

        val plans = driver.replay(sent, explain = true, writes = true)
        assertEquals(2, plans.size, plans.joinToString("\n\n"))
        assertTrue("Update on bench_writes" in plans.first(), plans.first())
        assertEquals(1, driver.replay(sent, explain = true).size, "A read bench explains its reads only")
        val seen = driver.fetchAll("SELECT seen FROM bench_writes").getOrThrow().single().get("seen").asInt()
        assertEquals(0, seen, "Rolled back")
    }

    @Test
    fun aNullValueIsReplayedAsNull() = serverTest(emptyList()) { server ->
        val statement = Statement.create("SELECT COALESCE(:v, 'none') AS v").bindNull("v", String::class)
        val plans = server.database.driver.replay(listOf(SentStatement(statement.sql, statement, 1)), explain = true)
        assertTrue("Result" in plans.single(), plans.single())
    }
}
