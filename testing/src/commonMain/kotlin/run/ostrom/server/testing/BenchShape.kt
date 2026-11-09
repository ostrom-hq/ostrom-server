// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.testing

import kotlin.test.assertTrue

// What a bench holds on every runner, whatever its load: the shape of a call, never its time (TimeBudget.kt).

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
