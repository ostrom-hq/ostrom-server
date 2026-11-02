// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
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
 * background job running meanwhile is not counted), replayed under `EXPLAIN (ANALYZE, BUFFERS)` as the serving role;
 * then the database time of a call, its statements replayed by [CONCURRENT_CALLS] callers at once on a pool of as
 * many connections ([CALLS_EACH] replays each), every transaction of the call replayed as one and rolled back.
 *
 * The in-process test server serves its calls one after another: its latency under concurrent calls measures the
 * test engine, not the database (`GET /health`, one `SELECT 1`, has a p95 near 90 ms there). The budget is held on the
 * database time; the HTTP p95 of a deployment is k6's (ostrom-e2e, `chart-load`).
 *
 * @param name The route and its case.
 * @param call One call.
 * @return The measure.
 */
suspend fun TestServer.measureRead(name: String, call: suspend HttpClient.() -> HttpResponse): ReadMeasure {
    repeat(WARM_UP_CALLS) {
        val response = client.call()
        check(response.status.isSuccess()) { "$name answers ${response.status}: ${response.bodyAsText()}" }
    }
    val http = List(HTTP_CALLS) { timed { client.call() } }.sorted()[HTTP_CALLS / 2]
    val statements = (1..RECORDINGS).map { driver.record { client.call() } }.minBy { it.size }
    val plans = database.driver.replay(statements, explain = true)
    val latencies = database.withRole(poolSize = CONCURRENT_CALLS) { role, pool ->
        database.superuser.execute("GRANT ${database.servingRole} TO $role").getOrThrow()
        coroutineScope {
            (1..CONCURRENT_CALLS).map {
                async {
                    pool.replay(statements, explain = false)
                    List(CALLS_EACH) { timed { pool.replay(statements, explain = false) } }
                }
            }.awaitAll().flatten().sorted()
        }
    }
    return ReadMeasure(name, statements, plans, latencies, http)
}

private inline fun timed(block: () -> Unit): Duration {
    val start = TimeSource.Monotonic.markNow()
    block()
    return start.elapsedNow()
}

/**
 * Replays [statements] as the call ran them: each transaction of the call in a transaction of its own, rolled back
 * afterwards, so that every statement sees the role and the organization its transaction had set. With [explain], each
 * query (`SELECT` or `WITH`, apart from setting the organization or taking a lock) runs under
 * `EXPLAIN (ANALYZE, BUFFERS)` and its plan is returned.
 *
 * @param statements What one request sent.
 * @param explain Whether to return the plans.
 * @return The plan of each query, in order; none without [explain].
 */
internal suspend fun Driver.replay(statements: List<SentStatement>, explain: Boolean): List<String> {
    val plans = mutableListOf<String>()
    for (group in statements.byTransaction()) {
        val tx = begin().getOrThrow()
        try {
            for (sent in group) {
                if (sent.isQuery()) {
                    val rows = tx.fetchAll(sent.copy(if (explain) EXPLAIN else "")).getOrThrow()
                    if (explain) plans += rows.joinToString("\n") { it.get("QUERY PLAN").asString() }
                } else {
                    tx.execute(sent.copy("")).getOrThrow()
                }
            }
        } finally {
            tx.rollback().getOrThrow()
        }
    }
    return plans
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

private val QUERY = Regex("^\\s*(SELECT|WITH)\\b", RegexOption.IGNORE_CASE)
private val SETUP = Regex("set_config\\(|pg_advisory", RegexOption.IGNORE_CASE)

private fun SentStatement.isQuery(): Boolean = QUERY.containsMatchIn(sql) && !SETUP.containsMatchIn(sql)

// A fresh statement for each run: the server's own is never shared between the concurrent replays.
private fun SentStatement.copy(prefix: String): Statement {
    val copy = Statement.create(prefix + sql)
    statement?.namedParametersValues?.forEach { (name, value) ->
        if (value == null) copy.bindNull(name, String::class) else copy.bind(name, value)
    }
    return copy
}

/**
 * Fails unless this route keeps the budget of a read page: a database p95 under [READ_P95_BUDGET], and no sequential
 * scan of
 * any of [largeTables], the organization tables the test filled past 10 000 rows.
 *
 * @param largeTables The tables never to scan.
 */
fun ReadMeasure.assertWithinBudget(largeTables: Set<String>) {
    val scanned = largeTables.filter { table -> plans.any { it.contains(Regex("Seq Scan on $table\\b")) } }
    assertTrue(scanned.isEmpty(), "$name scans $scanned:\n${describe()}")
    assertTrue(p95 < READ_P95_BUDGET, "$name: database p95 $p95 over $READ_P95_BUDGET:\n${describe()}")
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
