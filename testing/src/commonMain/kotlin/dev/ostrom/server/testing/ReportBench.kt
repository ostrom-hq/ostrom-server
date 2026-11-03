// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The ceiling of a report's p95 at [CONCURRENT_CALLS] concurrent calls ([assertReportWithinBudget]): a report adds up
 * a month of an organization's rows, so twenty at once saturate the CI runner's CPU and measure the runner more than
 * the query (orchestrator, 03/11/2026, server#244). The ceiling still catches a plan that degrades by an order.
 */
val REPORT_P95_CEILING: Duration = 1.seconds

/**
 * Fails unless this report keeps the budget of a report, set by the orchestrator on 03/11/2026 (server#244): one
 * execution alone (planning and execution time of its plans, `EXPLAIN ANALYZE`) within [READ_P95_BUDGET], no
 * sequential scan of [largeTables], and a p95 at [CONCURRENT_CALLS] concurrent calls within [REPORT_P95_CEILING].
 * A report counts distinct sessions over its period and so reads one row per session and day, which no additive
 * total can replace without changing what it counts; a page keeps [assertWithinBudget].
 *
 * @param largeTables The tables never to scan.
 */
fun ReadMeasure.assertReportWithinBudget(largeTables: Set<String>) {
    assertNoScan(largeTables)
    val alone = databaseTime(plans)
    assertTrue(alone <= READ_P95_BUDGET, "$name: alone $alone over $READ_P95_BUDGET:\n${describe()}")
    assertTrue(p95 <= REPORT_P95_CEILING, "$name: database p95 $p95 over $REPORT_P95_CEILING:\n${describe()}")
}
