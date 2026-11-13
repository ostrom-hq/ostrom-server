// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.ResolveDueWaitsUseCase
import run.ostrom.server.automations.infrastructure.DueWaitsWorker
import run.ostrom.server.automations.infrastructure.WaitEventSecretsImpl
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The clock of the waits that resolve by themselves, and the secrets of their event addresses. */
class DueWaitsWorkerTest {
    private val logged = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val logger = object : Logger {
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            logged += message to fields
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private class Passes(var failure: Throwable? = null) : ResolveDueWaitsUseCase {
        var count = 0

        override suspend fun pass(): Int {
            count++
            failure?.let { throw it }
            return 0
        }
    }

    @Test
    fun aFailedPassIsLoggedByItsTypeOnly() = runTest {
        DueWaitsWorker(Passes(IllegalStateException("wev_secret")), logger).pass()
        assertEquals(
            listOf("due waits pass failed" to mapOf<String, Any?>("error" to "IllegalStateException")),
            logged,
        )
    }

    @Test
    fun aCancellationIsNeverSwallowed() = runTest {
        assertFailsWith<CancellationException> { DueWaitsWorker(Passes(CancellationException("stop")), logger).pass() }
    }

    @Test
    fun aPassRunsEveryFiveSecondsUntilCancelled() = runTest {
        val passes = Passes()
        val job = launch { DueWaitsWorker(passes, logger).run() }
        advanceTimeBy(11.seconds)
        job.cancel()
        assertEquals(3, passes.count)
    }

    @Test
    fun aSecretIsTwoHundredFiftySixRandomBitsKeptAsItsSha256() = runTest {
        val secrets = WaitEventSecretsImpl()
        val secret = secrets.mint()
        assertTrue(Regex("wev_[0-9a-f]{64}").matches(secret), secret)
        assertNotEquals(secret, secrets.mint())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            secrets.hash("abc"),
        )
    }
}
