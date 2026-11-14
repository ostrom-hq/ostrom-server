// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The pool, except that a cancelled caller is told it was cancelled. sqlx4k's `begin` catches every exception while it
 * sends `BEGIN`, the caller's cancellation included, and returns it as an `SQLError` ("StandaloneCoroutine was
 * cancelled"): a cancelled request, job or heartbeat would then fail its scope like a broken database instead of
 * ending as cancelled. A failure seen while the caller is cancelled is therefore its cancellation, with the failure
 * kept as suppressed; a failure while it is active stays the failure. The statements sent on the pool get the same
 * treatment, whatever sqlx4k does with a cancellation there.
 *
 * @property inner The sqlx4k pool, for what only the pool itself offers (PostgreSQL's `LISTEN`,
 *   [databaseNotifications]).
 */
internal class CancellationKeepingDriver(val inner: Driver) : Driver by inner {
    override suspend fun begin(): Result<Transaction> = inner.begin().kept()

    override suspend fun execute(sql: String): Result<Long> = inner.execute(sql).kept()

    override suspend fun execute(statement: Statement): Result<Long> = inner.execute(statement).kept()

    override suspend fun fetchAll(sql: String): Result<ResultSet> = inner.fetchAll(sql).kept()

    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = inner.fetchAll(statement).kept()

    override suspend fun <T> transaction(f: suspend Transaction.() -> T): T = try {
        inner.transaction(f)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw cancellationOr(error)
    }

    /** This result, unless it failed while the caller was cancelled: the caller's cancellation is thrown then. */
    private suspend fun <T> Result<T>.kept(): Result<T> {
        val failure = exceptionOrNull() ?: return this
        val outcome = cancellationOr(failure)
        if (outcome !== failure) throw outcome
        return this
    }

    /** The caller's cancellation, [failure] suppressed in it, when the caller is cancelled; else [failure] itself. */
    private suspend fun cancellationOr(failure: Throwable): Throwable = try {
        currentCoroutineContext().ensureActive()
        failure
    } catch (cancellation: CancellationException) {
        cancellation.addSuppressed(failure)
        cancellation
    }
}
