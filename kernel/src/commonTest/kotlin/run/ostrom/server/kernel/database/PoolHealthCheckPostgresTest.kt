// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.testing.TestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `/ready`'s database check on a real pool (CRL-11): while every connection is taken and callers keep waiting for
 * one, the replica stays ready for the check's patience, then is not; once the callers are served, it is again.
 * The replicas smoke cannot show it yet: its callers authenticate on sqlx4k first, and fail there.
 */
class PoolHealthCheckPostgresTest {
    @Test
    fun callersThatKeepWaitingOnAFullPoolMakeTheReplicaNotReady() {
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                val database = TestDatabase.create() ?: return@withContext
                val jdbc = JdbcDatabase(database.servingConfig, JdbcDatabase.MIN_POOL_SIZE)
                try {
                    val check = PoolHealthCheck(jdbc::state, patience = PATIENCE)
                    assertTrue(check.isUp(), "an idle pool is ready")
                    saturate(jdbc, check)
                    assertTrue(check.isUp(), "served again, the replica is ready")
                } finally {
                    jdbc.close()
                    database.close()
                }
            }
        }
    }

    // Both connections held for HOLD by two blocking transactions, and a caller every 100 ms that waits for one.
    private suspend fun saturate(jdbc: JdbcDatabase, check: PoolHealthCheck) = coroutineScope {
        val holders = List(JdbcDatabase.MIN_POOL_SIZE) {
            // Exposed takes its connection at the first statement.
            async {
                jdbc.query {
                    exec("SELECT 1") {}
                    Thread.sleep(HOLD.inWholeMilliseconds)
                }
            }
        }
        val start = TimeSource.Monotonic.markNow()
        val waiters = launch {
            while (start.elapsedNow() < HOLD) {
                launch {
                    try {
                        jdbc.query { exec("SELECT 1") {} }
                    } catch (_: java.sql.SQLException) {
                        // Hikari's connectionTimeout: the caller gave up, another one waits already.
                    }
                }
                delay(100.milliseconds)
            }
        }
        delay(1.seconds)
        assertTrue(check.isUp(), "callers waiting for less than the patience leave the replica ready")
        delay(PATIENCE + 1.seconds)
        assertFalse(check.isUp(), "callers waiting for longer than the patience make it not ready")
        holders.awaitAll()
        waiters.join()
    }

    private companion object {
        val PATIENCE = 3.seconds
        val HOLD = 6.seconds
    }
}
