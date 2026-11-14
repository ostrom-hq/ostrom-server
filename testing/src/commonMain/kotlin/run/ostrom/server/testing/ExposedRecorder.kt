// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import io.github.smyrgeorge.sqlx4k.Statement
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Notes in the recording pools of the test servers what the modules on Exposed send while one records
 * ([RecordingDriver.record]), as each sqlx4k pool notes its own: a read bench then counts and replays the statements
 * of a request on either pool. Each statement is kept as Exposed sends it (`?` placeholders) with its values bound by
 * position, and its Exposed transaction is one transaction of the recording.
 *
 * Registered once, by Exposed itself: `JdbcTransaction.globalInterceptors` loads it with the `ServiceLoader` when the
 * class is initialized (`META-INF/services`), before any transaction runs, and the list is never changed again. A
 * test server only attaches its pool ([into]) in a concurrent set: changing Exposed's plain list while another
 * thread's transaction iterates it could throw a ConcurrentModificationException. Per-transaction interceptors would
 * need a hook in the kernel's every transaction for a test-only concern.
 */
internal class ExposedRecorder : GlobalStatementInterceptor {
    override fun beforeExecution(transaction: Transaction, context: StatementContext) {
        attached.forEach { it.note(transaction, context) }
    }

    override fun afterCommit(transaction: Transaction) {
        attached.forEach { it.end(transaction) }
    }

    override fun afterRollback(transaction: Transaction) {
        attached.forEach { it.end(transaction) }
    }

    /** One test server's pool, and the number of each Exposed transaction in its recording. */
    private class Attached(private val driver: RecordingDriver) {
        private val transactions = ConcurrentHashMap<String, Int>()

        fun note(transaction: Transaction, context: StatementContext) {
            if (!driver.isRecording) return
            val id = transactions.computeIfAbsent(transaction.id) { driver.newTransaction() }
            val sql = context.sql(transaction)
            val statement = Statement.create(sql)
            context.args.forEachIndexed { index, (_, value) -> statement.bind(index, value) }
            driver.note(sql, statement, id)
        }

        fun end(transaction: Transaction) {
            transactions.remove(transaction.id)
        }
    }

    companion object {
        private val attached: MutableSet<Attached> = ConcurrentHashMap.newKeySet()

        /**
         * Records the Exposed statements of every transaction into [driver] while [block] runs (when [driver]
         * records), then stops.
         *
         * @param driver The test server's recording pool.
         * @param block The test server's life.
         *
         * @return What [block] returns.
         */
        suspend fun <T> into(driver: RecordingDriver, block: suspend () -> T): T {
            val attachment = Attached(driver)
            attached += attachment
            try {
                return block()
            } finally {
                attached -= attachment
            }
        }
    }
}
