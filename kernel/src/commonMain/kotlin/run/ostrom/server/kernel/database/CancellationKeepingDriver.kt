// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The pool, except that a cancelled caller is told it was cancelled. sqlx4k's `begin` catches every exception while it
 * waits for a connection and starts the transaction, the caller's cancellation included, and returns it as an
 * `SQLError` ("StandaloneCoroutine was cancelled"): a cancelled request, job or heartbeat would then fail its scope
 * like a broken database instead of ending as cancelled. A failure seen while the caller is cancelled is therefore
 * its cancellation; a failure while it is active stays the failure.
 *
 * @param inner The sqlx4k pool.
 */
internal class CancellationKeepingDriver(private val inner: Driver) : Driver by inner {
    override suspend fun begin(): Result<Transaction> =
        inner.begin().also { if (it.isFailure) currentCoroutineContext().ensureActive() }

    override suspend fun <T> transaction(f: suspend Transaction.() -> T): T = try {
        inner.transaction(f)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        throw error
    }
}
