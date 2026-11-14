// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.testing.TestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** [databaseNow] and [databaseNowPlus] read the database's clock of the transaction, moved by the seconds bound. */
class DatabaseClockPostgresTest {
    @Test
    fun theDatabasesClockMovesByTheSecondsGivenEitherWay() = runTest {
        withContext(Dispatchers.Default) {
            val database = TestDatabase.create() ?: return@withContext
            try {
                JdbcDatabase(database.servingConfig, JdbcDatabase.MIN_POOL_SIZE).use { jdbc ->
                    val now = databaseNow()
                    val later = databaseNowPlus(90)
                    val earlier = databaseNowPlus(-5)
                    val row = jdbc.query { Table.Dual.select(now, later, earlier).single() }
                    assertEquals(90.seconds, row[later] - row[now])
                    assertEquals((-5).seconds, row[earlier] - row[now])
                }
            } finally {
                database.close()
            }
        }
    }
}
