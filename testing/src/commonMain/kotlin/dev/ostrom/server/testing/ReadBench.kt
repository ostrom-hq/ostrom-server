// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** The calls timed at once by [measureRead], the load the budget is set for. */
const val CONCURRENT_CALLS = 20

/** The budget of a read route's page: the p95 of its database time at [CONCURRENT_CALLS] concurrent calls. */
val READ_P95_BUDGET: Duration = 50.milliseconds

private const val WARM_UP_CALLS = 5
private const val HTTP_CALLS = 20
private const val CALLS_EACH = 10
private const val RECORDINGS = 3
private const val EXPLAIN = "EXPLAIN (ANALYZE, BUFFERS) "

/**
 * Measures a read route of this test server: [WARM_UP_CALLS] calls first (each must succeed); the median of
 * [HTTP_CALLS] whole calls one at a time; the statements of one call (the fewest of [RECORDINGS] recordings, so that a
 * background job running meanwhile is not counted), replayed under `EXPLAIN (ANALYZE, BUFFERS)` as the serving role
 * ([TRIALS] times, the plans of the median time kept); then the database time of a call: its statements replayed by
 * [CONCURRENT_CALLS] callers at once on a pool of as many connections ([CALLS_EACH] replays each), every transaction
 * of the call replayed as one and rolled back, and the planning and execution times PostgreSQL reports for its
 * queries added up, timed [TRIALS] times and the median trial kept ([medianTrial]). The whole measure runs alone on the
 * PostgreSQL server ([TestDatabase.exclusively]): the other test processes' benches wait, and wait for it.
 *
 * The time is the database's own, not the test's: the in-process test server serves its calls one after another, and
 * a client clock around 20 concurrent replays on a CI runner of a few cores measures the decoding and the scheduling
 * of the test (`SELECT 1` reached a p95 of 210 ms there). The HTTP p95 of a deployment is k6's (ostrom-e2e,
 * `chart-load`).
 *
 * A route that writes on every call (a runner's heartbeat) is measured with [writes]: its `UPDATE`, `INSERT` and
 * `DELETE` statements are explained too, so that a write that scans a table, or one sent once per row, is counted. Its
 * concurrent replays write the same rows, and wait for each other's locks: their p95 is a ceiling.
 *
 * @param name The route and its case.
 * @param writes Whether its writes are explained and counted as queries.
 * @param call One call.
 * @return The measure.
 */
suspend fun TestServer.measureRead(
    name: String,
    writes: Boolean = false,
    call: suspend HttpClient.() -> HttpResponse,
): ReadMeasure =
    // Alone on the server: no other test process's bench warms up, times or opens its large pool meanwhile
    // (TestDatabase.exclusively), so that two benches never measure each other.
    database.exclusively {
        // Every call must succeed: a refused call (a 429 of the read limits) sends fewer statements and would be
        // measured.
        val checked: suspend () -> Unit = {
            val response = client.call()
            check(response.status.isSuccess()) { "$name answers ${response.status}: ${response.bodyAsText()}" }
        }
        repeat(WARM_UP_CALLS) { checked() }
        val http = List(HTTP_CALLS) { timed { checked() } }.sorted()[HTTP_CALLS / 2]
        val statements = (1..RECORDINGS).map { driver.record(checked) }.minBy { it.size }
        val plans = List(TRIALS) { database.driver.replay(statements, explain = true, writes) }
            .sortedBy(::databaseTime)[TRIALS / 2]
        val latencies = database.withRole(poolSize = CONCURRENT_CALLS) { role, pool ->
            database.superuser.execute("GRANT ${database.servingRole} TO $role").getOrThrow()
            val callers = 1..CONCURRENT_CALLS
            // Every caller warmed up first, then all timed together: their replays overlap CONCURRENT_CALLS wide.
            coroutineScope { callers.map { async { pool.replay(statements, explain = true, writes) } }.awaitAll() }
            medianTrial(
                List(TRIALS) {
                    coroutineScope {
                        callers.map {
                            async { List(CALLS_EACH) { databaseTime(pool.replay(statements, explain = true, writes)) } }
                        }.awaitAll().flatten()
                    }
                },
            )
        }
        ReadMeasure(name, statements, plans, latencies, http)
    }

private val PLAN_TIME = Regex("^(Planning|Execution) Time: ([0-9.]+) ms$", RegexOption.MULTILINE)

/**
 * The time PostgreSQL spent on [plans]: the planning and execution times each one reports, added up.
 *
 * @param plans Plans of `EXPLAIN (ANALYZE, …)`.
 * @return Their time.
 */
internal fun databaseTime(plans: List<String>): Duration =
    plans.sumOf { plan -> PLAN_TIME.findAll(plan).sumOf { it.groupValues[2].toDouble() } }.milliseconds

private inline fun timed(block: () -> Unit): Duration {
    val start = TimeSource.Monotonic.markNow()
    block()
    return start.elapsedNow()
}

