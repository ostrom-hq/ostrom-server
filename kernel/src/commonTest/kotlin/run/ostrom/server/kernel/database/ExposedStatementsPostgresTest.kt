// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.testing.TestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The statements every module on Exposed shares ([ExposedStatements.kt]), on a real PostgreSQL. */
class ExposedStatementsPostgresTest {
    private object Rows : Table("kernel_statement_rows") {
        val k = long("k")
        val v = long("v")
        val note = text("note").nullable()
    }

    private fun StatementBuilder.insertRow(k: Long, v: Long) = Rows.insert {
        it[Rows.k] = k
        it[Rows.v] = v
    }

    private fun withRows(block: JdbcTransaction.() -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val database = TestDatabase.create() ?: return@withContext
            try {
                JdbcDatabase(database.servingConfig, JdbcDatabase.MIN_POOL_SIZE).use { jdbc ->
                    jdbc.query {
                        // A temporary table of this transaction's connection: the test's own rows, never migrated.
                        exec(
                            "CREATE TEMP TABLE kernel_statement_rows (k BIGINT NOT NULL, v BIGINT NOT NULL, note TEXT)",
                        )
                        block()
                        rollback()
                    }
                }
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun aWriteReturnsWhatItChangedAndAReadMapsItsRows() = withRows {
        listOf(1L to 10L, 2L to 20L, 3L to 30L).forEach { (k, v) ->
            assertEquals(1, written { insertRow(k, v) })
        }
        assertEquals(2, written { Rows.update({ Rows.k greater 1L }) { it[Rows.v] = 5L } })

        val read = Read(Rows.select(Rows.k).orderBy(Rows.k to SortOrder.ASC)) { it[Rows.k] }
        assertEquals(listOf(1L, 2L, 3L), read.rows())
        assertEquals(1L, read.firstOrNull())
        val one = Read(Rows.select(Rows.k).where { Rows.k eq 2L }) { it[Rows.k] }
        assertEquals(2L, one.single())
        assertEquals(2L, one.singleOrNull())
        val none = Read(Rows.select(Rows.k).where { Rows.k eq 9L }) { it[Rows.k] }
        assertNull(none.singleOrNull())
        assertNull(none.firstOrNull())
    }

    @Test
    fun theAggregatesCountAndBoundWhatTheirConditionKeeps() = withRows {
        listOf(1L to 10L, 2L to 20L, 3L to 30L).forEach { (k, v) ->
            write { insertRow(k, v) }
        }
        val count = countWhere(Rows.v greater 15L)
        val max = maxWhere(Rows.k eq 2L, Rows.v)
        val none = maxWhere(Rows.k eq 9L, Rows.v)
        val all = maxOrZero(Rows.v)
        val row = Rows.select(count, max, none, all).single()
        assertEquals(listOf(2L, 20L, 0L, 30L), listOf(row[count], row[max], row[none], row[all]))

        val sum = sumOrZero(Rows.select(Sum(Rows.v, Rows.v.columnType).asBigint()))
        val empty = sumOrZero(Rows.select(Sum(Rows.v, Rows.v.columnType).asBigint()).where { Rows.k eq 9L })
        val sums = Table.Dual.select(sum, empty).single()
        assertEquals(60L to 0L, sums[sum] to sums[empty])
    }

    @Test
    fun theKeysetConditionsCompareRowsAsAWhole() = withRows {
        listOf(1L to 1L, 1L to 2L, 2L to 1L).forEach { (k, v) ->
            write { insertRow(k, v) }
        }
        val pair: (ResultRow) -> Pair<Long, Long> = { it[Rows.k] to it[Rows.v] }
        val ordered = listOf(Rows.k to SortOrder.ASC, Rows.v to SortOrder.ASC).toTypedArray()
        val less = Rows.select(Rows.k, Rows.v)
            .where { rowLess(listOf(Rows.k, Rows.v), listOf(longLiteral(2), longLiteral(1))) }
            .orderBy(*ordered).map(pair)
        assertEquals(listOf(1L to 1L, 1L to 2L), less)

        val cursor = Rows.select(Rows.k, Rows.v).where { Rows.k eq 2L }
        val before = Rows.select(Rows.k, Rows.v).where { rowBefore(listOf(Rows.k, Rows.v), cursor) }
            .orderBy(*ordered).map(pair)
        assertEquals(listOf(1L to 1L, 1L to 2L), before)
    }

    @Test
    fun theAdvisoryLockOfAKeyIsHeldUntilTheTransactionEnds() = withRows {
        assertTrue("hashtextextended" in advisoryLock("orgs:a").prepareSQL(this))
        lockKeyUntilCommit("orgs:a")
        val held = exec("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid()") {
            it.next()
            it.getLong(1)
        }
        assertEquals(1L, held)
    }

    @Test
    fun aColumnWrittenWithAnotherIsReadOrFailsWithItsName() = withRows {
        val kept = ResultRow.createAndFillValues(mapOf(Rows.note to "n", Rows.k to 1L))
        assertEquals("n", kept.present(Rows.note, Rows.k))
        val malformed = ResultRow.createAndFillValues(mapOf(Rows.note to null, Rows.k to 1L))
        val error = assertFailsWith<IllegalStateException> { malformed.present(Rows.note, Rows.k) }
        assertEquals("note is set with k", error.message)
    }

    @Test
    fun theDatabasesClockMovesByAFiniteDurationToTheMicrosecondEitherWay() = withRows {
        val now = databaseNow()
        val later = databaseNowPlus(1_500.milliseconds + 250.microseconds)
        val earlier = databaseNowPlus((-2).seconds)
        val row = Table.Dual.select(now, later, earlier).single()
        assertEquals(1_500.milliseconds + 250.microseconds, row[later] - row[now])
        assertEquals((-2).seconds, row[earlier] - row[now])
        assertFailsWith<IllegalArgumentException> { databaseNowPlus(Duration.INFINITE) }
    }
}
