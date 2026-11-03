// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.atomicArrayOfNulls

/**
 * One statement the server sent, as it sent it.
 *
 * @property sql Its text.
 * @property statement The statement with its bound values; null when the server sent a bare string.
 * @property transaction Which transaction it ran in: statements of one transaction share it, a statement sent on the
 *   pool has its own.
 * @property inTransaction Whether it ran inside a transaction the server opened, which costs a `BEGIN` and a `COMMIT`
 *   more ([roundTrips]).
 */
class SentStatement(
    val sql: String,
    val statement: Statement?,
    val transaction: Int,
    val inTransaction: Boolean = false,
)

/**
 * The round trips to the database of [this] statements: one each, and the `BEGIN` and `COMMIT` of each transaction.
 *
 * @receiver Statements of a recording ([RecordingDriver.record]).
 * @return The round trips.
 */
fun List<SentStatement>.roundTrips(): Int = size + TRANSACTION_ENDS * filter { it.inTransaction }.distinctBy {
    it.transaction
}.size

/** A transaction's `BEGIN` and `COMMIT`. */
private const val TRANSACTION_ENDS = 2

/**
 * The serving role's pool of a [serverTest], which notes every statement the server sends while [record] runs: the
 * statements of one request, to count them (no N+1) and replay them under `EXPLAIN` ([explain]).
 *
 * Recorded: `execute` and `fetchAll`, on the pool and inside [transaction], the only ways the server reaches the
 * database (`begin`, `transactionCatching` and the flows of `fetch` are passed through unrecorded: no server code
 * uses them on a read route). The same three are what [through] sends to another pool.
 *
 * @param inner The pool.
 */
@OptIn(ExperimentalAtomicApi::class)
class RecordingDriver(private val inner: Driver) : Driver by inner {
    private val sent = AtomicReference<Recording?>(null)
    private val transactions = AtomicInt(0)
    private val routed = AtomicReference<Driver?>(null)

    // The pool the server's statements go to: [inner], or the one [through] gives meanwhile.
    private val target: Driver get() = routed.load() ?: inner

    /**
     * Runs [block] with the server's statements sent to [pool] instead of the test's own pool of 4: a load test then
     * measures the server on a pool the size of a deployment's (`OSTROM_DATABASE_MAX_CONNECTIONS`, 10 by default),
     * opened by [TestDatabase.withRole] only for its timed phase. A transaction begun before keeps its connection.
     *
     * @param pool A pool connected as a role granted the serving role.
     * @param block The work.
     * @return What [block] returns.
     */
    suspend fun <T> through(pool: Driver, block: suspend () -> T): T {
        check(routed.compareAndSet(null, pool)) { "The server's statements already go to another pool." }
        try {
            return block()
        } finally {
            routed.store(null)
        }
    }

    /**
     * Runs [block] and returns the statements sent meanwhile, by [block] and by anything else running then (a
     * background job): call it with nothing else in flight, and keep the smallest of a few recordings.
     *
     * @param block The work, a request.
     * @return The statements, in order.
     */
    suspend fun record(block: suspend () -> Unit): List<SentStatement> {
        val recording = Recording()
        sent.store(recording)
        try {
            block()
            return recording.statements()
        } finally {
            sent.store(null)
        }
    }

    internal fun newTransaction(): Int = transactions.addAndFetch(1)

    internal fun note(sql: String, statement: Statement?, transaction: Int? = null) {
        sent.load()?.add(SentStatement(sql, statement, transaction ?: newTransaction(), transaction != null))
    }

    override suspend fun execute(sql: String): Result<Long> = target.execute(sql).also { note(sql, null) }

    override suspend fun execute(statement: Statement): Result<Long> =
        target.execute(statement).also { note(statement.sql, statement) }

    override suspend fun fetchAll(sql: String): Result<ResultSet> = target.fetchAll(sql).also { note(sql, null) }

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> =
        target.fetchAll(statement).also { note(statement.sql, statement) }

    override suspend fun <T> transaction(f: suspend Transaction.() -> T): T {
        val id = newTransaction()
        return target.transaction { RecordingTransaction(this, this@RecordingDriver, id).f() }
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

/**
 * The statements of one recording, appended from any thread without a lock: a slot is claimed, then filled.
 * A request sends a few dozen statements; past [CAPACITY], the next one fails the test.
 */
@OptIn(ExperimentalAtomicApi::class)
private class Recording {
    private val slots = atomicArrayOfNulls<SentStatement>(CAPACITY)
    private val count = AtomicInt(0)

    fun add(statement: SentStatement) = slots.storeAt(count.fetchAndAdd(1), statement)

    // A slot claimed but not filled yet belongs to a statement still being sent: it is left out.
    fun statements(): List<SentStatement> = List(count.load()) { slots.loadAt(it) }.filterNotNull()

    private companion object {
        const val CAPACITY = 10_000
    }
}
