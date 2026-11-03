// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import kotlin.math.ceil
import kotlin.time.Duration

/**
 * What one read route costs on a filled database, measured by [measureRead].
 *
 * @property name The route and its case (`GET /api/v1/audit?action=…`).
 * @property statements The statements of one call, in order: their number is the queries per call.
 * @property plans The `EXPLAIN (ANALYZE, BUFFERS)` of each query among them, on the serving role with row-level
 *   security, in the organization the call set.
 * @property latencies The database time of one call, sorted: the planning and execution times PostgreSQL reports for
 *   its queries, replayed by [CONCURRENT_CALLS] callers at once, each transaction as the call ran it.
 * @property http The median of a whole call through the in-process test server, one at a time, for information: it
 *   includes the test engine and the JSON, not what the database costs.
 */
class ReadMeasure(
    val name: String,
    val statements: List<SentStatement>,
    val plans: List<String>,
    val latencies: List<Duration>,
    val http: Duration,
) {
    /** How many replays were timed. */
    val calls: Int get() = latencies.size

    /** The median database time of a call. */
    val p50: Duration get() = at(P50)

    /** Its 95th percentile, the figure held to [READ_P95_BUDGET]. */
    val p95: Duration get() = at(P95)

    private fun at(quantile: Double): Duration = latencies[ceil(quantile * latencies.size).toInt() - 1]

    /** The queries one call sends. */
    val queries: Int get() = statements.size

    /** The measure as written to the report: the figures, then each query with its plan. */
    fun describe(): String = buildString {
        appendLine("== $name")
        appendLine(
            "queries per call: $queries; database p50 $p50, p95 $p95 ($calls calls, $CONCURRENT_CALLS concurrent, " +
                "PostgreSQL's planning and execution time); " +
                "whole call in process $http",
        )
        statements.forEach { appendLine("-- ${it.sql}") }
        plans.forEach { appendLine(it).appendLine() }
    }

    private companion object {
        const val P50 = 0.5
        const val P95 = 0.95
    }
}
