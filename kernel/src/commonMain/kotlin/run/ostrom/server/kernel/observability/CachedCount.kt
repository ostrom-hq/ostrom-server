// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.observability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A count a gauge reads at each export ([OstromMetrics.observeGatesPending], [OstromMetrics.observeSessionsActive]):
 * the export never waits on the database, it reads the last value [refresh] stored.
 *
 * @param count Reads the count; it may throw, the last value then stays.
 */
@OptIn(ExperimentalAtomicApi::class)
class CachedCount(private val count: suspend () -> Long) {
    private val last = AtomicLong(0)

    /** The last count read, 0 before the first. */
    fun value(): Long = last.load()

    /** Reads the count again. */
    suspend fun refresh() {
        last.store(count())
    }

    /**
     * Reads the count now, then every [every]; a failed read is reported to [scope]'s handler and the next one runs.
     *
     * @param scope The server's scope.
     * @param every The period.
     * @return The loop, cancelled with [scope].
     */
    fun refreshEvery(scope: CoroutineScope, every: Duration = EVERY): Job = scope.launch {
        while (true) {
            supervisorScope { launch { refresh() } }
            delay(every)
        }
    }

    /** The default period. */
    companion object {
        /** As often as the server exports its metrics. */
        val EVERY: Duration = 30.seconds
    }
}
