// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.kernel.http.HealthCheck
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The state of a connection pool, read without any I/O.
 *
 * @property open Whether the pool is running and not closed.
 * @property waiting The callers waiting for a connection right now.
 */
data class PoolState(val open: Boolean, val waiting: Int)

/**
 * `/ready`'s database check (CRL-11): the pool's state, never a query. A database that stalls makes callers wait
 * for a connection; the replica leaves the Service only when some have waited for [patience] without a break, so a
 * short stall gives slow answers that clients retry instead of every replica not ready at once.
 *
 * @param state Reads the pool's state.
 * @param clock Measures the wait.
 * @param patience How long callers may keep waiting before the replica is not ready.
 */
class PoolHealthCheck(
    private val state: () -> PoolState,
    private val clock: TimeSource = TimeSource.Monotonic,
    private val patience: Duration = 30.seconds,
) : HealthCheck {
    override val name: String = "database"

    @Volatile
    private var waitingSince: TimeMark? = null

    override suspend fun isUp(): Boolean {
        val pool = state()
        if (pool.waiting == 0) waitingSince = null
        val since = if (pool.waiting == 0) null else waitingSince ?: clock.markNow().also { waitingSince = it }
        return pool.open && (since == null || since.elapsedNow() < patience)
    }
}
