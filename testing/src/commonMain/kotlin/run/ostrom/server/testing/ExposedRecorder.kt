// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import io.github.smyrgeorge.sqlx4k.Statement
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * Notes in [driver] what the modules on Exposed send while it records ([RecordingDriver.record]), as the sqlx4k pool
 * notes its own: a read bench then counts and replays the statements of a request on either pool. Each statement is
 * kept as Exposed sends it (`?` placeholders) with its values bound by position, and its Exposed transaction is one
 * transaction of the recording.
 *
 * @param driver The test server's recording pool.
 */
internal class ExposedRecorder(private val driver: RecordingDriver) : GlobalStatementInterceptor {
    private val transactions = ConcurrentHashMap<String, Int>()

    override fun beforeExecution(transaction: Transaction, context: StatementContext) {
        if (!driver.isRecording) return
        val id = transactions.computeIfAbsent(transaction.id) { driver.newTransaction() }
        val sql = context.sql(transaction)
        val statement = Statement.create(sql)
        context.args.forEachIndexed { index, (_, value) -> statement.bind(index, value) }
        driver.note(sql, statement, id)
    }

    override fun afterCommit(transaction: Transaction) {
        transactions.remove(transaction.id)
    }

    override fun afterRollback(transaction: Transaction) {
        transactions.remove(transaction.id)
    }

    /** Records the Exposed statements of every transaction while [block] runs, then stops. */
    inline fun <T> installed(block: () -> T): T {
        synchronized(JdbcTransaction.globalInterceptors) { JdbcTransaction.globalInterceptors += this }
        try {
            return block()
        } finally {
            synchronized(JdbcTransaction.globalInterceptors) { JdbcTransaction.globalInterceptors -= this }
        }
    }
}
