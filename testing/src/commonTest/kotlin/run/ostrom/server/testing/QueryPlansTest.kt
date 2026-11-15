// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.database.JdbcDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.test.Test
import kotlin.test.assertTrue

/** [plan] explains a statement as Exposed sends it, one line per node. */
class QueryPlansTest {
    @Test
    fun aStatementIsExplainedAnalyzedOneNodeALine() = runTest {
        withContext(Dispatchers.Default) {
            val database = TestDatabase.create() ?: return@withContext
            try {
                JdbcDatabase(database.servingConfig, JdbcDatabase.MIN_POOL_SIZE).use { jdbc ->
                    val plan = jdbc.query { plan { Table.Dual.select(intLiteral(1)) } }
                    assertTrue(plan.startsWith("Result"), plan)
                    assertTrue("actual time" in plan && "QUERY PLAN" !in plan, plan)
                }
            } finally {
                database.close()
            }
        }
    }
}