/**
 * Replays [statements] as the call ran them: each transaction of the call in a transaction of its own, rolled back
 * afterwards, so that every statement sees the role and the organization its transaction had set. With [explain], each
 * query (`SELECT`, `WITH`, `VALUES` or `TABLE`, apart from setting the organization or taking a lock) runs under
 * `EXPLAIN (ANALYZE, BUFFERS)` and its plan is returned.
 *
 * @param statements What one request sent.
 * @param explain Whether to return the plans.
 * @param writes Whether `UPDATE`, `INSERT` and `DELETE` statements are explained too, as queries.
 * @return The plan of each query, in order; none without [explain].
 */
internal suspend fun Driver.replay(
    statements: List<SentStatement>,
    explain: Boolean,
    writes: Boolean = false,
): List<String> {
    val plans = mutableListOf<String>()
    for (group in statements.byTransaction()) {
        val tx = begin().getOrThrow()
        try {
            plans += tx.replay(group, explain, writes)
        } finally {
            tx.rollback().getOrThrow()
        }
    }
    return plans
}

private suspend fun Transaction.replay(group: List<SentStatement>, explain: Boolean, writes: Boolean): List<String> =
    group.mapNotNull { sent ->
        // A query (a write too, with writes), apart from setting the organization or taking a lock.
        val query = QUERY.containsMatchIn(sent.sql) || (writes && WRITE.containsMatchIn(sent.sql))
        if (query && !SETUP.containsMatchIn(sent.sql)) {
            val rows = fetchAll(sent.copy(if (explain) EXPLAIN else "")).getOrThrow()
            if (explain) rows.joinToString("\n") { it.get("QUERY PLAN").asString() } else null
        } else {
            execute(sent.copy("")).getOrThrow()
            null
        }
    }

private fun List<SentStatement>.byTransaction(): List<List<SentStatement>> =
    fold(mutableListOf<MutableList<SentStatement>>()) { groups, sent ->
        if (groups.lastOrNull()?.let { it.last().transaction == sent.transaction } == true) {
            groups.last() += sent
        } else {
            groups += mutableListOf(sent)
        }
        groups
    }

private val QUERY = Regex("^[\\s(]*(SELECT|WITH|VALUES|TABLE)\\b", RegexOption.IGNORE_CASE)
private val WRITE = Regex("^\\s*(UPDATE|INSERT|DELETE)\\b", RegexOption.IGNORE_CASE)
private val SETUP = Regex("set_config\\(|pg_advisory", RegexOption.IGNORE_CASE)

// A fresh statement for each run: the server's own is never shared between the concurrent replays.
private fun SentStatement.copy(prefix: String): Statement {
    val copy = Statement.create(prefix + sql)
    // A null bound with `bindNull` is a typed null, bound again as it is.
    statement?.namedParametersValues.orEmpty().forEach { (name, value) -> copy.bind(name, value) }
    return copy
}

/**
 * Fails unless this route keeps the shape of a read page: no sequential scan of any of [largeTables], the organization
 * tables the test filled past 10 000 rows, and at most [maxQueries] queries per call (no N+1). Its database p95 is held
 * to [READ_P95_BUDGET] by [checkTime]: written for the Build's summary and the Perf workflow, failing here only past
 * [BLOCK_FACTOR] times the budget.
 *
 * @param largeTables The tables never to scan.
 * @param maxQueries The queries one call sends at most, set at what it sends today.
 */
fun ReadMeasure.assertWithinBudget(largeTables: Set<String>, maxQueries: Int) {
    assertNoScan(largeTables)
    assertQueriesAtMost(maxQueries)
    checkTime("$name: database p95", p95, READ_P95_BUDGET) { describe() }
}

/**
 * Fails if one call of this route sends more than [maxQueries] queries: a query added per row of the page (N+1) fails
 * on every runner, where a time only would on a quiet one.
 *
 * @param maxQueries The queries one call sends at most.
 */
fun ReadMeasure.assertQueriesAtMost(maxQueries: Int) {
    assertTrue(queries <= maxQueries, "$name sends $queries queries per call, over $maxQueries:\n${describe()}")
}

/**
 * Fails if this route scans any of [largeTables] sequentially: the organization tables the test filled past
 * 10 000 rows.
 *
 * @param largeTables The tables never to scan.
 */
fun ReadMeasure.assertNoScan(largeTables: Set<String>) {
    val scanned = largeTables.filter { table ->
        val scan = Regex("Seq Scan on ${Regex.escape(table)}\\b")
        plans.any { scan.containsMatchIn(it) }
    }
    assertTrue(scanned.isEmpty(), "$name scans $scanned:\n${describe()}")
}

/**
 * Writes [measures] to `build/reports/perf/<module>.txt` (uploaded with the CI's reports) and to the test's output.
 *
 * @param module The module measured.
 * @param measures Its routes.
 * @return The report.
 */
fun writeReadReport(module: String, measures: List<ReadMeasure>): String {
    val report = measures.joinToString("\n") { it.describe() }
    SystemFileSystem.createDirectories(Path("build/reports/perf"))
    SystemFileSystem.sink(Path("build/reports/perf/$module.txt")).buffered().use { it.writeString(report) }
    println(report)
    return report
}
