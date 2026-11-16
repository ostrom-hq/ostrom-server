// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Notes in the recorders of the test servers what the modules send while one records ([StatementRecorder.record]): a
 * read bench then counts and replays the statements of a request. Each statement is kept as Exposed sends it (`?`
 * placeholders) with its values and their column types by position, and its Exposed transaction is one transaction of
 * the recording.
 *
 * Registered once, by Exposed itself: `JdbcTransaction.globalInterceptors` loads it with the `ServiceLoader` when the
 * class is initialized (`META-INF/services`), before any transaction runs, and the list is never changed again. A
 * test server only attaches its recorder ([into]) in a concurrent set: changing Exposed's plain list while another
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

    /** One test server's recorder, and the number of each Exposed transaction in its recording. */
    private class Attached(private val recorder: StatementRecorder) {
        private val transactions = ConcurrentHashMap<String, Int>()

        fun note(transaction: Transaction, context: StatementContext) {
            val recording = recorder.recording ?: return
            val id = transactions.computeIfAbsent(transaction.id) { recorder.newTransaction() }
            recording.add(SentStatement(context.sql(transaction), context.args.toList(), id))
        }

        fun end(transaction: Transaction) {
            transactions.remove(transaction.id)
        }
    }

    companion object {
        private val attached: MutableSet<Attached> = ConcurrentHashMap.newKeySet()

        /**
         * Records the statements of every Exposed transaction into [recorder] while [block] runs (when [recorder]
         * records), then stops.
         *
         * @param recorder The test server's recorder.
         * @param block The test server's life.
         *
         * @return What [block] returns.
         */
        suspend fun <T> into(recorder: StatementRecorder, block: suspend () -> T): T {
            val attachment = Attached(recorder)
            attached += attachment
            try {
                return block()
            } finally {
                attached -= attachment
            }
        }
    }
}
