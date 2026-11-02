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
import kotlin.math.ceil
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * What one read route costs on a filled database, measured by [measureRead].
 *
 * @property name The route and its case (`GET /api/v1/audit?action=…`).
 * @property statements The statements of one call, in order: their number is the queries per call.
 * @property plans The `EXPLAIN (ANALYZE, BUFFERS)` of each query among them, on the serving role with row-level
 *   security, in the organization the call set.
 * @property p50 The median latency of [calls] calls, [CONCURRENT_CALLS] at a time.
 * @property p95 Their 95th percentile.
 * @property calls How many calls were timed.
 */
class ReadMeasure(
    val name: String,
    val statements: List<SentStatement>,
    val plans: List<String>,
    val p50: Duration,
    val p95: Duration,
    val calls: Int,
) {
    /** The queries one call sends. */
    val queries: Int get() = statements.size

    /** The measure as written to the report: the figures, then each query with its plan. */
    fun describe(): String = buildString {
        appendLine("== $name")
        appendLine("queries per call: $queries; p50 $p50, p95 $p95 ($calls calls, $CONCURRENT_CALLS concurrent)")
        statements.forEach { appendLine("-- ${it.sql}") }
        plans.forEach { appendLine(it).appendLine() }
    }
}

/** The calls timed at once by [measureRead], the load the budget is set for. */
const val CONCURRENT_CALLS = 20

/** The budget of a read route's page at [CONCURRENT_CALLS] concurrent calls. */
val READ_P95_BUDGET: Duration = 50.milliseconds

private const val WARM_UP_CALLS = 20
private const val CALLS_EACH = 10
private const val RECORDINGS = 3
private const val P50 = 0.5
private const val P95 = 0.95

/**
 * Measures a read route of this test server: [WARM_UP_CALLS] calls first (each must succeed), then the statements of
 * one call (the fewest of [RECORDINGS] recordings, so that a background job running meanwhile is not counted), replayed
 * under `EXPLAIN (ANALYZE, BUFFERS)` as the serving role in a transaction rolled back afterwards, then the latency of
 * [CONCURRENT_CALLS] callers making [CALLS_EACH] calls each.
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
    val statements = (1..RECORDINGS).map { driver.record { client.call() } }.minBy { it.size }
    val plans = database.driver.explain(statements)
    val latencies = coroutineScope {
        (1..CONCURRENT_CALLS).map {
            async {
                List(CALLS_EACH) {
                    val start = TimeSource.Monotonic.markNow()
                    client.call()
                    start.elapsedNow()
                }
            }
        }.awaitAll().flatten().sorted()
    }
    return ReadMeasure(name, statements, plans, latencies.at(P50), latencies.at(P95), latencies.size)
}

private fun List<Duration>.at(quantile: Double): Duration = this[ceil(quantile * size).toInt() - 1]

/**
 * Replays [statements] in one transaction rolled back afterwards: each query (`SELECT` or `WITH`, apart from setting
 * the organization or taking a lock) under `EXPLAIN (ANALYZE, BUFFERS)`, everything else as it ran, so that each query
 * sees the role and the organization the request had set.
 *
 * @param statements What one request sent.
 * @return The plan of each query, in order.
 */
internal suspend fun Driver.explain(statements: List<SentStatement>): List<String> {
    val tx = begin().getOrThrow()
    try {
        return statements.mapNotNull { sent ->
            if (sent.isQuery()) {
                tx.fetchAll(sent.explained()).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
            } else {
                (sent.statement?.let { tx.execute(it) } ?: tx.execute(sent.sql)).getOrThrow()
                null
            }
        }
    } finally {
        tx.rollback().getOrThrow()
    }
}

private val QUERY = Regex("^\\s*(SELECT|WITH)\\b", RegexOption.IGNORE_CASE)
private val SETUP = Regex("set_config\\(|pg_advisory", RegexOption.IGNORE_CASE)

private fun SentStatement.isQuery(): Boolean = QUERY.containsMatchIn(sql) && !SETUP.containsMatchIn(sql)

private fun SentStatement.explained(): Statement {
    val explained = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql")
    statement?.namedParametersValues?.forEach { (name, value) ->
        if (value == null) explained.bindNull(name, String::class) else explained.bind(name, value)
    }
    return explained
}

/**
 * Fails unless this route keeps the budget of a read page: a p95 under [READ_P95_BUDGET], and no sequential scan of
 * any of [largeTables], the organization tables the test filled past 10 000 rows.
 *
 * @param largeTables The tables never to scan.
 */
fun ReadMeasure.assertWithinBudget(largeTables: Set<String>) {
    val scanned = largeTables.filter { table -> plans.any { it.contains(Regex("Seq Scan on $table\\b")) } }
    assertTrue(scanned.isEmpty(), "$name scans $scanned:\n${describe()}")
    assertTrue(p95 < READ_P95_BUDGET, "$name: p95 $p95 over $READ_P95_BUDGET:\n${describe()}")
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
