// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.instance.infrastructure

import run.ostrom.server.kernel.database.ScheduledWork
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * [ScheduledWork] on PostgreSQL (`instance_scheduled_work`, [InstanceMigrations]), TeamCode's `SqlScheduledWork`: one
 * row per job, claimed by a single conditional `UPDATE` on the database's clock (two replicas racing for the same row
 * are serialized by its lock, and the second finds it no longer due), released by its owner only.
 *
 * @param driver The database.
 */
class ScheduledWorkImpl(private val driver: Driver) : ScheduledWork {
    override suspend fun runDue(
        name: String,
        interval: Duration,
        timeout: Duration,
        block: suspend () -> Unit,
    ): Boolean {
        require(name.length in 1..MAX_NAME) { "A job name has 1 to $MAX_NAME characters." }
        require(interval.isPositive()) { "A job's interval is positive." }
        require(timeout.isPositive() && timeout <= MAX_TIMEOUT) { "A job's timeout is positive and at most $MAX_TIMEOUT." }
        val owner = Uuid.random()
        if (!claim(name, owner, timeout + MARGIN)) return false
        try {
            withTimeout(timeout) { block() }
        } finally {
            withContext(NonCancellable) { release(name, owner, interval) }
        }
        return true
    }

    private suspend fun claim(name: String, owner: Uuid, lease: Duration): Boolean {
        driver.execute(
            Statement.create("INSERT INTO instance_scheduled_work (name) VALUES (:name) ON CONFLICT (name) DO NOTHING")
                .bind("name", name),
        ).getOrThrow()
        return driver.execute(
            Statement.create(
                """
                UPDATE instance_scheduled_work SET owner = :owner,
                available_at = clock_timestamp() + CAST(:lease AS BIGINT) * INTERVAL '1 millisecond',
                last_started_at = clock_timestamp()
                WHERE name = :name AND available_at <= clock_timestamp()
                """,
            ).bind("owner", owner).bind("lease", lease.inWholeMilliseconds).bind("name", name),
        ).getOrThrow() == 1L
    }

    private suspend fun release(name: String, owner: Uuid, interval: Duration) {
        driver.execute(
            Statement.create(
                """
                UPDATE instance_scheduled_work SET owner = NULL,
                available_at = clock_timestamp() + CAST(:interval AS BIGINT) * INTERVAL '1 millisecond'
                WHERE name = :name AND owner = :owner
                """,
            ).bind("interval", interval.inWholeMilliseconds).bind("name", name).bind("owner", owner),
        ).getOrThrow()
    }

    private companion object {
        const val MAX_NAME = 100
        val MAX_TIMEOUT = 5.minutes

        /** A claimed lease outlives the run's timeout by this much, so a slow release never lets a second run in. */
        val MARGIN = 15.seconds
    }
}
