// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The ceiling of a report's p95 at [CONCURRENT_CALLS] concurrent calls ([assertReportWithinBudget]): a report adds up
 * a month of an organization's rows, so twenty at once saturate the CI runner's CPU and measure the runner more than
 * the query (orchestrator, 03/11/2026, server#244). The ceiling still catches a plan that degrades by an order.
 */
val REPORT_P95_CEILING: Duration = 1.seconds

/**
 * Fails unless this report keeps the shape of a report: no sequential scan of [largeTables] and at most [maxQueries]
 * queries per call. A report counts distinct sessions over its period and so reads one row per session and day, which
 * no additive total can replace without changing what it counts; a page keeps [assertWithinBudget]. Its times, held
 * by [checkTime] (the Build's summary and the Perf workflow, failing here only past [BLOCK_FACTOR] times their
 * budget), are the budget the orchestrator set on 03/11/2026 (server#244): one execution alone (planning and execution
 * time of its plans, `EXPLAIN ANALYZE`) within [READ_P95_BUDGET], and a p95 at [CONCURRENT_CALLS] concurrent calls
 * within [REPORT_P95_CEILING].
 *
 * @param largeTables The tables never to scan.
 * @param maxQueries The queries one call sends at most, set at what it sends today.
 */
fun ReadMeasure.assertReportWithinBudget(largeTables: Set<String>, maxQueries: Int) {
    assertNoScan(largeTables)
    assertQueriesAtMost(maxQueries)
    checkTime("$name: database time alone", databaseTime(plans), READ_P95_BUDGET) { describe() }
    checkTime("$name: database p95", p95, REPORT_P95_CEILING) { describe() }
}
