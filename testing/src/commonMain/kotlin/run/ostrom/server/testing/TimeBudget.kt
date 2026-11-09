// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.testing

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * How far over its budget a time measured during a Build may go before it fails the Build: ten times, a block (a
 * lock never released, a plan that reads a table whole for every row) rather than a slow shared runner.
 */
const val BLOCK_FACTOR = 10

/** Where every measured time is written, one line each, relative to the module the tests run in. */
const val TIME_BUDGETS_FILE = "build/reports/perf/budgets.tsv"

private const val PERF_REPORTS = "build/reports/perf"

/**
 * Where [checkTime] writes, in [PERF_REPORTS]: the testing module's own tests of the checks write their made-up times
 * elsewhere, so that none reaches a Build's summary nor the Perf workflow (its first run counted nine of them).
 */
internal var timeBudgetsFile: String = TIME_BUDGETS_FILE

/**
 * Holds a time a test measured. A Build never fails on a time in milliseconds (orchestrator, JOURNAL 02:5x): a shared
 * runner under load slowed benches that no pull request had touched, twice in a row on main. The Build fails on what
 * cannot vary with the runner (the queries a call sends, the shape of their plans, the rows they read), and on a time
 * only past [BLOCK_FACTOR] times its budget. The time itself is written to [TIME_BUDGETS_FILE] (the measure, the
 * time and the budget), shown in the Build's summary and held to [budget] by the Perf workflow (`perf.yml`), on the
 * median of three runs (`scripts/perf-budgets.py`).
 *
 * @param name What was measured, unique among the measures.
 * @param measured The time.
 * @param budget What it should take at most.
 * @param details What the failure shows (the queries and their plans).
 */
fun checkTime(name: String, measured: Duration, budget: Duration, details: () -> String = { "" }) {
    require('\t' !in name && '\n' !in name) { "A measure's name is one line without a tab: $name" }
    val line = listOf(measured, budget).joinToString("\t", prefix = "$name\t", postfix = "\n") {
        it.toDouble(DurationUnit.MILLISECONDS).toString()
    }
    SystemFileSystem.createDirectories(Path(PERF_REPORTS))
    SystemFileSystem.sink(Path(timeBudgetsFile), append = true).buffered().use { it.writeString(line) }
    if (measured > budget * BLOCK_FACTOR) {
        fail("$name: $measured, over $BLOCK_FACTOR times its budget of $budget:\n${details()}")
    }
}
