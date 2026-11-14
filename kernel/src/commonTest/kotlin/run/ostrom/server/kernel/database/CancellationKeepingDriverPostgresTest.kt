// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.testing.TestDatabase
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A caller cancelled while sqlx4k opens its transaction: sqlx4k answers with an `SQLError` ("StandaloneCoroutine was
 * cancelled") when the cancellation lands while `BEGIN` is on its way (the flake of `RegisteredSessionsPostgresTest`,
 * whose cancelled heartbeats then failed the test's scope); the server's pool answers with the caller's cancellation.
 */
class CancellationKeepingDriverPostgresTest {
    // On the real clock: the pool's waits are real.
    private fun databaseTest(block: suspend (Driver) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val database = TestDatabase.create() ?: return@withContext
            try {
                block(database.driver)
            } finally {
                database.close()
            }
        }
    }

    /**
     * sqlx4k's `begin` as it behaves when the cancellation lands during `BEGIN`: it catches it and returns it as an
     * `SQLError`. [failing] makes it fail at once instead, as a broken database does.
     */
    private class SwallowingDriver(inner: Driver, private val failing: Boolean = false) : Driver by inner {
        override suspend fun begin(): Result<Transaction> = try {
            if (failing) Result.failure(SQLError(SQLError.Code.Database, "connection refused")) else awaitCancellation()
        } catch (error: CancellationException) {
            Result.failure(SQLError(SQLError.Code.Database, error.message))
        }

        override suspend fun <T> transaction(f: suspend Transaction.() -> T): T = begin().getOrThrow().f()
    }

    /** How a call ends when its coroutine is cancelled once the call is under way. */
    private suspend fun cancelled(call: suspend () -> Unit): Throwable? {
        var cause: Throwable? = null
        supervisorScope {
            val started = CompletableDeferred<Unit>()
            // The cause is read below; the handler only keeps a failure from being reported as the test's own.
            val caller = launch(CoroutineExceptionHandler { _, _ -> }) {
                started.complete(Unit)
                call()
            }
            caller.invokeOnCompletion { cause = it }
            started.await()
            yield()
            caller.cancel()
            caller.join()
        }
        return cause
    }

    @Test
    fun sqlx4kAloneTurnsTheCancellationIntoAnSqlError() = databaseTest { pool ->
        assertIs<SQLError>(cancelled { SwallowingDriver(pool).transaction { } }, "What the server's pool repairs")
    }

    @Test
    fun aCancelledTransactionEndsCancelled() = databaseTest { pool ->
        val driver = CancellationKeepingDriver(SwallowingDriver(pool))
        assertIs<CancellationException>(cancelled { driver.transaction { } })
    }

    @Test
    fun aCancelledBeginEndsCancelled() = databaseTest { pool ->
        val driver = CancellationKeepingDriver(SwallowingDriver(pool))
        assertIs<CancellationException>(cancelled { driver.begin() })
    }

    @Test
    fun aFailureOfAnActiveCallerStaysAFailure() = databaseTest { pool ->
        val broken = CancellationKeepingDriver(SwallowingDriver(pool, failing = true))
        assertTrue(broken.begin().exceptionOrNull() is SQLError)
        assertFailsWith<SQLError> { broken.transaction { } }

        val driver = CancellationKeepingDriver(pool)
        assertFailsWith<SQLError> { driver.transaction { execute("SELECT no_such_column").getOrThrow() } }
        val stopped = assertFailsWith<CancellationException> {
            driver.transaction { throw CancellationException("stopped") }
        }
        assertEquals("stopped", stopped.message, "A cancellation from inside passes as it is")
        val transaction = driver.begin().getOrThrow()
        transaction.execute("SELECT 1").getOrThrow()
        transaction.rollback().getOrThrow()
    }

    @Test
    fun theServersPoolEndsEveryCancelledTransactionCancelled() = databaseTest { pool ->
        // The real library, cancelled at every point of opening, running and closing a transaction.
        val driver = CancellationKeepingDriver(pool)
        repeat(CYCLES) {
            val cause = cancelled { driver.transaction { execute("SELECT 1").getOrThrow() } }
            assertTrue(cause == null || cause is CancellationException, "Cancelled, not $cause")
        }
    }

    private companion object {
        const val CYCLES = 200
    }
}
