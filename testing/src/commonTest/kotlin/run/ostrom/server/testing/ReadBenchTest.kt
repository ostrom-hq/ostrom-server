// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.TENANT_ROLE
import run.ostrom.server.kernel.database.TENANT_SETTING
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The read bench: the test server records the statements of a request, by their transactions, and only while
 * recording; they replay under `EXPLAIN` as the serving role; a route is timed under concurrent calls.
 */
class ReadBenchTest {
    @Test
    fun aCallOfOneQueryIsMeasuredAndTimed() = benchTest(emptyList(), null) { server ->
        val measure = server.measureRead("GET /ready") { selectOneThenReady(server) }
        assertEquals(listOf("SELECT 1"), measure.statements.map { it.sql })
        assertEquals(1, measure.queries)
        assertTrue("Result" in measure.plans.single(), measure.plans.single())
        assertEquals(CONCURRENT_CALLS * 10, measure.calls)
        assertTrue(measure.p50 <= measure.p95 && measure.http.isPositive())
        measure.assertWithinBudget(setOf("pg_class"), maxQueries = 1)
        val report = writeReadReport("testing", listOf(measure))
        assertTrue("== GET /ready" in report && "queries per call: 1" in report, report)
        val written = SystemFileSystem.source(Path("build/reports/perf/testing.txt")).buffered().use { it.readString() }
        assertEquals(report, written)
        val writing = server.measureRead("GET /ready", writes = true) { selectOneThenReady(server) }
        assertEquals(1, writing.queries, "A read route reads the same with its writes explained")
    }

    @Test
    fun aScanOrAnNPlusOneBreaksThePageButASlowRunnerOnlyPastTenTimes() = madeUpTimes {
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
    fun aReportIsHeldByItsShapeAndItsTimesOnlyPastTenTimes() = madeUpTimes {
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
    fun aTimeIsWrittenWithItsBudgetAndFailsOnlyPastTenTimesIt() = madeUpTimes {
        val file = Path(timeBudgetsFile)
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

    // A call that sends one query: the readiness route, after a query of the test's own as a route's would send.
    private suspend fun HttpClient.selectOneThenReady(server: TestServer): HttpResponse {
        server.koin.get<JdbcDatabase>().query { exec("SELECT 1") { } }
        return get("/ready")
    }

    private fun read(file: Path): String = SystemFileSystem.source(file).buffered().use { it.readString() }

    // The made-up times of these tests go to a file of their own, never to the module's budgets.tsv.
    private fun madeUpTimes(block: () -> Unit) {
        timeBudgetsFile = "build/reports/perf/made-up-budgets.tsv"
        try {
            block()
        } finally {
            timeBudgetsFile = TIME_BUDGETS_FILE
        }
    }

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
    fun writesAreExplainedOnlyWhenAsked() = serverTest(emptyList()) { server ->
        // The owner creates the table, as a migration would; the serving role writes it by default privileges.
        server.database.ownerJdbc.query {
            exec("CREATE TABLE bench_writes (n INTEGER PRIMARY KEY, seen INTEGER)")
            exec("INSERT INTO bench_writes VALUES (1, 0)")
        }
        val serving = server.koin.get<JdbcDatabase>()
        val sent = listOf("UPDATE bench_writes SET seen = 1 WHERE n = 1", "SELECT seen FROM bench_writes WHERE n = 1")
            .map { SentStatement(it, emptyList(), 1) }

        val plans = serving.replay(sent, explain = true, writes = true)
        assertEquals(2, plans.size, plans.joinToString("\n\n"))
        assertTrue("Update on bench_writes" in plans.first(), plans.first())
        assertEquals(1, serving.replay(sent, explain = true).size, "A read bench explains its reads only")
        assertEquals(listOf("0"), server.database.superuserTexts("SELECT seen FROM bench_writes"), "Rolled back")
    }

    @Test
    fun aNullAndAnArrayAreReplayedAsTheyWereBound() = serverTest(emptyList()) { server ->
        val serving = server.koin.get<JdbcDatabase>()
        val sent = server.recorder.record {
            serving.query {
                exec(
                    "SELECT COALESCE(?, 'none') || ':' || CAST(? = ANY (?) AS text)",
                    listOf(
                        TextColumnType() to null,
                        TextColumnType() to "b",
                        ArrayColumnType<String, List<String>>(TextColumnType()) to listOf("a", "b\"c", "b"),
                    ),
                ) { }
            }
        }
        assertEquals(listOf(listOf("none:true")), sent.map { serving.query { replayRecorded(it) } })
        val plans = serving.replay(sent, explain = true)
        assertTrue("Result" in plans.single(), plans.single())
    }

    @Test
    fun theStatementsOfExposedAreRecordedOnlyWhileRecordingAndReplayedByTheirTransaction() =
        serverTest(emptyList()) { server ->
            val jdbc = server.koin.get<JdbcDatabase>()
            val org = Uuid.random()
            jdbc.query { exec("SELECT 0") { } }
            val sent = server.recorder.record {
                jdbc.inOrg(org) { exec("SELECT CAST(? AS int) + 1", listOf(IntegerColumnType() to 1)) { } }
                jdbc.query { exec("SELECT 2") { } }
                assertFails {
                    jdbc.query {
                        exec("SELECT 3") { }
                        error("a failed request")
                    }
                }
            }
            jdbc.query { exec("SELECT 4") { } }
            assertFails { server.recorder.record { error("a failed request") } }
            jdbc.query { exec("SELECT 5") { } }
            val again = server.recorder.record { jdbc.query { exec("SELECT 6") { } } }
            assertEquals(listOf("SELECT 6"), again.map { it.sql })
            val expected = listOf(
                "SET LOCAL ROLE $TENANT_ROLE",
                "SELECT set_config('$TENANT_SETTING', ?, true)",
                "SELECT CAST(? AS int) + 1",
                "SELECT 2",
                "SELECT 3",
            )
            assertEquals(expected, sent.map { it.sql })
            assertEquals(listOf(org.toString()), sent[1].args.map { it.second })
            assertEquals(1, sent.take(3).map { it.transaction }.distinct().size, "One transaction")
            assertEquals(3, sent.map { it.transaction }.distinct().size, "Then one each")
            assertEquals(listOf(3, 1, 1), sent.byTransaction().map { it.size })
            val plans = jdbc.replay(sent, explain = true)
            assertEquals(3, plans.size, plans.joinToString("\n\n"))
            assertTrue(plans.all { "actual time" in it }, plans.joinToString("\n\n"))
            assertEquals(emptyList(), jdbc.replay(sent, explain = false))
        }
}
