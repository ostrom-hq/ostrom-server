// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.instance.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.kernel.database.written
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * [ScheduledWork] on PostgreSQL (`instance_scheduled_work`, [InstanceMigrations]), TeamCode's `SqlScheduledWork`: one
 * row per job, claimed by a single conditional `UPDATE` on the database's clock (two replicas racing for the same row
 * are serialized by its lock, and the second finds it no longer due), released by its owner only. Each claim and each
 * release is one short transaction, so `now()` is the time of the statement.
 *
 * @param database The serving role's pool.
 */
class ScheduledWorkImpl(private val database: JdbcDatabase) : ScheduledWork {
    override suspend fun runDue(
        name: String,
        interval: Duration,
        timeout: Duration,
        block: suspend () -> Unit,
    ): Boolean {
        require(name.length in 1..MAX_NAME) { "A job name has 1 to $MAX_NAME characters." }
        require(interval.isPositive()) { "A job's interval is positive." }
        require(timeout.isPositive() && timeout <= MAX_TIMEOUT) {
            "A job's timeout is positive and at most $MAX_TIMEOUT."
        }
        val owner = Uuid.random()
        if (!claim(name, owner, timeout + MARGIN)) return false
        try {
            withTimeout(timeout) { block() }
        } finally {
            withContext(NonCancellable) { release(name, owner, interval) }
        }
        return true
    }

    private suspend fun claim(name: String, owner: Uuid, lease: Duration): Boolean = database.query {
        val w = InstanceScheduledWork
        w.insertIgnore { it[w.name] = name }
        written { scheduledWorkClaim(name, owner, lease) } == 1
    }

    private suspend fun release(name: String, owner: Uuid, interval: Duration) {
        database.query {
            val w = InstanceScheduledWork
            w.update({ (w.name eq name) and (w.owner eq owner) }) {
                it[w.owner] = null
                it[w.availableAt] = databaseNowPlus(interval)
            }
        }
    }

    private companion object {
        const val MAX_NAME = 100
        private val MAX_TIMEOUT: Duration = 5.minutes

        /** A claimed lease outlives the run's timeout by this much, so a slow release never lets a second run in. */
        private val MARGIN: Duration = 15.seconds
    }
}

/**
 * The claim of [name]'s lease by [owner] for [lease], if it is due: one conditional update by the primary key. Two
 * replicas racing for the row are serialized by its lock, and the second finds it no longer due.
 */
internal fun StatementBuilder.scheduledWorkClaim(name: String, owner: Uuid, lease: Duration): UpdateStatement {
    val w = InstanceScheduledWork
    return w.update({ (w.name eq name) and (w.availableAt lessEq databaseNow()) }) {
        it[w.owner] = owner
        it[w.availableAt] = databaseNowPlus(lease)
        it[w.lastStartedAt] = databaseNow()
    }
}
