// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

/**
 * One statement the server sent, as it sent it.
 *
 * @property sql Its text.
 * @property statement The statement with its bound values; null when the server sent a bare string.
 * @property transaction Which transaction it ran in: statements of one transaction share it, a statement sent on the
 *   pool has its own.
 */
class SentStatement(val sql: String, val statement: Statement?, val transaction: Int)

/**
 * The serving role's pool of a [serverTest], which notes every statement the server sends while [record] runs: the
 * statements of one request, to count them (no N+1) and replay them under `EXPLAIN` ([explain]).
 *
 * Recorded: `execute` and `fetchAll`, on the pool and inside [transaction], the only ways the server reaches the
 * database (`begin`, `transactionCatching` and the flows of `fetch` are passed through unrecorded: no server code
 * uses them on a read route).
 *
 * @param inner The pool.
 */
@OptIn(ExperimentalAtomicApi::class)
class RecordingDriver(private val inner: Driver) : Driver by inner {
    private val sent = AtomicReference<List<SentStatement>?>(null)
    private val transactions = AtomicInt(0)

    /**
     * Runs [block] and returns the statements sent meanwhile, by [block] and by anything else running then (a
     * background job): call it with nothing else in flight, and keep the smallest of a few recordings.
     *
     * @param block The work, a request.
     * @return The statements, in order.
     */
    suspend fun record(block: suspend () -> Unit): List<SentStatement> {
        sent.store(emptyList())
        try {
            block()
            return sent.load().orEmpty()
        } finally {
            sent.store(null)
        }
    }

    internal fun newTransaction(): Int = transactions.addAndFetch(1)

    internal fun note(sql: String, statement: Statement?, transaction: Int = newTransaction()) {
        sent.update { it?.plus(SentStatement(sql, statement, transaction)) }
    }

    override suspend fun execute(sql: String): Result<Long> = inner.execute(sql).also { note(sql, null) }

    override suspend fun execute(statement: Statement): Result<Long> =
        inner.execute(statement).also { note(statement.sql, statement) }

    override suspend fun fetchAll(sql: String): Result<ResultSet> = inner.fetchAll(sql).also { note(sql, null) }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        inner.fetchAll(statement).also { note(statement.sql, statement) }

    override suspend fun <T> transaction(f: suspend Transaction.() -> T): T {
        val id = newTransaction()
        return inner.transaction { RecordingTransaction(this, this@RecordingDriver, id).f() }
    }
}

/**
 * A transaction of a [RecordingDriver], recording what runs in it.
 *
 * @param inner The transaction.
 * @param driver Where the statements are noted.
 * @param id The transaction's number among the noted statements.
 */
internal class RecordingTransaction(
    private val inner: Transaction,
    private val driver: RecordingDriver,
    private val id: Int,
) : Transaction by inner {
    override suspend fun execute(sql: String): Result<Long> = inner.execute(sql).also { driver.note(sql, null, id) }

    override suspend fun execute(statement: Statement): Result<Long> =
        inner.execute(statement).also { driver.note(statement.sql, statement, id) }

    override suspend fun fetchAll(sql: String): Result<ResultSet> =
        inner.fetchAll(sql).also { driver.note(sql, null, id) }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        inner.fetchAll(statement).also { driver.note(statement.sql, statement, id) }
}
